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
        HardcodedRoi(0.7600f, 0.4050f, 0.1800f, 0.0400f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
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
        // 仅取第一个负载数值
        HardcodedRoi(0.4300f, 0.5850f, 0.1200f, 0.0400f, "field_1_18|主机负载", "%RLA"),
        // 仅取L1电流数值
        HardcodedRoi(0.4100f, 0.6450f, 0.1400f, 0.0400f, "field_1_17|电机电流", "电流L1 L2 L3")
    )

    private val yorkCentrifugal = listOf(
        // 左上角
        HardcodedRoi(0.2600f, 0.4000f, 0.1200f, 0.0300f, "field_1_76|压缩机排气温度", "压缩机出口温度"),
        HardcodedRoi(0.2600f, 0.4300f, 0.1200f, 0.0300f, "field_1_82|压缩机导液开度", "%满载安培"),
        // 右上角
        HardcodedRoi(0.7800f, 0.4000f, 0.1400f, 0.0300f, "field_1_75|压缩机油箱温度", "油槽温度"),
        HardcodedRoi(0.7800f, 0.4300f, 0.1400f, 0.0300f, "field_1_74|压缩机油泵压力", "油压"),
        // 左中 冷却水
        HardcodedRoi(0.1000f, 0.5100f, 0.1200f, 0.0300f, "field_1_71|冷凝器出口水温", "冷却水温度 出水"),
        HardcodedRoi(0.1000f, 0.5400f, 0.1200f, 0.0300f, "field_1_79|冷凝器进口水温", "冷却水温度 返回"),
        // 右中 冷冻水
        HardcodedRoi(0.7800f, 0.5100f, 0.1400f, 0.0300f, "field_1_69|蒸发器出口水温", "冷冻水温度 出水"),
        HardcodedRoi(0.7800f, 0.5400f, 0.1400f, 0.0300f, "field_1_68|蒸发器进口水温", "冷冻水温度 返回"),
        // 底部左侧 冷凝器
        HardcodedRoi(0.2600f, 0.7700f, 0.1400f, 0.0300f, "field_1_77|冷凝器冷媒压力", "冷凝器压力"),
        HardcodedRoi(0.2600f, 0.8000f, 0.1400f, 0.0300f, "field_1_81|冷凝器冷凝温度", "冷凝器饱和温度"),
        // 底部右侧 蒸发器
        HardcodedRoi(0.6800f, 0.7700f, 0.2000f, 0.0300f, "field_1_78|蒸发器冷媒压力", "蒸发器压力"),
        HardcodedRoi(0.6800f, 0.8000f, 0.2000f, 0.0300f, "field_1_70|蒸发器蒸发温度", "蒸发器饱和温度")
    )

    private val screw3_1_abs = listOf(
        // 左上角
        HardcodedRoi(0.2000f, 0.4000f, 0.1800f, 0.0300f, "field_3_14|压缩机油压", "油压差"),
        HardcodedRoi(0.2600f, 0.4300f, 0.1200f, 0.0300f, "field_3_15|压缩机油箱温度", "油温"),
        // 右上角
        HardcodedRoi(0.7800f, 0.4000f, 0.1400f, 0.0300f, "field_3_16|压缩机排口温度", "压缩机出口温度"),
        HardcodedRoi(0.7800f, 0.4300f, 0.1400f, 0.0300f, "field_3_17|压缩机导液开度", "滑阀位置"),
        // 左中 冷却水
        HardcodedRoi(0.1000f, 0.5400f, 0.1200f, 0.0300f, "field_3_09|冷凝器出口水温", "冷却水温度 出水"),
        HardcodedRoi(0.1000f, 0.5700f, 0.1200f, 0.0300f, "field_3_08|冷凝器进口水温", "冷却水温度 返回"),
        // 右中 冷冻水
        HardcodedRoi(0.7800f, 0.5400f, 0.1400f, 0.0300f, "field_3_02|蒸发器出口水温", "冷冻水温度 出水"),
        HardcodedRoi(0.7800f, 0.5700f, 0.1400f, 0.0300f, "field_3_01|蒸发器进口水温", "冷冻水温度 返回"),
        // 底部左侧 冷凝器
        HardcodedRoi(0.2600f, 0.7900f, 0.1600f, 0.0300f, "field_3_12|冷凝器冷凝压力", "冷凝器压力"),
        HardcodedRoi(0.2600f, 0.8200f, 0.1600f, 0.0300f, "field_3_13|冷凝器冷凝温度", "冷凝器饱和温度"),
        // 底部右侧 蒸发器
        HardcodedRoi(0.6800f, 0.7900f, 0.2000f, 0.0300f, "field_3_05|蒸发器蒸发压力", "蒸发压力"),
        HardcodedRoi(0.6800f, 0.8200f, 0.2000f, 0.0300f, "field_3_06|蒸发器蒸发温度", "蒸发器饱和温度")
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
        map["cent_1_0"] = yorkCentrifugal
        map["screw_3_1_0"] = screw3_1_abs
        map["screw_3_2_0"] = screw3_1_abs.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
        map
    }

    // =====================================================================
    // 相对百分比坐标（基于纯发光屏幕区域，图库裁切模式使用）
    // 以屏幕左上角为原点，按屏幕自身宽高的百分比计算，适配手工裁切偏差
    // =====================================================================
    private val screw1EvapRel = listOf(
        RoiRelative(0.7500f, 0.2500f, 0.9500f, 0.3300f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
        // 左边界左移，确保数字7完整纳入
        RoiRelative(0.7400f, 0.3500f, 0.9500f, 0.4300f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
        RoiRelative(0.7500f, 0.4500f, 0.9500f, 0.5300f, "field_1_06|蒸发器蒸发温度", "蒸发器制冷剂饱和温度"),
        RoiRelative(0.6500f, 0.5500f, 0.9500f, 0.6300f, "field_1_05|蒸发器冷媒压力", "蒸发器制冷剂压力")
    )

    private val screw1CondRel = listOf(
        RoiRelative(0.7500f, 0.2500f, 0.9500f, 0.3300f, "field_1_08|冷凝器进口水温", "冷凝器回水温度"),
        RoiRelative(0.7500f, 0.3500f, 0.9500f, 0.4300f, "field_1_09|冷凝器出口水温", "冷凝器出水温度"),
        RoiRelative(0.7500f, 0.4500f, 0.9500f, 0.5300f, "field_1_13|冷凝器冷凝温度", "冷凝器制冷剂饱和温度"),
        RoiRelative(0.6500f, 0.5500f, 0.9500f, 0.6300f, "field_1_12|冷凝器冷媒压力", "冷凝器制冷剂压力")
    )

    private val screw1CompRel = listOf(
        RoiRelative(0.6500f, 0.2500f, 0.9500f, 0.3300f, "field_1_14|压缩机油压", "油压"),
        RoiRelative(0.7500f, 0.4500f, 0.9500f, 0.5300f, "field_1_15|压缩机排出口温度", "压缩机排出端冷剂温度"),
        // 仅框选第一个负载数值
        RoiRelative(0.4200f, 0.5700f, 0.5500f, 0.6500f, "field_1_18|主机负载", "%RLA"),
        // 仅框选L1电流数值
        RoiRelative(0.4000f, 0.6700f, 0.5700f, 0.7500f, "field_1_17|电机电流", "电流L1 L2 L3")
    )

    private val centRel = listOf(
        // 左上角
        RoiRelative(0.2400f, 0.3200f, 0.3800f, 0.3700f, "field_1_76|压缩机排气温度", "压缩机出口温度"),
        RoiRelative(0.2400f, 0.3700f, 0.3400f, 0.4200f, "field_1_82|压缩机导液开度", "%满载安培"),
        // 右上角
        RoiRelative(0.7600f, 0.3200f, 0.9200f, 0.3700f, "field_1_75|压缩机油箱温度", "油槽温度"),
        RoiRelative(0.7600f, 0.3700f, 0.9200f, 0.4200f, "field_1_74|压缩机油泵压力", "油压"),
        // 左中 冷却水
        RoiRelative(0.0800f, 0.5000f, 0.2000f, 0.5500f, "field_1_71|冷凝器出口水温", "冷却水温度 出水"),
        RoiRelative(0.0800f, 0.5500f, 0.2000f, 0.6000f, "field_1_79|冷凝器进口水温", "冷却水温度 返回"),
        // 右中 冷冻水
        RoiRelative(0.7600f, 0.5000f, 0.9200f, 0.5500f, "field_1_69|蒸发器出口水温", "冷冻水温度 出水"),
        RoiRelative(0.7600f, 0.5500f, 0.9200f, 0.6000f, "field_1_68|蒸发器进口水温", "冷冻水温度 返回"),
        // 底部左侧 冷凝器
        RoiRelative(0.2400f, 0.7600f, 0.4000f, 0.8100f, "field_1_77|冷凝器冷媒压力", "冷凝器压力"),
        RoiRelative(0.2400f, 0.8100f, 0.4000f, 0.8600f, "field_1_81|冷凝器冷凝温度", "冷凝器饱和温度"),
        // 底部右侧 蒸发器
        RoiRelative(0.6400f, 0.7600f, 0.9200f, 0.8100f, "field_1_78|蒸发器冷媒压力", "蒸发器压力"),
        RoiRelative(0.6400f, 0.8100f, 0.9200f, 0.8600f, "field_1_70|蒸发器蒸发温度", "蒸发器饱和温度")
    )

    private val screw3_1_rel = listOf(
        // 左上角
        RoiRelative(0.1800f, 0.3200f, 0.3800f, 0.3700f, "field_3_14|压缩机油压", "油压差"),
        RoiRelative(0.2400f, 0.3700f, 0.3600f, 0.4200f, "field_3_15|压缩机油箱温度", "油温"),
        // 右上角
        RoiRelative(0.7600f, 0.3200f, 0.9200f, 0.3700f, "field_3_16|压缩机排口温度", "压缩机出口温度"),
        RoiRelative(0.7600f, 0.3700f, 0.9200f, 0.4200f, "field_3_17|压缩机导液开度", "滑阀位置"),
        // 左中 冷却水
        RoiRelative(0.0800f, 0.5300f, 0.2000f, 0.5800f, "field_3_09|冷凝器出口水温", "冷却水温度 出水"),
        RoiRelative(0.0800f, 0.5800f, 0.2000f, 0.6300f, "field_3_08|冷凝器进口水温", "冷却水温度 返回"),
        // 右中 冷冻水
        RoiRelative(0.7600f, 0.5300f, 0.9200f, 0.5800f, "field_3_02|蒸发器出口水温", "冷冻水温度 出水"),
        RoiRelative(0.7600f, 0.5800f, 0.9200f, 0.6300f, "field_3_01|蒸发器进口水温", "冷冻水温度 返回"),
        // 底部左侧 冷凝器
        RoiRelative(0.2400f, 0.7800f, 0.4200f, 0.8300f, "field_3_12|冷凝器冷凝压力", "冷凝器压力"),
        RoiRelative(0.2400f, 0.8300f, 0.4200f, 0.8800f, "field_3_13|冷凝器冷凝温度", "冷凝器饱和温度"),
        // 底部右侧 蒸发器
        RoiRelative(0.6400f, 0.7800f, 0.9200f, 0.8300f, "field_3_05|蒸发器蒸发压力", "蒸发压力"),
        RoiRelative(0.6400f, 0.8300f, 0.9200f, 0.8800f, "field_3_06|蒸发器蒸发温度", "蒸发器饱和温度")
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
        map["cent_1_0"] = centRel
        map["screw_3_1_0"] = screw3_1_rel
        map["screw_3_2_0"] = screw3_1_rel.map { it.copy(fieldId = shiftId(it.fieldId, 30)) }
        map
    }

    // ============ 公共方法 ============
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
