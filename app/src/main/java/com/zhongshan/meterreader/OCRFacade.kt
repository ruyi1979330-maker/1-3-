// 文件名: OCRFacade.kt
package com.zhongshan.meterreader

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.widget.Toast
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
        DebugLogger.log("OCR", "图片尺寸: width=${bitmap.width}, height=${bitmap.height}, source=$source")
        try {
            if (template.isHeatExchanger) {
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
            }

            val results = mutableMapOf<String, String>()
            val bmpWidth = bitmap.width; val bmpHeight = bitmap.height

            val rois = mutableListOf<Rect>()
            val fieldMapping = mutableListOf<String>()

            when (source) {
                ImageSource.CAMERA -> {
                    val hardRois = DeviceOcrStrategy.getHardcodedRois(template.machineId, screenIndex)
                    for (roi in hardRois) {
                        val left = (roi.xPercent * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
                        val top = (roi.yPercent * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
                        val right = ((roi.xPercent + roi.wPercent) * bmpWidth).toInt().coerceAtMost(bmpWidth)
                        val bottom = ((roi.yPercent + roi.hPercent) * bmpHeight).toInt().coerceAtMost(bmpHeight)
                        rois.add(Rect(left, top, right, bottom)); fieldMapping.add(roi.fieldId)
                    }
                }
                ImageSource.GALLERY -> {
                    val relRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
                    for (roi in relRois) {
                        val left = (roi.xStartPct * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
                        val top = (roi.yStartPct * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
                        val right = (roi.xEndPct * bmpWidth).toInt().coerceAtMost(bmpWidth)
                        val bottom = (roi.yEndPct * bmpHeight).toInt().coerceAtMost(bmpHeight)
                        rois.add(Rect(left, top, right, bottom)); fieldMapping.add(roi.fieldId)
                    }
                }
            }

            if (rois.isEmpty()) return@withContext emptyMap()

            val binarizeResult = UltimateLcdBinarizer.processBitmap(bitmap, rois, resourcePool)
            if (binarizeResult == null) {
                DebugLogger.log("OCR", "二值化拼版失败")
                return@withContext emptyMap()
            }

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionResult = recognizer.process(binarizeResult.inputImage).await()
            val textBlocks = visionResult.textBlocks
            val lines = textBlocks.flatMap { block -> block.lines }

            for (line in lines) {
                val box = line.boundingBox ?: continue
                val centerY = box.top + box.height() / 2
                var matchedRoiIndex = -1
                for (i in binarizeResult.roiYRanges.indices) {
                    val (top, bottom) = binarizeResult.roiYRanges[i]
                    if (top == -1) continue
                    if (centerY in top..bottom) { matchedRoiIndex = i; break }
                }
                if (matchedRoiIndex != -1 && matchedRoiIndex < fieldMapping.size) {
                    val text = line.text.trim()
                    val normalized = text.replace(",", ".").replace(":", ".")
                    val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(normalized)
                    val number = match?.value
                    if (!number.isNullOrEmpty()) {
                        val fieldId = fieldMapping[matchedRoiIndex]
                        results[fieldId] = number
                        DebugLogger.log("OCR", "几何对齐 -> 字段=$fieldId, 值=$number")
                    }
                }
            }

            DebugLogger.log("OCR", "最终识别结果: $results")
            return@withContext results
        } finally {
            bitmap.recycle()
        }
    }
}
