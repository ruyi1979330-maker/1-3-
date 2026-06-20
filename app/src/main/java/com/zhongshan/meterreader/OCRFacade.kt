package com.zhongshan.meterreader

import android.content.Context
import android.graphics.Bitmap // 【关键修复】：补上了这一行！
import android.net.Uri
import android.widget.Toast
import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.StorageAndImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OCRFacade {

    suspend fun performSmartOcr(
        context: Context,
        imageUri: Uri,
        template: DeviceTemplate,
        screenIndex: Int
    ): Map<String, String> = withContext(Dispatchers.IO) {

        val bitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(context, imageUri)
        if (bitmap == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "【调试】图片加载失败，可能是相册权限或文件损坏", Toast.LENGTH_LONG).show()
            }
            return@withContext emptyMap()
        }

        try {
            // 1. 板交全图识别逻辑（保持不变）
            if (template.isHeatExchanger) {
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
            }

            // 2. 硬编码截图坐标识别逻辑
            val rois = DeviceOcrStrategy.getHardcodedRois(template.machineId, screenIndex)

            if (rois.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "【调试】严重错误：未能找到 ${template.machineId} 第 ${screenIndex} 屏的坐标配置！", Toast.LENGTH_LONG).show()
                }
                return@withContext emptyMap()
            }

            val results = mutableMapOf<String, String>()
            val bmpWidth = bitmap.width
            val bmpHeight = bitmap.height

            for (roi in rois) {
                // 根据百分比计算实际像素
                val cropX = (roi.xPercent * bmpWidth).toInt()
                val cropY = (roi.yPercent * bmpHeight).toInt()
                val cropW = (roi.wPercent * bmpWidth).toInt()
                val cropH = (roi.hPercent * bmpHeight).toInt()

                // 边界保护
                val safeX = cropX.coerceAtLeast(0).coerceAtMost(bmpWidth - 1)
                val safeY = cropY.coerceAtLeast(0).coerceAtMost(bmpHeight - 1)
                val safeW = cropW.coerceAtMost(bmpWidth - safeX)
                val safeH = cropH.coerceAtMost(bmpHeight - safeY)

                if (safeW <= 10 || safeH <= 10) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "【调试】错误：裁切区域 ${roi.label} 太小或超出屏幕边界（坐标偏移导致）", Toast.LENGTH_SHORT).show()
                    }
                    continue
                }

                // 裁切
                val croppedBmp = try {
                    Bitmap.createBitmap(bitmap, safeX, safeY, safeW, safeH)
                } catch (e: Exception) {
                    continue
                }

                // 识别
                val number = OCREngine.extractPureNumber(croppedBmp)
                if (number != null && number.isNotEmpty()) {
                    results[roi.fieldId] = number
                }
            }

            // 最终是否识别到了有效数据
            if (results.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "【调试】坐标可能偏移，未能从图片中提取出任何有效数字。请发此测试原图给我排查。", Toast.LENGTH_LONG).show()
                }
            }

            return@withContext results
        } finally {
            bitmap.recycle()
        }
    }
}
