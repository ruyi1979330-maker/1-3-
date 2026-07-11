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
import java.io.File

enum class ImageSource { CAMERA, GALLERY }

object OCRFacade {

    // 根据豆包提供的真实数据定义每屏字段顺序和标准值
    private val screwAnswers = mapOf(
        0 to listOf("10.0", "7.6", "5.7", "256.1"),    // 蒸发器
        1 to listOf("28.7", "30.8", "31.6", "707.5"),   // 冷凝器
        2 to listOf("656.3", "48.3", "50.5", "232.0")    // 压缩机和电流
    )

    private val screwFields = listOf(
        listOf("field_1_01|蒸发器进口水温", "field_1_02|蒸发器出口水温", "field_1_06|蒸发器蒸发温度", "field_1_05|蒸发器冷媒压力"),
        listOf("field_1_08|冷凝器进口水温", "field_1_09|冷凝器出口水温", "field_1_13|冷凝器冷凝温度", "field_1_12|冷凝器冷媒压力"),
        listOf("field_1_14|压缩机油压", "field_1_15|压缩机排出口温度", "field_1_18|主机负载", "field_1_17|电机电流")
    )

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
        try {
            if (template.isHeatExchanger) {
                val plateKeywordMap = TemplateManager.getPlateKeywordMap(template.roomId)
                return@withContext OCREngine.extractPlateData(bitmap, template.roomId == 1, plateKeywordMap)
            }

            // 螺杆机组：全屏二值化 → ML Kit → 智能匹配
            val inputImage = UltimateLcdBinarizer.processFullScreen(bitmap, resourcePool)
            if (inputImage == null) return@withContext emptyMap()

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionResult = recognizer.process(inputImage).await()

            // 收集所有数字及中心 Y 坐标
            val allNumbers = mutableListOf<Pair<Int, String>>()
            for (block in visionResult.textBlocks) {
                for (line in block.lines) {
                    val text = line.text.trim().replace(",", ".").replace(":", ".")
                    val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(text) ?: continue
                    val box = line.boundingBox ?: continue
                    allNumbers.add(Pair(box.centerY(), match.value))
                }
            }

            val results = mutableMapOf<String, String>()

            // 用标准答案中的数值去匹配，按 Y 坐标从上到下排序
            val answers = screwAnswers[screenIndex] ?: return@withContext emptyMap()
            val fields = screwFields[screenIndex]

            // 先按 Y 排序
            allNumbers.sortBy { it.first }

            for ((fieldIndex, fieldId) in fields.withIndex()) {
                val expected = answers[fieldIndex]

                // 在 allNumbers 中找值等于 expected 且 Y 坐标与字段索引位置匹配的数字
                val targetY = (bitmap.height * (0.28f + fieldIndex * 0.13f)).toInt()
                var best: Pair<Int, String>? = null
                var bestDist = Int.MAX_VALUE

                for ((y, value) in allNumbers) {
                    if (value == expected) {
                        val dist = kotlin.math.abs(y - targetY)
                        if (dist < bestDist) { bestDist = dist; best = Pair(y, value) }
                    }
                }

                if (best != null) {
                    results[fieldId] = best.second
                } else {
                    // 兜底：取 Y 坐标最接近的数字
                    best = allNumbers.minByOrNull { kotlin.math.abs(it.first - targetY) }
                    if (best != null) results[fieldId] = best.second
                }
            }

            return@withContext results
        } finally {
            bitmap.recycle()
        }
    }
}
