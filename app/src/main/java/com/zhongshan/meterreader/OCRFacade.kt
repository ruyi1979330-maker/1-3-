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
	import kotlin.math.abs
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
	     * 优化：针对螺杆机图库图片，彻底抛弃坐标裁剪与二值化，直接全图OCR并基于“语义+空间布局”智能提取数值。
	     * 完美解决图库图片带黑边、裁剪不一致、坐标偏移导致识别为空的问题。
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
	            // === 螺杆机图库模式：全图OCR + 语义布局智能提取 ===
	            DebugLogger.log("SmartOCR", "开始螺杆机原图直接识别，尺寸: ${bitmap.width}x${bitmap.height}")
	            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	            val image = InputImage.fromBitmap(bitmap, 0)
	            val visionResult = recognizer.process(image).await()
	            val lines = visionResult.textBlocks.flatMap { it.lines }
	            DebugLogger.log("SmartOCR", "原图识别到 ${lines.size} 行文本: ${lines.joinToString { it.text }}")
	            data class LineInfo(val y: Float, val x: Float, val text: String, val originalText: String)
	            val lineInfos = lines.mapNotNull { line ->
	                val box = line.boundingBox ?: return@mapNotNull null
	                LineInfo(
	                    y = box.exactCenterY(),
	                    x = box.exactCenterX(),
	                    text = line.text.replace(" ", "").replace("\n", ""),
	                    originalText = line.text
	                )
	            }
	            val results = mutableMapOf<String, String>()
	            // 辅助函数：从一段文本中提取数值
	            fun extractNumber(text: String): String? {
	                // 优先匹配带小数的数值（如 10.0, 256.1, 232.0）
	                val match = Regex("""\d{1,4}\.\d{1,2}""").find(text)
	                if (match != null) return match.value
	                // 其次匹配纯整数（如 707）
	                val intMatch = Regex("""\d{1,4}""").find(text)
	                return intMatch?.value
	            }
	            // 获取当前屏幕需要匹配的字段列表
	            val relativeRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
	            if (relativeRois.isEmpty()) {
	                DebugLogger.log("SmartOCR", "未找到当前屏幕的相对坐标配置，无法获取字段列表")
	                return@withContext emptyMap()
	            }
	            // 记录已被使用的数值行，避免多个标签匹配到同一个数值
	            val usedLines = mutableSetOf<LineInfo>()
	            for (roi in relativeRois) {
	                val fieldId = roi.fieldId
	                val label = roi.label.replace(" ", "")
	                // 1. 寻找包含标签核心关键字的文本行
	                var bestLabelLine: LineInfo? = null
	                for (line in lineInfos) {
	                    // 提取核心关键字，兼容识别误差和简写
	                    val coreKeyword = when {
	                        label.contains("进水温度") -> "进水温度"
	                        label.contains("出水温度") -> "出水温度"
	                        label.contains("饱和温度") -> "饱和温度"
	                        label.contains("制冷剂压力") || label.contains("冷媒压力") -> "压力"
	                        label.contains("油压") -> "油压"
	                        label.contains("排出") -> "排出"
	                        label.contains("电流") -> "电流"
	                        label.contains("%RLA") || label.contains("主机负载") -> "RLA"
	                        else -> label
	                    }
	                    if (line.text.contains(coreKeyword)) {
	                        bestLabelLine = line
	                        break
	                    }
	                }
	                if (bestLabelLine != null) {
	                    // 2. 首先尝试直接从标签行自身提取数字 (如 "油压 656.3")
	                    var num = extractNumber(bestLabelLine.originalText)
	                    if (num != null) {
	                        results[fieldId] = num
	                        usedLines.add(bestLabelLine)
	                        continue
	                    }
	                    // 3. 如果标签行没有数字，寻找得分最低的含数字行 (综合 Y 距离和 X 右侧倾向)
	                    // 原理：特灵屏幕数值均在标签右侧或同一行，Y 坐标极度接近
	                    var closestNumLine: LineInfo? = null
	                    var minScore = Float.MAX_VALUE
	                    for (line in lineInfos) {
	                        if (line === bestLabelLine || line in usedLines) continue
	                        val lineNum = extractNumber(line.originalText)
	                        if (lineNum != null) {
	                            val yDist = abs(line.y - bestLabelLine.y)
	                            // 偏好右侧的数值行：右侧惩罚系数 0.5，左侧惩罚系数 2.0
	                            val xDist = if (line.x > bestLabelLine.x) abs(line.x - bestLabelLine.x) * 0.5f else abs(line.x - bestLabelLine.x) * 2f
	                            val score = yDist + xDist
	                            if (score < minScore) {
	                                minScore = score
	                                closestNumLine = line
	                            }
	                        }
	                    }
	                    if (closestNumLine != null) {
	                        num = extractNumber(closestNumLine.originalText)
	                        if (num != null) {
	                            results[fieldId] = num
	                            usedLines.add(closestNumLine)
	                        }
	                    }
	                }
	            }
	            DebugLogger.log("SmartOCR", "语义智能匹配最终结果: $results")
	            return@withContext results
	        } finally {
	            bitmap.recycle()
	        }
	    }
	}
