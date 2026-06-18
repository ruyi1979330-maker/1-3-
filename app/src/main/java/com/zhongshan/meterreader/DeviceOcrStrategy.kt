package com.zhongshan.meterreader

import com.zhongshan.meterreader.util.OCREngine.OcrLine

object DeviceOcrStrategy {

    data class FieldRule(
        val fieldId: String,
        val keywords: List<String>,
        val searchBelow: Boolean = true,
        val maxBelow: Int = 5,
        val formLabel: String = ""
    )

    private fun getLineText(line: Any): String {
        return try {
            line.javaClass.methods.firstOrNull { it.name == "getText" || it.name == "text" }?.invoke(line) as? String ?: ""
        } catch (e: Exception) { "" }
    }

    private fun getLineY(line: Any): Int {
        return try {
            val bboxMethod = line.javaClass.methods.firstOrNull { 
                it.name == "getBoundingBox" || it.name == "getFrame" || it.name == "getRect" || it.name == "boundingBox"
            }
            val bbox = bboxMethod?.invoke(line)
            if (bbox != null) {
                bbox.javaClass.methods.firstOrNull { it.name == "top" || it.name == "getTop" }?.invoke(bbox) as? Int ?: 0
            } else {
                line.javaClass.methods.firstOrNull { it.name == "top" || it.name == "getTop" || it.name == "y" || it.name == "getY" }?.invoke(line) as? Int ?: 0
            }
        } catch (e: Exception) { 0 }
    }

    private val numberRegex = Regex("\\d+(\\.\\d+)?")

    private fun traneScrewRules(prefix: Int): Map<Int, List<FieldRule>> {
        val off = when (prefix) { 2 -> 30; 3 -> 50; else -> 0 }
        val no = when (prefix) { 2 -> "2#"; 3 -> "3#"; else -> "1#" }
        fun fid(n: Int) = "field_1_${String.format("%02d", n + off)}"
        return mapOf(
            0 to listOf(
                FieldRule(fid(1),  listOf("蒸发器进水温度","蒸发器进口水温","进水温度"),    true,  3, "${no}蒸发器进口 水温"),
                FieldRule(fid(2),  listOf("蒸发器出水温度","蒸发器出口水温","出水温度"),    true,  3, "${no}蒸发器出口 水温"),
                FieldRule(fid(6),  listOf("蒸发器制冷剂饱和温度","饱和温度","蒸发器饱和"),  true,  3, "${no}蒸发器 蒸发温度"),
                FieldRule(fid(5),  listOf("蒸发器制冷剂压力","制冷剂压力","蒸发器压力"),    true,  3, "${no}蒸发器 冷媒压力"),
                FieldRule(fid(4),  listOf("蒸发器趋近温度","趋近温度"),                    true,  2, "${no}蒸发器 趋近温度")
            ),
            1 to listOf(
                FieldRule(fid(8),  listOf("冷凝器回水温度","冷凝器进水温度","回水温度"),   true,  3, "${no}冷凝器进口 水温"),
                FieldRule(fid(9),  listOf("冷凝器出水温度","冷凝器出口温度"),              true,  3, "${no}冷凝器出口 水温"),
                FieldRule(fid(13), listOf("冷凝器制冷剂饱和温度","冷凝器饱和","饱和温度"), true,  3, "${no}冷凝器 冷凝温度"),
                FieldRule(fid(12), listOf("冷凝器制冷剂压力","冷凝器冷媒压力"),            true,  3, "${no}冷凝器 冷媒压力"),
                FieldRule(fid(11), listOf("冷凝器趋近温度"),                              true,  2, "${no}冷凝器 趋近温度")
            ),
            2 to listOf(
                FieldRule(fid(14), listOf("油压","由压"),                                 true,  2, "${no}压缩机 油压"),
                FieldRule(fid(15), listOf("排出端冷剂温度","压缩机制排出","排出端"),       true,  2, "${no}压缩机 排出口温度"),
                FieldRule(fid(18), listOf("RLA","%RLA","％RLA"),                         false, 0, "${no}主机 负载"),
                FieldRule(fid(17), listOf("电流L1","L1 L2 L3","L1L2L3"),                 false, 0, "${no}电机 电流")
            )
        )
    }

    private fun yorkCentRules(): List<FieldRule> = listOf(
        FieldRule("field_1_69", listOf("冷冻水温度","冻水出水","冷冻出水"),   true, 5, "蒸发器 出水温度"),
        FieldRule("field_1_68", listOf("冷冻水返回","冻水返回","冷冻回水"),   true, 5, "蒸发器 进水温度"),
        FieldRule("field_1_78", listOf("蒸发器压力","蒸发压力"),              true, 3, "蒸发器 冷媒压力"),
        FieldRule("field_1_70", listOf("蒸发器饱和","蒸发饱和温度"),          true, 3, "蒸发器 蒸发温度"),
        FieldRule("field_1_71", listOf("冷却水温度","冷却出水","冷水出"),     true, 5, "冷凝器 出水温度"),
        FieldRule("field_1_79", listOf("冷却水返回","冷却回水","冷水回"),     true, 5, "冷凝器 进水温度"),
        FieldRule("field_1_77", listOf("冷凝器压力","冷凝压力"),              true, 3, "冷凝器 冷媒压力"),
        FieldRule("field_1_81", listOf("冷凝器饱和","冷凝饱和温度"),          true, 3, "冷凝器 冷凝温度"),
        FieldRule("field_1_76", listOf("压缩机出口温度","压缩机出口"),        true, 3, "压缩机 出口温度"),
        FieldRule("field_1_82", listOf("滑阀位置","导叶开度","导液开度"),     true, 3, "压缩机 导液开度"),
        FieldRule("field_1_74", listOf("油压差","油压"),                      false,0, "压缩机 油压"),
        FieldRule("field_1_75", listOf("油温","油槽温度"),                    false,0, "压缩机 油温")
    )

    private fun yorkScrewRules(prefix: Int): List<FieldRule> {
        val off = if (prefix == 1) 0 else 30
        val no  = if (prefix == 1) "1#" else "2#"
        fun fid(n: Int) = "field_3_${String.format("%02d", n + off)}"
        return listOf(
            FieldRule(fid(2),  listOf("冷冻水温度","冻水出水","冷冻出水"),  true, 5, "${no}蒸发器 出水温度"),
            FieldRule(fid(1),  listOf("冷冻水返回","冻水返回","冷冻回水"), true, 5, "${no}蒸发器 进水温度"),
            FieldRule(fid(5),  listOf("蒸发器压力","蒸发压力"),            true, 3, "${no}蒸发器 蒸发压力"),
            FieldRule(fid(6),  listOf("蒸发器饱和","蒸发饱和温度"),        true, 3, "${no}蒸发器 蒸发温度"),
            FieldRule(fid(9),  listOf("冷却水温度","冷却出水","冷水出"),   true, 5, "${no}冷凝器 出水温度"),
            FieldRule(fid(8),  listOf("冷却水返回","冷却回水","冷水回"),   true, 5, "${no}冷凝器 进水温度"),
            FieldRule(fid(12), listOf("冷凝器压力","冷凝压力"),            true, 3, "${no}冷凝器 冷凝压力"),
            FieldRule(fid(13), listOf("冷凝器饱和","冷凝饱和温度"),        true, 3, "${no}冷凝器 冷凝温度"),
            FieldRule(fid(16), listOf("压缩机出口温度","压缩机出口"),      true, 3, "${no}压缩机 排口温度"),
            FieldRule(fid(17), listOf("滑阀位置","导叶开度"),              true, 3, "${no}压缩机 导液开度"),
            FieldRule(fid(14), listOf("油压差","油压"),                    false,0, "${no}压缩机 油压"),
            FieldRule(fid(15), listOf("油温","油槽温度"),                  false,0, "${no}压缩机 油温")
        )
    }

    fun extract(lines: List<OcrLine>, machineId: String, screenIndex: Int): Map<String, String> {
        val rules: List<FieldRule> = when {
            machineId == "screw_1"   -> traneScrewRules(1)[screenIndex] ?: emptyList()
            machineId == "screw_2"   -> traneScrewRules(2)[screenIndex] ?: emptyList()
            machineId == "screw_3"   -> traneScrewRules(3)[screenIndex] ?: emptyList()
            machineId == "cent_1"    -> yorkCentRules()
            machineId == "screw_3_1" -> yorkScrewRules(1)
            machineId == "screw_3_2" -> yorkScrewRules(2)
            else -> emptyList()
        }

        val sortedLines = lines.sortedBy { getLineY(it) }
        val extractedValues = mutableMapOf<String, String>()

        for (rule in rules) {
            val anchorIndex = sortedLines.indexOfFirst { line ->
                val text = getLineText(line)
                rule.keywords.any { kw -> text.contains(kw, ignoreCase = true) }
            }

            if (anchorIndex != -1) {
                val anchorText = getLineText(sortedLines[anchorIndex])

                if (!rule.searchBelow) {
                    val cleanVal = cleanNumber(anchorText)
                    if (cleanVal != null) {
                        extractedValues[rule.fieldId] = cleanVal
                        continue
                    }
                }

                var belowCount = 0
                var lastY = getLineY(sortedLines[anchorIndex])
                
                for (i in anchorIndex + 1 until sortedLines.size) {
                    val currentY = getLineY(sortedLines[i])
                    if (currentY - lastY > 40) {
                        belowCount++
                        lastY = currentY
                    }
                    if (belowCount > rule.maxBelow) break

                    val text = getLineText(sortedLines[i])
                    val cleanVal = cleanNumber(text)
                    if (cleanVal != null) {
                        extractedValues[rule.fieldId] = cleanVal
                        break
                    }
                }
            }
        }

        val finalResult = LinkedHashMap<String, String>()
        for (rule in rules) {
            val value = extractedValues[rule.fieldId] ?: continue
            val key = if (rule.formLabel.isNotEmpty()) "${rule.fieldId}|${rule.formLabel}" else rule.fieldId
            finalResult[key] = value
        }
        return finalResult
    }

    private fun cleanNumber(text: String): String? {
        val match = numberRegex.find(text) ?: return null
        var clean = match.value.replace(Regex("[^0-9.]"), "")
        val parts = clean.split(".")
        if (parts.size > 2) {
            clean = parts[0] + "." + parts.subList(1, parts.size).joinToString("")
        }
        if (clean.isEmpty() || clean == ".") return null
        
        val fVal = clean.toFloatOrNull()
        if (fVal != null && fVal in -100f..9999f) {
            return clean
        }
        return null
    }

    fun totalScreens(machineId: String): Int = when {
        machineId == "screw_1" || machineId == "screw_2" || machineId == "screw_3" -> 3
        else -> 1
    }

    fun screenName(machineId: String, screenIndex: Int): String = when {
        machineId.startsWith("screw_") && !machineId.startsWith("screw_3") -> when (screenIndex) {
            0 -> "蒸发器"; 1 -> "冷凝器"; 2 -> "压缩机与电流"; else -> "完成"
        }
        machineId == "cent_1"           -> "系统总览"
        machineId.startsWith("screw_3") -> "系统总览"
        else                            -> "全组板交"
    }

    // =====================================================================
    // 【新增】：供「定标器」获取对应机器每一屏的字段 ID 和中文名称
    // =====================================================================
    fun getFieldList(machineId: String, screenIndex: Int): Pair<List<String>, List<String>> {
        val rules: List<FieldRule> = when {
            machineId == "screw_1"   -> traneScrewRules(1)[screenIndex] ?: emptyList()
            machineId == "screw_2"   -> traneScrewRules(2)[screenIndex] ?: emptyList()
            machineId == "screw_3"   -> traneScrewRules(3)[screenIndex] ?: emptyList()
            machineId == "cent_1"    -> yorkCentRules()
            machineId == "screw_3_1" -> yorkScrewRules(1)
            machineId == "screw_3_2" -> yorkScrewRules(2)
            else -> emptyList()
        }
        val ids = rules.map { it.fieldId }
        val labels = rules.map { it.formLabel }
        return Pair(ids, labels)
    }
}
