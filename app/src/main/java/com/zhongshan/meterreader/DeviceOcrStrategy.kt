package com.zhongshan.meterreader

import com.zhongshan.meterreader.util.OCREngine
import com.zhongshan.meterreader.util.OCREngine.OcrLine

/**
 * 设备 OCR 提取策略
 *
 * =====================================================================
 * Bug Fix 本轮（来自日志+截图诊断）：
 *
 * 【Bug 1】约克螺杆/离心机：大量字段 searchBelow=false 导致识别失败
 *   根因：OPTIVIEW 屏幕布局中，"冷冻水温度"是独立标题行，
 *         其下方才是 "出水 7.8°C" 这一行数值。
 *         searchBelow=false 时只在标题行自身找数字，找不到，直接跳过。
 *         只有 "油压差 684.8 kPaD" 这种标签+数值同行的格式才能被 false 模式命中。
 *   修复：所有约克设备字段改为 searchBelow=true，maxBelow=3。
 *         特灵螺杆蒸发器/冷凝器同样改为 searchBelow=true。
 *
 * 【Bug 2】特灵螺杆蒸发器"未识别到有效数据"
 *   根因：Trane 面板文字格式有两种：
 *         A. "蒸发器进水温度  9.1 C"（标签+值同行）→ searchBelow=false 可命中
 *         B. "蒸发器进水温度\n9.1 C"（ML Kit 分两行识别）→ 需要 searchBelow=true
 *         ML Kit 对同一张图的识别结果不稳定，有时分行有时同行。
 *   修复：一律改为 searchBelow=true，maxBelow=2，同行有值就直接取，
 *         同行无值则向下找，两种格式都能兼容。
 *
 * 【Bug 3】日志显示用户通过微信(tencent.mm)相册选图
 *   根因：微信的 content:// URI 在微信进程不在前台时可能无法 openInputStream，
 *         导致 loadAndFixExifMatrixSecurely 返回 null → 整张图识别失败。
 *   修复：在 StorageAndImageUtils 里增加 copyToCache 兜底：
 *         先把 URI 内容复制到 APP 私有缓存文件，再从缓存文件读取，
 *         避免跨进程 URI 权限问题。（见 StorageAndImageUtils.kt）
 * =====================================================================
 */
object DeviceOcrStrategy {

    data class FieldRule(
        val fieldId: String,
        val keywords: List<String>,
        val searchBelow: Boolean = true,   // 默认改为 true，更健壮
        val maxBelow: Int = 3
    )

    // =====================================================================
    // 特灵螺杆机规则
    // Trane ADAPTIVE CONTROL 面板：标签和数值可能同行，也可能分两行
    // 统一使用 searchBelow=true，maxBelow=2
    // =====================================================================
    private fun traneScrewRules(prefix: Int): Map<Int, List<FieldRule>> {
        val off = when (prefix) { 2 -> 30; 3 -> 50; else -> 0 }
        fun fid(n: Int) = "field_1_${String.format("%02d", n + off)}"
        return mapOf(
            0 to listOf(  // 屏1：蒸发器
                FieldRule(fid(1),  listOf("蒸发器进水温度", "蒸发器进口水温"),          true, 2),
                FieldRule(fid(2),  listOf("蒸发器出水温度", "蒸发器出口水温"),          true, 2),
                FieldRule(fid(6),  listOf("蒸发器制冷剂饱和温度", "蒸发器饱和温度"),    true, 2),
                FieldRule(fid(5),  listOf("蒸发器制冷剂压力", "蒸发器冷媒压力"),        true, 2),
                FieldRule(fid(4),  listOf("蒸发器趋近温度"),                            true, 2)
            ),
            1 to listOf(  // 屏2：冷凝器
                FieldRule(fid(8),  listOf("冷凝器回水温度", "冷凝器进水温度", "冷凝器进口"), true, 2),
                FieldRule(fid(9),  listOf("冷凝器出水温度", "冷凝器出口"),               true, 2),
                FieldRule(fid(13), listOf("冷凝器制冷剂饱和温度", "冷凝器饱和温度"),     true, 2),
                FieldRule(fid(12), listOf("冷凝器制冷剂压力", "冷凝器冷媒压力"),         true, 2),
                FieldRule(fid(11), listOf("冷凝器趋近温度"),                             true, 2)
            ),
            2 to listOf(  // 屏3：压缩机与电流
                // 油压行格式："油压  649.7 kPag"（标签值同行，searchBelow=true 也能兼容）
                FieldRule(fid(14), listOf("油压"),                                       true, 2),
                // 排出口温度："压缩机制排出端冷剂温度  61.9 C"
                FieldRule(fid(15), listOf("压缩机制排出端", "排出端冷剂", "排出端"),     true, 2),
                // %RLA："%RLA  46.5  42.8  42.5 %"（同行多列，取第一个数字）
                FieldRule(fid(18), listOf("%RLA", "RLA"),                                true, 2),
                // 电流："电流L1 L2 L3  214.0 197.0 196.0 Amps"
                FieldRule(fid(17), listOf("电流L1", "L1 L2 L3"),                         true, 2)
            )
        )
    }

    // =====================================================================
    // 约克离心机规则
    // OPTIVIEW 屏幕：标题行（"冷冻水温度"）和数值行（"出水 7.8°C"）分开
    // 全部 searchBelow=true，maxBelow=3
    // =====================================================================
    private fun yorkCentRules(): List<FieldRule> = listOf(
        // 冷冻水侧（右侧面板，标题"冷冻水温度"，下方"出水 x.x"/"返回 x.x"）
        FieldRule("field_1_69", listOf("冷冻水温度", "冻水出水"),                 true, 3),  // 蒸发器出口=冷冻出水
        FieldRule("field_1_68", listOf("冷冻水返回", "冻水回水"),                 true, 3),  // 蒸发器进口=冷冻返回
        FieldRule("field_1_78", listOf("蒸发器压力"),                             true, 2),  // 蒸发器冷媒压力
        FieldRule("field_1_70", listOf("蒸发器饱和温度", "蒸发器饱和"),           true, 2),  // 蒸发器蒸发温度

        // 冷却水侧（左侧面板，标题"冷却水温度"，下方"出水 x.x"/"返回 x.x"）
        FieldRule("field_1_71", listOf("冷却水温度", "冷却出水"),                 true, 3),  // 冷凝器出口=冷却出水
        FieldRule("field_1_79", listOf("冷却水返回", "冷却回水"),                 true, 3),  // 冷凝器进口=冷却返回
        FieldRule("field_1_77", listOf("冷凝器压力"),                             true, 2),  // 冷凝器冷媒压力
        FieldRule("field_1_81", listOf("冷凝器饱和温度", "冷凝器饱和"),           true, 2),  // 冷凝器冷凝温度

        // 压缩机（右上区域）
        FieldRule("field_1_76", listOf("压缩机出口温度", "压缩机出口"),           true, 2),  // 压缩机排出口温度
        FieldRule("field_1_82", listOf("滑阀位置", "导叶开度", "导液开度"),       true, 2),  // 压缩机导液开度

        // 油（左上区域，"油压差 684.8 kPaD" 同行）
        FieldRule("field_1_74", listOf("油压差", "油压"),                         true, 2),  // 压缩机油泵压力
        FieldRule("field_1_75", listOf("油温", "油槽温度"),                       true, 2)   // 压缩机油箱温度
    )

    // =====================================================================
    // 约克螺杆机规则
    // 布局与离心机类似，标题行和数值行分开
    // =====================================================================
    private fun yorkScrewRules(prefix: Int): List<FieldRule> {
        val off = if (prefix == 1) 0 else 30
        fun fid(n: Int) = "field_3_${String.format("%02d", n + off)}"
        return listOf(
            // 冷冻水侧（右侧面板）
            FieldRule(fid(2),  listOf("冷冻水温度", "冻水出水"),          true, 3),  // 蒸发器出口=冷冻出水
            FieldRule(fid(1),  listOf("冷冻水返回", "冻水回水"),          true, 3),  // 蒸发器进口=冷冻返回
            FieldRule(fid(5),  listOf("蒸发器压力"),                      true, 2),  // 蒸发器蒸发压力
            FieldRule(fid(6),  listOf("蒸发器饱和温度", "蒸发器饱和"),    true, 2),  // 蒸发器蒸发温度

            // 冷却水侧（左侧面板）
            FieldRule(fid(9),  listOf("冷却水温度", "冷却出水"),          true, 3),  // 冷凝器出口=冷却出水
            FieldRule(fid(8),  listOf("冷却水返回", "冷却回水"),          true, 3),  // 冷凝器进口=冷却返回
            FieldRule(fid(12), listOf("冷凝器压力"),                      true, 2),  // 冷凝器冷凝压力
            FieldRule(fid(13), listOf("冷凝器饱和温度", "冷凝器饱和"),    true, 2),  // 冷凝器冷凝温度

            // 压缩机
            FieldRule(fid(16), listOf("压缩机出口温度", "压缩机出口"),    true, 2),  // 压缩机排口温度
            FieldRule(fid(17), listOf("滑阀位置", "导叶开度"),             true, 2),  // 压缩机导液开度

            // 油（"油压差 684.8 kPaD" 同行；"油槽温度 43.2°C" 同行）
            FieldRule(fid(14), listOf("油压差", "油压"),                   true, 2),  // 压缩机油压
            FieldRule(fid(15), listOf("油温", "油槽温度"),                 true, 2)   // 压缩机油箱温度
        )
    }

    // =====================================================================
    // 对外接口
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
            val value = OCREngine.extractValueByKeyword(lines, rule.keywords, rule.searchBelow, rule.maxBelow)
            if (value != null) result[rule.fieldId] = value
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
        machineId == "cent_1"             -> "系统总览"
        machineId.startsWith("screw_3")   -> "系统总览"
        else                              -> "全组板交"
    }
}
