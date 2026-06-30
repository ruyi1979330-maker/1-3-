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
	            // 2. 严格过滤并提取有效数值（带小数点或单位，且排除#标题干扰）
	            val extractedNumbers = mutableListOf<ExtractedNumber>()
	            for ((y, lineText) in allLines) {
	                if (lineText.contains("#")) continue // 过滤掉 1#板交 等标题行
	                val normalizedText = lineText.replace(",", ".")
	                // 限制最多3位整数，防止提取出 1091 这种四位乱码数字
	                val match = Regex("""\d{1,3}(\.\d{1,2})?""").find(normalizedText) ?: continue
	                val value = match.value
	                val hasDecimal = value.contains(".")
	                val hasUnit = lineText.contains("°C", true) || lineText.contains("MPa", true) || lineText.contains("A", true)
	                if (hasDecimal || hasUnit) {
	                    extractedNumbers.add(ExtractedNumber(y, value))
	                }
	            }
	            DebugLogger.log("OCR-Plate-Debug", "纯数值提取完成，共提取到 ${extractedNumbers.size} 个数值: ${extractedNumbers.map { it.value }}")
	            // 3. 根据Y坐标聚类，防止空白行导致的错位
	            val numGroups = if (isRoom1) 5 else 2
	            val fieldsPerGroup = if (isRoom1) 6 else 5
	            val prefix = if (isRoom1) "bj1_" else "bj3_"
	            val expectedFields = mutableListOf<String>()
	            for (g in 0 until numGroups) {
	                for (f in 0 until fieldsPerGroup) {
	                    expectedFields.add("${prefix}${g}${f + 1}")
	                }
	            }
	            // 预估表单各组在图片中的Y轴中心位置（按15%~85%区域均分）
	            val groupCenters = mutableListOf<Float>()
	            val startPct = 0.15f
	            val endPct = 0.85f
	            for (i in 0 until numGroups) {
	                if (numGroups == 1) {
	                    groupCenters.add((startPct + endPct) / 2 * bitmap.height)
	                } else {
	                    val step = (endPct - startPct) / (numGroups - 1)
	                    groupCenters.add((startPct + i * step) * bitmap.height)
	                }
	            }
	            // 按Y坐标间距聚类分组
	            val groupedNumbers = mutableListOf<NumberGroup>()
	            var currentValues = mutableListOf<String>()
	            var currentYSum = 0
	            var currentCount = 0
	            var lastY = -1
	            val yThreshold = (bitmap.height * 0.05).toInt() // 跳跃超过5%算作新一组
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
	            // 4. 将聚类后的数据对号入座填入表单
	            for (group in groupedNumbers) {
	                var bestGroupIndex = 0
	                var minDist = Float.MAX_VALUE
	                for (i in groupCenters.indices) {
	                    val dist = abs(group.avgY - groupCenters[i])
	                    if (dist < minDist) {
	                        minDist = dist
	                        bestGroupIndex = i
	                    }
	                }
	                val fieldStartIndex = bestGroupIndex * fieldsPerGroup
	                for (i in group.values.indices) {
	                    if (fieldStartIndex + i < expectedFields.size) {
	                        outData[expectedFields[fieldStartIndex + i]] = group.values[i]
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
