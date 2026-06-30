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

            // 2. 修复重叠区去重逻辑：相同文本且Y坐标差小于600视为重复
            rawLines.sortBy { it.first }
            val distinctLines = mutableListOf<Pair<Int, String>>()
            val seenTexts = mutableMapOf<String, Int>()
            for (pair in rawLines) {
                if (pair.second.isBlank()) continue
                if (seenTexts.containsKey(pair.second)) {
                    if (abs(pair.first - seenTexts[pair.second]!!) < 600) continue
                }
                distinctLines.add(pair)
                seenTexts[pair.second] = pair.first
            }

            // 3. 严格过滤：强制要求包含小数点，彻底干掉整数干扰（如13, 39, 0）
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

            // 4. 按Y坐标跳跃4%切成小片段
            extractedNumbers.sortBy { it.y }
            val fragments = mutableListOf<MutableList<ExtractedNumber>>()
            var currentFragment = mutableListOf<ExtractedNumber>()
            var lastY = -1
            val jumpThreshold = (bitmap.height * 0.04).toInt()
            for (num in extractedNumbers) {
                if (lastY != -1 && num.y - lastY > jumpThreshold) {
                    if (currentFragment.isNotEmpty()) {
                        fragments.add(currentFragment)
                        currentFragment = mutableListOf()
                    }
                }
                currentFragment.add(num)
                lastY = num.y
            }
            if (currentFragment.isNotEmpty()) fragments.add(currentFragment)

            // 5. 1D K-Means 聚类，自适应将片段聚成预定的组数
            val k = if (isRoom1) 6 else 2
            val clusters = Array(k) { mutableListOf<ExtractedNumber>() }
            if (extractedNumbers.isNotEmpty()) {
                var centers = mutableListOf<Float>()
                val minY = extractedNumbers.minOf { it.y }.toFloat()
                val maxY = extractedNumbers.maxOf { it.y }.toFloat()
                for (i in 0 until k) {
                    centers.add(minY + (maxY - minY) * i / (k - 1))
                }
                
                for (iter in 0 until 10) {
                    clusters.forEach { it.clear() }
                    for (frag in fragments) {
                        val avgY = frag.map { it.y }.average().toFloat()
                        var bestIdx = 0
                        var minDist = Float.MAX_VALUE
                        for (i in 0 until k) {
                            val dist = abs(avgY - centers[i])
                            if (dist < minDist) {
                                minDist = dist
                                bestIdx = i
                            }
                        }
                        clusters[bestIdx].addAll(frag)
                    }
                    for (i in 0 until k) {
                        if (clusters[i].isNotEmpty()) {
                            centers[i] = clusters[i].map { it.y }.average().toFloat()
                        }
                    }
                }
            }

            // 6. 将聚类结果按Y排序，分配设备名称并填入
            data class ClusterData(val avgY: Float, val numbers: List<ExtractedNumber>)
            val clusterDataList = clusters.map { 
                ClusterData(if (it.isNotEmpty()) it.map { n -> n.y }.average().toFloat() else Float.MAX_VALUE, it.sortedBy { n -> n.y })
            }.sortedBy { it.avgY }
            
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

            for (deviceIdx in clusterDataList.indices) {
                val nums = clusterDataList[deviceIdx].numbers
                for (i in nums.indices) {
                    if (i < fieldNames.size) {
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
