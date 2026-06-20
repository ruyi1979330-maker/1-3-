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

    // =====================================================================
    // 基于用户现场 3000×4000 带黑边原图测量的硬编码坐标（已含2#、3#偏移）
    // =====================================================================
    private val hardcodedConfigs = mapOf(
        // ------- 1号机房 特灵螺杆机 1# (screw_1) -------
        "screw_1_0" to listOf(
            HardcodedRoi(0.8187f, 0.3795f, 0.1290f, 0.0258f, "field_1_01", "1#蒸发器进口 水温"),
            HardcodedRoi(0.8363f, 0.4860f, 0.1103f, 0.0258f, "field_1_02", "1#蒸发器出口 水温"),
            HardcodedRoi(0.7280f, 0.5385f, 0.2187f, 0.0283f, "field_1_05", "1#蒸发器 冷媒压力"),
            HardcodedRoi(0.8363f, 0.4860f, 0.1103f, 0.0258f, "field_1_06", "1#蒸发器 蒸发温度")
        ),
        "screw_1_1" to listOf(
            HardcodedRoi(0.8177f, 0.3783f, 0.1310f, 0.0283f, "field_1_08", "1#冷凝器进口 水温"),
            HardcodedRoi(0.8177f, 0.3783f, 0.1310f, 0.0283f, "field_1_09", "1#冷凝器出口 水温"),
            HardcodedRoi(0.7280f, 0.5385f, 0.2187f, 0.0283f, "field_1_12", "1#冷凝器 冷媒压力"),
            HardcodedRoi(0.8177f, 0.3783f, 0.1310f, 0.0283f, "field_1_13", "1#冷凝器 冷凝温度")
        ),
        "screw_1_2" to listOf(
            HardcodedRoi(0.7310f, 0.3798f, 0.2200f, 0.0280f, "field_1_14", "1#压缩机 油压"),
            HardcodedRoi(0.7310f, 0.3798f, 0.2200f, 0.0280f, "field_1_15", "1#压缩机 排出口温度"),
            HardcodedRoi(0.4633f, 0.6453f, 0.4783f, 0.0298f, "field_1_18", "1#主机负载 (%RLA)"),
            HardcodedRoi(0.4633f, 0.6453f, 0.4783f, 0.0298f, "field_1_17", "1#电机 电流(L1)")
        ),

        // ------- 1号机房 特灵螺杆机 2# (screw_2, 偏移 +30) -------
        "screw_2_0" to listOf(
            HardcodedRoi(0.8187f, 0.3795f, 0.1290f, 0.0258f, "field_1_31", "2#蒸发器进口 水温"),
            HardcodedRoi(0.8363f, 0.4860f, 0.1103f, 0.0258f, "field_1_32", "2#蒸发器出口 水温"),
            HardcodedRoi(0.7280f, 0.5385f, 0.2187f, 0.0283f, "field_1_35", "2#蒸发器 冷媒压力"),
            HardcodedRoi(0.8363f, 0.4860f, 0.1103f, 0.0258f, "field_1_36", "2#蒸发器 蒸发温度")
        ),
        "screw_2_1" to listOf(
            HardcodedRoi(0.8177f, 0.3783f, 0.1310f, 0.0283f, "field_1_38", "2#冷凝器进口 水温"),
            HardcodedRoi(0.8177f, 0.3783f, 0.1310f, 0.0283f, "field_1_39", "2#冷凝器出口 水温"),
            HardcodedRoi(0.7280f, 0.5385f, 0.2187f, 0.0283f, "field_1_42", "2#冷凝器 冷媒压力"),
            HardcodedRoi(0.8177f, 0.3783f, 0.1310f, 0.0283f, "field_1_43", "2#冷凝器 冷凝温度")
        ),
        "screw_2_2" to listOf(
            HardcodedRoi(0.7310f, 0.3798f, 0.2200f, 0.0280f, "field_1_44", "2#压缩机 油压"),
            HardcodedRoi(0.7310f, 0.3798f, 0.2200f, 0.0280f, "field_1_45", "2#压缩机 排出口温度"),
            HardcodedRoi(0.4633f, 0.6453f, 0.4783f, 0.0298f, "field_1_48", "2#主机负载 (%RLA)"),
            HardcodedRoi(0.4633f, 0.6453f, 0.4783f, 0.0298f, "field_1_47", "2#电机 电流(L1)")
        ),

        // ------- 1号机房 特灵螺杆机 3# (screw_3, 偏移 +50) -------
        "screw_3_0" to listOf(
            HardcodedRoi(0.8187f, 0.3795f, 0.1290f, 0.0258f, "field_1_51", "3#蒸发器进口 水温"),
            HardcodedRoi(0.8363f, 0.4860f, 0.1103f, 0.0258f, "field_1_52", "3#蒸发器出口 水温"),
            HardcodedRoi(0.7280f, 0.5385f, 0.2187f, 0.0283f, "field_1_55", "3#蒸发器 冷媒压力"),
            HardcodedRoi(0.8363f, 0.4860f, 0.1103f, 0.0258f, "field_1_56", "3#蒸发器 蒸发温度")
        ),
        "screw_3_1" to listOf(
            HardcodedRoi(0.8177f, 0.3783f, 0.1310f, 0.0283f, "field_1_58", "3#冷凝器进口 水温"),
            HardcodedRoi(0.8177f, 0.3783f, 0.1310f, 0.0283f, "field_1_59", "3#冷凝器出口 水温"),
            HardcodedRoi(0.7280f, 0.5385f, 0.2187f, 0.0283f, "field_1_62", "3#冷凝器 冷媒压力"),
            HardcodedRoi(0.8177f, 0.3783f, 0.1310f, 0.0283f, "field_1_63", "3#冷凝器 冷凝温度")
        ),
        "screw_3_2" to listOf(
            HardcodedRoi(0.7310f, 0.3798f, 0.2200f, 0.0280f, "field_1_64", "3#压缩机 油压"),
            HardcodedRoi(0.7310f, 0.3798f, 0.2200f, 0.0280f, "field_1_65", "3#压缩机 排出口温度"),
            HardcodedRoi(0.4633f, 0.6453f, 0.4783f, 0.0298f, "field_1_68", "3#主机负载 (%RLA)"),
            HardcodedRoi(0.4633f, 0.6453f, 0.4783f, 0.0298f, "field_1_67", "3#电机 电流(L1)")
        ),

        // ------- 1号机房 约克离心机 (cent_1) -------
        "cent_1_0" to listOf(
            HardcodedRoi(0.7560f, 0.8445f, 0.1190f, 0.0180f, "field_1_69", "蒸发器 出水温度"),
            HardcodedRoi(0.7560f, 0.8445f, 0.1190f, 0.0180f, "field_1_68", "蒸发器 进水温度"),
            HardcodedRoi(0.2697f, 0.8438f, 0.1207f, 0.0210f, "field_1_78", "蒸发器 冷媒压力"),
            HardcodedRoi(0.2697f, 0.8438f, 0.1207f, 0.0210f, "field_1_70", "蒸发器 蒸发温度"),
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_1_71", "冷凝器 出水温度"),
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_1_79", "冷凝器 进水温度"),
            HardcodedRoi(0.2687f, 0.8575f, 0.1177f, 0.0235f, "field_1_77", "冷凝器 冷媒压力"),
            HardcodedRoi(0.2687f, 0.8575f, 0.1177f, 0.0235f, "field_1_81", "冷凝器 冷凝温度"),
            HardcodedRoi(0.2717f, 0.4335f, 0.0917f, 0.0228f, "field_1_76", "压缩机 出口温度"),
            HardcodedRoi(0.2717f, 0.4335f, 0.0917f, 0.0228f, "field_1_82", "压缩机 导液开度"),
            HardcodedRoi(0.7833f, 0.4235f, 0.1217f, 0.0225f, "field_1_74", "压缩机 油压"),
            HardcodedRoi(0.7833f, 0.4235f, 0.1217f, 0.0225f, "field_1_75", "压缩机 油温")
        ),

        // ------- 3号机房 约克螺杆机 1# (screw_3_1) -------
        "screw_3_1_0" to listOf(
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_3_02", "1#蒸发器 出水温度"),
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_3_01", "1#蒸发器 进水温度"),
            HardcodedRoi(0.2697f, 0.8438f, 0.1207f, 0.0210f, "field_3_05", "1#蒸发器 蒸发压力"),
            HardcodedRoi(0.2697f, 0.8438f, 0.1207f, 0.0210f, "field_3_06", "1#蒸发器 蒸发温度"),
            HardcodedRoi(0.2687f, 0.8575f, 0.1177f, 0.0235f, "field_3_09", "1#冷凝器 出水温度"),
            HardcodedRoi(0.2687f, 0.8575f, 0.1177f, 0.0235f, "field_3_08", "1#冷凝器 进水温度"),
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_3_12", "1#冷凝器 冷凝压力"),
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_3_13", "1#冷凝器 冷凝温度"),
            HardcodedRoi(0.8487f, 0.4448f, 0.0930f, 0.0235f, "field_3_16", "1#压缩机 排口温度"),
            HardcodedRoi(0.8487f, 0.4448f, 0.0930f, 0.0235f, "field_3_17", "1#压缩机 滑阀位置"),
            HardcodedRoi(0.2760f, 0.4513f, 0.1207f, 0.0228f, "field_3_14", "1#压缩机 油压"),
            HardcodedRoi(0.2760f, 0.4513f, 0.1207f, 0.0228f, "field_3_15", "1#压缩机 油温")
        ),

        // ------- 3号机房 约克螺杆机 2# (screw_3_2, 偏移 +30) -------
        "screw_3_2_0" to listOf(
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_3_32", "2#蒸发器 出水温度"),
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_3_31", "2#蒸发器 进水温度"),
            HardcodedRoi(0.2697f, 0.8438f, 0.1207f, 0.0210f, "field_3_35", "2#蒸发器 蒸发压力"),
            HardcodedRoi(0.2697f, 0.8438f, 0.1207f, 0.0210f, "field_3_36", "2#蒸发器 蒸发温度"),
            HardcodedRoi(0.2687f, 0.8575f, 0.1177f, 0.0235f, "field_3_39", "2#冷凝器 出水温度"),
            HardcodedRoi(0.2687f, 0.8575f, 0.1177f, 0.0235f, "field_3_38", "2#冷凝器 进水温度"),
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_3_42", "2#冷凝器 冷凝压力"),
            HardcodedRoi(0.7487f, 0.8645f, 0.1210f, 0.0195f, "field_3_43", "2#冷凝器 冷凝温度"),
            HardcodedRoi(0.8487f, 0.4448f, 0.0930f, 0.0235f, "field_3_46", "2#压缩机 排口温度"),
            HardcodedRoi(0.8487f, 0.4448f, 0.0930f, 0.0235f, "field_3_47", "2#压缩机 滑阀位置"),
            HardcodedRoi(0.2760f, 0.4513f, 0.1207f, 0.0228f, "field_3_44", "2#压缩机 油压"),
            HardcodedRoi(0.2760f, 0.4513f, 0.1207f, 0.0228f, "field_3_45", "2#压缩机 油温")
        )
    )

    fun getHardcodedRois(machineId: String, screenIndex: Int): List<HardcodedRoi> {
        return hardcodedConfigs["${machineId}_${screenIndex}"] ?: emptyList()
    }

    fun totalScreens(machineId: String): Int = when {
        machineId.startsWith("screw_") && !machineId.startsWith("screw_3") -> 3
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

    fun getFieldList(machineId: String, screenIndex: Int): Pair<List<String>, List<String>> {
        return Pair(emptyList(), emptyList())
    }
}
