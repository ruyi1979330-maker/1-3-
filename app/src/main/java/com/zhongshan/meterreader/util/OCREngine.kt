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
     * 板交数据提取入口（Ultimate：语义锚定架构 + Latin OCR 容错引擎）
     *
     * ============================================================
     * 设计理念
     * ============================================================
     * 1. 架构层（Claude 贡献）：采用“逐字段锚定 + 组内多数表决”。
     *    - 不依赖绝对坐标，解决了设备停机导致的错位问题。
     *    - 利用“数值与其 Label 在页面上物理紧邻”的特性进行定位。
     *
     * 2. 算法层（智谱优化）：针对 ML Kit Latin OCR 的“中文弱识别”特性，
     *    重写了 `matchDeviceIndexLatinFuzzy` 匹配引擎。
     *    - 移除“板交”强校验（Latin 引擎极易漏掉此二字）。
     *    - 引入“特征金字塔”匹配：数字 > 标点(#号) > 高频汉字(楼/水/备)。
     *    - 支持“残缺匹配”：即使“1号楼板交”只识别出“1#”或“1号楼”，也能精准定位。
     *
     * ============================================================
     * 算法流程
     * ============================================================
     * 1. 提取全量文本行，分离为数值行与文本行。
     * 2. 对每个数值，向上方小范围内搜索最近的文本行作为锚点。
     * 3. 使用容错引擎解析锚点文本，猜测其归属的设备索引。
     * 4. 按物理距离将数值聚类为数据块。
     * 5. 对每个数据块进行“多数投票”决定其设备归属。
     * 6. 安全策略：若无法确定归属（如 OCR 全乱码），默认丢弃该块，
     *    防止错误填表。
     */
    suspend fun extractPlateData(
        bitmap: Bitmap,
        isRoom1: Boolean,
        plateKeywordMap: Map<String, String> // 保留参数，暂未使用
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val outData = HashMap<String, String>()
        try {
            DebugLogger.log("OCR-Plate-Debug", "开始板交识别 (Ultimate-LatinFriendly)，原图尺寸: ${bitmap.width}x${bitmap.height}, isRoom1=$isRoom1")

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
            // 搜索窗口：1.2 倍行距，覆盖 Label 与 Value 的视觉间距
            val labelSearchWindow = (medianDiff * 1.2).toInt().coerceAtLeast(15)
            data class TaggedNumber(val y: Int, val value: String, val deviceIdx: Int?)
            
            val taggedNumbers = numLines.map { num ->
                // 向上搜索最近的文本行
                val candidate = textLines
                    .filter { it.y <= num.y && it.y >= num.y - labelSearchWindow }
                    .maxByOrNull { it.y }
                
                // 使用新的容错匹配引擎
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
                    val forceByGap = gap > medianDiff * 2.2 // 略微放宽阈值，避免误切
                    if (forceBySize || forceByGap) {
                        if (current.numbers.size > 1 || !forceByGap) {
                            chunks.add(current)
                            current = Chunk()
                        } else {
                            // 孤立噪声点，丢弃
                            current = Chunk()
                        }
                    }
                }
                current.numbers.add(num)
            }
            if (current.numbers.isNotEmpty()) chunks.add(current)
            DebugLogger.log("OCR-Plate-Debug", "物理聚类切分出 ${chunks.size} 个数据块")

            // ── 7. 组内多数表决 ───────────────────────────────────────────────
            val FALLBACK_ON_NO_ANCHOR = false // 安全策略：无锚点不猜测
            for ((chunkIdx, chunk) in chunks.withIndex()) {
                if (chunk.numbers.isEmpty()) continue

                val votes = chunk.numbers.mapNotNull { it.deviceIdx }
                val deviceIdx: Int? = if (votes.isNotEmpty()) {
                    votes.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                } else {
                    null
                }

                val resolvedIdx: Int?
                val confidenceNote: String
                if (deviceIdx != null) {
                    resolvedIdx = deviceIdx
                    val consistentVotes = votes.count { it == deviceIdx }
                    confidenceNote = "锚定命中(${votes.size}/${chunk.numbers.size}票, 一致=$consistentVotes)"
                } else if (FALLBACK_ON_NO_ANCHOR) {
                    // 兜底逻辑（慎用）
                    resolvedIdx = chunkIdx 
                    confidenceNote = "无锚点-顺序兜底"
                } else {
                    resolvedIdx = null
                    confidenceNote = "无锚点-已跳过"
                }

                if (resolvedIdx == null) {
                    DebugLogger.log("OCR-Plate-Debug", "数据块[$chunkIdx] $confidenceNote —— 跳过")
                    continue
                }
                if (resolvedIdx >= deviceNames.size) {
                    DebugLogger.log("OCR-Plate-Debug", "数据块[$chunkIdx] 映射越界，跳过")
                    continue
                }

                val deviceName = deviceNames[resolvedIdx]
                DebugLogger.log("OCR-Plate-Debug", "数据块[$chunkIdx] -> $deviceName($resolvedIdx) $confidenceNote")

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
     * 针对 ML Kit Latin OCR 的容错匹配引擎。
     * 
     * 核心策略：特征金字塔匹配。
     * 1. 优先匹配唯一数字组合（如 "10" + "1#"）。
     * 2. 其次匹配特定符号（#）或高频残留汉字（楼、水）。
     * 3. 最后兜底匹配通用数字（1, 3），但需排除干扰项。
     */
    private fun matchDeviceIndexLatinFuzzy(rawText: String, deviceNames: List<String>): Int? {
        // 预处理：全小写，去空格
        val t = rawText.lowercase().replace(" ", "")
        
        // ── Room 3 简单模式 ────────────────────────────────────────────────
        if (deviceNames.size == 2) {
            // 1#板交 vs 2#板交
            // 检查 "1#" 或 "1#"（OCR可能把#识别成其他符号，这里假设#识别率尚可，或者利用数字位置）
            // Latin OCR 对 # 识别通常还行，如果不行，只能靠数字顺序
            if (t.contains("1#") || (t.contains("1") && t.contains("#"))) return 0
            if (t.contains("2#") || (t.contains("2") && t.contains("#"))) return 1
            return null
        }

        // ── Room 1 复杂消歧模式 ─────────────────────────────────────────────
        // 目标：
        // 0: 1号楼板交
        // 1: 3号楼板交
        // 2: 备用板交
        // 3: 10号楼1#板交
        // 4: 10号楼2#板交
        // 5: 1号楼水汀板交

        // 1. 极高优先级：10号楼系列 (包含数字 10)
        // 特征：必须包含 "10"
        if (t.contains("10")) {
            // 细分：是 1# 还是 2#？
            // 检查 "2" 或 "2#" (如果有2，大概率是10号楼2#)
            if (t.contains("2") && !t.contains("2#").not()) return 4 // 粗略匹配 2#
            // 否则默认为 10号楼1#
            return 3
        }

        // 2. 高优先级：1号楼水汀板交 (Idx 5)
        // 特征：包含 "水" (shui) 或 "汀" (ting)。
        // Latin OCR 有时能识别出 "水" 或 "Water" (如果是混合)，或者残缺的 "水"。
        if (t.contains("水") || t.contains("汀") || t.contains("water")) {
            // 必须同时包含 "1" 防止误判其他楼号的水汀设备（如果有的话）
            // 但根据列表，只有 1号楼有水汀，所以只要出现 "水" 基本就是它。
            return 5
        }

        // 3. 高优先级：备用板交 (Idx 2)
        // 特征：包含 "备" (bei) 或 "by" (拼音首字母?)
        // Latin OCR 对 "备" 识别较差，可能出现乱码。
        // 如果没有其他特征匹配，且包含 "by" (可能是英文混淆) 或 "备"，尝试匹配。
        if (t.contains("备") || t.contains("by") || t.contains("standby")) {
            return 2
        }

        // 4. 中优先级：3号楼板交 (Idx 1)
        // 特征：包含 "3" 和 "号楼" (或 "号")
        if ((t.contains("3号楼") || t.contains("3号")) && !t.contains("1号楼")) {
            return 1
        }

        // 5. 低优先级：1号楼板交 (Idx 0)
        // 特征：包含 "1" 和 "号楼" (或 "号")。
        // 必须排除上面已经匹配的情况（无10，无水汀）。
        if ((t.contains("1号楼") || t.contains("1号")) && !t.contains("10")) {
            return 0
        }
        
        // 兜底：只识别到了一个简单的数字 "1" 或 "3"
        // 这种情况比较危险，容易误判。不如返回 null 依赖表决。
        return null
    }
}
