package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ArrayBlockingQueue

object OCRUtils {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val REUSABLE_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    private val OPTIMAL_CONCURRENCY = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() / 2, 4))
    private val ocrSemaphore = Semaphore(OPTIMAL_CONCURRENCY)

    suspend fun recognizeFieldsParallel(
        sourceBitmap: Bitmap,
        roiMap: Map<String, Rect>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val outData = java.util.concurrent.ConcurrentHashMap<String, String>()

        val validRoiMap = roiMap.filter { (_, rect) ->
            rect.left >= 0 && rect.top >= 0 &&
            rect.right <= sourceBitmap.width && rect.bottom <= sourceBitmap.height &&
            rect.width() > 0 && rect.height() > 0
        }
        if (validRoiMap.isEmpty()) return@withContext outData

        val maxW = validRoiMap.values.maxOf { it.width() }
        val maxH = validRoiMap.values.maxOf { it.height() }

        val bitmapPool = ArrayBlockingQueue<Bitmap>(OPTIMAL_CONCURRENCY)
        for (i in 0 until OPTIMAL_CONCURRENCY) {
            bitmapPool.offer(Bitmap.createBitmap(maxW, maxH, Bitmap.Config.ARGB_8888))
        }

        val deferredTasks = validRoiMap.entries.map { (fieldId, rect) ->
            async {
                ocrSemaphore.withPermit {
                    val pooledBitmap = bitmapPool.take()
                    try {
                        val canvas = Canvas(pooledBitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(
                            sourceBitmap,
                            rect,
                            Rect(0, 0, rect.width(), rect.height()),
                            REUSABLE_PAINT
                        )

                        val image = InputImage.fromBitmap(pooledBitmap, 0)
                        val visionText = recognizer.process(image).await()

                        val cleanedText = cleanOcrText(visionText.text)
                        if (cleanedText.isNotEmpty()) {
                            outData[fieldId] = cleanedText
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bitmapPool.offer(pooledBitmap)
                    }
                }
            }
        }

        deferredTasks.awaitAll()
        while (bitmapPool.isNotEmpty()) {
            bitmapPool.poll()?.recycle()
        }
        return@withContext outData
    }

    suspend fun recognizePlateScreenshot(
        screenshotBitmap: Bitmap,
        isRoom1: Boolean,
        plateKeywordMap: Map<String, String>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val outData = HashMap<String, String>()
        try {
            val image = InputImage.fromBitmap(screenshotBitmap, 0)
            val visionText = recognizer.process(image).await()
            val textLines = visionText.textBlocks
                .flatMap { it.lines }
                .sortedBy { it.boundingBox?.top ?: 0 }
                .map { it.text }

            var currentBjPrefix: String? = null

            textLines.forEachIndexed { index, lineText ->
                val matchedKeyword = plateKeywordMap.keys.find { lineText.contains(it) }
                if (matchedKeyword != null) currentBjPrefix = plateKeywordMap[matchedKeyword]
                if (currentBjPrefix == null) return@forEachIndexed

                if (lineText.contains("进水温度") || lineText.contains("进口温度"))
                    extractNextNumericValue(textLines, index)?.let { outData["${currentBjPrefix}1"] = it }
                if (lineText.contains("出水温度") || lineText.contains("出口温度"))
                    extractNextNumericValue(textLines, index)?.let { outData["${currentBjPrefix}2"] = it }
                if (lineText.contains("进水压力") || lineText.contains("进口压力"))
                    extractNextNumericValue(textLines, index)?.let { outData["${currentBjPrefix}3"] = it }
                if (lineText.contains("出水压力") || lineText.contains("出口压力"))
                    extractNextNumericValue(textLines, index)?.let { outData["${currentBjPrefix}4"] = it }
                if (lineText.contains("蒸汽压力"))
                    extractNextNumericValue(textLines, index)?.let { outData["${currentBjPrefix}5"] = it }
                if (isRoom1 && lineText.contains("水泵电流"))
                    extractNextNumericValue(textLines, index)?.let { outData["${currentBjPrefix}6"] = it }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext outData
    }

    private fun extractNextNumericValue(lines: List<String>, currentIndex: Int): String? {
        val regex = """\d+(\.\d+)?""".toRegex()
        val searchEnd = minOf(currentIndex + 4, lines.size)
        // 核心修复：建立白名单屏蔽墙，暴力抹除干扰数字
        val noisePattern = Regex("[0-9]+号[楼]?|[0-9]+#|板交|进[水口]温度|出[水口]温度|进[水口]压力|出[水口]压力|蒸汽压力|水泵电流|请填写数值|℃|MPa|KPa|A|%", RegexOption.IGNORE_CASE)
        
        for (i in currentIndex until searchEnd) {
            val cleanedLine = lines[i].replace(noisePattern, "")
            val candidate = regex.find(cleanedLine)?.value ?: continue
            val cleaned = cleanOcrText(candidate)
            if (cleaned.isNotEmpty()) return cleaned
        }
        return null
    }

    fun cleanOcrText(raw: String): String {
        val normalized = raw.replace("O", "0").replace("o", "0")
            .replace("l", "1").replace("I", "1")
            .replace(",", ".")
            .replace("℃", "")
            .replace(" ", "")
            .replace("\n", "").replace("\r", "")
            .replace("-", "")

        return Regex("""\d+(\.\d+)?""").find(normalized)?.value ?: ""
    }
}
