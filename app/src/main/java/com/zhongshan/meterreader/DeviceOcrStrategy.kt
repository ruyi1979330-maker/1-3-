// 文件名: DeviceOcrStrategy.kt
package com.zhongshan.meterreader

object DeviceOcrStrategy {
    data class HardcodedRoi(
        val xPercent: Float,
        val yPercent: Float,
        val wPercent: Float,
        val hPercent: Float,
        val fieldId: String,
        val label: String
    )

    data class RoiRelative(
        val xStartPct: Float,
        val yStartPct: Float,
        val xEndPct: Float,
        val yEndPct: Float,
        val fieldId: String,
        val label: String
    )

    // =====================================================================
    // 绝对百分比坐标（基于3000×4000带黑边原图，相机模式使用）
    // =====================================================================
    private val screw1Evaporator = listOf(
        HardcodedRoi(0.7600f, 0.3450f, 0.1800f, 0.0400f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
        HardcodedRoi(0.7500f, 0.4050f, 0.1900f, 0.0400f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
        HardcodedRoi(0.7600f, 0.4650f, 0.1800f, 0.0400f, "field_1_06|蒸发器蒸发温度", "蒸发器制冷剂饱和温度"),
        HardcodedRoi(0.6800f, 0.5250f, 0.2600f, 0.0400f, "field_1_05|蒸发器冷媒压力", "蒸发器制冷剂压力")
    )

    private val screw1Condenser = listOf(
        HardcodedRoi(0.7600f, 0.3450f, 0.1800f, 0.0400f, "field_1_08|冷凝器进口水温", "冷凝器回水温度"),
        HardcodedRoi(0.7600f, 0.4050f, 0.1800f, 0.0400f, "field_1_09|冷凝器出口水温", "冷凝器出水温度"),
        HardcodedRoi(0.7600f, 0.4650f, 0.1800f, 0.0400f, "field_1_13|冷凝器冷凝温度", "冷凝器制冷剂饱和温度"),
        HardcodedRoi(0.6800f, 0.5250f, 0.2600f, 0.0400f, "field_1_12|冷凝器冷媒压力", "冷凝器制冷剂压力")
    )

    private val screw1Compressor = listOf(
        HardcodedRoi(0.6800f, 0.3450f, 0.2600f, 0.0400f, "field_1_14|压缩机油压", "油压"),
        HardcodedRoi(0.7600f, 0.4650f, 0.1800f, 0.0400f, "field_1_15|压缩机排出口温度", "压缩机排出端冷剂温度"),
        HardcodedRoi(0.4300f, 0.5850f, 0.1200f, 0.0400f, "field_1_18|主机负载", "%RLA"),
        HardcodedRoi(0.4100f, 0.6450f, 0.1400f, 0.0400f, "field_1_17|电机电流", "电流L1 L2 L3")
    )

    private val absoluteConfigs: Map<String, List<HardcodedRoi>> = run {
        val map = mutableMapOf<String, List<HardcodedRoi>>()
        map["screw_1_0"] = screw1Evaporator
        map["screw_1_1"] = screw1Condenser
        map["screw_1_2"] = screw1Compressor
        map["screw_2_0"] = screw1Evaporator.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
        map["screw_2_1"] = screw1Condenser.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
        map["screw_2_2"] = screw1Compressor.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
        map["screw_3_0"] = screw1Evaporator.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
        map["screw_3_1"] = screw1Condenser.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
        map["screw_3_2"] = screw1Compressor.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
        map
    }

    // =====================================================================
    // 相对百分比坐标（基于纯发光屏幕区域，图库裁切模式使用）
    // =====================================================================
    private val screw1EvapRel = listOf(
        // 【修复】扩大框体宽度，确保完整捕获数字
        RoiRelative(0.62f, 0.22f, 0.97f, 0.35f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
        RoiRelative(0.62f, 0.35f, 0.97f, 0.48f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
        RoiRelative(0.62f, 0.48f, 0.97f, 0.61f, "field_1_06|蒸发器蒸发温度", "蒸发器制冷剂饱和温度"),
        RoiRelative(0.55f, 0.61f, 0.97f, 0.75f, "field_1_05|蒸发器冷媒压力", "蒸发器制冷剂压力")
    )

    private val screw1CondRel = listOf(
        // 【修复】扩大框体宽度，确保完整捕获数字
        RoiRelative(0.62f, 0.22f, 0.97f, 0.35f, "field_1_08|冷凝器进口水温", "冷凝器回水温度"),
        RoiRelative(0.62f, 0.35f, 0.97f, 0.48f, "field_1_09|冷凝器出口水温", "冷凝器出水温度"),
        RoiRelative(0.62f, 0.48f, 0.97f, 0.61f, "field_1_13|冷凝器冷凝温度", "冷凝器制冷剂饱和温度"),
        RoiRelative(0.55f, 0.61f, 0.97f, 0.75f, "field_1_12|冷凝器冷媒压力", "冷凝器制冷剂压力")
    )

    private val screw1CompRel = listOf(
        // 【修复】扩大框体宽度，油压和电流字段额外加宽以完整捕获 3-4 位数字
        RoiRelative(0.55f, 0.22f, 0.97f, 0.35f, "field_1_14|压缩机油压", "油压"),
        RoiRelative(0.62f, 0.48f, 0.97f, 0.61f, "field_1_15|压缩机排出口温度", "压缩机排出端冷剂温度"),
        RoiRelative(0.40f, 0.62f, 0.70f, 0.76f, "field_1_18|主机负载", "%RLA"),
        RoiRelative(0.40f, 0.76f, 0.75f, 0.90f, "field_1_17|电机电流", "电流L1 L2 L3")
    )

    private val relativeConfigs: Map<String, List<RoiRelative>> = run {
        val map = mutableMapOf<String, List<RoiRelative>>()
        map["screw_1_0"] = screw1EvapRel
        map["screw_1_1"] = screw1CondRel
        map["screw_1_2"] = screw1CompRel
        map["screw_2_0"] = screw1EvapRel.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
        map["screw_2_1"] = screw1CondRel.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
        map["screw_2_2"] = screw1CompRel.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
        map["screw_3_0"] = screw1EvapRel.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
        map["screw_3_1"] = screw1CondRel.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
        map["screw_3_2"] = screw1CompRel.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
        map
    }

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
        else -> "全组板交"
    }

    private fun shiftId(compoundId: String, offset: Int): String {
        val parts = compoundId.split("|")
        val rawId = parts[0]
        val label = if (parts.size > 1) parts[1] else ""
        val lastUnderscore = rawId.lastIndexOf("_")
        val prefix = rawId.substring(0, lastUnderscore + 1)
        val num = rawId.substring(lastUnderscore + 1).toInt() + offset
        val newId = prefix + num.toString().padStart(2, '0')
        return if (label.isNotEmpty()) "$newId|$label" else newId
    }
}
