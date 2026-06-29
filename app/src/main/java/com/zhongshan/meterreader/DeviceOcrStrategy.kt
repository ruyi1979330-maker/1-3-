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
    // 绝对百分比坐标（基于3000×4000黑边原图）
    // fieldId 统一为 "ID|中文名"
    // =====================================================================
    private val screw1Evaporator = listOf(
        HardcodedRoi(0.8197f, 0.3783f, 0.1270f, 0.0275f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
        HardcodedRoi(0.8363f, 0.4315f, 0.1123f, 0.0280f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
        HardcodedRoi(0.7270f, 0.5378f, 0.2217f, 0.0313f, "field_1_05|蒸发器冷媒压力", "蒸发器制冷剂压力"),
        HardcodedRoi(0.8373f, 0.4845f, 0.1103f, 0.0290f, "field_1_06|蒸发器蒸发温度", "蒸发器制冷剂饱和温度")
    )

    private val screw1Condenser = listOf(
        HardcodedRoi(0.8177f, 0.3775f, 0.1353f, 0.0298f, "field_1_08|冷凝器进口水温", "冷凝器回水温度"),
        HardcodedRoi(0.8177f, 0.4323f, 0.1333f, 0.0265f, "field_1_09|冷凝器出口水温", "冷凝器出水温度"),
        HardcodedRoi(0.7270f, 0.5378f, 0.2207f, 0.0313f, "field_1_12|冷凝器冷媒压力", "冷凝器制冷剂压力"),
        HardcodedRoi(0.8143f, 0.4853f, 0.1357f, 0.0298f, "field_1_13|冷凝器冷凝温度", "冷凝器制冷剂饱和温度")
    )

    private val screw1Compressor = listOf(
        HardcodedRoi(0.7300f, 0.3770f, 0.2220f, 0.0323f, "field_1_14|压缩机油压", "油压"),
        HardcodedRoi(0.8167f, 0.4850f, 0.1310f, 0.0280f, "field_1_15|压缩机排出口温度", "压缩机排出端冷剂温度"),
        // 收窄宽度，仅取第一个负载数值
        HardcodedRoi(0.4853f, 0.5888f, 0.1480f, 0.0305f, "field_1_18|主机负载", "%RLA"),
        // 收窄宽度，仅取L1电流数值
        HardcodedRoi(0.4643f, 0.6443f, 0.1523f, 0.0283f, "field_1_17|电机电流", "电流L1 L2 L3")
    )

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
    // 相对百分比坐标（基于纯发光屏幕区域）
    // 严格按量测像素换算，与绝对坐标一一对应
    // =====================================================================
    private val screw1EvapRel = listOf(
        RoiRelative(0.7522f, 0.2728f, 0.9461f, 0.3232f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
        RoiRelative(0.7776f, 0.3705f, 0.9491f, 0.4218f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
        RoiRelative(0.6107f, 0.5654f, 0.9491f, 0.6226f, "field_1_05|蒸发器冷媒压力", "蒸发器制冷剂压力"),
        RoiRelative(0.7791f, 0.4677f, 0.9476f, 0.5209f, "field_1_06|蒸发器蒸发温度", "蒸发器制冷剂饱和温度")
    )

    private val screw1CondRel = listOf(
        RoiRelative(0.8295f, 0.2713f, 0.9728f, 0.3260f, "field_1_08|冷凝器进口水温", "冷凝器回水温度"),
        RoiRelative(0.8295f, 0.3719f, 0.9707f, 0.4207f, "field_1_09|冷凝器出口水温", "冷凝器出水温度"),
        RoiRelative(0.7334f, 0.5659f, 0.9672f, 0.6234f, "field_1_12|冷凝器冷媒压力", "冷凝器制冷剂压力"),
        RoiRelative(0.8259f, 0.4694f, 0.9696f, 0.5241f, "field_1_13|冷凝器冷凝温度", "冷凝器制冷剂饱和温度")
    )

    private val screw1CompRel = listOf(
        RoiRelative(0.7350f, 0.2707f, 0.9697f, 0.3295f, "field_1_14|压缩机油压", "油压"),
        RoiRelative(0.8266f, 0.4676f, 0.9651f, 0.5187f, "field_1_15|压缩机排出口温度", "压缩机排出端冷剂温度"),
        // 收窄右边界，仅包含第一个负载数值
        RoiRelative(0.4764f, 0.6568f, 0.6328f, 0.7124f, "field_1_18|主机负载", "%RLA"),
        // 收窄右边界，仅包含L1电流数值
        RoiRelative(0.4542f, 0.7580f, 0.6152f, 0.8095f, "field_1_17|电机电流", "电流L1 L2 L3")
    )

    private val centRel = listOf(
        RoiRelative(0.8474f, 0.3618f, 0.9419f, 0.4021f, "field_1_69|蒸发器出口水温", "冷冻水温度 出水"),
        RoiRelative(0.8459f, 0.4050f, 0.9419f, 0.4410f, "field_1_68|蒸发器进口水温", "冷冻水温度 返回"),
        RoiRelative(0.7828f, 0.9045f, 0.9105f, 0.9419f, "field_1_78|蒸发器冷媒压力", "蒸发器压力"),
        RoiRelative(0.7817f, 0.9482f, 0.9091f, 0.9856f, "field_1_70|蒸发器蒸发温度", "蒸发器饱和温度"),
        RoiRelative(0.1144f, 0.3585f, 0.2125f, 0.3992f, "field_1_71|冷凝器出口水温", "冷却水温度 出水"),
        RoiRelative(0.1154f, 0.4050f, 0.2136f, 0.4443f, "field_1_79|冷凝器进口水温", "冷却水温度 返回"),
        RoiRelative(0.2554f, 0.9002f, 0.3838f, 0.9434f, "field_1_77|冷凝器冷媒压力", "冷凝器压力"),
        RoiRelative(0.2576f, 0.9482f, 0.3849f, 0.9856f, "field_1_81|冷凝器冷凝温度", "冷凝器饱和温度"),
        RoiRelative(0.2576f, 0.1607f, 0.3568f, 0.1996f, "field_1_76|压缩机排气温度", "压缩机出口温度"),
        RoiRelative(0.2576f, 0.2044f, 0.3308f, 0.2418f, "field_1_82|压缩机导液开度", "%满载安培"),
        RoiRelative(0.8124f, 0.1847f, 0.9441f, 0.2250f, "field_1_74|压缩机油泵压力", "油压"),
        RoiRelative(0.8146f, 0.1396f, 0.9430f, 0.1804f, "field_1_75|压缩机油箱温度", "油槽温度")
    )

    private val screw3_1_rel = listOf(
        RoiRelative(0.8402f, 0.4051f, 0.9375f, 0.4440f, "field_3_02|蒸发器出口水温", "冷冻水温度 出水"),
        RoiRelative(0.8402f, 0.4503f, 0.9385f, 0.4892f, "field_3_01|蒸发器进口水温", "冷冻水温度 返回"),
        RoiRelative(0.7737f, 0.9039f, 0.9013f, 0.9414f, "field_3_05|蒸发器蒸发压力", "蒸发压力"),
        RoiRelative(0.7748f, 0.9486f, 0.9035f, 0.9865f, "field_3_06|蒸发器蒸发温度", "蒸发器饱和温度"),
        RoiRelative(0.1139f, 0.4008f, 0.2086f, 0.4368f, "field_3_09|冷凝器出口水温", "冷却水温度 出水"),
        RoiRelative(0.1139f, 0.4426f, 0.2075f, 0.4834f, "field_3_08|冷凝器进口水温", "冷却水温度 返回"),
        RoiRelative(0.2538f, 0.8962f, 0.3782f, 0.9337f, "field_3_12|冷凝器冷凝压力", "冷凝器压力"),
        RoiRelative(0.2549f, 0.9385f, 0.3782f, 0.9774f, "field_3_13|冷凝器冷凝温度", "冷凝器饱和温度"),
        RoiRelative(0.8807f, 0.1470f, 0.9801f, 0.1845f, "field_3_16|压缩机排口温度", "压缩机出口温度"),
        RoiRelative(0.8821f, 0.2926f, 0.9544f, 0.3316f, "field_3_17|压缩机导液开度", "滑阀位置"),
        RoiRelative(0.2628f, 0.1557f, 0.3894f, 0.1903f, "field_3_14|压缩机油压", "油压差"),
        RoiRelative(0.2628f, 0.1951f, 0.3554f, 0.2326f, "field_3_15|压缩机油箱温度", "油温")
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
