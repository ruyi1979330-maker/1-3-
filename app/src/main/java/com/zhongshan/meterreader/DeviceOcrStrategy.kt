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

    // =====================================================================
    // 【本次新增 - 约克机组】单屏全数据模板
    // 业务规则：约克机仅拍 1 屏，totalScreens = 1。
    // 坐标采用全屏大框 (0,0,1,1)：约克解析引擎使用全文特征词匹配 +
    // 屏幕左右中线消歧（见 OCRFacade.extractYorkDataFromBitmap），
    // 不依赖坐标裁剪，此处 ROI 仅用于向 TraneOcrStateManager 提供 requiredFields。
    // fieldId 与约克 OCR 输出 key（semanticKey|中文标签）严格对齐。
    // =====================================================================
    private val yorkRoiFields = listOf(
        "evapRefPressure"    to "蒸发器压力",
        "evapTemp"           to "蒸发器饱和温度",
        "evapInTemp"         to "冷冻水温度返回",
        "evapOutTemp"        to "冷冻水温度出水",
        "condRefPressure"    to "冷凝器压力",
        "condTemp"           to "冷凝器饱和温度",
        "condInTemp"         to "冷却水温度返回",
        "condOutTemp"        to "冷却水温度出水",
        "compOilPressure"    to "油压差",
        "compOilTemp"        to "油温",
        "compDischargeTemp"  to "压缩机出口温度",
        "compGuideOpening"   to "滑阀位置",
        "motorCurrent"       to "满载安培"
    )

    private val yorkHardcoded: List<HardcodedRoi> =
        yorkRoiFields.map { (key, label) ->
            HardcodedRoi(0f, 0f, 1f, 1f, "$key|$label", label)
        }

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
        // 【本次新增】约克 1#/2# 单屏
        map["york_1_0"] = yorkHardcoded
        map["york_2_0"] = yorkHardcoded
        map
    }

    // =====================================================================
    // 相对百分比坐标（基于纯发光屏幕区域，图库裁切模式使用）
    // 修复：根据真实数据缩小 Y 轴范围，杜绝行间重叠
    // =====================================================================
    private val screw1EvapRel = listOf(
        RoiRelative(0.62f, 0.23f, 0.97f, 0.33f, "field_1_01|蒸发器进口水温", "蒸发器进水温度"),
        RoiRelative(0.62f, 0.35f, 0.97f, 0.45f, "field_1_02|蒸发器出口水温", "蒸发器出水温度"),
        RoiRelative(0.62f, 0.47f, 0.97f, 0.57f, "field_1_06|蒸发器蒸发温度", "蒸发器制冷剂饱和温度"),
        RoiRelative(0.55f, 0.59f, 0.97f, 0.70f, "field_1_05|蒸发器冷媒压力", "蒸发器制冷剂压力")
    )

    private val screw1CondRel = listOf(
        RoiRelative(0.62f, 0.23f, 0.97f, 0.33f, "field_1_08|冷凝器进口水温", "冷凝器回水温度"),
        RoiRelative(0.62f, 0.35f, 0.97f, 0.45f, "field_1_09|冷凝器出口水温", "冷凝器出水温度"),
        RoiRelative(0.62f, 0.47f, 0.97f, 0.57f, "field_1_13|冷凝器冷凝温度", "冷凝器制冷剂饱和温度"),
        RoiRelative(0.55f, 0.59f, 0.97f, 0.70f, "field_1_12|冷凝器冷媒压力", "冷凝器制冷剂压力")
    )

    private val screw1CompRel = listOf(
        // 油压 656.3：位于屏幕左侧中上部
        RoiRelative(0.05f, 0.23f, 0.40f, 0.35f, "field_1_14|压缩机油压", "油压"),
        // 排出口温度 48.3：位于屏幕右侧中部
        RoiRelative(0.55f, 0.47f, 0.97f, 0.58f, "field_1_15|压缩机排出口温度", "压缩机排出端冷剂温度"),
        // 负载 50.5%：位于屏幕左下方
        RoiRelative(0.35f, 0.63f, 0.70f, 0.76f, "field_1_18|主机负载", "%RLA"),
        // 电流 232.0 A：位于屏幕底部
        RoiRelative(0.35f, 0.78f, 0.75f, 0.91f, "field_1_17|电机电流", "电流L1 L2 L3")
    )

    // 【本次新增】约克机组相对坐标（全屏大框，仅作 requiredFields 占位，不参与裁剪）
    private val yorkRelative: List<RoiRelative> =
        yorkRoiFields.map { (key, label) ->
            RoiRelative(0f, 0f, 1f, 1f, "$key|$label", label)
        }

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
        // 【本次新增】约克 1#/2# 单屏
        map["york_1_0"] = yorkRelative
        map["york_2_0"] = yorkRelative
        map
    }

    fun getHardcodedRois(machineId: String, screenIndex: Int): List<HardcodedRoi> {
        return absoluteConfigs["${machineId}_${screenIndex}"] ?: emptyList()
    }

    fun getRelativeRois(machineId: String, screenIndex: Int): List<RoiRelative> {
        return relativeConfigs["${machineId}_${screenIndex}"] ?: emptyList()
    }

    // 【本次新增】york_1 / york_2 显式返回 1（单屏识别）
    fun totalScreens(machineId: String): Int = when (machineId) {
        "screw_1", "screw_2", "screw_3" -> 3
        "york_1", "york_2" -> 1
        else -> 1
    }

    // 【本次新增】york 开头返回 "约克机组全数据"
    fun screenName(machineId: String, screenIndex: Int): String = when {
        machineId in listOf("screw_1", "screw_2", "screw_3") -> when (screenIndex) {
            0 -> "蒸发器"; 1 -> "冷凝器"; 2 -> "压缩机与电流"; else -> "完成"
        }
        machineId.startsWith("york") -> "约克机组全数据"
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
