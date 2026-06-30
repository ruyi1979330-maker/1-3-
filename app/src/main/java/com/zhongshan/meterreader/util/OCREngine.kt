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
import kotlin.math.abs

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
    data class NumberGroup(val avgY: Float, val values: List<String>)

    suspend fun extractPlateData(
        bitmap: Bitmap,
        isRoom1: Boolean,
        plateKeywordMap: Map<String, String>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val outData = HashMap<String, String>()
        try {
            DebugLogger.log("OCR-Plate-Debug", "开始板交识别，原图尺寸: ${bitmap.width}x${bitmap.height}")
            
            // 1. 超长图分块识别
            val allLines = mutableListOf<Pair<Int, String>>()
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
                        allLines.add(Pair(top + y, line.text.trim()))
                    }
                    chunk.recycle()
                    if (endY == bitmap.height) break
                    y = endY - overlap
                }
                allLines.sortBy { it.first }
                val distinctLines = mutableListOf<Pair<Int, String>>()
                var lastY = -1000
                var lastText = ""
                for (pair in allLines) {
                    if (pair.first - lastY > 10 || pair.second != lastText) {
                        distinctLines.add(pair)
                        lastY = pair.first
                        lastText = pair.second
                    }
                }
                allLines.clear()
                allLines.addAll(distinctLines)
            } else {
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).await()
                allLines.addAll(result.textBlocks.flatMap { it.lines }.map { Pair(it.boundingBox?.top ?: 0, it.text.trim()) })
                allLines.sortBy { it.first }
            }

            // 2. 严格过滤并提取有效数值
            val extractedNumbers = mutableListOf<ExtractedNumber>()
            for ((y, lineText) in allLines) {
                if (lineText.contains("#")) continue // 过滤标题行
                val normalizedText = lineText.replace(",", ".")
                val match = Regex("""\d{1,3}(\.\d{1,2})?""").find(normalizedText) ?: continue
                val value = match.value
                
                // 【修复1】增加范围校验，过滤 333, 1091 等不合理乱码数字
                val fVal = value.toFloatOrNull()
                if (fVal == null || fVal !in 0f..150f) continue 
                
                val hasDecimal = value.contains(".")
                val hasUnit = lineText.contains("°C", true) || lineText.contains("MPa", true) || lineText.contains("A", true)
                if (hasDecimal || hasUnit) {
                    extractedNumbers.add(ExtractedNumber(y, value))
                }
            }
            
            DebugLogger.log("OCR-Plate-Debug", "纯数值提取完成，共提取到 ${extractedNumbers.size} 个数值: ${extractedNumbers.map { it.value }}")

            // 3. 根据Y坐标聚类，阈值设为6%高度
            val groupedNumbers = mutableListOf<NumberGroup>()
            var currentValues = mutableListOf<String>()
            var currentYSum = 0
            var currentCount = 0
            var lastY = -1
            val yThreshold = (bitmap.height * 0.06).toInt()

            for (num in extractedNumbers) {
                if (lastY != -1 && num.y - lastY > yThreshold) {
                    if (currentValues.isNotEmpty()) {
                        groupedNumbers.add(NumberGroup(currentYSum.toFloat() / currentCount, currentValues))
                        currentValues = mutableListOf()
                        currentYSum = 0
                        currentCount = 0
                    }
                }
                currentValues.add(num.value)
                currentYSum += num.y
                currentCount++
                lastY = num.y
            }
            if (currentValues.isNotEmpty()) {
                groupedNumbers.add(NumberGroup(currentYSum.toFloat() / currentCount, currentValues))
            }

            // 4. 将聚类后的数据对号入座，并带上设备名称Label
            val prefix = if (isRoom1) "bj1_" else "bj3_"
            
            // 预设各设备组在图片中的Y轴中心点比例
            val deviceCenters = if (isRoom1) {
                listOf(0.15f, 0.27f, 0.39f, 0.51f, 0.63f, 0.75f).map { it * bitmap.height }
            } else {
                listOf(0.25f, 0.60f).map { it * bitmap.height }
            }
            
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

            for (group in groupedNumbers) {
                var bestDeviceIndex = 0
                var minDist = Float.MAX_VALUE
                for (i in deviceCenters.indices) {
                    val dist = abs(group.avgY - deviceCenters[i])
                    if (dist < minDist) {
                        minDist = dist
                        bestDeviceIndex = i
                    }
                }
                
                for (i in group.values.indices) {
                    if (i < fieldNames.size) {
                        // 【修复2】生成带 Label 的复合键，让前端 JS 可通过文本精准匹配输入框
                        val label = "${deviceNames[bestDeviceIndex]}${fieldNames[i]}"
                        val key = "${prefix}${bestDeviceIndex}|$label"
                        outData[key] = group.values[i]
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
