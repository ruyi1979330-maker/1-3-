// 文件名: OCRFacade.kt
package com.zhongshan.meterreader

import android.content.Context
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class ImageSource { CAMERA, GALLERY }

object OCRFacade {

    // 每屏要识别的字段标签（中文），和对应的字段ID，顺序保持一致
    private val screwFieldLabels = listOf(
        listOf(
            "蒸发器进口水温" to "field_1_01|蒸发器进口水温",
            "蒸发器出口水温" to "field_1_02|蒸发器出口水温",
            "蒸发器蒸发温度" to "field_1_06|蒸发器蒸发温度",
            "蒸发器冷媒压力" to "field_1_05|蒸发器冷媒压力"
        ),
        listOf(
            "冷凝器进口水温" to "field_1_08|冷凝器进口水温",
            "冷凝器出口水温" to "field_1_09|冷凝器出口水温",
            "冷凝器冷凝温度" to "field_1_13|冷凝器冷凝温度",
            "冷凝器冷媒压力" to "field_1_12|冷凝器冷媒压力"
        ),
        listOf(
            "压缩机油压" to "field_1_14|压缩机油压",
            "压缩机排出口温度" to "field_1_15|压缩机排出口温度",
            "主机负载" to "field_1_18|主机负载",
            "电机电流" to "field_1_17|电机电流"
        )
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

            // 直接使用原图
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionResult = recognizer.process(inputImage).await()

            // 收集所有文本行，并记录其boundingBox的中心Y坐标
            data class TextLine(val text: String, val centerY: Int, val left: Int, val right: Int)

            val allLines = mutableListOf<TextLine>()
            for (block in visionResult.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    allLines.add(TextLine(
                        text = line.text.trim(),
                        centerY = (box.top + box.bottom) / 2,
                        left = box.left,
                        right = box.right
                    ))
                }
            }

            // 按Y坐标从上到下排序
            allLines.sortBy { it.centerY }

            // 获取当前屏的字段标签列表
            val labels = screwFieldLabels.getOrNull(screenIndex) ?: return@withContext emptyMap()
            val results = mutableMapOf<String, String>()

            for ((label, fieldId) in labels) {
                // 查找包含该标签的行
                var labelLine: TextLine? = null
                for (line in allLines) {
                    if (line.text.contains(label) || label.contains(line.text)) {
                        labelLine = line
                        break
                    }
                }

                if (labelLine != null) {
                    // 在label的同一行或下一行，找标签右侧的数字
                    // 策略：找到与labelY坐标最接近、且在label右侧的数字行
                    var bestLine: TextLine? = null
                    var bestDist = Int.MAX_VALUE

                    for (line in allLines) {
                        val text = line.text.replace(",", ".").replace(":", ".")
                        val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(text)
                        if (match != null) {
                            // 检查该数字行是否在标签行的同一行或附近（Y坐标差很小）
                            // 且该行在标签的右侧（left >= labelLine.left）
                            val yDist = kotlin.math.abs(line.centerY - labelLine!!.centerY)
                            if (yDist < bitmap.height * 0.03 && line.left >= labelLine!!.left) {
                                if (yDist < bestDist) {
                                    bestDist = yDist
                                    bestLine = line
                                }
                            }
                        }
                    }

                    if (bestLine != null) {
                        val text = bestLine.text.replace(",", ".").replace(":", ".")
                        val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(text)
                        if (match != null) {
                            results[fieldId] = match.value
                            DebugLogger.log("OCR", "标签匹配: $label -> ${match.value}")
                        }
                    } else {
                        DebugLogger.log("OCR", "标签 $label 未找到对应数字")
                    }
                } else {
                    DebugLogger.log("OCR", "未找到标签: $label")
                }
            }

            DebugLogger.log("OCR", "最终识别结果: $results")
            return@withContext results
        } finally {
            bitmap.recycle()
        }
    }
}
