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
	            // 核心修复 Q1：先按 Y 间隙切块，再分配标题，杜绝漏识别标题导致数据被吞噬
	            val groups = mutableListOf<DeviceGroup>()
	            var currentStart = 0
	            for (i in 1 until numericItems.size) {
	                val diff = numericItems[i].yTop - numericItems[i - 1].yTop
	                if (diff > medianDiff * 2.0) { // 严格遵守 2.0 倍指导原则
	                    val chunk = numericItems.subList(currentStart, i)
	                    groups.add(formGroup(chunk, dataItems.filter { it.isDeviceTitle }, deviceNames, groups.size))
	                    currentStart = i
	                }
	            }
	            val lastChunk = numericItems.subList(currentStart, numericItems.size)
	            groups.add(formGroup(lastChunk, dataItems.filter { it.isDeviceTitle }, deviceNames, groups.size))
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
	    private fun formGroup(
	        chunk: List<DataItem>,
	        titles: List<DataItem>,
	        deviceNames: List<String>,
	        fallbackIdx: Int
	    ): DeviceGroup {
	        if (chunk.isEmpty()) return DeviceGroup("", 0, emptyList())
	        val chunkTopY = chunk.first().yTop
	        var matchedTitle: String? = null
	        for (t in titles) {
	            if (t.yTop <= chunkTopY) {
	                matchedTitle = t.value
	            } else {
	                break
	            }
	        }
	        val deviceName = matchedTitle ?: deviceNames.getOrElse(fallbackIdx) { "未知板交" }
	        val deviceIdx = deviceNames.indexOf(deviceName).let { if (it >= 0) it else fallbackIdx }
	        return DeviceGroup(deviceName, deviceIdx, chunk.map { ExtractedNumber(it.yTop, it.value) })
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
	        for (dn in deviceNames) {
	            if (cleaned == cleanPlateTitle(dn)) return dn
	        }
	        for (dn in deviceNames) {
	            val cd = cleanPlateTitle(dn)
	            if (cd.isNotEmpty() && cleaned.startsWith(cd)) return dn
	        }
	        for (dn in deviceNames) {
	            val cd = cleanPlateTitle(dn)
	            // 修复：去掉 # 和 楼 防止 OCR 丢失这些符号时前缀匹配失败
	            val head = cd.replace("水汀板交", "").replace("板交", "").replace("#", "").replace("楼", "").trim()
	            if (head.isNotEmpty() && cleaned.startsWith(head) && cleaned.contains("板交")) {
	                return dn
	            }
	        }
	        for (dn in deviceNames) {
	            val kws = looseTitleKeywords[dn] ?: continue
	            if (kws.all { cleaned.contains(it) }) {
	                if (cleaned.contains("板交")) return dn
	            }
	        }
	        return null
	    }
	    private fun buildLooseTitleKeywords(deviceNames: List<String>): Map<String, List<String>> {
	        val map = HashMap<String, MutableList<String>>()
	        for (dn in deviceNames) {
	            val kws = mutableListOf<String>()
	            val nums = Regex("""\d+""").findAll(dn).map { it.value }.toList()
	            kws.addAll(nums)
	            kws.add("板交")
	            if (dn.contains("水汀")) kws.add("水汀")
	            if (dn.contains("备用")) kws.add("备用")
	            map.getOrPut(dn) { mutableListOf() }.addAll(kws)
	        }
	        return map
	    }
	}
