// 文件名: OCRFacade.kt
package com.zhongshan.meterreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.widget.Toast
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
import java.io.File

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
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "图片加载失败", Toast.LENGTH_LONG).show()
            }
            return@withContext emptyMap()
        }
        DebugLogger.log("OCR", "图片尺寸: width=${bitmap.width}, height=${bitmap.height}, source=$source")
        try {
            if (template.isHeatExchanger) {
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
            }

            val results = mutableMapOf<String, String>()
            val bmpWidth = bitmap.width
            val bmpHeight = bitmap.height
            val cropDir = File(context.externalCacheDir, "ocr_crops")
            if (!cropDir.exists()) cropDir.mkdirs()

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
                        rois.add(Rect(left, top, right, bottom))
                        fieldMapping.add(roi.fieldId)
                    }
                }
                ImageSource.GALLERY -> {
                    val relRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
                    for (roi in relRois) {
                        val left = (roi.xStartPct * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
                        val top = (roi.yStartPct * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
                        val right = (roi.xEndPct * bmpWidth).toInt().coerceAtMost(bmpWidth)
                        val bottom = (roi.yEndPct * bmpHeight).toInt().coerceAtMost(bmpHeight)
                        rois.add(Rect(left, top, right, bottom))
                        fieldMapping.add(roi.fieldId)
                    }
                }
            }

            if (rois.isEmpty()) return@withContext emptyMap()

            val inputImage = UltimateLcdBinarizer.processBitmap(bitmap, rois, resourcePool)
            if (inputImage == null) {
                DebugLogger.log("OCR", "二值化拼版失败")
                return@withContext emptyMap()
            }

            // 【架构师加固】动态重建画布拼版切片的垂直坐标映射区间
            val fieldYRanges = mutableListOf<Triple<Int, Int, String>>() // (开始Y, 结束Y, 字段ID)
            var currentYOffset = 0
            for (i in rois.indices) {
                val roi = rois[i]
                val startX = roi.left.coerceIn(0, bmpWidth - 1)
                val startY = roi.top.coerceIn(0, bmpHeight - 1)
                val endX = roi.right.coerceIn(0, bmpWidth)
                val endY = roi.bottom.coerceIn(0, bmpHeight)
                val boxWidth = endX - startX
                val boxHeight = endY - startY
                if (boxWidth <= 0 || boxHeight <= 0) continue
                
                // 将当前ROI在拼版大图上的上下边界和它的FieldId死死绑定在一起
                fieldYRanges.add(Triple(currentYOffset, currentYOffset + boxHeight, fieldMapping[i]))
                currentYOffset += boxHeight + 4
            }

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionResult = recognizer.process(inputImage).await()
            val lines = visionResult.textBlocks.flatMap { it.lines }

            // 【架构师加固】基于文本行的几何中心点Y坐标，反向检索所属字段。从根本上免疫任何丢行错位故障！
            for (line in lines) {
                val lineBox = line.boundingBox
                if (lineBox != null) {
                    val lineCenterY = lineBox.centerY()
                    // 寻找当前识别出来的行中心点落在哪一个ROI的区间内
                    val matchedField = fieldYRanges.find { lineCenterY in it.first..it.second }?.third
                    
                    if (matchedField != null) {
                        val text = line.text.trim()
                        val normalized = text.replace(",", ".").replace(":", ".")
                        val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(normalized)
                        val number = match?.value
                        if (!number.isNullOrEmpty()) {
                            results[matchedField] = number
                            DebugLogger.log("OCR", "几何精准对齐 -> 字段=$matchedField, 值=$number")
                        }
                    }
                }
            }

            return@withContext results
        } finally {
            bitmap.recycle()
        }
    }
}
