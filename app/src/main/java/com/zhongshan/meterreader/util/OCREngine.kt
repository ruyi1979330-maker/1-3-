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

    // 返回值：Pair(原始识别文本, 清洗后纯数字)
    suspend fun extractPureNumber(bitmap: Bitmap): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            val rawText = result.text.trim()
            val elementInfo = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }.joinToString(" | ") {
                "'${it.text}' at [${it.boundingBox?.left},${it.boundingBox?.top},${it.boundingBox?.right},${it.boundingBox?.bottom}]"
            }
            DebugLogger.log("OCR-Element", "元素信息: $elementInfo")

            val normalizedText = rawText.replace(",", ".").replace(":", ".")
            val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(normalizedText)
            val finalNumber = match?.value

            return@withContext Pair(rawText, finalNumber)
        } catch (e: Exception) {
            DebugLogger.log("OCR-Error", "extractPureNumber 异常: ${Log.getStackTraceString(e)}")
            return@withContext Pair(null, null)
        } finally {
            bitmap.recycle()
        }
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
            
            // 1. 超长图分块识别
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
                rawLines.addAll(result.textBlocks.flatMap { it.lines }.map { Pair(it.boundingBox?.top ?: 0, it.text.trim()) })
            }

            // 2. 按 Y 坐标严格去重：不比较文本，只按 Y 坐标距离去重，彻底解决重叠区文本不同导致的重复
            rawLines.sortBy { it.first }
            val distinctLines = mutableListOf<Pair<Int, String>>()
            var lastY = -1000
            for (pair in rawLines) {
                if (pair.second.isBlank()) continue
                if (pair.first - lastY > 20) { // Y坐标相差20像素算作不同行
                    distinctLines.add(pair)
                    lastY = pair.first
                }
            }

            // 3. 严格过滤：强制要求包含小数点
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

            // 4. 找Y坐标跳跃最大的几个点作为组分界线
            val numGroups = if (isRoom1) 6 else 2
            val fieldsPerGroup = if (isRoom1) 6 else 5
            val yDiffs = mutableListOf<Pair<Int, Int>>() // (索引, 差值)
            for (i in 1 until extractedNumbers.size) {
                yDiffs.add(Pair(i - 1, extractedNumbers[i].y - extractedNumbers[i-1].y))
            }
            
            // 设定最小跳跃阈值，防止同一组内因漏识别导致的大跳跃被误判
            val minJumpThreshold = (bitmap.height * 0.02).toInt()
            val splitIndices = yDiffs.filter { it.second > minJumpThreshold }
                .sortedByDescending { it.second }
                .take(numGroups - 1)
                .map { it.first }
                .sorted()

            // 5. 按分界点切分成组
            val groupedNumbers = mutableListOf<List<ExtractedNumber>>()
            var startIdx = 0
            for (splitIdx in splitIndices) {
                groupedNumbers.add(extractedNumbers.subList(startIdx, splitIdx + 1))
                startIdx = splitIdx + 1
            }
            groupedNumbers.add(extractedNumbers.subList(startIdx, extractedNumbers.size))

            // 6. 按顺序赋值，每组强制只取前 fieldsPerGroup 个，防干扰
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
                val nums = groupedNumbers[deviceIdx]
                for (i in nums.indices) {
                    if (i < fieldsPerGroup) {
                        val label = "${deviceNames[deviceIdx]}${fieldNames[i]}"
                        val key = "${prefix}${deviceIdx}|$label"
                        outData[key] = nums[i].value
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
