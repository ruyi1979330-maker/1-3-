package com.zhongshan.meterreader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.StorageAndImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ImageSource { CAMERA, GALLERY }

object OCRFacade {

    suspend fun performSmartOcr(
        context: Context,
        imageUri: Uri,
        template: DeviceTemplate,
        screenIndex: Int,
        source: ImageSource
    ): Map<String, String> = withContext(Dispatchers.IO) {

        val bitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(context, imageUri)
        if (bitmap == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "图片加载失败", Toast.LENGTH_LONG).show()
            }
            return@withContext emptyMap()
        }

        try {
            // 板交全图识别（不受双轨影响）
            if (template.isHeatExchanger) {
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
            }

            val results = mutableMapOf<String, String>()
            val bmpWidth = bitmap.width
            val bmpHeight = bitmap.height

            when (source) {
                ImageSource.CAMERA -> {
                    val rois = DeviceOcrStrategy.getHardcodedRois(template.machineId, screenIndex)
                    if (rois.isEmpty()) return@withContext emptyMap()

                    for (roi in rois) {
                        val cropX = (roi.xPercent * bmpWidth).toInt().coerceIn(0, bmpWidth - 1)
                        val cropY = (roi.yPercent * bmpHeight).toInt().coerceIn(0, bmpHeight - 1)
                        val cropW = (roi.wPercent * bmpWidth).toInt().coerceAtMost(bmpWidth - cropX)
                        val cropH = (roi.hPercent * bmpHeight).toInt().coerceAtMost(bmpHeight - cropY)

                        if (cropW <= 10 || cropH <= 10) continue
                        val cropped = try {
                            Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                        } catch (e: Exception) { continue }

                        val number = OCREngine.extractPureNumber(cropped)
                        if (!number.isNullOrEmpty()) results[roi.fieldId] = number
                    }
                }
                ImageSource.GALLERY -> {
                    val rois = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)
                    if (rois.isEmpty()) return@withContext emptyMap()

                    for (roi in rois) {
                        val xStart = roi.xStartPct
                        val yStart = roi.yStartPct
                        val xEnd = roi.xEndPct
                        val yEnd = roi.yEndPct

                        // 向外扩大5%容错边距
                        val margin = 0.05f
                        val x0 = (xStart - margin).coerceIn(0f, 1f) * bmpWidth
                        val y0 = (yStart - margin).coerceIn(0f, 1f) * bmpHeight
                        val x1 = (xEnd + margin).coerceIn(0f, 1f) * bmpWidth
                        val y1 = (yEnd + margin).coerceIn(0f, 1f) * bmpHeight

                        val cropX = x0.toInt().coerceIn(0, bmpWidth - 1)
                        val cropY = y0.toInt().coerceIn(0, bmpHeight - 1)
                        val cropW = (x1 - x0).toInt().coerceAtMost(bmpWidth - cropX)
                        val cropH = (y1 - y0).toInt().coerceAtMost(bmpHeight - cropY)

                        if (cropW <= 10 || cropH <= 10) continue
                        val cropped = try {
                            Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                        } catch (e: Exception) { continue }

                        val number = OCREngine.extractPureNumber(cropped)
                        if (!number.isNullOrEmpty()) results[roi.fieldId] = number
                    }
                }
            }

            return@withContext results
        } finally {
            bitmap.recycle()
        }
    }
}
