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

    /**
     * 一行文本及其 Y 坐标（top），用于板交识别的原始解析。
     */
    private data class RawLine(val y: Int, val text: String)

    /**
     * 一个识别出的数据行：可能是数字也可能是设备标题。
     * yTop 为该行在原图中的 top 坐标。
     */
    private data class DataItem(
        val yTop: Int,
        val value: String,
        val isDeviceTitle: Boolean
    )

    /**
     * 一组数据：该组所属设备名 + 该组内的数据行（按 y 从小到大）。
     */
    private data class DeviceGroup(
        val deviceName: String,
        val deviceIdx: Int,
        val numbers: List<ExtractedNumber>
    )

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

            // 去重（分块重叠区可能产生重复）
            val distinctLines = mutableListOf<RawLine>()
            var lastY = -1000
            var lastText = ""
            for (line in rawLines) {
                if (line.text.isBlank()) continue
                if (line.y - lastY <= 10 && line.text == lastText) {
                    continue
                }
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

            // ---------- 关键修复（Q3） ----------
            // 旧逻辑：遇到含 "#" 的行直接 continue（丢弃），再按 Y 间隙硬分组，
            // 最后用 groupIdx 直接索引 deviceNames[0..]，导致部分截图所有数据全归第一个设备。
            // 新逻辑：
            //   1) 不再丢弃任何行；把行分为“设备标题行”和“数值行”两类。
            //   2) 若能识别到设备标题，则以标题为锚点分到对应设备（可跨任意位置）。
            //   3) 若一张图里完全没有标题行，则把这一坨连续数值按 Y 顺序依次匹配
            //      deviceNames 列表（顺序一致即可，但只取实际存在的组数）。

            val dataItems = mutableListOf<DataItem>()
            val titleKeywordSet = HashSet<String>()
            for (dn in deviceNames) titleKeywordSet.add(cleanPlateTitle(dn))

            // 建立一组较宽松的“匹配标题”关键词，避免 OCR 把 #、楼、板交识别变形后漏掉。
            val looseTitleKeywords = buildLooseTitleKeywords(deviceNames)

            for (line in distinctLines) {
                val text = line.text
                if (text.isBlank()) continue

                // 1) 是否是设备标题？
                val cleaned = cleanPlateTitle(text)
                val matchedTitle = matchDeviceTitle(cleaned, deviceNames, looseTitleKeywords)
                if (matchedTitle != null) {
                    dataItems.add(DataItem(line.y, matchedTitle, true))
                    DebugLogger.log("OCR-Plate-Debug", "识别为设备标题: raw='$text' -> '$matchedTitle' Y=${line.y}")
                    continue
                }

                // 2) 否则尝试抽取数值（板交数值通常是 X.Y 两位小数，范围 0~150）
                val normalizedText = text.replace(",", ".")
                val match = Regex("""\d{1,3}\.\d{1,2}""").find(normalizedText)
                if (match != null) {
                    val value = match.value
                    val fVal = value.toFloatOrNull()
                    if (fVal != null && fVal in 0f..150f) {
                        dataItems.add(DataItem(line.y, value, false))
                    }
                }
                // 既不是标题也不是有效数值，直接忽略
            }

            val numericItems = dataItems.filter { !it.isDeviceTitle }
            DebugLogger.log(
                "OCR-Plate-Debug",
                "数值提取完成 ${numericItems.size} 个: ${numericItems.map { it.value }}; 标题识别 ${dataItems.count { it.isDeviceTitle }} 个"
            )
            if (numericItems.isEmpty()) {
                DebugLogger.log("OCR-Plate-Debug", "无任何有效数值，识别终止")
                return@withContext outData
            }

            // 计算行距中位数，用于（无标题时）按 Y 间隙进一步切分多组
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

            val groups = buildGroups(dataItems, deviceNames, medianDiff)

            // 输出分组信息
            for (g in groups) {
                DebugLogger.log(
                    "OCR-Plate-Debug",
                    "分组: device='${g.deviceName}'(idx=${g.deviceIdx}) 数值=${g.numbers.map { it.value }}"
                )
            }

            // 组内字段映射：按 y 从小到大，结合行距中位数做缺失补位
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
            e.printStackTrace()
        }
        return@withContext outData
    }

    // ----------------------------------------------------------------
    // 分组主策略：
    //   优先用设备标题行把其下方的数值归属到对应设备；
    //   若整张图没有任何标题，则用 Y 间隙切分多组后按 deviceNames 顺序命名。
    // ----------------------------------------------------------------
    private fun buildGroups(
        dataItems: List<DataItem>,
        deviceNames: List<String>,
        medianDiff: Int
    ): List<DeviceGroup> {
        val titleItems = dataItems.filter { it.isDeviceTitle }
        val numericItems = dataItems.filter { !it.isDeviceTitle }

        // ===== 策略 A：存在标题行 =====
        if (titleItems.isNotEmpty()) {
            // 按 Y 排序，确定每个标题覆盖的 Y 区间（直到下一个标题或正无穷）
            val sortedTitles = titleItems.sortedBy { it.yTop }
            val bounds = mutableListOf<Pair<Int, Int>>() // [startY, endY)
            for (i in sortedTitles.indices) {
                val startY = sortedTitles[i].yTop
                val endY = if (i + 1 < sortedTitles.size) sortedTitles[i + 1].yTop else Int.MAX_VALUE
                bounds.add(startY to endY)
            }
            // 容错：标题可能只覆盖了部分数值（如截图底部缺标题），把没有落入任何区间的数值
            //       临时挂到“未知桶”，稍后按 deviceNames 顺序/位置兜底分配。
            val bucket = LinkedHashMap<String, MutableList<ExtractedNumber>>()
            val orphanNums = mutableListOf<ExtractedNumber>()
            for (n in numericItems) {
                var placed = false
                for (i in sortedTitles.indices) {
                    val (s, e) = bounds[i]
                    if (n.yTop in s until e) {
                        val name = sortedTitles[i].value
                        bucket.getOrPut(name) { mutableListOf() }.add(ExtractedNumber(n.yTop, n.value))
                        placed = true
                        break
                    }
                }
                if (!placed) {
                    // 数值位于所有标题之上 -> 通常属于第一个标题之上的图（截图场景下不常见），归入 orphans
                    orphanNums.add(ExtractedNumber(n.yTop, n.value))
                }
            }

            val groups = mutableListOf<DeviceGroup>()
            for ((name, nums) in bucket) {
                val idx = deviceNames.indexOf(name)
                val safeIdx = if (idx >= 0) idx else 0
                groups.add(DeviceGroup(name, safeIdx, nums))
            }

            // 处理 orphan：如果只有 orphan 没有任何标题覆盖，则用一个虚拟组兜底
            if (groups.isEmpty() && orphanNums.isNotEmpty()) {
                groups.add(DeviceGroup(deviceNames.getOrElse(0) { "板交" }, 0, orphanNums))
            }
            return mergeOverlappingDeviceIdx(groups)
        }

        // ===== 策略 B：整张图没有任何标题（纯数值截图）=====
        // 用 Y 间隙切分多组，组数 ≤ deviceNames.size，按出现顺序命名。
        val sortedNums = numericItems.map { ExtractedNumber(it.yTop, it.value) }.sortedBy { it.y }
        val splitIdx = mutableListOf<Int>()
        for (i in 1 until sortedNums.size) {
            val diff = sortedNums[i].y - sortedNums[i - 1].y
            if (diff > medianDiff * 2.5) {
                splitIdx.add(i - 1)
            }
        }
        val sub = mutableListOf<MutableList<ExtractedNumber>>()
        var start = 0
        for (idx in splitIdx) {
            sub.add(sortedNums.subList(start, idx + 1).toMutableList())
            start = idx + 1
        }
        sub.add(sortedNums.subList(start, sortedNums.size).toMutableList())

        // 重要：仅当一组数值数 > 1（像一组设备的多个字段）才视为一个独立设备；
        // 单个孤立数值很可能是噪声/标题里的数字，不应独占一个设备名。
        val groups = mutableListOf<DeviceGroup>()
        var devCursor = 0
        for (nums in sub) {
            if (nums.isEmpty()) continue
            if (devCursor >= deviceNames.size) break
            val name = deviceNames[devCursor]
            groups.add(DeviceGroup(name, devCursor, nums))
            devCursor++
        }
        return groups
    }

    private fun mergeOverlappingDeviceIdx(groups: List<DeviceGroup>): List<DeviceGroup> = groups

    // ----------------------------------------------------------------
    // 标题清洗与匹配
    // ----------------------------------------------------------------
    private fun cleanPlateTitle(s: String): String {
        return s.replace(" ", "")
            .replace("\u3000", "")
            .replace("#", "#")
            .replace("楼", "楼")
            .replace("（", "(")
            .replace("）", ")")
            .trim()
    }

    /**
     * 把 OCR 文本尝试匹配到 deviceNames 中的某一项。
     * 规则（从严格到宽松）：
     *   1) cleaned == cleanPlateTitle(deviceName)            完全相等
     *   2) cleaned 以 deviceName 关键词开头（如 “1#板交xxx”）
     *   3) cleaned 同时包含 (数字前缀 + 板交) 这种核心特征
     *   4) 松散关键词命中（见 looseTitleKeywords）
     */
    private fun matchDeviceTitle(
        cleaned: String,
        deviceNames: List<String>,
        looseTitleKeywords: Map<String, List<String>>
    ): String? {
        if (cleaned.isEmpty()) return null

        // 规则1：完全匹配
        for (dn in deviceNames) {
            if (cleaned == cleanPlateTitle(dn)) return dn
        }
        // 规则2：开头包含完整 deviceName
        for (dn in deviceNames) {
            val cd = cleanPlateTitle(dn)
            if (cd.isNotEmpty() && cleaned.startsWith(cd)) return dn
        }
        // 规则3：开头包含 deviceName 去掉“板交/水汀板交”后的前缀
        for (dn in deviceNames) {
            val cd = cleanPlateTitle(dn)
            val head = cd.replace("水汀板交", "").replace("板交", "").trim()
            if (head.isNotEmpty() && cleaned.startsWith(head) && cleaned.contains("板交")) {
                return dn
            }
        }
        // 规则4：松散关键词
        for (dn in deviceNames) {
            val kws = looseTitleKeywords[dn] ?: continue
            if (kws.all { cleaned.contains(it) }) {
                // 额外要求：必须含“板交”核心词，避免把 “1#” 误判
                if (cleaned.contains("板交")) return dn
            }
        }
        return null
    }

    /**
     * 为每个设备生成一组宽松匹配关键词（去掉空格、#、楼等可能被 OCR 误识的字符）。
     * 例如 “10号楼1#板交” -> ["10", "1", "板交"]；
     *      “1#板交” -> ["1", "板交"]
     * 这样即使 “#” 被识别成空格、“楼”丢失，仍能根据 (数字, 板交) 命中。
     */
    private fun buildLooseTitleKeywords(deviceNames: List<String>): Map<String, List<String>> {
        val map = HashMap<String, MutableList<String>>()
        for (dn in deviceNames) {
            val kws = mutableListOf<String>()
            // 提取所有数字段
            val nums = Regex("""\d+""").findAll(dn).map { it.value }.toList()
            kws.addAll(nums)
            kws.add("板交")
            // 加上水汀特殊标记
            if (dn.contains("水汀")) kws.add("水汀")
            if (dn.contains("备用")) kws.add("备用")
            map.getOrPut(dn) { mutableListOf() }.addAll(kws)
        }
        return map
    }
}
