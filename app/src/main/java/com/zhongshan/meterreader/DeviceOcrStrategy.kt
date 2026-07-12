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
    // 铁律：相邻行 Y 范围绝不重叠，预留至少 2% 的安全间隙
    // =====================================================================
    private val screw1Evaporator = listOf(
        // 行1：Y中心约 1120 -> 0.280 (范围 0.230~0.330)
        HardcodedRoi(0.620f, 0.230f, 0.330f, 0.100f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
        // 行2：Y中心约 1600 -> 0.400 (范围 0.350~0.450，与行1间隔2%)
        HardcodedRoi(0.620f, 0.350f, 0.330f, 0.100f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
        // 行3：Y中心约 2080 -> 0.520 (范围 0.470~0.570，与行2间隔2%)
        HardcodedRoi(0.620f, 0.470f, 0.330f, 0.100f, "field_1_06|蒸发器饱和温度", "蒸发温度"),
        // 行4：Y中心约 2580 -> 0.645 (范围 0.595~0.695，与行3间隔2.5%)
        HardcodedRoi(0.620f, 0.595f, 0.330f, 0.100f, "field_1_10|蒸发器冷媒压力", "低压压力")
    )

    private val screw1Condenser = listOf(
        HardcodedRoi(0.620f, 0.230f, 0.330f, 0.100f, "field_1_03|冷凝器进口水温", "冷凝器进水温度"),
        HardcodedRoi(0.620f, 0.350f, 0.330f, 0.100f, "field_1_04|冷凝器出口水温", "冷凝器出水温度"),
        HardcodedRoi(0.620f, 0.470f, 0.330f, 0.100f, "field_1_07|冷凝器饱和温度", "冷凝温度"),
        HardcodedRoi(0.620f, 0.595f, 0.330f, 0.100f, "field_1_11|冷凝器冷媒压力", "高压压力")
    )

    private val screw1Compressor = listOf(
        // 油压在左侧
        HardcodedRoi(0.150f, 0.330f, 0.300f, 0.100f, "field_1_14|油压差", "油压差"),
        // 排气温度在右侧偏中
        HardcodedRoi(0.650f, 0.450f, 0.300f, 0.100f, "field_1_09|排气温度", "排气温度"),
        // 负载在左下
        HardcodedRoi(0.150f, 0.570f, 0.300f, 0.100f, "field_1_05|机组负荷", "负载"),
        // 运行电流在右下
        HardcodedRoi(0.650f, 0.570f, 0.300f, 0.100f, "field_1_15|运行电流", "线电流")
    )

    // =====================================================================
    // 相对百分比坐标（基于相册纯屏幕区域裁剪图使用）
    // =====================================================================
    private val screw1EvaporatorRel = listOf(
        RoiRelative(0.60f, 0.10f, 0.95f, 0.25f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
        RoiRelative(0.60f, 0.30f, 0.95f, 0.45f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
        RoiRelative(0.60f, 0.50f, 0.95f, 0.65f, "field_1_06|蒸发器饱和温度", "蒸发温度"),
        RoiRelative(0.60f, 0.70f, 0.95f, 0.85f, "field_1_10|蒸发器冷媒压力", "低压压力")
    )

    private val screw1CondenserRel = listOf(
        RoiRelative(0.60f, 0.10f, 0.95f, 0.25f, "field_1_03|冷凝器进口水温", "冷凝器进水温度"),
        RoiRelative(0.60f, 0.30f, 0.95f, 0.45f, "field_1_04|冷凝器出口水温", "冷凝器出水温度"),
        RoiRelative(0.60f, 0.50f, 0.95f, 0.65f, "field_1_07|冷凝器饱和温度", "冷凝温度"),
        RoiRelative(0.60f, 0.70f, 0.95f, 0.85f, "field_1_11|冷凝器冷媒压力", "高压压力")
    )

    private val screw1CompressorRel = listOf(
        RoiRelative(0.10f, 0.25f, 0.45f, 0.40f, "field_1_14|油压差", "油压差"),
        RoiRelative(0.55f, 0.45f, 0.90f, 0.60f, "field_1_09|排气温度", "排气温度"),
        RoiRelative(0.10f, 0.65f, 0.45f, 0.80f, "field_1_05|机组负荷", "负载"),
        RoiRelative(0.55f, 0.65f, 0.90f, 0.80f, "field_1_15|运行电流", "线电流")
    )

    // =====================================================================
    // 映射表
    // =====================================================================
    private val absoluteConfigs = mapOf(
        "screw_1_0" to screw1Evaporator,
        "screw_1_1" to screw1Condenser,
        "screw_1_2" to screw1Compressor,
        "screw_2_0" to screw1Evaporator.map { it.copy(fieldId = shiftId(it.fieldId, 20)) },
        "screw_2_1" to screw1Condenser.map { it.copy(fieldId = shiftId(it.fieldId, 20)) },
        "screw_2_2" to screw1Compressor.map { it.copy(fieldId = shiftId(it.fieldId, 20)) },
        "screw_3_0" to screw1Evaporator.map { it.copy(fieldId = shiftId(it.fieldId, 50)) },
        "screw_3_1" to screw1Condenser.map { it.copy(fieldId = shiftId(it.fieldId, 50)) },
        "screw_3_2" to screw1Compressor.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
    )

    private val relativeConfigs = mapOf(
        "screw_1_0" to screw1EvaporatorRel,
        "screw_1_1" to screw1CondenserRel,
        "screw_1_2" to screw1CompressorRel,
        "screw_2_0" to screw1EvaporatorRel.map { it.copy(fieldId = shiftId(it.fieldId, 20)) },
        "screw_2_1" to screw1CondenserRel.map { it.copy(fieldId = shiftId(it.fieldId, 20)) },
        "screw_2_2" to screw1CompressorRel.map { it.copy(fieldId = shiftId(it.fieldId, 20)) },
        "screw_3_0" to screw1EvaporatorRel.map { it.copy(fieldId = shiftId(it.fieldId, 50)) },
        "screw_3_1" to screw1CondenserRel.map { it.copy(fieldId = shiftId(it.fieldId, 50)) },
        "screw_3_2" to screw1CompressorRel.map { it.copy(fieldId = shiftId(it.fieldId, 50)) }
    )

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
        if (parts.size < 2) return compoundId
        val rawId = parts[0]
        val label = parts[1]
        val match = Regex("""field_1_(\d{2})""").find(rawId)
        if (match != null) {
            val num = match.groupValues[1].toInt() + offset
            val prefix = if (offset == 20) "2" else if (offset == 50) "3" else "1"
            return "field_${prefix}_${num.toString().padStart(2, '0')}|$label"
        }
        return compoundId
    }
}
