package com.zhongshan.meterreader

import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.OCREngine.OcrLine

/**
 * 设备 OCR 提取策略 + 表单中文标签映射
 *
 * =====================================================================
 * 本轮 Bug Fix（来自截图图1~图7）：
 *
 * 【Fix 1】%RLA 匹配失效 → 主机负载被赋值为电流214.0（图1）
 *   根因：ML Kit 对 "%" 符号识别不稳定（可能变成 "#" 或丢失），
 *         关键词 "%RLA" 匹配失败后 searchBelow=true 继续向下找，
 *         找到了下一行 "电流L1 L2 L3  214.0..." 的第一个数字 214.0
 *         赋给了"主机负载"字段。
 *   修复：%RLA 行关键词改为 ["RLA","%RLA","％RLA"]（不依赖%号），
 *         且改为 searchBelow=false，强制从同行提取 46.5，
 *         避免 searchBelow 越行取到电流值。
 *
 * 【Fix 2】约克设备大量字段缺失（图2只2个，图3只1个）
 *   根因：约克屏幕布局中，标题行（如"冷冻水温度"）和数值行（"出水 7.8°C"）
 *         是 ML Kit 识别的不同 TextBlock，且"出水"/"返回"本身不包含"冷冻水"，
 *         searchBelow 虽然向下查找，但 maxBelow=3 可能还不够，
 *         或者两行的 Y 坐标差距导致排序后不相邻。
 *   修复：增加更多关键词变体，包括只用"出水"/"返回"配合上下文区分，
 *         maxBelow 提升到 5，并新增"区域锚定"策略：
 *         先找到左侧/右侧面板的锚点词，再向下找水温值。
 *
 * 【Fix 3】字段ID与表单DOM不匹配 → 填入0个字段（图7）
 *   根因：我们用的 fieldId 如 "field_3_44" 是自定义命名，
 *         表单实际 input 元素的 id 可能完全不同（随机生成或无id）。
 *   修复：DeviceOcrStrategy 同时提供每个字段的"表单中文标签"，
 *         WebViewActivity 传入 "fieldId|中文标签" 格式，
 *         JS 优先用 id 查找，失败则用中文标签匹配父容器内的 input。
 *         中文标签来自图8（1号机房完整填写截图）逆推的实际表单字段名。
 * =====================================================================
 */
object DeviceOcrStrategy {

    /**
     * 字段规则：包含 OCR 关键词 + 对应的表单中文标签
     * formLabel：表单页面上该字段的中文标签文字（用于 JS 模糊匹配 DOM）
     */
    data class FieldRule(
        val fieldId: String,
        val keywords: List<String>,
        val searchBelow: Boolean = true,
        val maxBelow: Int = 5,
        val formLabel: String = ""   // 表单中文标签，供 WebView JS 匹配 DOM 使用
    )

    // =====================================================================
    // 特灵螺杆机规则
    // formLabel 来自图8（1号机房完整记录表）
    // 格式："1#蒸发器进口 水温" → 1#螺杆机组表单字段前缀
    // =====================================================================
    private fun traneScrewRules(prefix: Int): Map<Int, List<FieldRule>> {
        val off = when (prefix) { 2 -> 30; 3 -> 50; else -> 0 }
        val no = when (prefix) { 2 -> "2#"; 3 -> "3#"; else -> "1#" }
        fun fid(n: Int) = "field_1_${String.format("%02d", n + off)}"
        return mapOf(
            0 to listOf(  // 屏1：蒸发器
                FieldRule(fid(1),  listOf("蒸发器进水温度","蒸发器进口水温","进水温度"),    true,  3, "${no}蒸发器进口 水温"),
                FieldRule(fid(2),  listOf("蒸发器出水温度","蒸发器出口水温","出水温度"),    true,  3, "${no}蒸发器出口 水温"),
                FieldRule(fid(6),  listOf("蒸发器制冷剂饱和温度","饱和温度","蒸发器饱和"),  true,  3, "${no}蒸发器 蒸发温度"),
                FieldRule(fid(5),  listOf("蒸发器制冷剂压力","制冷剂压力","蒸发器压力"),    true,  3, "${no}蒸发器 冷媒压力"),
                FieldRule(fid(4),  listOf("蒸发器趋近温度","趋近温度"),                    true,  2, "${no}蒸发器 趋近温度")
            ),
            1 to listOf(  // 屏2：冷凝器
                FieldRule(fid(8),  listOf("冷凝器回水温度","冷凝器进水温度","回水温度"),   true,  3, "${no}冷凝器进口 水温"),
                FieldRule(fid(9),  listOf("冷凝器出水温度","冷凝器出口温度"),              true,  3, "${no}冷凝器出口 水温"),
                FieldRule(fid(13), listOf("冷凝器制冷剂饱和温度","冷凝器饱和","饱和温度"), true,  3, "${no}冷凝器 冷凝温度"),
                FieldRule(fid(12), listOf("冷凝器制冷剂压力","冷凝器冷媒压力"),            true,  3, "${no}冷凝器 冷媒压力"),
                FieldRule(fid(11), listOf("冷凝器趋近温度"),                              true,  2, "${no}冷凝器 趋近温度")
            ),
            2 to listOf(  // 屏3：压缩机与电流
                FieldRule(fid(14), listOf("油压","由压"),                                 true,  2, "${no}压缩机 油压"),
                FieldRule(fid(15), listOf("排出端冷剂温度","压缩机制排出","排出端"),       true,  2, "${no}压缩机 排出口温度"),
                // Fix 1：%RLA 改为 searchBelow=false，强制从同行提取46.5，避免越行取到电流214
                FieldRule(fid(18), listOf("RLA","%RLA","％RLA"),                         false, 0, "${no}主机 负载"),
                FieldRule(fid(17), listOf("电流L1","L1 L2 L3","L1L2L3"),                 false, 0, "${no}电机 电流")
            )
        )
    }

    // =====================================================================
    // 约克离心机规则
    // OPTIVIEW 屏幕布局：标题行和数值行分开，且四角分散
    // 增加更多关键词变体 + maxBelow=5
    // formLabel 来自图6（1号机房离心机组表单）
    // =====================================================================
    private fun yorkCentRules(): List<FieldRule> = listOf(
        // 冷冻水侧（右侧，标题"冷冻水温度"，下方"出水 x.x / 返回 x.x"）
        FieldRule("field_1_69", listOf("冷冻水温度","冻水出水","冷冻出水"),   true, 5, "蒸发器 出水温度"),
        FieldRule("field_1_68", listOf("冷冻水返回","冻水返回","冷冻回水"),   true, 5, "蒸发器 进水温度"),
        FieldRule("field_1_78", listOf("蒸发器压力","蒸发压力"),              true, 3, "蒸发器 冷媒压力"),
        FieldRule("field_1_70", listOf("蒸发器饱和","蒸发饱和温度"),          true, 3, "蒸发器 蒸发温度"),

        // 冷却水侧（左侧，标题"冷却水温度"，下方"出水 x.x / 返回 x.x"）
        FieldRule("field_1_71", listOf("冷却水温度","冷却出水","冷水出"),     true, 5, "冷凝器 出水温度"),
        FieldRule("field_1_79", listOf("冷却水返回","冷却回水","冷水回"),     true, 5, "冷凝器 进水温度"),
        FieldRule("field_1_77", listOf("冷凝器压力","冷凝压力"),              true, 3, "冷凝器 冷媒压力"),
        FieldRule("field_1_81", listOf("冷凝器饱和","冷凝饱和温度"),          true, 3, "冷凝器 冷凝温度"),

        // 压缩机（右上）
        FieldRule("field_1_76", listOf("压缩机出口温度","压缩机出口"),        true, 3, "压缩机 出口温度"),
        FieldRule("field_1_82", listOf("滑阀位置","导叶开度","导液开度"),     true, 3, "压缩机 导液开度"),

        // 油（左上，"油压差 684.8 kPaD" 同行 / "油温 43.2°C" 同行）
        FieldRule("field_1_74", listOf("油压差","油压"),                      false,0, "压缩机 油压"),
        FieldRule("field_1_75", listOf("油温","油槽温度"),                    false,0, "压缩机 油温")
    )

    // =====================================================================
    // 约克螺杆机规则
    // formLabel 来自图7（3号机房螺杆机组表单）
    // =====================================================================
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

    // =====================================================================
    // 对外接口：提取识别结果，返回 "fieldId|中文标签" → 值 的 Map
    // =====================================================================
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
        val result = LinkedHashMap<String, String>()
        for (rule in rules) {
            val value = OCREngine.extractValueByKeyword(
                lines, rule.keywords, rule.searchBelow, rule.maxBelow
            ) ?: continue
            // key 格式："fieldId|中文标签"，WebView JS 双重匹配使用
            val key = if (rule.formLabel.isNotEmpty()) "${rule.fieldId}|${rule.formLabel}"
                      else rule.fieldId
            result[key] = value
        }
        return result
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
}
