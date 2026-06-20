package com.zhongshan.meterreader

object DeviceOcrStrategy {

    data class HardcodedRoi(
        val xPercent: Float, // 左上角X相对宽度百分比 (0.0~1.0)
        val yPercent: Float, // 左上角Y相对高度百分比 (0.0~1.0)
        val wPercent: Float, // 框的宽度百分比
        val hPercent: Float, // 框的高度百分比
        val fieldId: String,
        val label: String
    )

    // =====================================================================
    // 用户电脑定标坐标硬编码（已包含 2# 和 3# 机器的字段偏移）
    // =====================================================================
    private val hardcodedConfigs = mapOf(
        // ------- 1号机房 特灵螺杆机 1# (screw_1) -------
        "screw_1_0" to listOf(
            HardcodedRoi(0.8243f, 0.2743f, 0.1402f, 0.0554f, "field_1_01", "1#蒸发器进口 水温"),
            HardcodedRoi(0.8406f, 0.3734f, 0.1228f, 0.0518f, "field_1_02", "1#蒸发器出口 水温"),
            HardcodedRoi(0.7293f, 0.5617f, 0.2436f, 0.0595f, "field_1_05", "1#蒸发器 冷媒压力"),
            HardcodedRoi(0.8406f, 0.4676f, 0.1169f, 0.0464f, "field_1_06", "1#蒸发器 蒸发温度")
        ),
        "screw_1_1" to listOf(
            HardcodedRoi(0.8238f, 0.2755f, 0.1370f, 0.0488f, "field_1_08", "1#冷凝器进口 水温"),
            HardcodedRoi(0.8238f, 0.3714f, 0.1363f, 0.0488f, "field_1_09", "1#冷凝器出口 水温"),
            HardcodedRoi(0.7310f, 0.5614f, 0.2291f, 0.0565f, "field_1_12", "1#冷凝器 冷媒压力"),
            HardcodedRoi(0.8200f, 0.4677f, 0.1401f, 0.0475f, "field_1_13", "1#冷凝器 冷凝温度")
        ),
        "screw_1_2" to listOf(
            HardcodedRoi(0.7304f, 0.2755f, 0.2273f, 0.0551f, "field_1_14", "1#压缩机 油压"),
            HardcodedRoi(0.8202f, 0.4691f, 0.1355f, 0.0475f, "field_1_15", "1#压缩机 排出口温度"),
            HardcodedRoi(0.4754f, 0.6564f, 0.4806f, 0.0524f, "field_1_18", "1#主机负载 (%RLA)"),
            HardcodedRoi(0.4532f, 0.7522f, 0.4993f, 0.0529f, "field_1_17", "1#电机 电流(L1)")
        ),

        // ------- 1号机房 特灵螺杆机 2# (screw_2, 字段偏移 +30) -------
        "screw_2_0" to listOf(
            HardcodedRoi(0.8243f, 0.2743f, 0.1402f, 0.0554f, "field_1_31", "2#蒸发器进口 水温"),
            HardcodedRoi(0.8406f, 0.3734f, 0.1228f, 0.0518f, "field_1_32", "2#蒸发器出口 水温"),
            HardcodedRoi(0.7293f, 0.5617f, 0.2436f, 0.0595f, "field_1_35", "2#蒸发器 冷媒压力"),
            HardcodedRoi(0.8406f, 0.4676f, 0.1169f, 0.0464f, "field_1_36", "2#蒸发器 蒸发温度")
        ),
        "screw_2_1" to listOf(
            HardcodedRoi(0.8238f, 0.2755f, 0.1370f, 0.0488f, "field_1_38", "2#冷凝器进口 水温"),
            HardcodedRoi(0.8238f, 0.3714f, 0.1363f, 0.0488f, "field_1_39", "2#冷凝器出口 水温"),
            HardcodedRoi(0.7310f, 0.5614f, 0.2291f, 0.0565f, "field_1_42", "2#冷凝器 冷媒压力"),
            HardcodedRoi(0.8200f, 0.4677f, 0.1401f, 0.0475f, "field_1_43", "2#冷凝器 冷凝温度")
        ),
        "screw_2_2" to listOf(
            HardcodedRoi(0.7304f, 0.2755f, 0.2273f, 0.0551f, "field_1_44", "2#压缩机 油压"),
            HardcodedRoi(0.8202f, 0.4691f, 0.1355f, 0.0475f, "field_1_45", "2#压缩机 排出口温度"),
            HardcodedRoi(0.4754f, 0.6564f, 0.4806f, 0.0524f, "field_1_48", "2#主机负载 (%RLA)"),
            HardcodedRoi(0.4532f, 0.7522f, 0.4993f, 0.0529f, "field_1_47", "2#电机 电流(L1)")
        ),

        // ------- 1号机房 特灵螺杆机 3# (screw_3, 字段偏移 +50) -------
        "screw_3_0" to listOf(
            HardcodedRoi(0.8243f, 0.2743f, 0.1402f, 0.0554f, "field_1_51", "3#蒸发器进口 水温"),
            HardcodedRoi(0.8406f, 0.3734f, 0.1228f, 0.0518f, "field_1_52", "3#蒸发器出口 水温"),
            HardcodedRoi(0.7293f, 0.5617f, 0.2436f, 0.0595f, "field_1_55", "3#蒸发器 冷媒压力"),
            HardcodedRoi(0.8406f, 0.4676f, 0.1169f, 0.0464f, "field_1_56", "3#蒸发器 蒸发温度")
        ),
        "screw_3_1" to listOf(
            HardcodedRoi(0.8238f, 0.2755f, 0.1370f, 0.0488f, "field_1_58", "3#冷凝器进口 水温"),
            HardcodedRoi(0.8238f, 0.3714f, 0.1363f, 0.0488f, "field_1_59", "3#冷凝器出口 水温"),
            HardcodedRoi(0.7310f, 0.5614f, 0.2291f, 0.0565f, "field_1_62", "3#冷凝器 冷媒压力"),
            HardcodedRoi(0.8200f, 0.4677f, 0.1401f, 0.0475f, "field_1_63", "3#冷凝器 冷凝温度")
        ),
        "screw_3_2" to listOf(
            HardcodedRoi(0.7304f, 0.2755f, 0.2273f, 0.0551f, "field_1_64", "3#压缩机 油压"),
            HardcodedRoi(0.8202f, 0.4691f, 0.1355f, 0.0475f, "field_1_65", "3#压缩机 排出口温度"),
            HardcodedRoi(0.4754f, 0.6564f, 0.4806f, 0.0524f, "field_1_68", "3#主机负载 (%RLA)"),
            HardcodedRoi(0.4532f, 0.7522f, 0.4993f, 0.0529f, "field_1_67", "3#电机 电流(L1)")
        ),

        // ------- 1号机房 约克离心机 (cent_1) -------
        "cent_1_0" to listOf(
            HardcodedRoi(0.7876f, 0.2953f, 0.1585f, 0.0426f, "field_1_69", "蒸发器 出水温度"),
            HardcodedRoi(0.7897f, 0.3396f, 0.1564f, 0.0453f, "field_1_68", "蒸发器 进水温度"),
            HardcodedRoi(0.6492f, 0.8929f, 0.2620f, 0.0421f, "field_1_78", "蒸发器 冷媒压力"),
            HardcodedRoi(0.5985f, 0.9382f, 0.3138f, 0.0474f, "field_1_70", "蒸发器 蒸发温度"),
            HardcodedRoi(0.0611f, 0.2879f, 0.1578f, 0.0485f, "field_1_71", "冷凝器 出水温度"),
            HardcodedRoi(0.0593f, 0.3332f, 0.1596f, 0.0549f, "field_1_79", "冷凝器 进水温度"),
            HardcodedRoi(0.1280f, 0.8881f, 0.2631f, 0.0581f, "field_1_77", "冷凝器 冷媒压力"),
            HardcodedRoi(0.0780f, 0.9350f, 0.3109f, 0.0517f, "field_1_81", "冷凝器 冷凝温度"),
            HardcodedRoi(0.0773f, 0.0666f, 0.2854f, 0.0517f, "field_1_76", "压缩机 出口温度"),
            HardcodedRoi(0.1269f, 0.1119f, 0.2114f, 0.0501f, "field_1_82", "压缩机 导液开度"),
            HardcodedRoi(0.7560f, 0.0949f, 0.1891f, 0.0469f, "field_1_74", "压缩机 油压"),
            HardcodedRoi(0.7063f, 0.0448f, 0.2398f, 0.0517f, "field_1_75", "压缩机 油温")
        ),

        // ------- 3号机房 约克螺杆机 1# (screw_3_1) -------
        "screw_3_1_0" to listOf(
            HardcodedRoi(0.7761f, 0.3347f, 0.1574f, 0.0497f, "field_3_02", "1#蒸发器 出水温度"),
            HardcodedRoi(0.7761f, 0.3845f, 0.1541f, 0.0465f, "field_3_01", "1#蒸发器 进水温度"),
            HardcodedRoi(0.6369f, 0.8821f, 0.2588f, 0.0497f, "field_3_05", "1#蒸发器 蒸发压力"),
            HardcodedRoi(0.5899f, 0.9259f, 0.3058f, 0.0513f, "field_3_06", "1#蒸发器 蒸发温度"),
            HardcodedRoi(0.0602f, 0.3284f, 0.1538f, 0.0465f, "field_3_09", "1#冷凝器 出水温度"),
            HardcodedRoi(0.0623f, 0.3765f, 0.1517f, 0.0439f, "field_3_08", "1#冷凝器 进水温度"),
            HardcodedRoi(0.1260f, 0.8683f, 0.2524f, 0.0513f, "field_3_12", "1#冷凝器 冷凝压力"),
            HardcodedRoi(0.0790f, 0.9133f, 0.3015f, 0.0545f, "field_3_13", "1#冷凝器 冷凝温度"),
            HardcodedRoi(0.6903f, 0.0471f, 0.2827f, 0.0497f, "field_3_16", "1#压缩机 排口温度"),
            HardcodedRoi(0.7668f, 0.2089f, 0.1823f, 0.0465f, "field_3_17", "1#压缩机 滑阀位置"),
            HardcodedRoi(0.1816f, 0.0550f, 0.2083f, 0.0481f, "field_3_14", "1#压缩机 油压"),
            HardcodedRoi(0.2065f, 0.1031f, 0.1552f, 0.0481f, "field_3_15", "1#压缩机 油温")
        ),

        // ------- 3号机房 约克螺杆机 2# (screw_3_2, 字段偏移 +30) -------
        "screw_3_2_0" to listOf(
            HardcodedRoi(0.7761f, 0.3347f, 0.1574f, 0.0497f, "field_3_32", "2#蒸发器 出水温度"),
            HardcodedRoi(0.7761f, 0.3845f, 0.1541f, 0.0465f, "field_3_31", "2#蒸发器 进水温度"),
            HardcodedRoi(0.6369f, 0.8821f, 0.2588f, 0.0497f, "field_3_35", "2#蒸发器 蒸发压力"),
            HardcodedRoi(0.5899f, 0.9259f, 0.3058f, 0.0513f, "field_3_36", "2#蒸发器 蒸发温度"),
            HardcodedRoi(0.0602f, 0.3284f, 0.1538f, 0.0465f, "field_3_39", "2#冷凝器 出水温度"),
            HardcodedRoi(0.0623f, 0.3765f, 0.1517f, 0.0439f, "field_3_38", "2#冷凝器 进水温度"),
            HardcodedRoi(0.1260f, 0.8683f, 0.2524f, 0.0513f, "field_3_42", "2#冷凝器 冷凝压力"),
            HardcodedRoi(0.0790f, 0.9133f, 0.3015f, 0.0545f, "field_3_43", "2#冷凝器 冷凝温度"),
            HardcodedRoi(0.6903f, 0.0471f, 0.2827f, 0.0497f, "field_3_46", "2#压缩机 排口温度"),
            HardcodedRoi(0.7668f, 0.2089f, 0.1823f, 0.0465f, "field_3_47", "2#压缩机 滑阀位置"),
            HardcodedRoi(0.1816f, 0.0550f, 0.2083f, 0.0481f, "field_3_44", "2#压缩机 油压"),
            HardcodedRoi(0.2065f, 0.1031f, 0.1552f, 0.0481f, "field_3_45", "2#压缩机 油温")
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

    // 这个函数已废弃（用于手机端定标时获取字段名），现在定标已完全移交给电脑硬编码
    fun getFieldList(machineId: String, screenIndex: Int): Pair<List<String>, List<String>> {
        return Pair(emptyList(), emptyList())
    }
}
