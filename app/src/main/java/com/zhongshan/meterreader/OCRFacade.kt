	// 文件名: OCRFacade.kt
	package com.zhongshan.meterreader
	import android.content.Context
	import android.graphics.Bitmap
	import android.graphics.Rect
	import android.net.Uri
	import android.widget.Toast
	import androidx.camera.core.ImageProxy
	import com.google.mlkit.vision.common.InputImage
	import com.google.mlkit.vision.text.TextRecognition
	import com.google.mlkit.vision.text.latin.TextRecognizerOptions
	import com.zhongshan.meterreader.data.DeviceTemplate
	import com.zhongshan.meterreader.util.BinarizeResourcePool
	import com.zhongshan.meterreader.util.OCREngine
	import com.zhongshan.meterreader.util.StorageAndImageUtils
	import com.zhongshan.meterreader.util.UltimateLcdBinarizer
	import kotlinx.coroutines.Dispatchers
	import kotlinx.coroutines.tasks.await
	import kotlinx.coroutines.withContext
	enum class ImageSource { CAMERA, GALLERY }
	object OCRFacade {
	    /**
	     * 阶段二/四：无感视频流识别接口
	     */
	    suspend fun performStreamOcr(
	        imageProxy: ImageProxy,
	        template: DeviceTemplate,
	        screenIndex: Int,
	        resourcePool: BinarizeResourcePool
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        val relativeRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
	        if (relativeRois.isEmpty()) {
	            DebugLogger.log("StreamOCR", "未找到相对坐标配置: ${template.machineId}, 屏: $screenIndex")
	            return@withContext emptyMap()
	        }
	        val imgWidth = imageProxy.width
	        val imgHeight = imageProxy.height
	        val rois = relativeRois.map { roi ->
	            Rect(
	                (roi.xStartPct * imgWidth).toInt(),
	                (roi.yStartPct * imgHeight).toInt(),
	                (roi.xEndPct * imgWidth).toInt(),
	                (roi.yEndPct * imgHeight).toInt()
	            )
	        }
	        val fieldMapping = relativeRois.map { it.fieldId }
	        val binarizeResult = UltimateLcdBinarizer.processImageProxy(imageProxy, rois, resourcePool)
	        if (binarizeResult == null) {
	            DebugLogger.log("StreamOCR", "二值化结果为空")
	            return@withContext emptyMap()
	        }
	        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	        val visionResult = recognizer.process(binarizeResult.inputImage).await()
	        val lines = visionResult.textBlocks.flatMap { block -> block.lines }
	        DebugLogger.log("StreamOCR", "ML Kit 识别到 ${lines.size} 行文本: ${lines.joinToString { it.text }}")
	        val results = mutableMapOf<String, String>()
	        for (line in lines) {
	            val box = line.boundingBox ?: continue
	            val centerY = box.top + box.height() / 2
	            var matchedIndex = -1
	            for (i in binarizeResult.roiYRanges.indices) {
	                val (top, bottom) = binarizeResult.roiYRanges[i]
	                if (top == -1) continue
	                if (centerY in top..bottom) { matchedIndex = i; break }
	            }
	            if (matchedIndex in fieldMapping.indices) {
	                val text = line.text.trim().replace(",", ".").replace(":", ".")
	                val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(text)
	                if (match != null) {
	                    results[fieldMapping[matchedIndex]] = match.value
	                }
	            }
	        }
	        DebugLogger.log("StreamOCR", "帧匹配最终结果: $results")
	        return@withContext results
	    }
	    /**
	     * 兼容原相册模式的单张图片识别接口
	     * 优化：针对螺杆机图库图片，彻底抛弃坐标裁剪，直接全图OCR。
	     * 鉴于特灵屏幕中文标签常无法被ML Kit识别，采用“基于单位特征与固定布局顺序”的纯数字提取方案。
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
	            // 板交识别保持原有稳定逻辑不变
	            if (template.isHeatExchanger) {
	                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
	                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
	            }
	            // === 螺杆机图库模式：全图OCR + 基于特征和顺序的数字提取 ===
	            DebugLogger.log("SmartOCR", "开始螺杆机原图直接识别，尺寸: ${bitmap.width}x${bitmap.height}")
	            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	            val image = InputImage.fromBitmap(bitmap, 0)
	            val visionResult = recognizer.process(image).await()
	            val lines = visionResult.textBlocks.flatMap { it.lines }
	            DebugLogger.log("SmartOCR", "ML Kit 原始识别到 ${lines.size} 行文本")
	            // 1. 提取所有文本行，并按 Y 坐标从上到下排序
	            data class LineInfo(val y: Float, val x: Float, val text: String)
	            val sortedLines = lines.mapNotNull { line ->
	                val box = line.boundingBox ?: return@mapNotNull null
	                LineInfo(box.exactCenterY(), box.exactCenterX(), line.text.trim())
	            }.sortedBy { it.y }
	            DebugLogger.log("SmartOCR", "--- 按Y坐标排序后的文本行 ---")
	            sortedLines.forEachIndexed { index, lineInfo ->
	                DebugLogger.log("SmartOCR", "行[$index] Y=${lineInfo.y.toInt()} X=${lineInfo.x.toInt()} Text='${lineInfo.text}'")
	            }
	            // 2. 提取所有含有数字的行
	            data class NumLineInfo(val y: Float, val text: String, val nums: List<String>)
	            val numLines = sortedLines.mapNotNull { lineInfo ->
	                val matches = Regex("""\d{1,4}\.\d{1,2}|\d{1,4}""").findAll(lineInfo.text).map { it.value }.toList()
	                if (matches.isNotEmpty()) {
	                    NumLineInfo(lineInfo.y, lineInfo.text, matches)
	                } else null
	            }
	            DebugLogger.log("SmartOCR", "--- 提取到的含数字行 ---")
	            numLines.forEachIndexed { index, numLine ->
	                DebugLogger.log("SmartOCR", "数字行[$index] Y=${numLine.y.toInt()} Text='${numLine.text}' Nums=${numLine.nums}")
	            }
	            if (numLines.isEmpty()) {
	                DebugLogger.log("SmartOCR", "未提取到任何数字，匹配终止")
	                return@withContext emptyMap()
	            }
	            val results = mutableMapOf<String, String>()
	            // 获取当前屏幕需要匹配的字段列表
	            val relativeRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
	            if (relativeRois.isEmpty()) {
	                DebugLogger.log("SmartOCR", "未找到当前屏幕的相对坐标配置，无法获取字段列表")
	                return@withContext emptyMap()
	            }
	            // 3. 根据屏幕类型采用不同匹配策略
	            when (screenIndex) {
	                0, 1 -> { // 蒸发器(0)和冷凝器(1)
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
	                    DebugLogger.log("SmartOCR", "分类结果 - 温度行数量: ${tempNums.size}, 压力行: ${pressureNum?.text ?: "无"}")
	                    for (roi in relativeRois) {
	                        val label = roi.label
	                        when {
	                            label.contains("冷媒压力") || label.contains("制冷剂压力") -> {
	                                pressureNum?.nums?.firstOrNull()?.let {
	                                    results[roi.fieldId] = it
	                                    DebugLogger.log("SmartOCR", "匹配成功: ${roi.label} = $it")
	                                }
	                            }
	                            label.contains("进水温度") || label.contains("回水温度") -> {
	                                tempNums.getOrNull(0)?.nums?.firstOrNull()?.let {
	                                    results[roi.fieldId] = it
	                                    DebugLogger.log("SmartOCR", "匹配成功: ${roi.label} = $it")
	                                }
	                            }
	                            label.contains("出水温度") -> {
	                                tempNums.getOrNull(1)?.nums?.firstOrNull()?.let {
	                                    results[roi.fieldId] = it
	                                    DebugLogger.log("SmartOCR", "匹配成功: ${roi.label} = $it")
	                                }
	                            }
	                            label.contains("饱和温度") || label.contains("蒸发温度") || label.contains("冷凝温度") -> {
	                                tempNums.getOrNull(2)?.nums?.firstOrNull()?.let {
	                                    results[roi.fieldId] = it
	                                    DebugLogger.log("SmartOCR", "匹配成功: ${roi.label} = $it")
	                                }
	                            }
	                        }
	                    }
	                }
	                2 -> { // 压缩机(2)
	                    var pressureNum: NumLineInfo? = null
	                    var currentNum: NumLineInfo? = null
	                    val tempNums = mutableListOf<NumLineInfo>()
	                    val pureNums = mutableListOf<NumLineInfo>() // 纯数字行，无单位(可能是%RLA)
	                    for (numLine in numLines) {
	                        val lowerText = numLine.text.lowercase()
	                        when {
	                            lowerText.contains("kpag") || lowerText.contains("kpa") || lowerText.contains("mpa") -> pressureNum = numLine
	                            lowerText.contains("amps") || lowerText.contains("amp") -> currentNum = numLine
	                            lowerText.contains("c") || lowerText.contains("℃") -> tempNums.add(numLine)
	                            else -> pureNums.add(numLine) // 例如 "50.5 45.6 46.7"
	                        }
	                    }
	                    DebugLogger.log("SmartOCR", "分类结果 - 温度行: ${tempNums.size}, 压力行: ${pressureNum?.text ?: "无"}, 电流行: ${currentNum?.text ?: "无"}, 纯数字行: ${pureNums.size}")
	                    for (roi in relativeRois) {
	                        val label = roi.label
	                        when {
	                            label.contains("油压") -> {
	                                pressureNum?.nums?.firstOrNull()?.let {
	                                    results[roi.fieldId] = it
	                                    DebugLogger.log("SmartOCR", "匹配成功: ${roi.label} = $it")
	                                }
	                            }
	                            label.contains("电流") -> {
	                                currentNum?.nums?.firstOrNull()?.let {
	                                    results[roi.fieldId] = it
	                                    DebugLogger.log("SmartOCR", "匹配成功: ${roi.label} = $it (取L1)")
	                                }
	                            }
	                            label.contains("排出") || label.contains("排气") -> {
	                                tempNums.getOrNull(0)?.nums?.firstOrNull()?.let {
	                                    results[roi.fieldId] = it
	                                    DebugLogger.log("SmartOCR", "匹配成功: ${roi.label} = $it")
	                                }
	                            }
	                            label.contains("负载") || label.contains("RLA") -> {
	                                pureNums.getOrNull(0)?.nums?.firstOrNull()?.let {
	                                    results[roi.fieldId] = it
	                                    DebugLogger.log("SmartOCR", "匹配成功: ${roi.label} = $it")
	                                }
	                            }
	                        }
	                    }
	                }
	            }
	            DebugLogger.log("SmartOCR", "最终提取结果: $results")
	            return@withContext results
	        } finally {
	            bitmap.recycle()
	        }
	    }
	}
