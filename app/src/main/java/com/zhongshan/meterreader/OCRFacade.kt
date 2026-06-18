package com.zhongshan.meterreader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.zhongshan.meterreader.data.RoiBox
import com.zhongshan.meterreader.data.RoiConfigManager
import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.StorageAndImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OCRFacade {

    suspend fun performSmartOcr(
        context: Context,
        imageUri: Uri,
        template: com.zhongshan.meterreader.data.DeviceTemplate,
        screenIndex: Int
    ): Map<String, String> = withContext(Dispatchers.IO) {

        val bitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(context, imageUri)
            ?: return@withContext emptyMap()

        // 1. 获取管理员在定标器里点好的坐标配置
        val rois = RoiConfigManager.getRoiConfigs(template.machineId, screenIndex)

        // 如果还没有配置过坐标，提示返回空
        if (rois.isEmpty()) {
            bitmap.recycle()
            return@withContext emptyMap()
        }

        val results = mutableMapOf<String, String>()

        try {
            // 2. 循环裁切并识别
            for (roi in rois) {
                // 根据百分比计算实际像素
                val cropX = (roi.xPercent * bitmap.width).toInt()
                val cropY = (roi.yPercent * bitmap.height).toInt()
                val cropW = (roi.wPercent * bitmap.width).toInt()
                val cropH = (roi.hPercent * bitmap.height).toInt()

                // 边界保护
                val safeX = cropX.coerceAtLeast(0).coerceAtMost(bitmap.width - 1)
                val safeY = cropY.coerceAtLeast(0).coerceAtMost(bitmap.height - 1)
                val safeW = cropW.coerceAtMost(bitmap.width - safeX)
                val safeH = cropH.coerceAtMost(bitmap.height - safeY)

                if (safeW <= 0 || safeH <= 0) continue

                // 裁切
                val croppedBmp = try {
                    Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
                } catch (e: Exception) { continue }

                // 识别并清洗数字
                val number = OCREngine.extractPureNumber(croppedBmp)
                if (number != null && number.isNotEmpty()) {
                    results[roi.fieldId] = number
                }
            }
        } finally {
            bitmap.recycle()
        }
        return@withContext results
    }
}
