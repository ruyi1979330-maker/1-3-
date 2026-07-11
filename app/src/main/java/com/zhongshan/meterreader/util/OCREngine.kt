package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object OCREngine {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // LCD点阵屏专属字符纠错字典
    private val lcdCharCorrection = mapOf(
        'O' to '0', 'o' to '0', 'Q' to '0', 'D' to '0',
        'l' to '1', 'I' to '1', 'i' to '1', '|' to '1', ']' to '1',
        'Z' to '2', 'z' to '2',
        'S' to '5', 's' to '5',
        'B' to '8', 'b' to '8',
        'g' to '9', 'q' to '9', 'G' to '9'
    )

    // ====================== 新增：Sprite Sheet精准识别（实时扫码/高质量模式） ======================
    /**
     * 基于稀疏ROI的精准识别
     * @param processResult 预处理后的Sprite结果
     * @param templateConfig 仪表模板配置
     * @return key=业务字段key，value=识别数值
     */
    suspend fun recognizeBySprite(
        processResult: UltimateLcdBinarizer.ProcessResult,
        templateConfig: MeterTemplateConfig
    ): Map<String, String> = withContext(Dispatchers.Default) {
        val resultMap = mutableMapOf<String, String>()
        try {
            val ocrResult = recognizer.process(processResult.inputImage).await()

            // 提取所有文本行，按中心Y坐标排序
            val textLines = ocrResult.textBlocks.flatMap { it.lines }
                .mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val centerY = (box.top + box.bottom) / 2
                    centerY to line.text.trim()
                }
                .sortedBy { it.first }

            // 按Y坐标区间精准匹配字段，彻底杜绝错位
            for ((centerY, text) in textLines) {
                val matchedField = processResult.fieldYRangeMap.entries.firstOrNull { (_, range) ->
                    centerY in range
                } ?: continue

                val cleanValue = cleanLcdNumber(text) ?: continue
                val fieldKey = matchedField.key

                // 业务范围前置校验，过滤离谱结果
                val validRange = templateConfig.validRangeMap[fieldKey]
                if (validRange != null) {
                    val num = cleanValue.toFloatOrNull() ?: continue
                    if (num !in validRange) continue
                }

                resultMap[fieldKey] = cleanValue
            }
            DebugLogger.log("OCR-Sprite", "识别结果: $resultMap")
        } catch (e: Exception) {
            DebugLogger.log("OCR-Error", "Sprite识别异常: ${Log.getStackTraceString(e)}")
        }
        return@withContext resultMap
    }

    // ====================== 原有接口修复（拍照/相册模式直接提效） ======================
    /**
     * 提取纯数字（兼容原有接口）
     * 修复：移除bitmap.recycle()，新增预处理+字符纠错，优化正则
     */
    suspend fun extractPureNumber(bitmap: Bitmap): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            // LCD屏专属预处理：黄底漂白+对比度增强
            val enhancedBitmap = enhanceLcdBitmap(bitmap)
            val image = InputImage.fromBitmap(enhancedBitmap, 0)
            val result = recognizer.process(image).await()
            val rawText = result.text.trim()

            val elementInfo = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }.joinToString(" | ") {
                "'${it.text}' at [${it.boundingBox?.left},${it.boundingBox?.top},${it.boundingBox?.right},${it.boundingBox?.bottom}]"
            }
            DebugLogger.log("OCR-Element", "元素信息: $elementInfo")

            val finalNumber = cleanLcdNumber(rawText)
            return@withContext Pair(rawText, finalNumber)
        } catch (e: Exception) {
            DebugLogger.log("OCR-Error", "extractPureNumber 异常: ${Log.getStackTraceString(e)}")
            return@withContext Pair(null, null)
        }
        // 移除finally中的bitmap.recycle()，外部传入的bitmap由调用方管理生命周期
    }

    /**
     * 板交识别（兼容原有接口）
     * 修复：废弃Y坐标盲分组，改用关键词锚点匹配，彻底解决数据错位
     */
    suspend fun extractPlateData(
        bitmap: Bitmap,
        isRoom1: Boolean,
        plateKeywordMap: Map<String, String>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val outData = HashMap<String, String>()
        try {
            DebugLogger.log("OCR-Plate-Debug", "开始板交识别，原图尺寸: ${bitmap.width}x${bitmap.height}")
            
            // LCD预处理
            val enhancedBitmap = enhanceLcdBitmap(bitmap)
            val image = InputImage.fromBitmap(enhancedBitmap, 0)
            val result = recognizer.process(image).await()

            // 按行提取文本，关键词匹配取值
            val lines = result.textBlocks.flatMap { it.lines }
                .map { it.text.trim() }

            for (lineText in lines) {
                // 匹配关键词，支持部分命中
                val matchedKeyword = plateKeywordMap.keys.firstOrNull { keyword ->
                    lineText.contains(keyword, ignoreCase = true)
                } ?: continue

                val cleanValue = cleanLcdNumber(lineText) ?: continue
                outData[plateKeywordMap[matchedKeyword] ?: matchedKeyword] = cleanValue
            }

            DebugLogger.log("OCR-Plate-Debug", "最终映射结果: $outData")
        } catch (e: Exception) {
            DebugLogger.log("OCR-Plate-Error", "板交识别发生异常: ${Log.getStackTraceString(e)}")
            e.printStackTrace()
        }
        return@withContext outData
    }

    // ====================== 内部工具方法 ======================
    /** LCD数字清洗：字符纠错 + 正则提取（兼容整数+小数） */
    private fun cleanLcdNumber(raw: String): String? {
        val corrected = raw.map { lcdCharCorrection[it] ?: it }.joinToString("")
        val normalized = corrected.replace(",", ".").replace(":", ".")
        // 优化正则：1-4位整数，1-2位小数可选，不再强制小数点
        return Regex("""\d{1,4}(\.\d{1,2})?""").find(normalized)?.value
    }

    /** 黄底LCD屏基础预处理：灰度化+对比度拉伸（兼容原有Bitmap输入） */
    private fun enhanceLcdBitmap(original: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colorMatrix = ColorMatrix().apply {
            // 黄底漂白：提取绿通道对比度最好，提亮背景为纯白
            set(floatArrayOf(
                0f, 1f, 0f, 0f, -20f,
                0f, 1f, 0f, 0f, -20f,
                0f, 0f, 1.2f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            // 叠加高对比度，让点阵文字边缘锐利
            val contrast = 1.8f
            val brightness = -30f
            postConcat(ColorMatrix().apply {
                set(floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                ))
            })
        }
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
            isFilterBitmap = true
        }
        canvas.drawBitmap(original, 0f, 0f, paint)
        return bmp
    }
}
