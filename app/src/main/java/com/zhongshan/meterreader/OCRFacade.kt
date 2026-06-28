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

            // 统一使用绝对坐标，废弃之前错误的相对坐标
            val rois = DeviceOcrStrategy.getHardcodedRois(template.machineId, screenIndex)
            if (rois.isEmpty()) return@withContext emptyMap()

            val results = mutableMapOf<String, String>()
            val bmpWidth = bitmap.width
            val bmpHeight = bitmap.height

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

            return@withContext results
        } finally {
            bitmap.recycle()
        }
    }
}
