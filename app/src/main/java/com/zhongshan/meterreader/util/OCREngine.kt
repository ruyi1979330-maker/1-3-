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
	    suspend fun extractPlateData(
	        bitmap: Bitmap,
	        isRoom1: Boolean,
	        plateKeywordMap: Map<String, String> // 保留参数避免接口变动，内部不再依赖
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        val outData = HashMap<String, String>()
	        try {
	            DebugLogger.log("OCR-Plate-Debug", "开始板交识别，原图尺寸: ${bitmap.width}x${bitmap.height}")
	            // 【修复1】超长图分块识别，解决高度超限导致返回0行的问题
	            val allLines = mutableListOf<Pair<Int, String>>() // Pair(Y坐标, 文本)
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
	                // 按Y坐标排序并去重重叠区域
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
	                DebugLogger.log("OCR-Plate-Debug", "超长图分块识别完成，共 ${distinctLines.size} 行文本")
	                allLines.clear()
	                allLines.addAll(distinctLines)
	            } else {
	                val image = InputImage.fromBitmap(bitmap, 0)
	                val result = recognizer.process(image).await()
	                allLines.addAll(result.textBlocks.flatMap { it.lines }.map { Pair(it.boundingBox?.top ?: 0, it.text.trim()) })
	                allLines.sortBy { it.first }
	            }
	            // 【修复2】顺序提取数值法，彻底解决中文乱码导致关键字匹配失败的问题
	            val extractedNumbers = mutableListOf<String>()
	            for ((_, lineText) in allLines) {
	                val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(lineText) ?: continue
	                val value = match.value
	                val hasDecimal = value.contains(".")
	                // 严格匹配单位特征，防止误提取标题中的数字（如1#中的1）
	                val hasUnit = lineText.contains("°C", true) || lineText.contains("MPa", true) || lineText.contains("A", true)
	                if (hasDecimal || hasUnit) {
	                    extractedNumbers.add(value)
	                }
	            }
	            DebugLogger.log("OCR-Plate-Debug", "纯数值提取完成，共提取到 ${extractedNumbers.size} 个数值: $extractedNumbers")
	            // 【修复3】按固定表单结构顺序赋值
	            val expectedFields = if (isRoom1) {
	                listOf("bj1_01", "bj1_02", "bj1_03", "bj1_04", "bj1_05", "bj1_06",
	                       "bj1_11", "bj1_12", "bj1_13", "bj1_14", "bj1_15", "bj1_16",
	                       "bj1_21", "bj1_22", "bj1_23", "bj1_24", "bj1_25", "bj1_26",
	                       "bj1_31", "bj1_32", "bj1_33", "bj1_34", "bj1_35", "bj1_36",
	                       "bj1_41", "bj1_42", "bj1_43", "bj1_44", "bj1_45", "bj1_46",
	                       "bj1_51", "bj1_52", "bj1_53", "bj1_54", "bj1_55", "bj1_56")
	            } else {
	                listOf("bj3_01", "bj3_02", "bj3_03", "bj3_04", "bj3_05",
	                       "bj3_11", "bj3_12", "bj3_13", "bj3_14", "bj3_15")
	            }
	            for (i in expectedFields.indices) {
	                if (i < extractedNumbers.size) {
	                    outData[expectedFields[i]] = extractedNumbers[i]
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
