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
	    private data class RawLine(val y: Int, val text: String)
	    private data class DataItem(val yTop: Int, val value: String, val isDeviceTitle: Boolean)
	    private data class DeviceGroup(val deviceName: String, val deviceIdx: Int, val numbers: List<ExtractedNumber>)
	    suspend fun extractPlateData(
	        bitmap: Bitmap,
	        isRoom1: Boolean,
	        plateKeywordMap: Map<String, String>
	    ): Map<String, String> = withContext(Dispatchers.IO) {
	        val outData = HashMap<String, String>()
	        try {
	            DebugLogger.log("OCR-Plate-Debug", "开始板交识别，原图尺寸: ${bitmap.width}x${bitmap.height}, isRoom1=$isRoom1")
	            val rawLines = mutableListOf<RawLine>()
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
	                        rawLines.add(RawLine(top + y, line.text.trim()))
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
	                        .map { RawLine(it.boundingBox?.top ?: 0, it.text.trim()) }
	                )
	            }
	            rawLines.sortBy { it.y }
	            val distinctLines = mutableListOf<RawLine>()
	            var lastY = -1000
	            var lastText = ""
	            for (line in rawLines) {
	                if (line.text.isBlank()) continue
	                if (line.y - lastY <= 10 && line.text == lastText) continue
	                distinctLines.add(line)
	                lastY = line.y
	                lastText = line.text
	            }
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
	            val fieldsPerGroup = fieldNames.size
	            val dataItems = mutableListOf<DataItem>()
	            val looseTitleKeywords = buildLooseTitleKeywords(deviceNames)
	            for (line in distinctLines) {
	                val text = line.text
	                if (text.isBlank()) continue
	                val cleaned = cleanPlateTitle(text)
	                val matchedTitle = matchDeviceTitle(cleaned, deviceNames, looseTitleKeywords)
	                if (matchedTitle != null) {
	                    dataItems.add(DataItem(line.y, matchedTitle, true))
	                    DebugLogger.log("OCR-Plate-Debug", "识别为设备标题: raw='$text' -> '$matchedTitle' Y=${line.y}")
	                    continue
	                }
	                val normalizedText = text.replace(",", ".")
	                val match = Regex("""\d{1,3}\.\d{1,2}""").find(normalizedText)
	                if (match != null) {
	                    val value = match.value
	                    val fVal = value.toFloatOrNull()
	                    if (fVal != null && fVal in 0f..150f) {
	                        dataItems.add(DataItem(line.y, value, false))
	                    }
	                }
	            }
	            val numericItems = dataItems.filter { !it.isDeviceTitle }.sortedBy { it.yTop }
	            if (numericItems.isEmpty()) {
	                DebugLogger.log("OCR-Plate-Debug", "无任何有效数值，识别终止")
	                return@withContext outData
	            }
	            val allYs = numericItems.map { it.yTop }.sorted()
	            val yDiffs = mutableListOf<Int>()
	            for (i in 1 until allYs.size) {
	                yDiffs.add(allYs[i] - allYs[i - 1])
	            }
	            val medianDiff = if (yDiffs.isNotEmpty()) {
	                val sorted = yDiffs.sorted()
	                sorted[sorted.size / 2].coerceAtLeast(20)
	            } else {
	                100
	            }
	            DebugLogger.log("OCR-Plate-Debug", "行距中位数 = $medianDiff")
	            val groups = buildGroups(dataItems, deviceNames, medianDiff, fieldsPerGroup)
	            for (g in groups) {
	                DebugLogger.log("OCR-Plate-Debug", "分组: device='${g.deviceName}'(idx=${g.deviceIdx}) 数值=${g.numbers.map { it.value }}")
	            }
	            for (g in groups) {
	                val sorted = g.numbers.sortedBy { it.y }
	                if (sorted.isEmpty()) continue
	                val padded = mutableListOf<String?>()
	                padded.add(sorted[0].value)
	                for (i in 1 until sorted.size) {
	                    val diff = sorted[i].y - sorted[i - 1].y
	                    if (diff > medianDiff * 1.5) {
	                        val missingCount = (diff / medianDiff).toInt() - 1
	                        for (m in 0 until missingCount) padded.add(null)
	                    }
	                    padded.add(sorted[i].value)
	                }
	                for (i in padded.indices) {
	                    if (i >= fieldsPerGroup) break
	                    val v = padded[i] ?: continue
	                    val label = "${g.deviceName}${fieldNames[i]}"
	                    val key = "${prefix}${g.deviceIdx}|$label"
	                    outData[key] = v
	                    DebugLogger.log("OCR-Plate-Debug", "写入 $key = $v")
	                }
	            }
	            DebugLogger.log("OCR-Plate-Debug", "最终映射结果: $outData")
	        } catch (e: Exception) {
	            DebugLogger.log("OCR-Plate-Error", "板交识别发生异常: ${Log.getStackTraceString(e)}")
	        }
	        return@withContext outData
	    }
	    private fun buildGroups(
	        dataItems: List<DataItem>,
	        deviceNames: List<String>,
	        medianDiff: Int,
	        fieldsPerGroup: Int
	    ): List<DeviceGroup> {
	        val titles = dataItems.filter { it.isDeviceTitle }.sortedBy { it.yTop }
	        val numericItems = dataItems.filter { !it.isDeviceTitle }.sortedBy { it.yTop }
	        // 核心修复 Q1：按 Y 间隙和“固定字段数”双重判定进行强制物理切块
	        val sub = mutableListOf<MutableList<ExtractedNumber>>()
	        var currentGroup = mutableListOf<ExtractedNumber>()
	        for (i in numericItems.indices) {
	            val num = ExtractedNumber(numericItems[i].yTop, numericItems[i].value)
	            if (currentGroup.isNotEmpty()) {
	                val diff = num.y - currentGroup.last().y
	                // 超过 2.0 倍行距，或者已经达到了一个设备应有的字段数，强制切组
	                if (diff > medianDiff * 2.0 || currentGroup.size >= fieldsPerGroup) {
	                    if (currentGroup.size > 1 || diff <= medianDiff * 2.0) {
	                        sub.add(currentGroup)
	                        currentGroup = mutableListOf()
	                    } else {
	                        currentGroup.clear() // 噪声丢弃
	                    }
	                }
	            }
	            currentGroup.add(num)
	        }
	        if (currentGroup.isNotEmpty()) {
	            sub.add(currentGroup)
	        }
	        val groups = mutableListOf<DeviceGroup>()
	        var titleCursor = 0
	        var devCursor = 0
	        for (chunk in sub) {
	            if (chunk.isEmpty()) continue
	            val chunkTopY = chunk.first().y
	            // 找在这个 chunk 之前最近的标题
	            var matchedTitle: String? = null
	            while (titleCursor < titles.size && titles[titleCursor].yTop <= chunkTopY) {
	                matchedTitle = titles[titleCursor].value
	                titleCursor++
	            }
	            val deviceName = matchedTitle ?: deviceNames.getOrElse(devCursor) { "未知板交" }
	            val deviceIdx = deviceNames.indexOf(deviceName).let { if (it >= 0) it else devCursor }
	            groups.add(DeviceGroup(deviceName, deviceIdx, chunk))
	            if (matchedTitle != null) {
	                devCursor = deviceIdx + 1
	            } else {
	                devCursor++
	            }
	        }
	        return groups
	    }
	    private fun cleanPlateTitle(s: String): String {
	        return s.replace(" ", "")
	            .replace("\u3000", "")
	            .replace("#", "#")
	            .replace("楼", "楼")
	            .replace("（", "(")
	            .replace("）", ")")
	            .trim()
	    }
	    private fun matchDeviceTitle(
	        cleaned: String,
	        deviceNames: List<String>,
	        looseTitleKeywords: Map<String, List<String>>
	    ): String? {
	        if (cleaned.isEmpty()) return null
	        // 1. 完全匹配
	        for (dn in deviceNames) {
	            if (cleaned == cleanPlateTitle(dn)) return dn
	        }
	        // 2. 开头匹配
	        for (dn in deviceNames) {
	            val cd = cleanPlateTitle(dn)
	            if (cd.isNotEmpty() && cleaned.startsWith(cd)) return dn
	        }
	        // 3. 核心数字序列匹配（即使“号”、“#”丢失也能匹配）
	        val cleanedNums = Regex("""\d+""").findAll(cleaned).map { it.value }.toList()
	        for (dn in deviceNames) {
	            val dnNums = Regex("""\d+""").findAll(dn).map { it.value }.toList()
	            if (cleaned.contains("板交") && cleanedNums == dnNums) {
	                return dn
	            }
	        }
	        // 4. 松散匹配
	        for (dn in deviceNames) {
	            val kws = looseTitleKeywords[dn] ?: continue
	            var allMatch = true
	            for (kw in kws) {
	                if (!cleaned.contains(kw)) {
	                    allMatch = false
	                    break
	                }
	            }
	            if (allMatch && cleaned.contains("板交")) return dn
	        }
	        return null
	    }
	    private fun buildLooseTitleKeywords(deviceNames: List<String>): Map<String, List<String>> {
	        val map = HashMap<String, MutableList<String>>()
	        for (dn in deviceNames) {
	            val kws = mutableListOf<String>()
	            val nums = Regex("""\d+""").findAll(dn).map { it.value }.toList()
	            kws.addAll(nums)
	            if (dn.contains("板交")) kws.add("板交")
	            if (dn.contains("水汀")) kws.add("水汀")
	            if (dn.contains("备用")) kws.add("备用")
	            if (dn.contains("楼")) kws.add("楼")
	            map.getOrPut(dn) { mutableListOf() }.addAll(kws.distinct())
	        }
	        return map
	    }
	}
