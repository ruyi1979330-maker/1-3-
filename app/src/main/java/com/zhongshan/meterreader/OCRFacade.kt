	// 文件名: OCRFacade.kt
	package com.zhongshan.meterreader
	import android.content.Context
	import android.graphics.Bitmap
	import android.graphics.Rect
	import android.net.Uri
	import android.widget.Toast
	import androidx.camera.core.ImageProxy
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
	            // 修改：图库模式统一使用相对百分比坐标，适配任意尺寸的裁剪图
	            val relativeRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
	            if (relativeRois.isEmpty()) return@withContext emptyMap()
	            val imgWidth = bitmap.width
	            val imgHeight = bitmap.height
	            val rois = relativeRois.map { roi ->
	                Rect(
	                    (roi.xStartPct * imgWidth).toInt(),
	                    (roi.yStartPct * imgHeight).toInt(),
	                    (roi.xEndPct * imgWidth).toInt(),
	                    (roi.yEndPct * imgHeight).toInt()
	                )
	            }
	            val fieldMapping = relativeRois.map { it.fieldId }
	            val binarizeResult = UltimateLcdBinarizer.processBitmap(bitmap, rois, resourcePool)
	            if (binarizeResult == null) return@withContext emptyMap()
	            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	            val visionResult = recognizer.process(binarizeResult.inputImage).await()
	            val lines = visionResult.textBlocks.flatMap { block -> block.lines }
	            DebugLogger.log("SmartOCR", "相册识别到 ${lines.size} 行文本: ${lines.joinToString { it.text }}")
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
	            val finalResults = mutableMapOf<String, String>()
	            for ((index, fieldId) in fieldMapping.withIndex()) {
	                if (fieldId in results) {
	                    finalResults[fieldId] = results[fieldId]!!
	                }
	            }
	            DebugLogger.log("SmartOCR", "相册识别最终结果: $finalResults")
	            return@withContext finalResults
	        } finally {
	            bitmap.recycle()
	        }
	    }
	}
