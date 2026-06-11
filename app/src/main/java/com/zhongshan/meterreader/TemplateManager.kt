package com.zhongshan.meterreader

import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.data.OcrField
import com.zhongshan.meterreader.data.RoiRegion
import com.zhongshan.meterreader.data.ScreenTemplate

object TemplateManager {

    private fun roi(x: Float, y: Float, w: Float, h: Float) = RoiRegion(x, y, w, h)

    private const val URL_ROOM_1 = "https://appflow.zs-hospital.sh.cn/public/form/dae1b4dba6f94b2bb9ed1d2f4d33b0bc"
    private const val URL_ROOM_3 = "https://appflow.zs-hospital.sh.cn/public/form/1efd9996ea034ce2b2bc792551c4c6b5"

    private val plateMaps = mapOf(
        1 to mapOf(
            "1号板交"    to "bj1_0",
            "3号板交"    to "bj1_1",
            "备用板交"   to "bj1_2",
            "1号楼板交"  to "bj1_0",
            "3号楼板交"  to "bj1_1",
            "1号楼"      to "bj1_0",
            "3号楼"      to "bj1_1",
            "备用"       to "bj1_2",
            "10号1#板交" to "bj1_3",
            "10号2#板交" to "bj1_4",
            "10号楼1#"   to "bj1_3",
            "10号楼2#"   to "bj1_4",
            "10号1#"     to "bj1_3",
            "10号2#"     to "bj1_4",
            "水汀"       to "bj1_5"
        ),
        3 to mapOf(
            "1#板交" to "bj3_0",
            "2#板交" to "bj3_1",
            "1#"     to "bj3_0",
            "2#"     to "bj3_1"
        )
    )

    fun getPlateKeywordMap(roomId: Int): Map<String, String> = plateMaps[roomId] ?: emptyMap()

    // =====================================================================
    // 特灵螺杆机 ROI 修正（基于实机截图逆推）
    // 第1屏蒸发器、第2屏冷凝器：数值在屏幕右侧约 x=0.70，从上到下排列
    // 第3屏压缩机：
    //   油压 649.7 kPag        → 整行右侧，y≈0.14
    //   排出端冷剂温度 61.9 C  → 整行右侧，y≈0.28
    //   %RLA 46.5/42.8/42.5   → 三列，取最左列，y≈0.45
    //   电流L1/L2/L3          → 三列，取最左列(L1=214.0)，y≈0.54
    // =====================================================================
    private fun traneScrewScreens(prefix: Int): List<ScreenTemplate> {
        val offset = when (prefix) { 2 -> 30; 3 -> 50; else -> 0 }
        return listOf(
            ScreenTemplate("蒸发器", listOf(
                OcrField("蒸发器进口水温", "field_1_${String.format("%02d", 1 + offset)}", roi(0.70f, 0.22f, 0.24f, 0.08f)),
                OcrField("蒸发器出口水温", "field_1_${String.format("%02d", 2 + offset)}", roi(0.70f, 0.30f, 0.24f, 0.08f)),
                OcrField("蒸发器蒸发温度", "field_1_${String.format("%02d", 6 + offset)}", roi(0.70f, 0.36f, 0.24f, 0.08f)),
                OcrField("蒸发器冷媒压力", "field_1_${String.format("%02d", 5 + offset)}", roi(0.70f, 0.43f, 0.24f, 0.08f))
            )),
            ScreenTemplate("冷凝器", listOf(
                OcrField("冷凝器进口水温", "field_1_${String.format("%02d", 8 + offset)}", roi(0.70f, 0.17f, 0.24f, 0.08f)),
                OcrField("冷凝器出口水温", "field_1_${String.format("%02d", 9 + offset)}", roi(0.70f, 0.25f, 0.24f, 0.08f)),
                OcrField("冷凝器冷凝温度", "field_1_${String.format("%02d", 13 + offset)}", roi(0.70f, 0.32f, 0.24f, 0.08f)),
                OcrField("冷凝器冷媒压力", "field_1_${String.format("%02d", 12 + offset)}", roi(0.70f, 0.40f, 0.24f, 0.08f))
            )),
            ScreenTemplate("压缩机与电流", listOf(
                OcrField("压缩机油压",       "field_1_${String.format("%02d", 14 + offset)}", roi(0.52f, 0.13f, 0.42f, 0.08f)),
                OcrField("压缩机排出口温度", "field_1_${String.format("%02d", 15 + offset)}", roi(0.52f, 0.27f, 0.42f, 0.08f)),
                OcrField("主机负载",         "field_1_${String.format("%02d", 18 + offset)}", roi(0.28f, 0.43f, 0.22f, 0.08f)),
                OcrField("电机电流(L1)",     "field_1_${String.format("%02d", 17 + offset)}", roi(0.28f, 0.52f, 0.22f, 0.08f))
            ))
        )
    }

    // =====================================================================
    // 约克离心机 ROI 修正（基于约克离心机.jpg 08.06.2026 运行截图）
    // OPTIVIEW CONTROL 屏幕中央显示机组俯视图
    // 左上：油压差 684.8 kPaD, 油温 43.2°C
    // 左中：冷却水温度 出水 33.2°C / 返回 30.0°C
    // 右上：压缩机出口温度 61.1°C, %满载安培 50%, 滑阀位置 52%
    // 右中：冷冻水温度 出水 7.8°C / 返回 10.4°C
    // 下方左：冷凝器压力 1193.1 kPaG / 饱和温度 33.2°C
    // 下方右：蒸发器压力 497.9 kPaG / 饱和温度 5.8°C
    // =====================================================================
    private fun yorkCentScreens() = listOf(
        ScreenTemplate("系统总览", listOf(
            OcrField("蒸发器出口水温",   "field_1_69", roi(0.62f, 0.30f, 0.28f, 0.08f)),
            OcrField("蒸发器进口水温",   "field_1_68", roi(0.62f, 0.38f, 0.28f, 0.08f)),
            OcrField("蒸发器冷媒压力",   "field_1_78", roi(0.50f, 0.84f, 0.40f, 0.08f)),
            OcrField("蒸发器蒸发温度",   "field_1_70", roi(0.50f, 0.90f, 0.40f, 0.08f)),
            OcrField("冷凝器出口水温",   "field_1_71", roi(0.02f, 0.30f, 0.28f, 0.08f)),
            OcrField("冷凝器进口水温",   "field_1_79", roi(0.02f, 0.38f, 0.28f, 0.08f)),
            OcrField("冷凝器冷媒压力",   "field_1_77", roi(0.02f, 0.84f, 0.40f, 0.08f)),
            OcrField("冷凝器冷凝温度",   "field_1_81", roi(0.02f, 0.90f, 0.40f, 0.08f)),
            OcrField("压缩机排出口温度", "field_1_76", roi(0.62f, 0.17f, 0.28f, 0.08f)),
            OcrField("压缩机导液开度",   "field_1_82", roi(0.62f, 0.34f, 0.28f, 0.08f)),
            OcrField("压缩机油泵压力",   "field_1_74", roi(0.06f, 0.17f, 0.42f, 0.08f)),
            OcrField("压缩机油箱温度",   "field_1_75", roi(0.06f, 0.25f, 0.30f, 0.08f))
        ))
    )

    // =====================================================================
    // 约克螺杆机 ROI 修正（基于约克螺杆机.jpg 运行状态截图）
    // 布局与离心机相似，但字段内容不同
    // 左上：压缩机出口温度, %满载安培, 电流限制
    // 左中：冷却水温度 出水/返回
    // 右上：油槽温度, 油压
    // 右中：冷冻水温度 出水/返回/设定值
    // 右侧栏：滑阀位置
    // 下方左：冷凝器压力/饱和温度
    // 下方右：蒸发器压力/饱和温度
    // =====================================================================
    private fun yorkScrewScreens(prefix: Int): List<ScreenTemplate> {
        val offset = if (prefix == 1) 0 else 30
        return listOf(
            ScreenTemplate("系统总览", listOf(
                OcrField("蒸发器出口水温", "field_3_${String.format("%02d", 2 + offset)}", roi(0.60f, 0.41f, 0.30f, 0.08f)),
                OcrField("蒸发器进口水温", "field_3_${String.format("%02d", 1 + offset)}", roi(0.60f, 0.48f, 0.30f, 0.08f)),
                OcrField("蒸发器蒸发压力", "field_3_${String.format("%02d", 5 + offset)}", roi(0.50f, 0.82f, 0.40f, 0.08f)),
                OcrField("蒸发器蒸发温度", "field_3_${String.format("%02d", 6 + offset)}", roi(0.50f, 0.88f, 0.40f, 0.08f)),
                OcrField("冷凝器出口水温", "field_3_${String.format("%02d", 9 + offset)}", roi(0.02f, 0.41f, 0.30f, 0.08f)),
                OcrField("冷凝器进口水温", "field_3_${String.format("%02d", 8 + offset)}", roi(0.02f, 0.48f, 0.30f, 0.08f)),
                OcrField("冷凝器冷凝压力", "field_3_${String.format("%02d", 12 + offset)}", roi(0.02f, 0.82f, 0.40f, 0.08f)),
                OcrField("冷凝器冷凝温度", "field_3_${String.format("%02d", 13 + offset)}", roi(0.02f, 0.88f, 0.40f, 0.08f)),
                OcrField("压缩机排口温度", "field_3_${String.format("%02d", 16 + offset)}", roi(0.02f, 0.16f, 0.38f, 0.08f)),
                OcrField("压缩机导液开度", "field_3_${String.format("%02d", 17 + offset)}", roi(0.60f, 0.33f, 0.30f, 0.08f)),
                OcrField("压缩机油压",     "field_3_${String.format("%02d", 14 + offset)}", roi(0.60f, 0.24f, 0.30f, 0.08f)),
                OcrField("压缩机油箱温度", "field_3_${String.format("%02d", 15 + offset)}", roi(0.60f, 0.16f, 0.30f, 0.08f))
            ))
        )
    }

    val allTemplates = listOf(
        DeviceTemplate("1号机房 特灵螺杆1#", "screw_1",   1, URL_ROOM_1, traneScrewScreens(1), listOf("pump_7", "pump_8")),
        DeviceTemplate("1号机房 特灵螺杆2#", "screw_2",   1, URL_ROOM_1, traneScrewScreens(2), listOf("pump_5", "pump_6")),
        DeviceTemplate("1号机房 特灵螺杆3#", "screw_3",   1, URL_ROOM_1, traneScrewScreens(3), listOf("pump_3", "pump_4")),
        DeviceTemplate("1号机房 约克离心机", "cent_1",    1, URL_ROOM_1, yorkCentScreens(),    listOf("pump_1", "pump_2")),
        DeviceTemplate("3号机房 约克螺杆1#", "screw_3_1", 3, URL_ROOM_3, yorkScrewScreens(1)),
        DeviceTemplate("3号机房 约克螺杆2#", "screw_3_2", 3, URL_ROOM_3, yorkScrewScreens(2)),
        DeviceTemplate("1号机房 板交(全组)", "hx1_all",   1, URL_ROOM_1, emptyList(), emptyList(), true),
        DeviceTemplate("3号机房 板交(全组)", "hx3_all",   3, URL_ROOM_3, emptyList(), emptyList(), true)
    )

    fun findById(machineId: String): DeviceTemplate? =
        allTemplates.firstOrNull { it.machineId == machineId }

    fun getTabName(template: DeviceTemplate): String = when {
        template.isHeatExchanger               -> "板交"
        template.machineId.startsWith("cent_") -> "离心机组"
        template.roomId == 3                   -> "螺杆机"
        else                                   -> "螺杆机组"
    }
}
