package com.zhongshan.meterreader

import android.content.Context
import android.net.Uri
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
            ?: return@withContext emptyMap()

        try {
            val lines = OCREngine.recognizeAllLines(bitmap)

            if (lines.isEmpty()) return@withContext emptyMap()

            if (template.isHeatExchanger) {
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                OCREngine.extractPlateData(lines, template.roomId == 1, plateKeywordMap)
            } else {
                DeviceOcrStrategy.extract(lines, template.machineId, screenIndex)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
