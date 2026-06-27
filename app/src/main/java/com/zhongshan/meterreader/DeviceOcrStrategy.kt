package com.zhongshan.meterreader

object DeviceOcrStrategy {

    data class HardcodedRoi(
        val xPercent: Float,
        val yPercent: Float,
        val wPercent: Float,
        val hPercent: Float,
        val fieldId: String,   // 格式："底层标识符|目标表单项目名"
        val label: String      // 屏幕显示文字（仅供调试或二次校对对照用）
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
    // 1号机房：特灵螺杆机 (Trane Screw Chiller)
    // =====================================================================

    private val screw1Evaporator = listOf(
        HardcodedRoi(0.8197f, 0.3783f, 0.1422f, 0.0275f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
        HardcodedRoi(0.8363f, 0.4315f, 0.1258f, 0.0280f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
        HardcodedRoi(0.7270f, 0.5378f, 0.2483f, 0.0313f, "field_1_05|蒸发器冷媒压力", "蒸发器制冷剂压力"),
        HardcodedRoi(0.8373f, 0.4845f, 0.1236f, 0.0290f, "field_1_06|蒸发器蒸发温度", "蒸发器制冷剂饱和温度")
    )

    private val screw1Condenser = listOf(
        HardcodedRoi(0.8177f, 0.3775f, 0.1516f, 0.0298f, "field_1_08|冷凝器进口水温", "冷凝器回水温度"),
        HardcodedRoi(0.8177f, 0.4323f, 0.1493f, 0.0265f, "field_1_09|冷凝器出口水温", "冷凝器出水温度"),
        HardcodedRoi(0.7270f, 0.5378f, 0.2472f, 0.0313f, "field_1_12|冷凝器冷媒压力", "冷凝器制冷剂压力"),
        HardcodedRoi(0.8143f, 0.4853f, 0.1520f, 0.0298f, "field_1_13|冷凝器冷凝温度", "冷凝器制冷剂饱和温度")
    )

    private val screw1Compressor = listOf(
        HardcodedRoi(0.7300f, 0.3770f, 0.2486f, 0.0323f, "field_1_14|压缩机油压", "油压"),
        HardcodedRoi(0.8167f, 0.4850f, 0.1467f, 0.0280f, "field_1_15|压缩机排出口温度", "压缩机排出端冷剂温度"),
        HardcodedRoi(0.4853f, 0.5888f, 0.5216f, 0.0305f, "field_1_18|主机负载", "%RLA"),
        HardcodedRoi(0.4643f, 0.6443f, 0.5357f, 0.0283f, "field_1_17|电机电流", "电流L1 L2 L3")
    )

    // =====================================================================
    // 1号机房：约克离心机 (York Centrifugal)
    // =====================================================================

    private val yorkCentrifugal = listOf(
        HardcodedRoi(0.8167f, 0.5385f, 0.0873f, 0.0210f, "field_1_69|蒸发器出口水温", "冷冻水温度 出水"),
        HardcodedRoi(0.8153f, 0.5610f, 0.0887f, 0.0188f, "field_1_68|蒸发器进口水温", "冷冻水温度 返回"),
        HardcodedRoi(0.7570f, 0.8213f, 0.1180f, 0.0195f, "field_1_78|蒸发器冷媒压力", "蒸发器压力"),
        HardcodedRoi(0.7560f, 0.8440f, 0.1177f, 0.0195f, "field_1_70|蒸发器蒸发温度", "蒸发器饱和温度"),
        HardcodedRoi(0.1393f, 0.5368f, 0.0907f, 0.0213f, "field_1_71|冷凝器出口水温", "冷却水温度 出水"),
        HardcodedRoi(0.1403f, 0.5610f, 0.0907f, 0.0205f, "field_1_79|冷凝器进口水温", "冷却水温度 返回"),
        HardcodedRoi(0.2697f, 0.8190f, 0.1187f, 0.0225f, "field_1_77|冷凝器冷媒压力", "冷凝器压力"),
        HardcodedRoi(0.2717f, 0.8440f, 0.1177f, 0.0195f, "field_1_81|冷凝器冷凝温度", "冷凝器饱和温度"),
        HardcodedRoi(0.2717f, 0.4338f, 0.0917f, 0.0203f, "field_1_76|压缩机排气温度", "压缩机出口温度"),
        HardcodedRoi(0.2717f, 0.4565f, 0.0677f, 0.0195f, "field_1_82|压缩机导液开度", "%满载安培"),
        HardcodedRoi(0.7843f, 0.4463f, 0.1217f, 0.0210f, "field_1_74|压缩机油泵压力", "油压"),
        HardcodedRoi(0.7863f, 0.4228f, 0.1187f, 0.0213f, "field_1_75|压缩机油箱温度", "油槽温度")
    )

    // =====================================================================
    // 2号机房：约克螺杆机 (York Screw)
    // =====================================================================

    private val screw3_1_abs = listOf(
        HardcodedRoi(0.8113f, 0.5828f, 0.0897f, 0.0203f, "field_3_02|蒸发器出口水温", "冷冻水温度 出水"),
        HardcodedRoi(0.8113f, 0.6063f, 0.0907f, 0.0203f, "field_3_01|蒸发器进口水温", "冷冻水温度 返回"),
        HardcodedRoi(0.7500f, 0.8423f, 0.1177f, 0.0195f, "field_3_05|蒸发器蒸发压力", "蒸发压力"),
        HardcodedRoi(0.7510f, 0.8655f, 0.1187f, 0.0198f, "field_3_06|蒸发器蒸发温度", "蒸发器饱和温度"),
        HardcodedRoi(0.1417f, 0.5805f, 0.0873f, 0.0188f, "field_3_09|冷凝器出口水温", "冷却水温度 出水"),
        HardcodedRoi(0.1417f, 0.6023f, 0.0863f, 0.0213f, "field_3_08|冷凝器进口水温", "冷却水温度 返回"),
        HardcodedRoi(0.2707f, 0.8383f, 0.1147f, 0.0195f, "field_3_12|冷凝器冷凝压力", "冷凝器压力"),
        HardcodedRoi(0.2717f, 0.8603f, 0.1137f, 0.0203f, "field_3_13|冷凝器冷凝温度", "冷凝器饱和温度"),
        HardcodedRoi(0.8487f, 0.4485f, 0.0917f, 0.0195f, "field_3_16|压缩机排口温度", "压缩机出口温度"),
        HardcodedRoi(0.8500f, 0.5243f, 0.0667f, 0.0203f, "field_3_17|压缩机导液开度", "滑阀位置"),
        HardcodedRoi(0.2790f, 0.4530f, 0.1167f, 0.0180f, "field_3_14|压缩机油压", "油压差"),
        HardcodedRoi(0.2790f, 0.4735f, 0.0853f, 0.0195f, "field_3_15|压缩机油箱温度", "油温")
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
    // 方案 B：相对百分比坐标（维持与绝对坐标强对齐）
    // =====================================================================

    private val screw1EvapRel = screw1Evaporator.map { RoiRelative(0.7522f, 0.2728f, 0.9461f, 0.3232f, it.fieldId, it.label) }
    private val screw1CondRel = screw1Condenser.map { RoiRelative(0.8295f, 0.2713f, 0.9725f, 0.3259f, it.fieldId, it.label) }
    private val screw1CompRel = screw1Compressor.map { RoiRelative(0.7350f, 0.2707f, 0.9697f, 0.3295f, it.fieldId, it.label) }
    private val centRel = yorkCentrifugal.map { RoiRelative(0.8474f, 0.3619f, 0.9419f, 0.4021f, it.fieldId, it.label) }
    private val screw3_1_rel = screw3_1_abs.map { RoiRelative(0.8402f, 0.4051f, 0.9375f, 0.4440f, it.fieldId, it.label) }

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

    // ============ 公共提取方法 ============

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

    // 防闪退的核心底层：确保偏移运算仅作用于数字，保留右侧中文标签不受污染
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
