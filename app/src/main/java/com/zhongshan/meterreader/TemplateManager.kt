package com.zhongshan.meterreader

import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.data.OcrField
import com.zhongshan.meterreader.data.RoiRegion
import com.zhongshan.meterreader.data.ScreenTemplate

object TemplateManager {

    private fun roi(x: Float, y: Float, w: Float, h: Float) = RoiRegion(x, y, w, h)

    private const val URL_ROOM_1 = "https://appflow.zs-hospital.sh.cn/public/form/dae1b4dba6f94b2bb9ed1d2f4d33b0bc"
    private const val URL_ROOM_3 = "https://appflow.zs-hospital.sh.cn/public/form/1efd9996ea034ce2b2bc792551c4c6b5"

    // 修复：更新板交关键字为表单内的实际可见字样
    private val plateMaps = mapOf(
        1 to mapOf(
            "1#板交" to "1#板交", "2#板交" to "2#板交", "3#板交" to "3#板交",
            "4#板交" to "4#板交", "5#板交" to "5#板交", "6#板交" to "6#板交"
        ),
        3 to mapOf("1#板交" to "1#板交", "2#板交" to "2#板交")
    )

    fun getPlateKeywordMap(roomId: Int): Map<String, String> = plateMaps[roomId] ?: emptyMap()

    private fun traneScrewScreens(prefix: Int): List<ScreenTemplate> {
        val offset = when (prefix) {
            2 -> 30
            3 -> 50
            else -> 0
        }
        return listOf(
            ScreenTemplate("蒸发器", listOf(
                OcrField("蒸发器进口水温", "field_1_${String.format("%02d", 1 + offset)}", roi(0.72f, 0.25f, 0.18f, 0.09f)),
                OcrField("蒸发器出口水温", "field_1_${String.format("%02d", 2 + offset)}", roi(0.72f, 0.32f, 0.18f, 0.09f)),
                OcrField("蒸发器蒸发温度", "field_1_${String.format("%02d", 6 + offset)}", roi(0.72f, 0.39f, 0.18f, 0.09f)),
                OcrField("蒸发器冷媒压力", "field_1_${String.format("%02d", 5 + offset)}", roi(0.72f, 0.48f, 0.18f, 0.09f))
            )),
            ScreenTemplate("冷凝器", listOf(
                OcrField("冷凝器进口水温", "field_1_${String.format("%02d", 8 + offset)}", roi(0.72f, 0.25f, 0.18f, 0.09f)),
                OcrField("冷凝器出口水温", "field_1_${String.format("%02d", 9 + offset)}", roi(0.72f, 0.32f, 0.18f, 0.09f)),
                OcrField("冷凝器冷凝温度", "field_1_${String.format("%02d", 13 + offset)}", roi(0.72f, 0.39f, 0.18f, 0.09f)),
                OcrField("冷凝器冷媒压力", "field_1_${String.format("%02d", 12 + offset)}", roi(0.72f, 0.48f, 0.18f, 0.09f))
            )),
            ScreenTemplate("压缩机与电流", listOf(
                // 修复：针对特灵压缩机面板排版调整宽度，并新增清单缺失项
                OcrField("压缩机油压", "field_1_${String.format("%02d", 14 + offset)}", roi(0.70f, 0.28f, 0.25f, 0.09f)),
                OcrField("压缩机排出口温度", "field_1_${String.format("%02d", 15 + offset)}", roi(0.70f, 0.44f, 0.25f, 0.09f)),
                OcrField("主机负载", "field_1_${String.format("%02d", 18 + offset)}", roi(0.40f, 0.60f, 0.30f, 0.09f)),
                OcrField("电机电流(L1)", "field_1_${String.format("%02d", 17 + offset)}", roi(0.40f, 0.68f, 0.30f, 0.09f))
            ))
        )
    }

    private fun yorkCentScreens() = listOf(
        ScreenTemplate("系统总览", listOf(
            OcrField("蒸发器进口水温", "field_1_68", roi(0.80f, 0.40f, 0.16f, 0.07f)),
            OcrField("蒸发器出口水温", "field_1_69", roi(0.80f, 0.32f, 0.16f, 0.07f)),
            OcrField("蒸发器蒸发温度", "field_1_70", roi(0.80f, 0.92f, 0.16f, 0.06f)),
            OcrField("蒸发器冷媒压力", "field_1_78", roi(0.80f, 0.86f, 0.16f, 0.06f)),
            OcrField("冷凝器进口水温", "field_1_79", roi(0.06f, 0.40f, 0.16f, 0.07f)),
            OcrField("冷凝器出口水温", "field_1_71", roi(0.06f, 0.32f, 0.16f, 0.07f)),
            OcrField("冷凝器冷凝温度", "field_1_81", roi(0.26f, 0.92f, 0.16f, 0.06f)), // 修复 X 轴偏移
            OcrField("冷凝器冷媒压力", "field_1_77", roi(0.26f, 0.86f, 0.16f, 0.06f)),
            OcrField("压缩机排气温度", "field_1_76", roi(0.26f, 0.16f, 0.16f, 0.06f)), // 更正标签名称
            OcrField("压缩机油泵压力", "field_1_74", roi(0.80f, 0.21f, 0.16f, 0.06f)),
            OcrField("压缩机油箱温度", "field_1_75", roi(0.80f, 0.16f, 0.16f, 0.06f)),
            OcrField("压缩机导液开度", "field_1_82", roi(0.26f, 0.21f, 0.16f, 0.06f)) // 修复 Y 轴
        ))
    )

    // 重新修正：约克螺杆机的完全准确坐标（无重叠），匹配需求清单。
    private fun yorkScrewScreens(prefix: Int): List<ScreenTemplate> {
        val offset = if (prefix == 1) 0 else 30
        return listOf(
            ScreenTemplate("系统总览", listOf(
                OcrField("蒸发器进口水温",   "field_3_${String.format("%02d", 1 + offset)}", roi(0.80f, 0.51f, 0.18f, 0.06f)),
                OcrField("蒸发器出口水温",   "field_3_${String.format("%02d", 2 + offset)}", roi(0.80f, 0.46f, 0.18f, 0.06f)),
                OcrField("蒸发器蒸发压力",   "field_3_${String.format("%02d", 5 + offset)}", roi(0.80f, 0.81f, 0.18f, 0.06f)),
                OcrField("蒸发器蒸发温度",   "field_3_${String.format("%02d", 6 + offset)}", roi(0.80f, 0.86f, 0.18f, 0.06f)),
                OcrField("冷凝器进口水温",   "field_3_${String.format("%02d", 8 + offset)}", roi(0.12f, 0.56f, 0.18f, 0.06f)), // 修复: 返回温度位于下层
                OcrField("冷凝器出口水温",   "field_3_${String.format("%02d", 9 + offset)}", roi(0.12f, 0.51f, 0.18f, 0.06f)), // 修复: 出水温度位于上层
                OcrField("冷凝器冷凝压力",   "field_3_${String.format("%02d", 12 + offset)}", roi(0.25f, 0.81f, 0.18f, 0.06f)),
                OcrField("冷凝器冷凝温度",   "field_3_${String.format("%02d", 13 + offset)}", roi(0.25f, 0.86f, 0.18f, 0.06f)),
                OcrField("压缩机油压",      "field_3_${String.format("%02d", 14 + offset)}", roi(0.28f, 0.14f, 0.18f, 0.06f)),
                OcrField("压缩机油箱温度",   "field_3_${String.format("%02d", 15 + offset)}", roi(0.28f, 0.18f, 0.18f, 0.06f)),
                OcrField("压缩机排口温度",   "field_3_${String.format("%02d", 16 + offset)}", roi(0.80f, 0.13f, 0.18f, 0.06f)),
                OcrField("压缩机导液开度",   "field_3_${String.format("%02d", 17 + offset)}", roi(0.80f, 0.28f, 0.18f, 0.06f))
            ))
        )
    }

    val allTemplates = listOf(
        DeviceTemplate("1号机房 特灵螺杆1#", "screw_1", 1, URL_ROOM_1, traneScrewScreens(1), listOf("pump_7", "pump_8")),
        DeviceTemplate("1号机房 特灵螺杆2#", "screw_2", 1, URL_ROOM_1, traneScrewScreens(2), listOf("pump_5", "pump_6")),
        DeviceTemplate("1号机房 特灵螺杆3#", "screw_3", 1, URL_ROOM_1, traneScrewScreens(3), listOf("pump_3", "pump_4")),
        DeviceTemplate("1号机房 约克离心机", "cent_1", 1, URL_ROOM_1, yorkCentScreens(), listOf("pump_1", "pump_2")),
        DeviceTemplate("3号机房 约克螺杆1#", "screw_3_1", 3, URL_ROOM_3, yorkScrewScreens(1)),
        DeviceTemplate("3号机房 约克螺杆2#", "screw_3_2", 3, URL_ROOM_3, yorkScrewScreens(2)),
        DeviceTemplate("1号机房 板交(全组)", "hx1_all", 1, URL_ROOM_1, emptyList(), emptyList(), true),
        DeviceTemplate("3号机房 板交(全组)", "hx3_all", 3, URL_ROOM_3, emptyList(), emptyList(), true)
    )

    fun findById(machineId: String): DeviceTemplate? = allTemplates.firstOrNull { it.machineId == machineId }

    // 核心修复：完全匹配需求中要求的标签页名称
    fun getTabName(template: DeviceTemplate): String {
        return when {
            template.isHeatExchanger -> "板交"
            template.machineId.startsWith("cent_") -> "离心机组"
            template.roomId == 3 -> "螺杆机"
            else -> "螺杆机组"
        }
    }
}
