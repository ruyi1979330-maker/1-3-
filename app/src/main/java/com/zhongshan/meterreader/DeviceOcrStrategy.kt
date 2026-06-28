package com.zhongshan.meterreader

/**
 * 2026-06-28 修复：使用用户提供的精确像素坐标更新所有设备 ROI。
 * 所有坐标基于 3000x4000 大黑边原图，统一使用绝对坐标。
 */
object DeviceOcrStrategy {

    data class HardcodedRoi(
        val xPercent: Float,
        val yPercent: Float,
        val wPercent: Float,
        val hPercent: Float,
        val fieldId: String,   // 格式："唯一ID|带机组号的中文名"
        val label: String      // 屏幕文字（仅辅助）
    )

    data class RoiRelative(
        val xStartPct: Float,
        val yStartPct: Float,
        val xEndPct: Float,
        val yEndPct: Float,
        val fieldId: String,
        val label: String
    )

    // ===================== 工具函数：将像素坐标转为百分比 =====================
    private fun px2pct(px: Int, base: Int): Float = px.toFloat() / base.toFloat()

    private fun makeRoi(
        left: Int, top: Int, right: Int, bottom: Int,
        fieldId: String, label: String
    ): HardcodedRoi {
        return HardcodedRoi(
            xPercent = px2pct(left, 3000),
            yPercent = px2pct(top, 4000),
            wPercent = px2pct(right - left, 3000),
            hPercent = px2pct(bottom - top, 4000),
            fieldId = fieldId,
            label = label
        )
    }

    // ===================== 特灵螺杆机 1# =====================
    // 蒸发器 (屏0)
    private val screw1Evap = listOf(
        makeRoi(2459, 1513, 2840, 1623, "field_1_01|1#蒸发器进口水温", "蒸发器进水温度"),
        makeRoi(2509, 1726, 2846, 1838, "field_1_02|1#蒸发器出口水温", "蒸发器出水温度"),
        makeRoi(2181, 2151, 2846, 2276, "field_1_05|1#蒸发器冷媒压力", "蒸发器制冷剂压力"),
        makeRoi(2512, 1938, 2843, 2054, "field_1_06|1#蒸发器蒸发温度", "蒸发器制冷剂饱和温度")
    )
    // 冷凝器 (屏1)
    private val screw1Cond = listOf(
        makeRoi(2453, 1510, 2859, 1629, "field_1_08|1#冷凝器进口水温", "冷凝器回水温度"),
        makeRoi(2453, 1729, 2853, 1835, "field_1_09|1#冷凝器出口水温", "冷凝器出水温度"),
        makeRoi(2181, 2151, 2843, 2276, "field_1_12|1#冷凝器冷媒压力", "冷凝器制冷剂压力"),
        makeRoi(2443, 1941, 2850, 2060, "field_1_13|1#冷凝器冷凝温度", "冷凝器制冷剂饱和温度")
    )
    // 压缩机 (屏2)
    private val screw1Comp = listOf(
        makeRoi(2190, 1508, 2856, 1637, "field_1_14|1#压缩机油压", "油压"),
        makeRoi(2450, 1940, 2843, 2052, "field_1_15|1#压缩机排出口温度", "压缩机排出端冷剂温度"),
        makeRoi(1456, 2355, 2853, 2477, "field_1_18|1#主机负载", "%RLA"),
        makeRoi(1393, 2577, 2828, 2690, "field_1_17|1#电机电流", "电流L1 L2 L3")
    )

    // ===================== 约克离心机 (单台) =====================
    private val yorkCent = listOf(
        makeRoi(2450, 2154, 2712, 2238, "field_1_69|蒸发器出口水温", "冷冻水温度 出水"),
        makeRoi(2446, 2244, 2712, 2319, "field_1_68|蒸发器进口水温", "冷冻水温度 返回"),
        makeRoi(2271, 3285, 2625, 3363, "field_1_78|蒸发器冷媒压力", "蒸发器压力"),
        makeRoi(2268, 3376, 2621, 3454, "field_1_70|蒸发器蒸发温度", "蒸发器饱和温度"),
        makeRoi(418, 2147, 690, 2232, "field_1_71|冷凝器出口水温", "冷却水温度 出水"),
        makeRoi(421, 2244, 693, 2326, "field_1_79|冷凝器进口水温", "冷却水温度 返回"),
        makeRoi(809, 3276, 1165, 3366, "field_1_77|冷凝器冷媒压力", "冷凝器压力"),
        makeRoi(815, 3376, 1168, 3454, "field_1_81|冷凝器冷凝温度", "冷凝器饱和温度"),
        makeRoi(815, 1735, 1090, 1816, "field_1_76|压缩机排气温度", "压缩机出口温度"),
        makeRoi(815, 1826, 1018, 1904, "field_1_82|压缩机导液开度", "%满载安培"),
        makeRoi(2353, 1785, 2718, 1869, "field_1_74|压缩机油泵压力", "油压"),
        makeRoi(2359, 1691, 2715, 1776, "field_1_75|压缩机油箱温度", "油槽温度")
    )

    // ===================== 约克螺杆机 1# =====================
    private val screw3_1 = listOf(
        makeRoi(2434, 2331, 2703, 2412, "field_3_02|1#蒸发器出口水温", "冷冻水温度 出水"),
        makeRoi(2434, 2425, 2706, 2506, "field_3_01|1#蒸发器进口水温", "冷冻水温度 返回"),
        makeRoi(2250, 3369, 2603, 3447, "field_3_05|1#蒸发器蒸发压力", "蒸发压力"),
        makeRoi(2253, 3462, 2609, 3541, "field_3_06|1#蒸发器蒸发温度", "蒸发器饱和温度"),
        makeRoi(425, 2322, 687, 2397, "field_3_09|1#冷凝器出口水温", "冷却水温度 出水"),
        makeRoi(425, 2409, 684, 2494, "field_3_08|1#冷凝器进口水温", "冷却水温度 返回"),
        makeRoi(812, 3353, 1156, 3431, "field_3_12|1#冷凝器冷凝压力", "冷凝器压力"),
        makeRoi(815, 3441, 1156, 3522, "field_3_13|1#冷凝器冷凝温度", "冷凝器饱和温度"),
        makeRoi(2546, 1794, 2821, 1872, "field_3_16|1#压缩机排口温度", "压缩机出口温度"),
        makeRoi(2550, 2097, 2750, 2178, "field_3_17|1#压缩机导液开度", "滑阀位置"),
        makeRoi(837, 1812, 1187, 1884, "field_3_14|1#压缩机油压", "油压差"),
        makeRoi(837, 1894, 1093, 1972, "field_3_15|1#压缩机油箱温度", "油温")
    )

    // ===================== 构建绝对坐标配置 =====================
    private fun shiftFieldId(compoundId: String, offset: Int, oldPrefix: String, newPrefix: String): String {
        val parts = compoundId.split("|")
        val rawId = parts[0]
        val label = if (parts.size > 1) parts[1].replace(oldPrefix, newPrefix) else ""
        val lastUnderscore = rawId.lastIndexOf("_")
        val prefix = rawId.substring(0, lastUnderscore + 1)
        val num = rawId.substring(lastUnderscore + 1).toInt() + offset
        val newId = prefix + num.toString().padStart(2, '0')
        return if (label.isNotEmpty()) "$newId|$label" else newId
    }

    private val absoluteConfigs: Map<String, List<HardcodedRoi>> = run {
        val map = mutableMapOf<String, List<HardcodedRoi>>()
        map["screw_1_0"] = screw1Evap
        map["screw_1_1"] = screw1Cond
        map["screw_1_2"] = screw1Comp

        // 特灵2# (偏移+30)
        map["screw_2_0"] = screw1Evap.map { it.copy(fieldId = shiftFieldId(it.fieldId, 30, "1#", "2#")) }
        map["screw_2_1"] = screw1Cond.map { it.copy(fieldId = shiftFieldId(it.fieldId, 30, "1#", "2#")) }
        map["screw_2_2"] = screw1Comp.map { it.copy(fieldId = shiftFieldId(it.fieldId, 30, "1#", "2#")) }
        // 特灵3# (偏移+50)
        map["screw_3_0"] = screw1Evap.map { it.copy(fieldId = shiftFieldId(it.fieldId, 50, "1#", "3#")) }
        map["screw_3_1"] = screw1Cond.map { it.copy(fieldId = shiftFieldId(it.fieldId, 50, "1#", "3#")) }
        map["screw_3_2"] = screw1Comp.map { it.copy(fieldId = shiftFieldId(it.fieldId, 50, "1#", "3#")) }

        map["cent_1_0"] = yorkCent

        map["screw_3_1_0"] = screw3_1
        // 约克螺杆2# (偏移+30)
        map["screw_3_2_0"] = screw3_1.map { it.copy(fieldId = shiftFieldId(it.fieldId, 30, "1#", "2#")) }
        map
    }

    // ===================== 相对坐标配置（仍保留，但当前已不使用） =====================
    private val relativeConfigs: Map<String, List<RoiRelative>> = emptyMap()  // 暂时清空，强制使用绝对坐标

    fun getHardcodedRois(machineId: String, screenIndex: Int): List<HardcodedRoi> {
        return absoluteConfigs["${machineId}_${screenIndex}"] ?: emptyList()
    }

    fun getRelativeRois(machineId: String, screenIndex: Int): List<RoiRelative> {
        return relativeConfigs["${machineId}_${screenIndex}"] ?: emptyList()
    }

    fun totalScreens(machineId: String): Int = when (machineId) {
        "screw_1", "screw_2", "screw_3" -> 3
        else -> 1
    }

    fun screenName(machineId: String, screenIndex: Int): String = when {
        machineId in listOf("screw_1", "screw_2", "screw_3") -> when (screenIndex) {
            0 -> "蒸发器"; 1 -> "冷凝器"; 2 -> "压缩机与电流"; else -> "完成"
        }
        machineId == "cent_1"   -> "系统总览"
        machineId.startsWith("screw_3_") -> "系统总览"
        else -> "全组板交"
    }
}
