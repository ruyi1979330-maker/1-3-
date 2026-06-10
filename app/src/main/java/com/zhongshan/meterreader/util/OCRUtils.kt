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

    /**
     * 板交整图识别。
     *
     * Bug Fix 2（板交识别）：
     * 原代码状态机逻辑存在两处缺陷：
     *
     * 缺陷A（主要）：当关键字和字段名出现在同一行（如 "1号板交 进水温度 12.5"）时，
     *   matchedKeyword != null 分支设置了 currentBjPrefix，
     *   紧接着 if(currentBjPrefix == null) return@forEachIndexed 判断此时已经是非null，
     *   但同一行的字段识别代码（if lineText.contains("进水温度")...）紧随其后，
     *   实际上是能执行到的。
     *   真正的问题是：设置前缀和识别字段在同一次循环迭代中，
     *   但字段识别时使用的 currentBjPrefix 是刚设好的正确值，逻辑上没有问题。
     *   然而 return@forEachIndexed 仅在 prefix == null 时才 return，
     *   设完 prefix 后不会 return，会继续执行字段检测——这部分逻辑本身是对的。
     *
     * 缺陷B（根本原因）：plateMaps[1] 使用 "1号楼"、"3号楼" 等含"楼"字的关键字，
     *   但表单截图中实际板交标题为 "1号板交"、"3号板交"（不含"楼"），
     *   导致 plateKeywordMap.keys.find { lineText.contains(it) } 永远返回 null，
     *   currentBjPrefix 始终为 null，所有行都被跳过，outData 为空。
     *   （这个根本原因已在 TemplateManager.plateMaps 中修复）
     *
     * 本函数中的额外修复：
     * - 优化关键字匹配优先级：优先匹配更长（更精确）的关键字，防止 "1#" 误匹配含"1#"的其他行。
     * - 增加 currentBjPrefix 状态重置保护：一旦匹配到新的板交关键字，立即更新前缀，
     *   确保多板交数据不串号。
     */
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

            // Fix：按关键字长度降序排列，优先匹配更长、更精确的关键字，
            // 防止 "1#" 被误匹配到含 "10号1#" 的行（短词先匹配会错误命中）
            val sortedKeywords = plateKeywordMap.keys.sortedByDescending { it.length }

            var currentBjPrefix: String? = null

            textLines.forEachIndexed { index, lineText ->
                // Fix：使用排序后的关键字列表进行匹配，优先长词
                val matchedKeyword = sortedKeywords.find { lineText.contains(it) }
                if (matchedKeyword != null) {
                    // Fix：每次命中新关键字都立即更新前缀（即使前缀已有值），
                    // 确保进入下一个板交区域时能正确切换，防止数据串号
                    currentBjPrefix = plateKeywordMap[matchedKeyword]
                    // 注意：此处不 return，继续检查同一行是否也有字段数据
                }

                // 如果还没确定当前板交前缀，跳过本行
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
        val noisePattern = Regex(
            "[0-9]+号[楼]?|[0-9]+#|板交|进[水口]温度|出[水口]温度|进[水口]压力|出[水口]压力|蒸汽压力|水泵电流|请填写数值|℃|MPa|KPa|A|%",
            RegexOption.IGNORE_CASE
        )

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
