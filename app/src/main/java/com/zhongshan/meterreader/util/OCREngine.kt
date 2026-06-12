package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 核心 OCR 引擎：全图文字行识别 + 关键字行匹配提取数值
 *
 * 设计原则：
 *   彻底放弃 ROI 坐标裁剪方案。
 *
 *   ROI 方案的根本缺陷：
 *     ROI 坐标是基于"全图"百分比的，但实际拍照时
 *     手机与设备面板的距离、角度每次不同，导致 LCD 屏幕
 *     在全图中的位置和尺寸随机漂移 ±15%~20%，
 *     使任何预设坐标都无法稳定命中目标数值区域。
 *
 *   新方案：
 *     1. 对全图做一次 OCR，获取所有文字行及其边界框
 *     2. 对每个目标字段，定义若干"关键字"（就是屏幕上该行的标签文字）
 *     3. 在 OCR 结果中找到包含关键字的行
 *     4. 在该行的右侧或紧接的下一行中提取数值
 *     这样无论 LCD 在图片中的位置如何变化，只要文字能被识别，就能提取正确值
 */
object OCREngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    // =====================================================================
    // 数据类：一行文字 + 其在图片中的位置
    // =====================================================================
    data class OcrLine(
        val text: String,
        val rect: Rect,       // 在原图中的像素坐标
        val centerY: Int = rect.centerY(),
        val centerX: Int = rect.centerX()
    )

    // =====================================================================
    // 对整张图片做 OCR，返回按 y 坐标排序的所有文字行
    // =====================================================================
    suspend fun recognizeAllLines(bitmap: Bitmap): List<OcrLine> =
        withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).await()
                result.textBlocks
                    .flatMap { block -> block.lines }
                    .mapNotNull { line ->
                        val rect = line.boundingBox ?: return@mapNotNull null
                        OcrLine(line.text.trim(), rect)
                    }
                    .sortedBy { it.centerY }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    // =====================================================================
    // 从已识别的行列表中，按关键字找到对应行，然后提取其右侧或下一行的数值
    //
    // @param lines       整图 OCR 行列表（已按 y 排序）
    // @param keywords    目标行应包含的关键字（任意一个匹配即命中）
    // @param searchBelow 是否向下搜索（true = 在关键字行下方找数值，
    //                    false = 在关键字行右侧找数值）
    // @param maxBelow    向下搜索的最大行数（默认3）
    // =====================================================================
    fun extractValueByKeyword(
        lines: List<OcrLine>,
        keywords: List<String>,
        searchBelow: Boolean = false,
        maxBelow: Int = 3
    ): String? {
        for (i in lines.indices) {
            val line = lines[i]
            val lineText = line.text

            // 找到包含任意关键字的行
            if (keywords.none { lineText.contains(it) }) continue

            // 策略1：直接从关键字所在行右侧提取数值
            // （适用于 "标签  数值" 在同一行的格式，如 Trane 面板）
            val inlineValue = extractFirstNumber(lineText, excludeKeywords = keywords)
            if (inlineValue != null) return inlineValue

            // 策略2：向下找最近包含数值的行
            // （适用于标签和数值在不同行的表单）
            if (searchBelow) {
                val endIdx = minOf(i + maxBelow, lines.size)
                for (j in i + 1 until endIdx) {
                    val candidate = extractFirstNumber(lines[j].text)
                    if (candidate != null) return candidate
                }
            }
        }
        return null
    }

    // =====================================================================
    // 从文字中提取第一个数值（整数或小数）
    // excludeKeywords：排除这些关键字本身包含的数字（如 "L1" 里的 "1"）
    // =====================================================================
    fun extractFirstNumber(text: String, excludeKeywords: List<String> = emptyList()): String? {
        var cleaned = text
        // 先去掉关键字，避免误读关键字里的数字
        for (kw in excludeKeywords) cleaned = cleaned.replace(kw, " ")
        // 去除常见单位和噪声
        cleaned = cleaned
            .replace(Regex("[kK][pP][aA][gGdD]?"), " ")   // kPag, kPaD, kpa
            .replace(Regex("[mM][pP][aA]"), " ")            // MPa
            .replace("℃", " ").replace("°C", " ").replace("C", " ")
            .replace("Amps", " ").replace("Amp", " ")
            .replace("%", " ")
            .replace(Regex("[A-Z][0-9]"), " ")              // L1, L2, L3
            .replace(Regex("Oil|Loss|Level|Sensor|RLA"), " ", )
            .replace(Regex("[oO]"), "0")                    // O→0
            .replace(Regex("[lI](?=\\d|\\.)"), "1")         // l/I→1（后跟数字时）

        // 匹配数值（支持小数，支持649.7这种大数）
        val match = Regex("""\b\d{1,4}(\.\d{1,2})?\b""").findAll(cleaned)
            .map { it.value }
            .filter { it.toDoubleOrNull() != null }
            // 过滤明显无意义的值（年份、很大的无意义数字）
            .filter { it.toDouble() < 9000 }
            .firstOrNull()
        return match
    }

    // =====================================================================
    // 板交整图识别（保持关键字状态机逻辑，改用全图 OCR 结果）
    // =====================================================================
    fun extractPlateData(
        lines: List<OcrLine>,
        isRoom1: Boolean,
        plateKeywordMap: Map<String, String>
    ): Map<String, String> {
        val outData = HashMap<String, String>()
        val sortedKeywords = plateKeywordMap.keys.sortedByDescending { it.length }
        var currentBjPrefix: String? = null

        lines.forEachIndexed { index, ocrLine ->
            val lineText = ocrLine.text
            val matchedKeyword = sortedKeywords.find { lineText.contains(it) }
            if (matchedKeyword != null) currentBjPrefix = plateKeywordMap[matchedKeyword]
            if (currentBjPrefix == null) return@forEachIndexed

            val allLines = lines.map { it.text }

            if (lineText.contains("进水温度") || lineText.contains("进口温度"))
                extractNextNumericValue(allLines, index)?.let { outData["${currentBjPrefix}1"] = it }
            if (lineText.contains("出水温度") || lineText.contains("出口温度"))
                extractNextNumericValue(allLines, index)?.let { outData["${currentBjPrefix}2"] = it }
            if (lineText.contains("进水压力") || lineText.contains("进口压力"))
                extractNextNumericValue(allLines, index)?.let { outData["${currentBjPrefix}3"] = it }
            if (lineText.contains("出水压力") || lineText.contains("出口压力"))
                extractNextNumericValue(allLines, index)?.let { outData["${currentBjPrefix}4"] = it }
            if (lineText.contains("蒸汽压力"))
                extractNextNumericValue(allLines, index)?.let { outData["${currentBjPrefix}5"] = it }
            if (isRoom1 && lineText.contains("水泵电流"))
                extractNextNumericValue(allLines, index)?.let { outData["${currentBjPrefix}6"] = it }
        }
        return outData
    }

    private fun extractNextNumericValue(lines: List<String>, currentIndex: Int): String? {
        val searchEnd = minOf(currentIndex + 4, lines.size)
        for (i in currentIndex until searchEnd) {
            val candidate = extractFirstNumber(lines[i]) ?: continue
            if (candidate.isNotEmpty()) return candidate
        }
        return null
    }
}
