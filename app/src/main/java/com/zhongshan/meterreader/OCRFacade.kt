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
	     */
	    suspend fun performStreamOcr(
	        imageProxy: ImageProxy,
	        template: DeviceTemplate,
	        screenIndex: Int,
	        resourcePool: BinarizeResourcePool
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        val startTs = System.currentTimeMillis()
	        val intermediates = ArrayList<Bitmap>()
	        try {
	            val rawBitmap: Bitmap = try {
	                imageProxy.toBitmap()
	            } catch (e: Throwable) {
	                DebugLogger.log("StreamOCR", "toBitmap 失败: ${e.javaClass.simpleName} ${e.message}")
	                return@withContext emptyMap()
	            } ?: return@withContext emptyMap()
	            if (rawBitmap.width <= 0 || rawBitmap.height <= 0) {
	                return@withContext emptyMap()
	            }
	            intermediates.add(rawBitmap)
	            val rawW = rawBitmap.width
	            val rawH = rawBitmap.height
	            val rotation = try { imageProxy.imageInfo.rotationDegrees } catch (e: Throwable) { 0 }
	            val rotatedBitmap: Bitmap = if (rotation != 0) {
	                try {
	                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
	                    val rb = Bitmap.createBitmap(rawBitmap, 0, 0, rawW, rawH, matrix, true)
	                    if (rb !== rawBitmap) intermediates.add(rb)
	                    rb
	                } catch (e: Throwable) { rawBitmap }
	            } else { rawBitmap }
	            if (rotatedBitmap.width <= 0 || rotatedBitmap.height <= 0) {
	                return@withContext emptyMap()
	            }
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
	                } catch (e: Throwable) { rotatedBitmap }
	            } else { rotatedBitmap }
	            if (finalBitmap.width <= 0 || finalBitmap.height <= 0) {
	                return@withContext emptyMap()
	            }
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
	                System.gc()
	                emptyMap()
	            } catch (e: Throwable) {
	                emptyMap()
	            }
	            return@withContext ocrResult
	        } catch (e: Throwable) {
	            return@withContext emptyMap()
	        } finally {
	            for (b in intermediates) {
	                try { if (!b.isRecycled) b.recycle() } catch (_: Throwable) {}
	            }
	        }
	    }
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
	    private suspend fun extractScrewDataFromBitmap(
	        bitmap: Bitmap,
	        template: DeviceTemplate,
	        screenIndex: Int,
	        tag: String
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	        val image = InputImage.fromBitmap(bitmap, 0)
	        val visionResult = recognizer.process(image).await()
	        val lines = visionResult.textBlocks.flatMap { it.lines }
	        data class LineInfo(val y: Float, val x: Float, val text: String)
	        val sortedLines = lines.mapNotNull { line ->
	            val box = line.boundingBox ?: return@mapNotNull null
	            LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())
	        }.sortedBy { it.y }
	        data class NumLineInfo(val y: Float, val text: String, val nums: List<String>)
	        val numLines = sortedLines.mapNotNull { lineInfo ->
	            val matches = Regex("""\d{1,4}\.\d{1,2}|\d{1,4}""").findAll(lineInfo.text).map { it.value }.toList()
	            if (matches.isNotEmpty()) { NumLineInfo(lineInfo.y, lineInfo.text, matches) } else null
	        }
	        if (numLines.isEmpty()) { return@withContext emptyMap() }
	        val results = mutableMapOf<String, String>()
	        val relativeRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
	        if (relativeRois.isEmpty()) { return@withContext emptyMap() }
	        when (screenIndex) {
	            0, 1 -> {
	                val tempNums = mutableListOf<NumLineInfo>()
	                var pressureNum: NumLineInfo? = null
	                for (numLine in numLines) {
	                    val lowerText = numLine.text.lowercase()
	                    when {
	                        lowerText.contains("kpag") || lowerText.contains("kpa") || lowerText.contains("mpa") -> { pressureNum = numLine }
	                        lowerText.contains("c") || lowerText.contains("℃") -> { tempNums.add(numLine) }
	                    }
	                }
	                for (roi in relativeRois) {
	                    val label = roi.label
	                    when {
	                        label.contains("冷媒压力") || label.contains("制冷剂压力") -> {
	                            pressureNum?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
	                        }
	                        label.contains("进水温度") || label.contains("回水温度") -> {
	                            tempNums.getOrNull(0)?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
	                        }
	                        label.contains("出水温度") -> {
	                            tempNums.getOrNull(1)?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
	                        }
	                        label.contains("饱和温度") || label.contains("蒸发温度") || label.contains("冷凝温度") -> {
	                            tempNums.getOrNull(2)?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
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
	                    }
	                }
	                for (roi in relativeRois) {
	                    val label = roi.label
	                    when {
	                        label.contains("油压") -> { pressureNum?.nums?.firstOrNull()?.let { results[roi.fieldId] = it } }
	                        label.contains("电流") -> { currentNum?.nums?.firstOrNull()?.let { results[roi.fieldId] = it } }
	                        label.contains("排出") || label.contains("排气") -> { tempNums.getOrNull(0)?.nums?.firstOrNull()?.let { results[roi.fieldId] = it } }
	                        label.contains("负载") || label.contains("RLA") -> {
	                            val rlaLine = numLines.firstOrNull { it.text.matches(Regex("""[\d\.\s]+""")) && it.nums.isNotEmpty() }
	                            rlaLine?.nums?.firstOrNull()?.let { results[roi.fieldId] = it }
	                        }
	                    }
	                }
	            }
	        }
	        return@withContext results
	    }
	    // =====================================================================
	    // 【深度优化版】约克螺杆机 OCR 解析引擎
	    // 策略升级：
	    //   1) 单位锚点：用 kPaG/C 定位数值，即使标签乱码也能提取；
	    //   2) 特征组合：冷凝/蒸发 + 压力/温度 组合判断，容错 OCR 漏字错字；
	    //   3) 范围校验：蒸发压力 300-600，冷凝压力 800-2000，油压 300-1000，过滤误匹配；
	    //   4) 设定值过滤：排除"设定值"行的数据，避免污染实际运行参数；
	    //   5) 坐标辅助：对无标签的温度行，用左右坐标区分冷冻水/冷却水。
	    // =====================================================================
	    private suspend fun extractYorkDataFromBitmap(
	        bitmap: Bitmap,
	        tag: String
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        DebugLogger.log(tag, "开始约克机组原图识别，尺寸: ${bitmap.width}x${bitmap.height}")
	        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
	        val image = InputImage.fromBitmap(bitmap, 0)
	        val visionResult = recognizer.process(image).await()
	        val lines = visionResult.textBlocks.flatMap { it.lines }
	        DebugLogger.log(tag, "ML Kit 原始识别到 ${lines.size} 行文本")
	        data class YorkLine(val y: Float, val x: Float, val text: String, val cleaned: String)
	        val sortedLines = lines.mapNotNull { line ->
	            val box = line.boundingBox ?: return@mapNotNull null
	            val raw = line.text.trim()
	            YorkLine(box.exactCenterY(), box.exactCenterX(), raw, raw.replace(" ", "").replace("\u3000", ""))
	        }.sortedBy { it.y }
	        if (sortedLines.isEmpty()) { return@withContext emptyMap() }
	        val midX = bitmap.width / 2f
	        val numRegex = Regex("""-?\d+\.?\d*""")
	        fun cleanNum(raw: String): String? {
	            val m = Regex("""-?\d{1,4}(\.\d{1,2})?""").find(raw) ?: return null
	            return m.value
	        }
	        // 【新增】数值范围校验
	        fun isInRange(key: String, value: Float): Boolean {
	            return when (key) {
	                "evapRefPressure" -> value in 300f..600f       // 蒸发器压力 300-600 kPaG
	                "condRefPressure" -> value in 800f..2000f      // 冷凝器压力 800-2000 kPaG
	                "compOilPressure" -> value in 300f..1000f      // 油压差 300-1000 kPa
	                "motorCurrent" -> value in 0f..150f            // 满载安培 0-150%
	                "compGuideOpening" -> value in 0f..100f        // 滑阀位置 0-100%
	                "evapTemp", "condTemp" -> value in -20f..60f   // 饱和温度 -20~60°C
	                "evapInTemp", "evapOutTemp", "condInTemp", "condOutTemp" -> value in -10f..50f
	                "compOilTemp", "compDischargeTemp" -> value in 0f..120f
	                else -> true
	            }
	        }
	        // 【新增】判断是否为设定值行
	        fun isSetValueLine(text: String): Boolean {
	            return text.contains("设定") || text.contains("设置") || text.contains("set")
	        }
	        // 【优化】特征组合匹配，支持容错
	        fun matchKeywords(text: String, keywords: List<String>, side: Char): Boolean {
	            // 1. 精确匹配
	            for (kw in keywords) {
	                if (text.contains(kw)) return true
	            }
	            // 2. 冷凝器压力：冷凝 + (力|压力|kPa)
	            if (keywords.any { it.contains("冷凝") } && text.contains("冷凝") && 
	                (text.contains("力") || text.contains("kPa") || text.contains("kpa"))) return true
	            // 3. 蒸发器压力：蒸发/发證/素发 + (力|压力|kPa)，排除油压
	            if (keywords.any { it.contains("蒸发") } && !text.contains("油") &&
	                (text.contains("蒸发") || text.contains("发證") || text.contains("素发") || text.contains("发E")) &&
	                (text.contains("力") || text.contains("kPa") || text.contains("kpa"))) return true
	            // 4. 油温：油 + (温|溫)
	            if (keywords.contains("油温") && text.contains("油") && 
	                (text.contains("温") || text.contains("溫"))) return true
	            // 5. 压缩机出口温度
	            if (keywords.any { it.contains("压缩机") } && text.contains("压缩机") && 
	                (text.contains("出") || text.contains("口") || text.contains("温度"))) return true
	            // 6. 滑阀位置
	            if (keywords.contains("滑阀位置") && text.contains("滑阀")) return true
	            // 7. 满载安培
	            if (keywords.any { it.contains("满载") } && 
	                (text.contains("满载") || text.contains("电流") || text.contains("安培"))) return true
	            // 8. 冷却水温度：冷茶/冷却/冷冻
	            if (keywords.any { it.contains("冷却") || it.contains("冷冻") } &&
	                (text.contains("冷却") || text.contains("冷茶") || text.contains("冷冻"))) return true
	            return false
	        }
	        // 【优化】同行取数，增加范围校验
	        fun extractNumberWithValidation(text: String, keyword: String, key: String): String? {
	            val matches = numRegex.findAll(text).toList()
	            if (matches.isEmpty()) return null
	            val kIdx = text.indexOf(keyword)
	            val candidates = if (kIdx >= 0) {
	                matches.filter { it.range.first >= kIdx + keyword.length } + matches
	            } else { matches }
	            for (match in candidates) {
	                val numStr = cleanNum(match.value) ?: continue
	                val numValue = numStr.toFloatOrNull() ?: continue
	                if (isInRange(key, numValue)) {
	                    return numStr
	                }
	            }
	            return null
	        }
	        // 【重构】查找数值
	        fun findValue(key: String, keywords: List<String>, side: Char): String? {
	            val applySide = side != '?'
	            val candidates = if (applySide) {
	                sortedLines.filter { if (side == 'L') it.x < midX else it.x >= midX }
	            } else sortedLines
	            // 第一轮：同行同时含关键字与数字（排除设定值行）
	            for (line in candidates) {
	                if (isSetValueLine(line.cleaned)) continue
	                if (matchKeywords(line.cleaned, keywords, side)) {
	                    val num = extractNumberWithValidation(line.cleaned, keywords.first(), key)
	                    if (num != null) {
	                        DebugLogger.log(tag, "约克匹配[命中同行] key=$key 值=$num 原文=${line.text}")
	                        return num
	                    }
	                }
	            }
	            // 第二轮：关键字行无数值 → 取下方最近的含数字行
	            val kwLine = candidates.firstOrNull { 
	                !isSetValueLine(it.cleaned) && matchKeywords(it.cleaned, keywords, side) 
	            }
	            if (kwLine != null) {
	                val yThreshold = bitmap.height * 0.12f  // 放宽至12%高度
	                val nearby = candidates
	                    .filter { it.y > kwLine.y && (it.y - kwLine.y) <= yThreshold && !isSetValueLine(it.cleaned) }
	                    .mapNotNull { ln -> 
	                        val numStr = cleanNum(numRegex.find(ln.cleaned)?.value ?: "")
	                        if (numStr != null && isInRange(key, numStr.toFloatOrNull() ?: 0f)) {
	                            ln.y to numStr
	                        } else null
	                    }
	                    .minByOrNull { it.first }
	                if (nearby != null) {
	                    DebugLogger.log(tag, "约克匹配[命中邻近行] key=$key 值=${nearby.second}")
	                    return nearby.second
	                }
	            }
	            // 【新增】第三轮：单位锚点兜底
	            // 对于压力字段，查找含 kPaG 但不含"油"的行
	            if (key == "evapRefPressure" || key == "condRefPressure") {
	                for (line in candidates) {
	                    if (isSetValueLine(line.cleaned) || line.cleaned.contains("油")) continue
	                    if (line.cleaned.contains("kPaG") || line.cleaned.contains("kpag")) {
	                        val isCondenser = line.cleaned.contains("冷凝") || line.x >= midX
	                        if ((key == "condRefPressure" && isCondenser) || (key == "evapRefPressure" && !isCondenser)) {
	                            val num = extractNumberWithValidation(line.cleaned, "", key)
	                            if (num != null) {
	                                DebugLogger.log(tag, "约克匹配[单位锚点] key=$key 值=$num 原文=${line.text}")
	                                return num
	                            }
	                        }
	                    }
	                }
	            }
	            DebugLogger.log(tag, "约克匹配[未命中] key=$key")
	            return null
	        }
	        // 【调整】字段定义：压力字段取消左右屏限制，温度字段保留
	        val fieldDefs = listOf(
	            // —— 压力字段（不限制左右屏，用关键词区分）——
	            YorkFieldDefLocal("condRefPressure",   "冷凝器压力",     listOf("冷凝器冷凝压力", "冷凝压力", "冷凝器压力", "冷凝"), '?'),
	            YorkFieldDefLocal("evapRefPressure",   "蒸发器压力",     listOf("蒸发器蒸发压力", "蒸发压力", "蒸发器压力", "蒸发"), '?'),
	            // —— 饱和温度（不限制左右屏）——
	            YorkFieldDefLocal("condTemp",          "冷凝器饱和温度", listOf("冷凝器饱和温度", "冷凝器饱和", "冷凝壽饱和"), '?'),
	            YorkFieldDefLocal("evapTemp",          "蒸发器饱和温度", listOf("蒸发器饱和温度", "蒸发器饱和", "素发器饱和"), '?'),
	            // —— 水温（用左右屏区分冷冻水/冷却水）——
	            YorkFieldDefLocal("evapInTemp",        "冷冻水温度返回", listOf("冷冻水温度返回", "冷冻水返回", "冷冻水温度返", "冷冻水"), 'L'),
	            YorkFieldDefLocal("evapOutTemp",       "冷冻水温度出水", listOf("冷冻水温度出水", "冷冻水出水", "冷冻水温度出", "冷冻水"), 'L'),
	            YorkFieldDefLocal("condInTemp",        "冷却水温度返回", listOf("冷却水温度返回", "冷却水返回", "冷却水温度返", "冷却水"), 'R'),
	            YorkFieldDefLocal("condOutTemp",       "冷却水温度出水", listOf("冷却水温度出水", "冷却水出水", "冷却水温度出", "冷却水"), 'R'),
	            // —— 压缩机/电机区 ——
	            YorkFieldDefLocal("compOilPressure",   "油压差",         listOf("油压差", "油压"), '?'),
	            YorkFieldDefLocal("compOilTemp",       "油温",           listOf("油温", "油箱温度", "油溫"), '?'),
	            YorkFieldDefLocal("compDischargeTemp", "压缩机出口温度", listOf("压缩机出口温度", "出口温度", "排口温度", "压缩机出"), '?'),
	            YorkFieldDefLocal("compGuideOpening",  "滑阀位置",       listOf("滑阀位置", "滑阀"), '?'),
	            YorkFieldDefLocal("motorCurrent",      "满载安培",       listOf("%满载安培", "满载安培", "%满载", "满载"), '?')
	        )
	        val results = mutableMapOf<String, String>()
	        for (def in fieldDefs) {
	            val raw = findValue(def.key, def.keywords, def.side) ?: continue
	            val value = cleanNum(raw) ?: raw
	            results["${def.key}|${def.label}"] = value
	        }
	        // 【新增】第四轮：温度行坐标兜底
	        // 如果水温字段未命中，尝试用纯温度行的坐标来匹配
	        val tempKeys = listOf("evapInTemp", "evapOutTemp", "condInTemp", "condOutTemp")
	        if (tempKeys.any { results["${it}|${fieldDefs.find { d -> d.key == it }?.label}"] == null }) {
	            val tempLines = sortedLines.filter { line ->
	                !isSetValueLine(line.cleaned) && 
	                (line.cleaned.contains("C") || line.cleaned.contains("℃")) &&
	                numRegex.containsMatchIn(line.cleaned)
	            }.sortedBy { it.y }
	            // 左半屏温度行 = 冷冻水，右半屏温度行 = 冷却水
	            val leftTemps = tempLines.filter { it.x < midX }
	            val rightTemps = tempLines.filter { it.x >= midX }
	            for (def in fieldDefs) {
	                if (def.key !in tempKeys) continue
	                val compoundKey = "${def.key}|${def.label}"
	                if (results[compoundKey] != null) continue
	                val temps = if (def.side == 'L') leftTemps else rightTemps
	                val index = if (def.key.contains("In")) 0 else 1  // In=返回(第1个), Out=出水(第2个)
	                temps.getOrNull(index)?.let { line ->
	                    val num = cleanNum(numRegex.find(line.cleaned)?.value ?: "")
	                    if (num != null) {
	                        val numValue = num.toFloatOrNull() ?: 0f
	                        if (isInRange(def.key, numValue)) {
	                            results[compoundKey] = num
	                            DebugLogger.log(tag, "约克匹配[温度坐标兜底] key=${def.key} 值=$num")
	                        }
	                    }
	                }
	            }
	        }
	        DebugLogger.log(tag, "约克最终提取结果: $results")
	        return@withContext results
	    }
	    // 本地数据类，避免与外部冲突
	    private data class YorkFieldDefLocal(
	        val key: String,
	        val label: String,
	        val keywords: List<String>,
	        val side: Char
	    )
	}
