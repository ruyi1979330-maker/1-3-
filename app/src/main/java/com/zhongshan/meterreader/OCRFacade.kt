package com.zhongshan.meterreader

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.util.OCRUtils
import com.zhongshan.meterreader.util.StorageAndImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OCRFacade {
    suspend fun performSmartOcr(
        context: Context,
        imageUri: Uri,
        template: DeviceTemplate,
        screenIndex: Int = 0
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val safeBitmap = StorageAndImageUtils.loadAndFixExifMatrixSecurely(context, imageUri)
            ?: return@withContext emptyMap()

        try {
            if (template.isHeatExchanger) {
                val keywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                return@withContext OCRUtils.recognizePlateScreenshot(safeBitmap, template.roomId == 1, keywordMap)
            } else {
                val w = safeBitmap.width
                val h = safeBitmap.height
                val screen = template.screens.getOrNull(screenIndex) ?: return@withContext emptyMap()

                val roiMap = screen.fields.associate { field ->
                    val r = field.roi
                    val rect = Rect(
                        (Math.max(0f, r.xPercent - 0.03f) * w).toInt(),
                        (Math.max(0f, r.yPercent - 0.03f) * h).toInt(),
                        (Math.min(1f, r.xPercent + r.widthPercent + 0.03f) * w).toInt(),
                        (Math.min(1f, r.yPercent + r.heightPercent + 0.03f) * h).toInt()
                    )
                    field.formFieldId to rect
                }
                return@withContext OCRUtils.recognizeFieldsParallel(safeBitmap, roiMap)
            }
        } finally {
            safeBitmap.recycle()
        }
    }
}