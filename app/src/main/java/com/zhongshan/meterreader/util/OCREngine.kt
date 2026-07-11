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
import com.zhongshan.meterreader.DebugLogger
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

    // ====================== 新增：Sprite Sheet精准识别 ======================
    suspend fun recognizeBySprite(
        processResult: UltimateLcdBinarizer.ProcessResult,
        templateConfig: MeterTemplateConfig
    ): Map<String, String> = withContext(Dispatchers.Default) {
        val resultMap = mutableMapOf<String, String>()
        try {
            val ocrResult = recognizer.process(processResult.inputImage).await()

            val textLines = ocrResult.textBlocks.flatMap { it.lines }
                .mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val centerY = (box.top + box.bottom) / 2
                    centerY to line.text.trim()
                }
                .sortedBy { it.first }

            for ((centerY, text) in textLines) {
                val matchedField = processResult.fieldYRangeMap.entries.firstOrNull { (_, range) ->
                    centerY in range
                } ?: continue

                val cleanValue = cleanLcdNumber(text) ?: continue
                val fieldKey = matchedField.key

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

    // ====================== 原有接口修复（拍照/相册模式） ======================
    suspend fun extractPureNumber(bitmap: Bitmap): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
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
    }

    suspend fun extractPlateData(
        bitmap: Bitmap,
        isRoom1: Boolean,
        plateKeywordMap: Map<String, String>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val outData = HashMap<String, String>()
        try {
            DebugLogger.log("OCR-Plate-Debug", "开始板交识别，原图尺寸: ${bitmap.width}x${bitmap.height}")
            
            val enhancedBitmap = enhanceLcdBitmap(bitmap)
            val image = InputImage.fromBitmap(enhancedBitmap, 0)
            val result = recognizer.process(image).await()

            val lines = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }

            for (lineText in lines) {
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
    private fun cleanLcdNumber(raw: String): String? {
        val corrected = raw.map { lcdCharCorrection[it] ?: it }.joinToString("")
        val normalized = corrected.replace(",", ".").replace(":", ".")
        return Regex("""\d{1,4}(\.\d{1,2})?""").find(normalized)?.value
    }

    private fun enhanceLcdBitmap(original: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val colorMatrix = ColorMatrix().apply {
            set(floatArrayOf(
                0f, 1f, 0f, 0f, -20f,
                0f, 1f, 0f, 0f, -20f,
                0f, 0f, 1.2f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
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
