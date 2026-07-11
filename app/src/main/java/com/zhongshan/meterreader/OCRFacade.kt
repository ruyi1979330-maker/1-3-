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
            // 板交仍然走旧逻辑
            if (template.isHeatExchanger) {
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
            }

            // ==================== 螺杆机组：新流程 ====================
            val results = mutableMapOf<String, String>()
            val bmpWidth = bitmap.width
            val bmpHeight = bitmap.height

            val rois = mutableListOf<Rect>()
            val fieldMapping = mutableListOf<String>()

            when (source) {
                ImageSource.CAMERA -> {
                    val hardRois = DeviceOcrStrategy.getHardcodedRois(template.machineId, screenIndex)
                    DebugLogger.log("OCR", "CAMERA 模式，获取到 ${hardRois.size} 个绝对坐标 ROI")
                    for (roi in hardRois) {
                        val left = (roi.xPercent * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
                        val top = (roi.yPercent * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
                        val right = ((roi.xPercent + roi.wPercent) * bmpWidth).toInt().coerceAtMost(bmpWidth)
                        val bottom = ((roi.yPercent + roi.hPercent) * bmpHeight).toInt().coerceAtMost(bmpHeight)
                        rois.add(Rect(left, top, right, bottom))
                        fieldMapping.add(roi.fieldId)
                        DebugLogger.log("OCR-ROI", "绝对ROI: ${roi.fieldId} → Rect($left, $top, $right, $bottom)")
                    }
                }
                ImageSource.GALLERY -> {
                    val relRois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
                    DebugLogger.log("OCR", "GALLERY 模式，获取到 ${relRois.size} 个相对坐标 ROI")
                    for (roi in relRois) {
                        val left = (roi.xStartPct * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
                        val top = (roi.yStartPct * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
                        val right = (roi.xEndPct * bmpWidth).toInt().coerceAtMost(bmpWidth)
                        val bottom = (roi.yEndPct * bmpHeight).toInt().coerceAtMost(bmpHeight)
                        rois.add(Rect(left, top, right, bottom))
                        fieldMapping.add(roi.fieldId)
                        DebugLogger.log("OCR-ROI", "相对ROI: ${roi.fieldId} → Rect($left, $top, $right, $bottom)")
                    }
                }
            }

            if (rois.isEmpty()) {
                DebugLogger.log("OCR", "❌ ROI 列表为空，无法继续识别")
                return@withContext emptyMap()
            }

            // Step 1: 二值化拼版
            val inputImage = UltimateLcdBinarizer.processBitmap(bitmap, rois, resourcePool)
            if (inputImage == null) {
                DebugLogger.log("OCR", "❌ 二值化拼版失败，processBitmap 返回 null")
                return@withContext emptyMap()
            }
            DebugLogger.log("OCR", "✅ 二值化拼版成功")

            // Step 2: 构建几何映射区间
            val fieldYRanges = mutableListOf<Triple<Int, Int, String>>()
            var currentYOffset = 0
            for (i in rois.indices) {
                val roi = rois[i]
                val boxHeight = roi.height()
                if (boxHeight <= 0) continue
                fieldYRanges.add(Triple(currentYOffset, currentYOffset + boxHeight, fieldMapping[i]))
                currentYOffset += boxHeight + 4
            }
            DebugLogger.log("OCR", "几何映射区间: ${fieldYRanges.joinToString { "(${it.first}-${it.second}:${it.third.split("|").last()})" }}")

            // Step 3: ML Kit 识别
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionResult = recognizer.process(inputImage).await()
            val allText = visionResult.text
            DebugLogger.log("OCR-MLKit", "ML Kit 原始全文: \"$allText\"")
            DebugLogger.log("OCR-MLKit", "文本块数量: ${visionResult.textBlocks.size}")

            val lines = visionResult.textBlocks.flatMap { it.lines }
            DebugLogger.log("OCR-MLKit", "识别行数: ${lines.size}")

            for ((lineIdx, line) in lines.withIndex()) {
                val lineBox = line.boundingBox
                val lineText = line.text.trim()
                DebugLogger.log("OCR-MLKit", "行[$lineIdx]: text=\"$lineText\", boundingBox=$lineBox")

                if (lineBox != null) {
                    val lineCenterY = lineBox.centerY()
                    val matchedField = fieldYRanges.find { lineCenterY in it.first..it.second }?.third

                    if (matchedField != null) {
                        val normalized = lineText.replace(",", ".").replace(":", ".")
                        val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(normalized)
                        val number = match?.value
                        if (!number.isNullOrEmpty()) {
                            results[matchedField] = number
                            DebugLogger.log("OCR", "✅ 几何对齐成功: 字段=$matchedField, 值=$number")
                        } else {
                            DebugLogger.log("OCR", "⚠️ 几何对齐到字段=$matchedField，但文本\"$lineText\"中未提取到有效数字")
                        }
                    } else {
                        DebugLogger.log("OCR", "⚠️ 行[$lineIdx] centerY=$lineCenterY 未落入任何ROI区间")
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
