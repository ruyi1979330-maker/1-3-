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

    /**
     * 板交数据提取入口（Ultimate v2：语义锚定 + 几何兜底混合引擎）
     *
     * ============================================================
     * 核心修复：解决 Latin OCR 无法识别中文导致的丢数据问题
     * ============================================================
     * 针对 1号机房 "1号楼"、"3号楼" 等中文标题识别失败的情况，
     * 引入 "几何固定区域" 作为第二级判断依据。
     *
     * 策略：
     * 1. 优先使用语义锚定（文本匹配）。如果匹配成功（如 "10号楼"），置信度最高。
     * 2. 如果语义锚定失败（无中文识别），启用几何兜底。
     *    - 基于全页面长截图固定布局假设，计算数据块的平均 Y 坐标。
     *    - 将 Y 坐标映射到屏幕对应的设备槽位。
     *    - 这样即使完全没有文本识别，只要布局固定，数据依然不会丢失或错位。
     */
    suspend fun extractPlateData(
        bitmap: Bitmap,
        isRoom1: Boolean,
        plateKeywordMap: Map<String, String> // 保留参数，暂未使用
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val outData = HashMap<String, String>()
        try {
            DebugLogger.log("OCR-Plate-Debug", "开始板交识别 (Ultimate-Hybrid)，原图尺寸: ${bitmap.width}x${bitmap.height}, isRoom1=$isRoom1")

            // ── 1. 文本行提取与去重 ──────────────────────────────────────────
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

            // ── 2. 业务配置定义 ──────────────────────────────────────────────
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
            val prefix = if (isRoom1) "bj1_" else "bj3_"

            // ── 3. 拆分数值行 / 非数值行 ───────────────────────────────────────
            data class NumLine(val y: Int, val value: String)
            data class TextLine(val y: Int, val text: String)
            val numLines = mutableListOf<NumLine>()
            val textLines = mutableListOf<TextLine>()
            for (line in distinctLines) {
                val normalizedText = line.text.replace(",", ".")
                val match = Regex("""\d{1,3}\.\d{1,2}""").find(normalizedText)
                val fVal = match?.value?.toFloatOrNull()
                if (match != null && fVal != null && fVal in 0f..150f) {
                    numLines.add(NumLine(line.y, match.value))
                } else {
                    textLines.add(TextLine(line.y, line.text))
                }
            }
            numLines.sortBy { it.y }
            textLines.sortBy { it.y }

            if (numLines.isEmpty()) {
                DebugLogger.log("OCR-Plate-Debug", "无任何有效数值，识别终止")
                return@withContext outData
            }

            // ── 4. 计算行距中位数（聚类切分用） ──────────────────────────────
            val allYs = numLines.map { it.y }
            val yDiffs = (1 until allYs.size).map { allYs[it] - allYs[it - 1] }
            val medianDiff = if (yDiffs.isNotEmpty()) {
                val sorted = yDiffs.sorted()
                sorted[sorted.size / 2].coerceAtLeast(20)
            } else {
                100
            }
            DebugLogger.log("OCR-Plate-Debug", "行距中位数 = $medianDiff, 数字行总数 = ${numLines.size}")

            // ── 5. 逐数值就近锚定 ───────────────────────────────────────────────
            val labelSearchWindow = (medianDiff * 1.2).toInt().coerceAtLeast(15)
            data class TaggedNumber(val y: Int, val value: String, val deviceIdx: Int?)
            
            val taggedNumbers = numLines.map { num ->
                val candidate = textLines
                    .filter { it.y <= num.y && it.y >= num.y - labelSearchWindow }
                    .maxByOrNull { it.y }
                val deviceIdx = candidate?.let { matchDeviceIndexLatinFuzzy(it.text, deviceNames) }
                TaggedNumber(num.y, num.value, deviceIdx)
            }

            val matchedCount = taggedNumbers.count { it.deviceIdx != null }
            DebugLogger.log("OCR-Plate-Debug", "逐字段锚定完成：${matchedCount}/${taggedNumbers.size} 个数值成功匹配到设备标签")

            // ── 6. 物理聚类切分 ─────────────────────────────────────────────────
            data class Chunk(val numbers: MutableList<TaggedNumber> = mutableListOf())
            val chunks = mutableListOf<Chunk>()
            var current = Chunk()
            for (num in taggedNumbers) {
                if (current.numbers.isNotEmpty()) {
                    val gap = num.y - current.numbers.last().y
                    val forceBySize = current.numbers.size >= fieldsPerGroup
                    val forceByGap = gap > medianDiff * 2.2
                    if (forceBySize || forceByGap) {
                        if (current.numbers.size > 1 || !forceByGap) {
                            chunks.add(current)
                            current = Chunk()
                        } else {
                            current = Chunk()
                        }
                    }
                }
                current.numbers.add(num)
            }
            if (current.numbers.isNotEmpty()) chunks.add(current)
            DebugLogger.log("OCR-Plate-Debug", "物理聚类切分出 ${chunks.size} 个数据块")

            // ── 7. 混合引擎决策：语义锚定 OR 几何兜底 ───────────────────────
            for ((chunkIdx, chunk) in chunks.withIndex()) {
                if (chunk.numbers.isEmpty()) continue

                val votes = chunk.numbers.mapNotNull { it.deviceIdx }
                
                // 计算平均 Y 坐标用于几何兜底
                val avgY = chunk.numbers.map { it.y }.average().toInt()

                val deviceIdx: Int? = if (votes.isNotEmpty()) {
                    // 尝试多数表决
                    votes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                } else {
                    null
                }

                val resolvedIdx: Int?
                val confidenceNote: String
                
                if (deviceIdx != null) {
                    // 策略 A：语义锚定成功
                    resolvedIdx = deviceIdx
                    confidenceNote = "语义锚定命中"
                } else {
                    // 策略 B：语义锚定失败 -> 触发几何兜底 (Fixed Zone Fallback)
                    // 适用于固定布局的全页面截图
                    val geoIdx = getDeviceIndexByZone(avgY, bitmap.height, deviceNames.size)
                    resolvedIdx = geoIdx
                    confidenceNote = "语义失败-几何兜底(Y=$avgY)"
                }

                if (resolvedIdx == null || resolvedIdx >= deviceNames.size) {
                    DebugLogger.log("OCR-Plate-Debug", "数据块[$chunkIdx] 映射异常，跳过")
                    continue
                }

                val deviceName = deviceNames[resolvedIdx]
                DebugLogger.log("OCR-Plate-Debug", "数据块[$chunkIdx] -> $deviceName($resolvedIdx) [$confidenceNote]")

                // ── 组内空位补齐 ─────────────────────────────────────────────
                val sortedValues = chunk.numbers.sortedBy { it.y }
                val padded = mutableListOf<String?>()
                if (sortedValues.isNotEmpty()) {
                    padded.add(sortedValues[0].value)
                    for (i in 1 until sortedValues.size) {
                        val diff = sortedValues[i].y - sortedValues[i - 1].y
                        if (diff > medianDiff * 1.5) {
                            val missingCount = (diff / medianDiff).toInt() - 1
                            repeat(missingCount.coerceIn(0, fieldsPerGroup)) { padded.add(null) }
                        }
                        padded.add(sortedValues[i].value)
                    }
                }

                for (i in padded.indices) {
                    if (i >= fieldsPerGroup) break
                    val v = padded[i] ?: continue
                    val label = "${deviceName}${fieldNames[i]}"
                    val key = "${prefix}${resolvedIdx}|$label"
                    outData[key] = v
                }
            }

            DebugLogger.log("OCR-Plate-Debug", "最终映射结果: $outData")
        } catch (e: Exception) {
            DebugLogger.log("OCR-Plate-Error", "板交识别发生异常: ${Log.getStackTraceString(e)}")
        }
        return@withContext outData
    }

    /**
     * 几何兜底：固定区域映射
     * 根据数据块的平均 Y 坐标，将其映射到固定的设备槽位。
     * 假设：截图为全页面固定布局。
     */
    private fun getDeviceIndexByZone(y: Int, imgHeight: Int, deviceCount: Int): Int {
        val headerRatio = 0.12f // 顶部表头缓冲
        val headerHeight = (imgHeight * headerRatio).toInt()
        val effectiveHeight = imgHeight - headerHeight
        val slotHeight = (effectiveHeight.toFloat() / deviceCount).coerceAtLeast(50f)
        val maxIndex = deviceCount - 1
        
        // 计算相对 Y 坐标
        val relativeY = (y - headerHeight).coerceAtLeast(0)
        
        // 计算槽位索引
        return (relativeY / slotHeight).toInt().coerceIn(0, maxIndex)
    }

    /**
     * 针对 ML Kit Latin OCR 的容错匹配引擎。
     */
    private fun matchDeviceIndexLatinFuzzy(rawText: String, deviceNames: List<String>): Int? {
        val t = rawText.lowercase().replace(" ", "")
        
        // ── Room 3 简单模式 ────────────────────────────────────────────────
        if (deviceNames.size == 2) {
            if (t.contains("1#") || (t.contains("1") && t.contains("#"))) return 0
            if (t.contains("2#") || (t.contains("2") && t.contains("#"))) return 1
            return null
        }

        // ── Room 1 复杂消歧模式 ─────────────────────────────────────────────
        // 0: 1号楼板交, 1: 3号楼板交, 2: 备用板交
        // 3: 10号楼1#板交, 4: 10号楼2#板交, 5: 1号楼水汀板交

        // 1. 极高优先级：10号楼系列 (包含数字 10)
        if (t.contains("10")) {
            if (t.contains("2") && !t.contains("2#").not()) return 4
            return 3
        }

        // 2. 高优先级：1号楼水汀板交 (Idx 5)
        if (t.contains("水") || t.contains("汀") || t.contains("water")) {
            return 5
        }

        // 3. 高优先级：备用板交 (Idx 2)
        if (t.contains("备") || t.contains("by") || t.contains("standby")) {
            return 2
        }

        // 4. 中优先级：3号楼板交 (Idx 1)
        if ((t.contains("3号楼") || t.contains("3号")) && !t.contains("1号楼")) {
            return 1
        }

        // 5. 低优先级：1号楼板交 (Idx 0)
        if ((t.contains("1号楼") || t.contains("1号")) && !t.contains("10")) {
            return 0
        }
        
        return null
    }
}
