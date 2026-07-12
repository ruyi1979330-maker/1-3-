	// 文件名: OCRFacade.kt
	package com.zhongshan.meterreader
	import android.content.Context
	import android.graphics.Bitmap
	import android.graphics.Matrix
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
	import kotlinx.coroutines.Dispatchers
	import kotlinx.coroutines.tasks.await
	import kotlinx.coroutines.withContext
	enum class ImageSource { CAMERA, GALLERY }
	object OCRFacade {
	    /**
	     * 阶段二/四：无感视频流识别接口
	     * 优化：将相机流直接转为 Bitmap，复用图库的全图 OCR 逻辑，彻底解决相机预览尺寸不匹配和黑边导致的 ROI 坐标错位问题。
	     */
	    suspend fun performStreamOcr(
	        imageProxy: ImageProxy,
	        template: DeviceTemplate,
	        screenIndex: Int,
	        resourcePool: BinarizeResourcePool
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        // 将 ImageProxy 转为 Bitmap 并处理旋转
	        val rawBitmap = imageProxy.toBitmap()
	        val rotation = imageProxy.imageInfo.rotationDegrees
	        val bitmap = if (rotation != 0) {
	            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
	            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true).also {
	                rawBitmap.recycle()
	            }
	        } else {
	            rawBitmap
	        }
	        try {
	            if (template.isHeatExchanger) {
	                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
	                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
	            }
	            return@withContext extractScrewDataFromBitmap(bitmap, template, screenIndex, "StreamOCR")
	        } finally {
	            bitmap.recycle()
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
	            if (template.isHeatExchanger) {
	                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
	                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
	            }
	            return@withContext extractScrewDataFromBitmap(bitmap, template, screenIndex, "SmartOCR")
	        } finally {
	            bitmap.recycle()
	        }
	    }
	    /**
	     * 螺杆机全图 OCR 与智能提取核心逻辑
	     * 统一供图库识别和相机流识别调用，抛弃坐标裁剪与二值化。
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
	            DebugLogger.log(tag, "行[$index] Y=${lineInfo.y.toInt()} X=${lineInfo.x.toInt()} Text='${lineInfo.text}'")
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
	            DebugLogger.log(tag, "数字行[$index] Y=${numLine.y.toInt()} Text='${numLine.text}' Nums=${numLine.nums}")
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
	            2 -> { // 压缩机(2)
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
	                            // 修复：精准匹配只包含数字和小数点及空格的行，排除 "0il L0Ss" 等干扰
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
	}
