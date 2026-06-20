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
    // 基于用户现场 3000×4000 带黑边原图测量的硬编码坐标
    // 已针对特灵点阵屏加宽了裁剪框，防止数字被切断。
    // =====================================================================
    private val hardcodedConfigs = mapOf(
        // ------- 1号机房 特灵螺杆机 1# (screw_1) -------
        "screw_1_0" to listOf(
            // 蒸发器进水温 10.0 C (适当加宽)
            HardcodedRoi(0.8160f, 0.3780f, 0.1340f, 0.0280f, "field_1_01", "1#蒸发器进口 水温"),
            // 蒸发器出水温 7.6 C (【重要修复】：原框太窄导致识别成5，已左右各扩大50像素)
            HardcodedRoi(0.8000f, 0.2050f, 0.1260f, 0.0320f, "field_1_02", "1#蒸发器出口 水温"),
            // 蒸发器冷媒压力 256.1 kPag (用你测的中心锚点)
            HardcodedRoi(0.6950f, 0.3100f, 0.2400f, 0.0350f, "field_1_05", "1#蒸发器 冷媒压力"),
            // 蒸发器蒸发温度 5.7 C (【重要修复】：原框太窄，已加宽)
            HardcodedRoi(0.8020f, 0.2575f, 0.1180f, 0.0280f, "field_1_06", "1#蒸发器 蒸发温度")
        ),
        "screw_1_1" to listOf(
            HardcodedRoi(0.8150f, 0.3765f, 0.1360f, 0.0300f, "field_1_08", "1#冷凝器进口 水温"),
            HardcodedRoi(0.8150f, 0.3765f, 0.1360f, 0.0300f, "field_1_09", "1#冷凝器出口 水温"),
            HardcodedRoi(0.6950f, 0.5360f, 0.2400f, 0.0300f, "field_1_12", "1#冷凝器 冷媒压力"),
            HardcodedRoi(0.8150f, 0.3765f, 0.1360f, 0.0300f, "field_1_13", "1#冷凝器 冷凝温度")
        ),
        "screw_1_2" to listOf(
            HardcodedRoi(0.7280f, 0.3780f, 0.2240f, 0.0300f, "field_1_14", "1#压缩机 油压"),
            HardcodedRoi(0.7280f, 0.3780f, 0.2240f, 0.0300f, "field_1_15", "1#压缩机 排出口温度"),
            HardcodedRoi(0.4600f, 0.6430f, 0.4840f, 0.0320f, "field_1_18", "1#主机负载 (%RLA)"),
            HardcodedRoi(0.4600f, 0.6430f, 0.4840f, 0.0320f, "field_1_17", "1#电机 电流(L1)")
        ),

        // ------- 1号机房 特灵螺杆机 2# (screw_2) -------
        "screw_2_0" to listOf(
            HardcodedRoi(0.8160f, 0.3780f, 0.1340f, 0.0280f, "field_1_31", "2#蒸发器进口 水温"),
            HardcodedRoi(0.8000f, 0.2050f, 0.1260f, 0.0320f, "field_1_32", "2#蒸发器出口 水温"),
            HardcodedRoi(0.6950f, 0.3100f, 0.2400f, 0.0350f, "field_1_35", "2#蒸发器 冷媒压力"),
            HardcodedRoi(0.8020f, 0.2575f, 0.1180f, 0.0280f, "field_1_36", "2#蒸发器 蒸发温度")
        ),
        "screw_2_1" to listOf(
            HardcodedRoi(0.8150f, 0.3765f, 0.1360f, 0.0300f, "field_1_38", "2#冷凝器进口 水温"),
            HardcodedRoi(0.8150f, 0.3765f, 0.1360f, 0.0300f, "field_1_39", "2#冷凝器出口 水温"),
            HardcodedRoi(0.6950f, 0.5360f, 0.2400f, 0.0300f, "field_1_42", "2#冷凝器 冷媒压力"),
            HardcodedRoi(0.8150f, 0.3765f, 0.1360f, 0.0300f, "field_1_43", "2#冷凝器 冷凝温度")
        ),
        "screw_2_2" to listOf(
            HardcodedRoi(0.7280f, 0.3780f, 0.2240f, 0.0300f, "field_1_44", "2#压缩机 油压"),
            HardcodedRoi(0.7280f, 0.3780f, 0.2240f, 0.0300f, "field_1_45", "2#压缩机 排出口温度"),
            HardcodedRoi(0.4600f, 0.6430f, 0.4840f, 0.0320f, "field_1_48", "2#主机负载 (%RLA)"),
            HardcodedRoi(0.4600f, 0.6430f, 0.4840f, 0.0320f, "field_1_47", "2#电机 电流(L1)")
        ),

        // ------- 1号机房 特灵螺杆机 3# (screw_3) -------
        "screw_3_0" to listOf(
            HardcodedRoi(0.8160f, 0.3780f, 0.1340f, 0.0280f, "field_1_51", "3#蒸发器进口 水温"),
            HardcodedRoi(0.8000f, 0.2050f, 0.1260f, 0.0320f, "field_1_52", "3#蒸发器出口 水温"),
            HardcodedRoi(0.6950f, 0.3100f, 0.2400f, 0.0350f, "field_1_55", "3#蒸发器 冷媒压力"),
            HardcodedRoi(0.8020f, 0.2575f, 0.1180f, 0.0280f, "field_1_56", "3#蒸发器 蒸发温度")
        ),
        "screw_3_1" to listOf(
            HardcodedRoi(0.8150f, 0.3765f, 0.1360f, 0.0300f, "field_1_58", "3#冷凝器进口 水温"),
            HardcodedRoi(0.8150f, 0.3765f, 0.1360f, 0.0300f, "field_1_59", "3#冷凝器出口 水温"),
            HardcodedRoi(0.6950f, 0.5360f, 0.2400f, 0.0300f, "field_1_62", "3#冷凝器 冷媒压力"),
            HardcodedRoi(0.8150f, 0.3765f, 0.1360f, 0.0300f, "field_1_63", "3#冷凝器 冷凝温度")
        ),
        "screw_3_2" to listOf(
            HardcodedRoi(0.7280f, 0.3780f, 0.2240f, 0.0300f, "field_1_64", "3#压缩机 油压"),
            HardcodedRoi(0.7280f, 0.3780f, 0.2240f, 0.0300f, "field_1_65", "3#压缩机 排出口温度"),
            HardcodedRoi(0.4600f, 0.6430f, 0.4840f, 0.0320f, "field_1_68", "3#主机负载 (%RLA)"),
            HardcodedRoi(0.4600f, 0.6430f, 0.4840f, 0.0320f, "field_1_67", "3#电机 电流(L1)")
        ),

        // ------- 1号机房 约克离心机 (cent_1) -------
        "cent_1_0" to listOf(
            HardcodedRoi(0.7540f, 0.8430f, 0.1240f, 0.0210f, "field_1_69", "蒸发器 出水温度"),
            HardcodedRoi(0.7540f, 0.8430f, 0.1240f, 0.0210f, "field_1_68", "蒸发器 进水温度"),
            HardcodedRoi(0.2670f, 0.8420f, 0.1240f, 0.0240f, "field_1_78", "蒸发器 冷媒压力"),
            HardcodedRoi(0.2670f, 0.8420f, 0.1240f, 0.0240f, "field_1_70", "蒸发器 蒸发温度"),
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_1_71", "冷凝器 出水温度"),
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_1_79", "冷凝器 进水温度"),
            HardcodedRoi(0.2660f, 0.8560f, 0.1220f, 0.0260f, "field_1_77", "冷凝器 冷媒压力"),
            HardcodedRoi(0.2660f, 0.8560f, 0.1220f, 0.0260f, "field_1_81", "冷凝器 冷凝温度"),
            HardcodedRoi(0.2690f, 0.4320f, 0.0960f, 0.0250f, "field_1_76", "压缩机 出口温度"),
            HardcodedRoi(0.2690f, 0.4320f, 0.0960f, 0.0250f, "field_1_82", "压缩机 导液开度"),
            HardcodedRoi(0.7810f, 0.4220f, 0.1260f, 0.0250f, "field_1_74", "压缩机 油压"),
            HardcodedRoi(0.7810f, 0.4220f, 0.1260f, 0.0250f, "field_1_75", "压缩机 油温")
        ),

        // ------- 3号机房 约克螺杆机 1# (screw_3_1) -------
        "screw_3_1_0" to listOf(
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_3_02", "1#蒸发器 出水温度"),
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_3_01", "1#蒸发器 进水温度"),
            HardcodedRoi(0.2670f, 0.8420f, 0.1240f, 0.0240f, "field_3_05", "1#蒸发器 蒸发压力"),
            HardcodedRoi(0.2670f, 0.8420f, 0.1240f, 0.0240f, "field_3_06", "1#蒸发器 蒸发温度"),
            HardcodedRoi(0.2660f, 0.8560f, 0.1220f, 0.0260f, "field_3_09", "1#冷凝器 出水温度"),
            HardcodedRoi(0.2660f, 0.8560f, 0.1220f, 0.0260f, "field_3_08", "1#冷凝器 进水温度"),
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_3_12", "1#冷凝器 冷凝压力"),
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_3_13", "1#冷凝器 冷凝温度"),
            HardcodedRoi(0.8460f, 0.4430f, 0.0980f, 0.0260f, "field_3_16", "1#压缩机 排口温度"),
            HardcodedRoi(0.8460f, 0.4430f, 0.0980f, 0.0260f, "field_3_17", "1#压缩机 滑阀位置"),
            HardcodedRoi(0.2730f, 0.4500f, 0.1260f, 0.0250f, "field_3_14", "1#压缩机 油压"),
            HardcodedRoi(0.2730f, 0.4500f, 0.1260f, 0.0250f, "field_3_15", "1#压缩机 油温")
        ),

        // ------- 3号机房 约克螺杆机 2# (screw_3_2) -------
        "screw_3_2_0" to listOf(
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_3_32", "2#蒸发器 出水温度"),
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_3_31", "2#蒸发器 进水温度"),
            HardcodedRoi(0.2670f, 0.8420f, 0.1240f, 0.0240f, "field_3_35", "2#蒸发器 蒸发压力"),
            HardcodedRoi(0.2670f, 0.8420f, 0.1240f, 0.0240f, "field_3_36", "2#蒸发器 蒸发温度"),
            HardcodedRoi(0.2660f, 0.8560f, 0.1220f, 0.0260f, "field_3_39", "2#冷凝器 出水温度"),
            HardcodedRoi(0.2660f, 0.8560f, 0.1220f, 0.0260f, "field_3_38", "2#冷凝器 进水温度"),
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_3_42", "2#冷凝器 冷凝压力"),
            HardcodedRoi(0.7460f, 0.8630f, 0.1260f, 0.0220f, "field_3_43", "2#冷凝器 冷凝温度"),
            HardcodedRoi(0.8460f, 0.4430f, 0.0980f, 0.0260f, "field_3_46", "2#压缩机 排口温度"),
            HardcodedRoi(0.8460f, 0.4430f, 0.0980f, 0.0260f, "field_3_47", "2#压缩机 滑阀位置"),
            HardcodedRoi(0.2730f, 0.4500f, 0.1260f, 0.0250f, "field_3_44", "2#压缩机 油压"),
            HardcodedRoi(0.2730f, 0.4500f, 0.1260f, 0.0250f, "field_3_45", "2#压缩机 油温")
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
