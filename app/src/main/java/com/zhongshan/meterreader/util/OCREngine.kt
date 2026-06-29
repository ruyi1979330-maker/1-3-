	package com.zhongshan.meterreader.util
	import android.graphics.Bitmap
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
	            // 修复：取消人工对比度和饱和度干预，直接使用原图识别，保留屏幕发光特征
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
	            return@withContext Pair(null, null)
	        } finally {
	            bitmap.recycle()
	        }
	    }
	    suspend fun extractPlateData(
	        bitmap: Bitmap,
	        isRoom1: Boolean,
	        plateKeywordMap: Map<String, String>
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        val outData = HashMap<String, String>()
	        try {
	            val image = InputImage.fromBitmap(bitmap, 0)
	            val result = recognizer.process(image).await()
	            val allLines = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }
	            val sortedKeywords = plateKeywordMap.keys.sortedByDescending { it.length }
	            var currentBjPrefix: String? = null
	            allLines.forEachIndexed { index, lineText ->
	                val matchedKeyword = sortedKeywords.find { lineText.contains(it) }
	                if (matchedKeyword != null) currentBjPrefix = plateKeywordMap[matchedKeyword]
	                if (currentBjPrefix == null) return@forEachIndexed
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
	        } catch (e: Exception) {
	            e.printStackTrace()
	        }
	        return@withContext outData
	    }
	    private fun extractNextNumericValue(lines: List<String>, currentIndex: Int): String? {
	        val searchEnd = minOf(currentIndex + 4, lines.size)
	        for (i in currentIndex until searchEnd) {
	            val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(lines[i]) ?: continue
	            var clean = match.value.replace(Regex("[^0-9.]"), "")
	            val parts = clean.split(".")
	            if (parts.size > 2) clean = "${parts[0]}.${parts[1]}"
	            if (clean.isNotEmpty() && clean != ".") {
	                val fVal = clean.toFloatOrNull()
	                if (fVal != null && fVal in -100f..9999f) return clean
	            }
	        }
	        return null
	    }
	}
