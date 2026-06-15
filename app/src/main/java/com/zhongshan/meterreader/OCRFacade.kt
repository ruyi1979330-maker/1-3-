package com.zhongshan.meterreader

import android.content.Context
import android.net.Uri
import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.StorageAndImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OCR 门面层
 * 统一调用入口，负责：
 *   1. 加载并修正 EXIF 旋转
 *   2. 对全图做一次 OCR（替换原来的 ROI 裁剪 + 多次 OCR）
 *   3. 根据设备类型分发到对应的提取策略
 *   4. 板交模式特殊处理
 */
object OCRFacade {

    suspend fun performSmartOcr(
        context: Context,
        imageUri: Uri,
        template: DeviceTemplate,
        screenIndex: Int
    ): Map<String, String> = withContext(Dispatchers.IO) {

        // 加载图片（自动修正 EXIF 旋转）
        val bitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(context, imageUri)
            ?: return@withContext emptyMap()

        try {
            // 全图 OCR（只做一次，效率更高）
            val lines = OCREngine.recognizeAllLines(bitmap)

            if (lines.isEmpty()) return@withContext emptyMap()

            if (template.isHeatExchanger) {
                // 板交模式：关键字状态机识别
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                OCREngine.extractPlateData(lines, template.roomId == 1, plateKeywordMap)
            } else {
                // 普通设备：按屏次提取对应字段
                DeviceOcrStrategy.extract(lines, template.machineId, screenIndex)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
