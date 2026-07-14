// 文件名: OCREngine.kt
package com.zhongshan.meterreader.util

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.zhongshan.meterreader.DebugLogger

object OCREngine {
private val recognizer by lazy {
TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
}

data class ExtractedNumber(val y: Int, val value: String)
 
suspend fun extractPlateData(
    bitmap: Bitmap,
    isRoom1: Boolean,
    plateKeywordMap: Map<String, String>
): Map<String, String> = withContext(Dispatchers.IO) {
    val outData = HashMap<String, String>()
    try {
        DebugLogger.log("OCR-Plate-Debug", "开始板交识别，原图尺寸: ${bitmap.width}x${bitmap.height}")
        val rawLines = mutableListOf<Pair<Int, String>>()
        if (bitmap.height > 4000) {
            val chunkHeight = 4000
            val overlap = 500
            var y = 0
            while (y < bitmap.height) {
                val endY = minOf(y + chunkHeight, bitmap.height)
                val chunk = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, endY - y)
                val image = InputImage.fromBitmap(chunk, 0)
                val result = recognizer.process(image).await()
                result.textBlocks.flatMap { it.lines }.forEach { line ->
                    val top = line.boundingBox?.top ?: 0
                    rawLines.add(Pair(top + y, line.text.trim()))
                }
                chunk.recycle()
                if (endY == bitmap.height) break
                y = endY - overlap
            }
        } else {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            rawLines.addAll(
                result.textBlocks.flatMap { it.lines }
                    .map { Pair(it.boundingBox?.top ?: 0, it.text.trim()) }
            )
        }
        rawLines.sortBy { it.first }
        val distinctLines = mutableListOf<Pair<Int, String>>()
        var lastY = -1000
        var lastText = ""
        for (pair in rawLines) {
            if (pair.second.isBlank()) continue
            if (pair.first - lastY <= 10 && pair.second == lastText) {
                continue
            }
            distinctLines.add(pair)
            lastY = pair.first
            lastText = pair.second
        }
        val extractedNumbers = mutableListOf<ExtractedNumber>()
        for ((y, lineText) in distinctLines) {
            if (lineText.contains("#")) continue
            val normalizedText = lineText.replace(",", ".")
            val match = Regex("""\d{1,3}\.\d{1,2}""").find(normalizedText) ?: continue
            val value = match.value
            val fVal = value.toFloatOrNull()
            if (fVal == null || fVal !in 0f..150f) continue
            extractedNumbers.add(ExtractedNumber(y, value))
        }
        DebugLogger.log("OCR-Plate-Debug", "纯数值提取完成，共提取到 ${extractedNumbers.size} 个数值: ${extractedNumbers.map { it.value }}")
        if (extractedNumbers.isEmpty()) return@withContext outData
        
        // 修复Q3：移除固定组数，采用纯动态分组，并按组顺序分配设备名
        val fieldsPerGroup = if (isRoom1) 6 else 5
        val allYDiffs = mutableListOf<Pair<Int, Int>>()
        for (i in 1 until extractedNumbers.size) {
            allYDiffs.add(Pair(i - 1, extractedNumbers[i].y - extractedNumbers[i - 1].y))
        }
        val globalSortedDiffs = allYDiffs.map { it.second }.sorted()
        val globalMedianDiff = if (globalSortedDiffs.isNotEmpty()) globalSortedDiffs[globalSortedDiffs.size / 2] else 100
        
        val splitIndices = allYDiffs.filter { it.second > globalMedianDiff * 3 }
            .map { it.first }
            .sorted()
            
        val groupedNumbers = mutableListOf<List<ExtractedNumber>>()
        var startIdx = 0
        for (splitIdx in splitIndices) {
            groupedNumbers.add(extractedNumbers.subList(startIdx, splitIdx + 1))
            startIdx = splitIdx + 1
        }
        groupedNumbers.add(extractedNumbers.subList(startIdx, extractedNumbers.size))
        
        val prefix = if (isRoom1) "bj1_" else "bj3_"
        val deviceNames = if (isRoom1) {
            listOf("1号楼板交", "3号楼板交", "备用板交", "10号楼1#板交", "10号楼2#板交", "1号楼水汀板交")
        } else {
            listOf("1#板交", "2#板交")
        }
        val fieldNames = if (isRoom1) {
            listOf("进水温度", "出水温度", "进水压力", "出水压力", "蒸汽压力", "水泵电流")
        } else {
            listOf("进水温度", "出水温度", "进水压力", "出水压力", "蒸汽压力")
        }
        
        for (deviceIdx in groupedNumbers.indices) {
            if (deviceIdx >= deviceNames.size) break
            val group = groupedNumbers[deviceIdx].sortedBy { it.y }
            if (group.isEmpty()) continue
            val yDiffs = mutableListOf<Int>()
            for (i in 1 until group.size) {
                yDiffs.add(group[i].y - group[i - 1].y)
            }
            val sortedDiffs = yDiffs.sorted()
            val medianDiff = if (sortedDiffs.isNotEmpty()) sortedDiffs[sortedDiffs.size / 2] else 100
            val paddedValues = mutableListOf<String?>()
            paddedValues.add(group[0].value)
            for (i in 1 until group.size) {
                val diff = group[i].y - group[i - 1].y
                if (diff > medianDiff * 1.5) {
                    val missingCount = (diff / medianDiff).toInt() - 1
                    for (m in 0 until missingCount) {
                        paddedValues.add(null)
                    }
                }
                paddedValues.add(group[i].value)
            }
            for (i in paddedValues.indices) {
                if (i < fieldsPerGroup) {
                    if (paddedValues[i] != null) {
                        val label = "${deviceNames[deviceIdx]}${fieldNames[i]}"
                        val key = "${prefix}${deviceIdx}|$label"
                        outData[key] = paddedValues[i]!!
                    }
                }
            }
        }
        DebugLogger.log("OCR-Plate-Debug", "最终映射结果: $outData")
    } catch (e: Exception) {
        DebugLogger.log("OCR-Plate-Error", "板交识别发生异常: ${Log.getStackTraceString(e)}")
        e.printStackTrace()
    }
    return@withContext outData
}
}
