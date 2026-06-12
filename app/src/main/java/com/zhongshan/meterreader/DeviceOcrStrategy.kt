package com.zhongshan.meterreader

import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.OCREngine.OcrLine

/**
 * 设备 OCR 提取策略
 *
 * 每种设备/屏次对应一套"关键字→字段ID"的提取规则。
 * 使用全图文字行匹配，完全不依赖坐标 ROI。
 *
 * =========================================================
 * 特灵螺杆机（Trane ADAPTIVE CONTROL）屏幕文字格式：
 *   屏1（蒸发器）：
 *     "蒸发器进水温度  9.1 C"    → 蒸发器进口水温
 *     "蒸发器出水温度  7.6 C"    → 蒸发器出口水温
 *     "蒸发器制冷剂饱和温度  6.3 C" → 蒸发器蒸发温度
 *     "蒸发器制冷剂压力  264.2 kPag" → 蒸发器冷媒压力
 *
 *   屏2（冷凝器）：
 *     "冷凝器回水温度  29.7 C"   → 冷凝器进口水温
 *     "冷凝器出水温度  31.2 C"   → 冷凝器出口水温
 *     "冷凝器制冷剂饱和温度  32.0 C" → 冷凝器冷凝温度
 *     "冷凝器制冷剂压力  717.0 kPag" → 冷凝器冷媒压力
 *
 *   屏3（压缩机）：
 *     "油压  649.7 kPag"         → 压缩机油压
 *     "压缩机制排出端冷剂温度  61.9 C" → 压缩机排出口温度
 *     "%RLA  46.5  42.8  42.5 %" → 主机负载（取第一个值）
 *     "电流L1 L2 L3  214.0 197.0 196.0 Amps" → 电机电流（取L1）
 *
 * =========================================================
 * 约克离心机 / 约克螺杆机（OPTIVIEW CONTROL）屏幕文字格式：
 *   整个屏幕包含所有参数，但文字分散在图形四角
 *   使用关键字匹配提取各参数
 * =========================================================
 */
object DeviceOcrStrategy {

    // =====================================================================
    // 特灵螺杆机 - 三屏提取规则
    // =====================================================================
    data class FieldRule(
        val fieldId: String,
        val keywords: List<String>,   // 屏幕上该参数行包含的关键词（任一匹配）
        val searchBelow: Boolean = false
    )

    private fun traneScrewRules(prefix: Int): Map<Int, List<FieldRule>> {
        val off = when (prefix) { 2 -> 30; 3 -> 50; else -> 0 }
        fun fid(n: Int) = "field_1_${String.format("%02d", n + off)}"
        return mapOf(
            // 屏1：蒸发器
            0 to listOf(
                FieldRule(fid(1), listOf("蒸发器进水温度", "蒸发器进口水温", "蒸发器回水")),
                FieldRule(fid(2), listOf("蒸发器出水温度", "蒸发器出口水温")),
                FieldRule(fid(6), listOf("蒸发器制冷剂饱和温度", "蒸发器饱和", "蒸发器蒸发温度")),
                FieldRule(fid(5), listOf("蒸发器制冷剂压力", "蒸发器冷媒压力", "蒸发器压力")),
                FieldRule(fid(3), listOf("蒸发器趋近温度", "蒸发器超近")) // 可选
            ),
            // 屏2：冷凝器
            1 to listOf(
                FieldRule(fid(8),  listOf("冷凝器回水温度", "冷凝器进水温度", "冷凝器进口")),
                FieldRule(fid(9),  listOf("冷凝器出水温度", "冷凝器出口")),
                FieldRule(fid(13), listOf("冷凝器制冷剂饱和温度", "冷凝器饱和", "冷凝器冷凝温度")),
                FieldRule(fid(12), listOf("冷凝器制冷剂压力", "冷凝器冷媒压力", "冷凝器压力")),
                FieldRule(fid(10), listOf("冷凝器趋近温度", "冷凝器超近")) // 可选
            ),
            // 屏3：压缩机与电流
            2 to listOf(
                FieldRule(fid(14), listOf("油压")),
                FieldRule(fid(15), listOf("压缩机制排出端", "排出端冷剂", "压缩机排出", "排气温度")),
                FieldRule(fid(18), listOf("%RLA", "RLA", "满载电流")),     // 主机负载
                FieldRule(fid(17), listOf("电流L1", "L1 L2", "电流 L1"))   // 电机电流L1
            )
        )
    }

    private fun yorkCentRules(): List<FieldRule> = listOf(
        // 冷冻水侧
        FieldRule("field_1_69", listOf("冷冻水温度", "冷冻水出水", "出水", "冻水出")),   // 蒸发器出口水温（冷冻水出水）
        FieldRule("field_1_68", listOf("冷冻水返回", "冻水回水", "冻水返", "返回")),      // 蒸发器进口水温（冷冻水返回）
        FieldRule("field_1_78", listOf("蒸发器压力", "蒸发器蒸发")),                      // 蒸发器冷媒压力
        FieldRule("field_1_70", listOf("蒸发器饱和温度", "蒸发器饱和")),                  // 蒸发器蒸发温度
        // 冷却水侧
        FieldRule("field_1_71", listOf("冷却水温度", "冷却水出水", "冷却出")),             // 冷凝器出口水温（冷却水出水）
        FieldRule("field_1_79", listOf("冷却水返回", "冷却回水", "冷却返")),               // 冷凝器进口水温（冷却水返回）
        FieldRule("field_1_77", listOf("冷凝器压力", "冷凝压力")),                        // 冷凝器冷媒压力
        FieldRule("field_1_81", listOf("冷凝器饱和温度", "冷凝器饱和")),                  // 冷凝器冷凝温度
        // 压缩机
        FieldRule("field_1_76", listOf("压缩机出口温度", "压缩机出口")),                   // 压缩机排出口温度
        FieldRule("field_1_82", listOf("滑阀位置", "导叶开度", "导液开度")),               // 压缩机导液开度
        FieldRule("field_1_74", listOf("油压差", "油压")),                                // 压缩机油泵压力
        FieldRule("field_1_75", listOf("油温", "油槽温度", "润滑油温"))                    // 压缩机油箱温度
    )

    private fun yorkScrewRules(prefix: Int): List<FieldRule> {
        val off = if (prefix == 1) 0 else 30
        fun fid(n: Int) = "field_3_${String.format("%02d", n + off)}"
        return listOf(
            FieldRule(fid(2),  listOf("冷冻水温度", "冷冻水出水", "冻水出")),              // 蒸发器出口水温
            FieldRule(fid(1),  listOf("冷冻水返回", "冻水回水", "冻水返")),                // 蒸发器进口水温
            FieldRule(fid(5),  listOf("蒸发器压力")),                                      // 蒸发器蒸发压力
            FieldRule(fid(6),  listOf("蒸发器饱和温度", "蒸发器饱和")),                    // 蒸发器蒸发温度
            FieldRule(fid(9),  listOf("冷却水温度", "冷却水出水", "冷却出")),              // 冷凝器出口水温
            FieldRule(fid(8),  listOf("冷却水返回", "冷却回水", "冷却返")),                // 冷凝器进口水温
            FieldRule(fid(12), listOf("冷凝器压力")),                                      // 冷凝器冷凝压力
            FieldRule(fid(13), listOf("冷凝器饱和温度", "冷凝器饱和")),                    // 冷凝器冷凝温度
            FieldRule(fid(16), listOf("压缩机出口温度", "压缩机出口")),                    // 压缩机排口温度
            FieldRule(fid(17), listOf("滑阀位置", "导叶开度")),                            // 压缩机导液开度
            FieldRule(fid(14), listOf("油压差", "油压")),                                  // 压缩机油压
            FieldRule(fid(15), listOf("油温", "油槽温度"))                                 // 压缩机油箱温度
        )
    }

    // =====================================================================
    // 对外接口：根据 machineId 和 screenIndex 返回该屏的提取结果
    // =====================================================================
    fun extract(
        lines: List<OcrLine>,
        machineId: String,
        screenIndex: Int
    ): Map<String, String> {
        val rules: List<FieldRule> = when {
            machineId == "screw_1" -> traneScrewRules(1)[screenIndex] ?: emptyList()
            machineId == "screw_2" -> traneScrewRules(2)[screenIndex] ?: emptyList()
            machineId == "screw_3" -> traneScrewRules(3)[screenIndex] ?: emptyList()
            machineId == "cent_1"  -> yorkCentRules()
            machineId == "screw_3_1" -> yorkScrewRules(1)
            machineId == "screw_3_2" -> yorkScrewRules(2)
            else -> emptyList()
        }

        val result = LinkedHashMap<String, String>()
        for (rule in rules) {
            val value = OCREngine.extractValueByKeyword(
                lines, rule.keywords, rule.searchBelow
            )
            if (value != null) result[rule.fieldId] = value
        }
        return result
    }

    // =====================================================================
    // 屏幕数量（用于判断是否还有下一屏）
    // =====================================================================
    fun totalScreens(machineId: String): Int = when {
        machineId.startsWith("screw_") && !machineId.startsWith("screw_3") -> 3  // 特灵螺杆3屏
        else -> 1  // 约克设备单屏，板交单屏
    }

    fun screenName(machineId: String, screenIndex: Int): String = when {
        machineId.startsWith("screw_") && !machineId.startsWith("screw_3") -> when (screenIndex) {
            0 -> "蒸发器"
            1 -> "冷凝器"
            2 -> "压缩机与电流"
            else -> "完成"
        }
        machineId == "cent_1"    -> "系统总览"
        machineId.startsWith("screw_3") -> "系统总览"
        else -> "全组板交"
    }
}
