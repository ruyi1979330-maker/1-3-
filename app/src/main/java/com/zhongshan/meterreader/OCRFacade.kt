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
	    private const val TARGET_WIDTH = 3000
	    private const val TARGET_HEIGHT = 4000
	    private val evaporatorRois = listOf(
	        Rect(1860, 920, 2850, 1320) to "field_1_01|蒸发器进口水温",
	        Rect(1860, 1400, 2850, 1800) to "field_1_02|蒸发器出口水温",
	        Rect(1860, 1880, 2850, 2280) to "field_1_06|蒸发器蒸发温度",
	        Rect(1740, 2360, 2850, 2800) to "field_1_05|蒸发器冷媒压力"
	    )
	    private val condenserRois = listOf(
	        Rect(1860, 920, 2850, 1320) to "field_1_08|冷凝器进口水温",
	        Rect(1860, 1400, 2850, 1800) to "field_1_09|冷凝器出口水温",
	        Rect(1860, 1880, 2850, 2280) to "field_1_13|冷凝器冷凝温度",
	        Rect(1740, 2360, 2850, 2800) to "field_1_12|冷凝器冷媒压力"
	    )
	    private val compressorRois = listOf(
	        Rect(150, 920, 1200, 1400) to "field_1_14|压缩机油压",
	        Rect(1740, 1880, 2850, 2280) to "field_1_15|压缩机排出口温度",
	        Rect(1050, 2520, 2100, 3040) to "field_1_18|主机负载",
	        Rect(1050, 3120, 2160, 3640) to "field_1_17|电机电流"
	    )
	    private fun getRoisForScreen(screenIndex: Int): List<Pair<Rect, String>> {
	        return when (screenIndex) {
	            0 -> evaporatorRois
	            1 -> condenserRois
	            2 -> compressorRois
	            else -> emptyList()
	        }
	    }
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
	        if (relativeRois.isEmpty()) return@withContext emptyMap()
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
	        if (binarizeResult == null) return@withContext emptyMap()
	        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	        val visionResult = recognizer.process(binarizeResult.inputImage).await()
	        val lines = visionResult.textBlocks.flatMap { block -> block.lines }
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
	        for (fieldId in fieldMapping) {
	            if (fieldId in results) {
	                finalResults[fieldId] = results[fieldId]!!
	            }
	        }
	        return@withContext finalResults
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
	            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, TARGET_WIDTH, TARGET_HEIGHT, true)
	            val roisWithFields = getRoisForScreen(screenIndex)
	            val rois = roisWithFields.map { it.first }
	            val fieldMapping = roisWithFields.map { it.second }
	            val binarizeResult = UltimateLcdBinarizer.processBitmap(scaledBitmap, rois, resourcePool)
	            scaledBitmap.recycle()
	            if (binarizeResult == null) return@withContext emptyMap()
	            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	            val visionResult = recognizer.process(binarizeResult.inputImage).await()
	            val lines = visionResult.textBlocks.flatMap { block -> block.lines }
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
	            return@withContext finalResults
	        } finally {
	            bitmap.recycle()
	        }
	    }
	}
