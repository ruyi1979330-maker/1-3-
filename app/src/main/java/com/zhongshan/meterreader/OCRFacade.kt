	// 文件名: OCRFacade.kt
	package com.zhongshan.meterreader
	import android.content.Context
	import android.graphics.Bitmap
	import android.graphics.Matrix
	import android.net.Uri
	import android.widget.Toast
	import androidx.camera.core.ImageProxy
	import com.google.mlkit.vision.common.InputImage
	import com.google.mlkit.vision.text.TextRecognition
	import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
	import com.google.mlkit.vision.text.latin.TextRecognizerOptions
	import com.zhongshan.meterreader.data.DeviceTemplate
	import com.zhongshan.meterreader.util.BinarizeResourcePool
	import com.zhongshan.meterreader.util.OCREngine
	import com.zhongshan.meterreader.util.StorageAndImageUtils
	import kotlinx.coroutines.Dispatchers
	import kotlinx.coroutines.tasks.await
	import kotlinx.coroutines.withContext
	import kotlin.math.abs
	enum class ImageSource { CAMERA, GALLERY }
	object OCRFacade {
	    /**
	     * 阶段二/四：无感视频流识别接口
	     * 优化：将相机流转为 Bitmap 并放大，复用全图 OCR 逻辑，解决预览尺寸过小导致识别为空的问题。
	     *
	     * 修复 Q1（相机帧处理闪退）：
	     *   - ImageProxy.close() 完全交由调用方(MainActivity)在 finally 里执行，本方法绝不重复 close；
	     *   - Bitmap 转换/旋转/缩放全部包裹在 try/finally 中，任何异常都不会向外抛 OOM/空指针导致闪退；
	     *   - 对 width/height<=0、toBitmap 返回 null 等异常输入做保护；
	     *   - 中间产生的 Bitmap 统一在 finally 里 recycle，避免内存泄漏叠加导致的 OOM 闪退。
	     *
	     * 增加 Q3 日志：原始尺寸、旋转角度、最终送识别尺寸、OCR/总耗时、结果字段数与各字段值。
	     */
	    suspend fun performStreamOcr(
	        imageProxy: ImageProxy,
	        template: DeviceTemplate,
	        screenIndex: Int,
	        resourcePool: BinarizeResourcePool
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        val startTs = System.currentTimeMillis()
	        // 修复 Q1：用 val 级联 + intermediates 列表统一回收，避免 var 被 lambda 捕获导致的
	        //         smart-cast 失败与所有权混乱，保证任何异常都不会 OOM 闪退。
	        // 注意：本方法绝不 close imageProxy（交由 MainActivity 的 finally 处理），避免重复 close。
	        val intermediates = ArrayList<Bitmap>()
	        try {
	            // 1) ImageProxy -> Bitmap
	            val rawBitmap: Bitmap = try {
	                imageProxy.toBitmap()
	            } catch (e: Throwable) {
	                DebugLogger.log("StreamOCR", "toBitmap 失败: ${e.javaClass.simpleName} ${e.message}")
	                return@withContext emptyMap()
	            } ?: return@withContext emptyMap()
	            if (rawBitmap.width <= 0 || rawBitmap.height <= 0) {
	                DebugLogger.log("StreamOCR", "toBitmap 得到无效 Bitmap，跳过")
	                return@withContext emptyMap()
	            }
	            intermediates.add(rawBitmap)
	            val rawW = rawBitmap.width
	            val rawH = rawBitmap.height
	            // 2) 旋转
	            val rotation = try { imageProxy.imageInfo.rotationDegrees } catch (e: Throwable) { 0 }
	            DebugLogger.log("StreamOCR", "原始帧尺寸: ${rawW}x${rawH} 旋转角度: $rotation")
	            val rotatedBitmap: Bitmap = if (rotation != 0) {
	                try {
	                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
	                    val rb = Bitmap.createBitmap(rawBitmap, 0, 0, rawW, rawH, matrix, true)
	                    if (rb !== rawBitmap) intermediates.add(rb)
	                    rb
	                } catch (e: Throwable) {
	                    DebugLogger.log("StreamOCR", "旋转 Bitmap 失败，使用原图: ${e.message}")
	                    rawBitmap
	                }
	            } else {
	                rawBitmap
	            }
	            if (rotatedBitmap.width <= 0 || rotatedBitmap.height <= 0) {
	                return@withContext emptyMap()
	            }
	            // 3) 放大
	            val targetWidth = 1080
	            val finalBitmap: Bitmap = if (rotatedBitmap.width < targetWidth) {
	                try {
	                    val scale = targetWidth.toFloat() / rotatedBitmap.width
	                    val sb = Bitmap.createScaledBitmap(
	                        rotatedBitmap, targetWidth,
	                        (rotatedBitmap.height * scale).toInt().coerceAtLeast(1), true
	                    )
	                    if (sb !== rotatedBitmap) intermediates.add(sb)
	                    sb
	                } catch (e: Throwable) {
	                    DebugLogger.log("StreamOCR", "放大 Bitmap 失败，使用原图: ${e.message}")
	                    rotatedBitmap
	                }
	            } else {
	                rotatedBitmap
	            }
	            if (finalBitmap.width <= 0 || finalBitmap.height <= 0) {
	                return@withContext emptyMap()
	            }
	            DebugLogger.log("StreamOCR", "最终送识别尺寸: ${finalBitmap.width}x${finalBitmap.height}")
	            // 4) 识别（核心逻辑不修改，仅包裹异常 + 记录耗时/结果）
	            // 【本次新增-约克】调度层路由：york 开头走独立约克解析引擎，与特灵/板交完全隔离。
	            val ocrStartTs = System.currentTimeMillis()
	            val ocrResult: Map<String, String> = try {
	                when {
	                    template.machineId.startsWith("york") ->
	                        extractYorkDataFromBitmap(finalBitmap, "StreamOCR")
	                    template.isHeatExchanger -> {
	                        val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
	                        OCREngine.extractPlateData(finalBitmap, template.roomId == 1, plateKeywordMap)
	                    }
	                    else ->
	                        extractScrewDataFromBitmap(finalBitmap, template, screenIndex, "StreamOCR")
	                }
	            } catch (oom: OutOfMemoryError) {
	                DebugLogger.log("StreamOCR", "识别过程 OOM: ${oom.message}")
	                System.gc()
	                emptyMap()
	            } catch (e: Throwable) {
	                DebugLogger.log("StreamOCR", "识别过程异常: ${e.javaClass.simpleName} ${e.message}")
	                emptyMap()
	            }
	            val ocrElapsed = System.currentTimeMillis() - ocrStartTs
	            val totalElapsed = System.currentTimeMillis() - startTs
	            DebugLogger.log(
	                "StreamOCR",
	                "识别完成 OCR耗时=${ocrElapsed}ms 总耗时=${totalElapsed}ms 字段数=${ocrResult.size}"
	            )
	            if (ocrResult.isNotEmpty()) {
	                val fieldsDump = ocrResult.entries
	                    .joinToString(", ") { "${it.key}=${it.value}" }
	                    .take(500)
	                DebugLogger.log("StreamOCR", "识别字段: $fieldsDump")
	            }
	            return@withContext ocrResult
	        } catch (e: Throwable) {
	            DebugLogger.log("StreamOCR", "performStreamOcr 顶层异常: ${e.javaClass.simpleName} ${e.message}")
	            return@withContext emptyMap()
	        } finally {
	            // 统一回收所有中间 Bitmap（同一对象引用只会 recycle 一次，重复 recycle 已被 isRecycled 拦截）
	            for (b in intermediates) {
	                try {
	                    if (!b.isRecycled) b.recycle()
	                } catch (_: Throwable) {}
	            }
	        }
	    }
	    /**
	     * 兼容原相册模式的单张图片识别接口
	     */
	    suspend fun performSmartOcr(
	        context: Context,
	        imageUri: Uri,
	        template: DeviceTemplate,
	        screenIndex: Int,
	        source: ImageSource,
	        resourcePool: BinarizeResourcePool
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        val bitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(context, imageUri)
	        if (bitmap == null) {
	            withContext(Dispatchers.Main) { Toast.makeText(context, "图片加载失败", Toast.LENGTH_LONG).show() }
	            return@withContext emptyMap()
	        }
	        try {
	            // 【本次新增-约克】调度层路由：york 开头走独立约克解析引擎。
	            when {
	                template.machineId.startsWith("york") ->
	                    return@withContext extractYorkDataFromBitmap(bitmap, "SmartOCR")
	                template.isHeatExchanger -> {
	                    val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
	                    return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
	                }
	                else ->
	                    return@withContext extractScrewDataFromBitmap(bitmap, template, screenIndex, "SmartOCR")
	            }
	        } finally {
	            bitmap.recycle()
	        }
	    }
	    /**
	     * 螺杆机全图 OCR 与智能提取核心逻辑
	     * 统一供图库识别和相机流识别调用，抛弃坐标裁剪与二值化。
	     * 红线：特灵机组逻辑已稳定，保持原样不动。
	     */
	    private suspend fun extractScrewDataFromBitmap(
	        bitmap: Bitmap,
	        template: DeviceTemplate,
	        screenIndex: Int,
	        tag: String
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        DebugLogger.log(tag, "开始螺杆机原图直接识别，尺寸: ${bitmap.width}x${bitmap.height}")
	        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	        val image = InputImage.fromBitmap(bitmap, 0)
	        val visionResult = recognizer.process(image).await()
	        val lines = visionResult.textBlocks.flatMap { it.lines }
	        DebugLogger.log(tag, "ML Kit 原始识别到 ${lines.size} 行文本")
	        data class LineInfo(val y: Float, val x: Float, val text: String)
	        val sortedLines = lines.mapNotNull { line ->
	            val box = line.boundingBox ?: return@mapNotNull null
	            LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())
	        }.sortedBy { it.y }
	        DebugLogger.log(tag, "--- 按Y坐标排序后的文本行 ---")
	        sortedLines.forEachIndexed { index, lineInfo ->
	            DebugLogger.log(tag, "行[$index] Y=${lineInfo.y.toInt()} X=${lineInfo.x.toInt()} Text=${lineInfo.text}")
	        }
	        data class NumLineInfo(val y: Float, val text: String, val nums: List<String>)
	        val numLines = sortedLines.mapNotNull { lineInfo ->
	            val matches = Regex("""\d{1,4}\.\d{1,2}|\d{1,4}""").findAll(lineInfo.text).map { it.value }.toList()
	            if (matches.isNotEmpty()) {
	                NumLineInfo(lineInfo.y, lineInfo.text, matches)
	            } else null
	        }
	        DebugLogger.log(tag, "--- 提取到的含数字行 ---")
	        numLines.forEachIndexed { index, numLine ->
	            DebugLogger.log(tag, "数字行[$index] Y=${numLine.y.toInt()} Text=${numLine.text} Nums=${numLine.nums}")
	        }
	        if (numLines.isEmpty()) {
	            DebugLogger.log(tag, "未提取到任何数字，匹配终止")
	            return@withContext emptyMap()
	        }
	        val results = mutableMapOf<String, String>()
	        val relativeRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
	        if (relativeRois.isEmpty()) {
	            DebugLogger.log(tag, "未找到当前屏幕的相对坐标配置，无法获取字段列表")
	            return@withContext emptyMap()
	        }
	        when (screenIndex) {
	            0, 1 -> {
	                val tempNums = mutableListOf<NumLineInfo>()
	                var pressureNum: NumLineInfo? = null
	                for (numLine in numLines) {
	                    val lowerText = numLine.text.lowercase()
	                    when {
	                        lowerText.contains("kpag") || lowerText.contains("kpa") || lowerText.contains("mpa") -> {
	                            pressureNum = numLine
	                        }
	                        lowerText.contains("c") || lowerText.contains("℃") -> {
	                            tempNums.add(numLine)
	                        }
	                    }
	                }
	                DebugLogger.log(tag, "分类结果 - 温度行数量: ${tempNums.size}, 压力行: ${pressureNum?.text ?: "无"}")
	                for (roi in relativeRois) {
	                    val label = roi.label
	                    when {
	                        label.contains("冷媒压力") || label.contains("制冷剂压力") -> {
	                            pressureNum?.nums?.firstOrNull()?.let {
	                                results[roi.fieldId] = it
	                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
	                            }
	                        }
	                        label.contains("进水温度") || label.contains("回水温度") -> {
	                            tempNums.getOrNull(0)?.nums?.firstOrNull()?.let {
	                                results[roi.fieldId] = it
	                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
	                            }
	                        }
	                        label.contains("出水温度") -> {
	                            tempNums.getOrNull(1)?.nums?.firstOrNull()?.let {
	                                results[roi.fieldId] = it
	                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
	                            }
	                        }
	                        label.contains("饱和温度") || label.contains("蒸发温度") || label.contains("冷凝温度") -> {
	                            tempNums.getOrNull(2)?.nums?.firstOrNull()?.let {
	                                results[roi.fieldId] = it
	                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
	                            }
	                        }
	                    }
	                }
	            }
	            2 -> {
	                var pressureNum: NumLineInfo? = null
	                var currentNum: NumLineInfo? = null
	                val tempNums = mutableListOf<NumLineInfo>()
	                for (numLine in numLines) {
	                    val lowerText = numLine.text.lowercase()
	                    when {
	                        lowerText.contains("kpag") || lowerText.contains("kpa") || lowerText.contains("mpa") -> pressureNum = numLine
	                        lowerText.contains("amps") || lowerText.contains("amp") -> currentNum = numLine
	                        lowerText.contains("c") || lowerText.contains("℃") -> tempNums.add(numLine)
	                        else -> {
	                            // 纯数字行用于匹配 %RLA (如 "50.5 45.6 46.7")
	                        }
	                    }
	                }
	                DebugLogger.log(tag, "分类结果 - 温度行: ${tempNums.size}, 压力行: ${pressureNum?.text ?: "无"}, 电流行: ${currentNum?.text ?: "无"}")
	                for (roi in relativeRois) {
	                    val label = roi.label
	                    when {
	                        label.contains("油压") -> {
	                            pressureNum?.nums?.firstOrNull()?.let {
	                                results[roi.fieldId] = it
	                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
	                            }
	                        }
	                        label.contains("电流") -> {
	                            currentNum?.nums?.firstOrNull()?.let {
	                                results[roi.fieldId] = it
	                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it (取L1)")
	                            }
	                        }
	                        label.contains("排出") || label.contains("排气") -> {
	                            tempNums.getOrNull(0)?.nums?.firstOrNull()?.let {
	                                results[roi.fieldId] = it
	                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
	                            }
	                        }
	                        label.contains("负载") || label.contains("RLA") -> {
	                            val rlaLine = numLines.firstOrNull {
	                                it.text.matches(Regex("""[\d\.\s]+""")) && it.nums.isNotEmpty()
	                            }
	                            rlaLine?.nums?.firstOrNull()?.let {
	                                results[roi.fieldId] = it
	                                DebugLogger.log(tag, "匹配成功: ${roi.label} = $it")
	                            }
	                        }
	                    }
	                }
	            }
	        }
	        DebugLogger.log(tag, "最终提取结果: $results")
	        return@withContext results
	    }
	    // =====================================================================
	    // 【本次新增-约克】约克螺杆机单屏全数据 OCR 解析引擎
	    // 红线：完全独立于 extractScrewDataFromBitmap / extractPlateData，二者方法体一字未改。
	    //
	    // 策略：
	    //   1) 全图 ML Kit 识别 → 取所有文本行（含 Y/X 中心坐标）；
	    //   2) 按约克专有特征词匹配行，同行取数（取关键字右侧第一个数字，否则取最后一个数字）；
	    //      若该关键字行无数值，回退到 Y 坐标紧邻的下一含数行；
	    //   3) 对歧义词“饱和温度”使用屏幕左右中线消歧（左半=蒸发器，右半=冷凝器），
	    //      复合词“蒸发器饱和温度/冷凝器饱和温度”自带前缀无需消歧。
	    //
	    // 输出 key 形如 "semanticKey|中文标签"，与 DeviceOcrStrategy.yorkRoiFields 严格对齐。
	    // 注意：motorCurrent 此处输出原始 %满载安培 数值；
	    //       × 2.5 的乘数拦截计算由 MainActivity.buildYorkFillData 执行，本引擎不掺入业务换算。
	    // =====================================================================
	    private suspend fun extractYorkDataFromBitmap(
	        bitmap: Bitmap,
	        tag: String
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        DebugLogger.log(tag, "开始约克机组原图识别，尺寸: ${bitmap.width}x${bitmap.height}")
	        // 【核心修复1】：约克屏幕包含大量中文标签，必须使用中文识别引擎。
	        // 原有的 TextRecognizerOptions.DEFAULT_OPTIONS 仅支持拉丁文，会将中文识别为乱码(如将"油压差"识别为"E")。
	        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
	        val image = InputImage.fromBitmap(bitmap, 0)
	        val visionResult = recognizer.process(image).await()
	        val lines = visionResult.textBlocks.flatMap { it.lines }
	        DebugLogger.log(tag, "ML Kit 原始识别到 ${lines.size} 行文本")
	        data class YorkLine(val y: Float, val x: Float, val text: String, val cleaned: String)
	        val sortedLines = lines.mapNotNull { line ->
	            val box = line.boundingBox ?: return@mapNotNull null
	            val raw = line.text.trim()
	            YorkLine(
	                box.exactCenterY(),
	                box.exactCenterX(),
	                raw,
	                raw.replace(" ", "").replace("\u3000", "")
	            )
	        }.sortedBy { it.y }
	        DebugLogger.log(tag, "--- 约克 按Y排序后文本行 ---")
	        sortedLines.forEachIndexed { idx, l ->
	            DebugLogger.log(tag, "约克行[$idx] Y=${l.y.toInt()} X=${l.x.toInt()} Text=${l.text}")
	        }
	        if (sortedLines.isEmpty()) {
	            DebugLogger.log(tag, "约克：未识别到任何文本行")
	            return@withContext emptyMap()
	        }
	        // 【核心修复2】：屏幕左右中线（像素）：左半=蒸发器，右半=冷凝器
	        val midX = bitmap.width / 2f
	        val numRegex = Regex("""-?\d+\.?\d*""")
	        fun cleanNum(raw: String): String? {
	            val m = Regex("""-?\d{1,4}(\.\d{1,2})?""").find(raw) ?: return null
	            return m.value
	        }
	        fun extractNumber(text: String, keyword: String): String? {
	            val matches = numRegex.findAll(text).toList()
	            if (matches.isEmpty()) return null
	            val kIdx = text.indexOf(keyword)
	            val chosen: String = if (kIdx >= 0) {
	                val after = matches.firstOrNull { it.range.first >= kIdx + keyword.length }
	                after?.value ?: matches.last().value
	            } else matches.last().value
	            return cleanNum(chosen)
	        }
	        // side='L'=左半屏(蒸发器) 'R'=右半屏(冷凝器) '?'=全屏不限
	        fun findValue(keywords: List<String>, side: Char): String? {
	            val applySide = side != '?'
	            val candidates = if (applySide) {
	                sortedLines.filter { if (side == 'L') it.x < midX else it.x >= midX }
	            } else sortedLines
	            for (kw in keywords) {
	                // 第一轮：同行同时含关键字与数字
	                for (line in candidates) {
	                    if (line.cleaned.contains(kw)) {
	                        val num = extractNumber(line.cleaned, kw)
	                        if (num != null) {
	                            DebugLogger.log(tag, "约克匹配[命中同行] 关键词=$kw side=$side 值=$num 原文=${line.text}")
	                            return num
	                        }
	                    }
	                }
	                // 第二轮：关键字行无数值 → 取该行 Y 增大方向最近的含数字行（同侧范围内）
	                val kwLine = candidates.firstOrNull { it.cleaned.contains(kw) }
	                if (kwLine != null) {
	                    val yThreshold = bitmap.height * 0.06f
	                    val nearby = candidates
	                        .filter { it.y > kwLine.y && (it.y - kwLine.y) <= yThreshold }
	                        .mapNotNull { ln -> cleanNum(numRegex.find(ln.cleaned)?.value ?: "")?.let { v -> ln.y to v } }
	                        .minByOrNull { it.first }
	                    if (nearby != null) {
	                        DebugLogger.log(tag, "约克匹配[命中邻近行] 关键词=$kw side=$side 值=${nearby.second}")
	                        return nearby.second
	                    }
	                }
	            }
	            DebugLogger.log(tag, "约克匹配[未命中] keywords=$keywords side=$side")
	            return null
	        }
	        data class YorkFieldDef(
	            val key: String,
	            val label: String,
	            val keywords: List<String>,
	            val side: Char
	        )
	        val fieldDefs = listOf(
	            // —— 蒸发器侧（左半屏）——
	            YorkFieldDef("evapRefPressure",   "蒸发器压力",     listOf("蒸发器蒸发压力", "蒸发压力", "蒸发器压力"), 'L'),
	            YorkFieldDef("evapTemp",          "蒸发器饱和温度", listOf("蒸发器饱和温度", "饱和温度"), 'L'),
	            YorkFieldDef("evapInTemp",        "冷冻水温度返回", listOf("冷冻水温度返回", "冷冻水返回", "冷冻水温度返"), 'L'),
	            YorkFieldDef("evapOutTemp",       "冷冻水温度出水", listOf("冷冻水温度出水", "冷冻水出水", "冷冻水温度出"), 'L'),
	            // —— 冷凝器侧（右半屏）——
	            YorkFieldDef("condRefPressure",   "冷凝器压力",     listOf("冷凝器冷凝压力", "冷凝压力", "冷凝器压力"), 'R'),
	            YorkFieldDef("condTemp",          "冷凝器饱和温度", listOf("冷凝器饱和温度", "饱和温度"), 'R'),
	            YorkFieldDef("condInTemp",        "冷却水温度返回", listOf("冷却水温度返回", "冷却水返回", "冷却水温度返"), 'R'),
	            YorkFieldDef("condOutTemp",       "冷却水温度出水", listOf("冷却水温度出水", "冷却水出水", "冷却水温度出"), 'R'),
	            // —— 压缩机/电机区（整屏，通常居中或单列，不消歧）——
	            YorkFieldDef("compOilPressure",   "油压差",         listOf("油压差", "油压"), '?'),
	            YorkFieldDef("compOilTemp",       "油温",           listOf("油温", "油箱温度"), '?'),
	            YorkFieldDef("compDischargeTemp", "压缩机出口温度", listOf("压缩机出口温度", "出口温度", "排口温度"), '?'),
	            YorkFieldDef("compGuideOpening",  "滑阀位置",       listOf("滑阀位置", "滑阀"), '?'),
	            YorkFieldDef("motorCurrent",      "满载安培",       listOf("%满载安培", "满载安培"), '?')
	        )
	        val results = mutableMapOf<String, String>()
	        for (def in fieldDefs) {
	            val raw = findValue(def.keywords, def.side) ?: continue
	            val value = cleanNum(raw) ?: raw
	            results["${def.key}|${def.label}"] = value
	        }
	        DebugLogger.log(tag, "约克最终提取结果: $results")
	        return@withContext results
	    }
	}
