package com.zhongshan.meterreader

import com.zhongshan.meterreader.data.DeviceTemplate
import com.zhongshan.meterreader.data.OcrField
import com.zhongshan.meterreader.data.RoiRegion
import com.zhongshan.meterreader.data.ScreenTemplate

object TemplateManager {

    private fun roi(x: Float, y: Float, w: Float, h: Float) = RoiRegion(x, y, w, h)

    private const val URL_ROOM_1 = "https://appflow.zs-hospital.sh.cn/public/form/dae1b4dba6f94b2bb9ed1d2f4d33b0bc"
    private const val URL_ROOM_3 = "https://appflow.zs-hospital.sh.cn/public/form/1efd9996ea034ce2b2bc792551c4c6b5"

    // =========================================================
    // Bug Fix 1：板交关键字映射修正
    // 原代码：plateMaps[1] 使用 "1号楼"、"3号楼" 等含"楼"字的关键字，
    //         但1号机房实际表单截图里标题为 "1号板交"、"3号板交"、"备用板交"，
    //         不含"楼"字，导致 lineText.contains(keyword) 永远 false，
    //         currentBjPrefix 始终为 null，outData 始终为空，
    //         表现为"未识别到有效数据"。
    //
    // 修复方案：同时保留带"楼"和不带"楼"两套关键字，
    //          以及增加 "1号板交"、"3号板交"、"备用板交" 等精确匹配词，
    //          确保无论截图来自哪种界面风格都能命中。
    // =========================================================
    private val plateMaps = mapOf(
        1 to mapOf(
            // 不带"楼"的精确词（表单标题实际显示格式）—— 新增核心修复
            "1号板交"   to "bj1_0",
            "3号板交"   to "bj1_1",
            "备用板交"  to "bj1_2",
            // 带"楼"的关键字（保留兼容，防止其他界面风格）
            "1号楼板交" to "bj1_0",
            "3号楼板交" to "bj1_1",
            "1号楼"     to "bj1_0",
            "3号楼"     to "bj1_1",
            "备用"      to "bj1_2",
            // 10号楼板交
            "10号1#板交"  to "bj1_3",
            "10号2#板交"  to "bj1_4",
            "10号楼1#"    to "bj1_3",
            "10号楼2#"    to "bj1_4",
            "10号1#"      to "bj1_3",
            "10号2#"      to "bj1_4",
            // 水汀板交
            "水汀"        to "bj1_5"
        ),
        3 to mapOf(
            // 3号机房板交标题格式："1#板交" / "2#板交"
            "1#板交" to "bj3_0",
            "2#板交" to "bj3_1",
            "1#"     to "bj3_0",
            "2#"     to "bj3_1"
        )
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
                OcrField("压缩机排出口温度", "field_1_${String.format("%02d", 15 + offset)}", roi(0.72f, 0.40f, 0.18f, 0.08f)),
                OcrField("压缩机油压",       "field_1_${String.format("%02d", 14 + offset)}", roi(0.72f, 0.16f, 0.18f, 0.08f)),
                OcrField("电机电流(L1)",     "field_1_${String.format("%02d", 17 + offset)}", roi(0.32f, 0.72f, 0.15f, 0.10f)),
                OcrField("主机负载",         "field_1_${String.format("%02d", 18 + offset)}", roi(0.32f, 0.64f, 0.15f, 0.10f))
            ))
        )
    }

    private fun yorkCentScreens() = listOf(
        ScreenTemplate("系统总览", listOf(
            OcrField("蒸发器进口水温",   "field_1_68", roi(0.80f, 0.40f, 0.16f, 0.07f)),
            OcrField("蒸发器出口水温",   "field_1_69", roi(0.80f, 0.32f, 0.16f, 0.07f)),
            OcrField("蒸发器蒸发温度",   "field_1_70", roi(0.80f, 0.92f, 0.16f, 0.06f)),
            OcrField("蒸发器冷媒压力",   "field_1_78", roi(0.80f, 0.86f, 0.16f, 0.06f)),
            OcrField("冷凝器进口水温",   "field_1_79", roi(0.06f, 0.40f, 0.16f, 0.07f)),
            OcrField("冷凝器出口水温",   "field_1_71", roi(0.06f, 0.32f, 0.16f, 0.07f)),
            OcrField("冷凝器冷凝温度",   "field_1_81", roi(0.06f, 0.92f, 0.16f, 0.06f)),
            OcrField("冷凝器冷媒压力",   "field_1_77", roi(0.26f, 0.86f, 0.16f, 0.06f)),
            OcrField("压缩机排出口温度", "field_1_76", roi(0.26f, 0.16f, 0.16f, 0.06f)),
            OcrField("压缩机油泵压力",   "field_1_74", roi(0.80f, 0.21f, 0.16f, 0.06f)),
            OcrField("压缩机油箱温度",   "field_1_75", roi(0.80f, 0.16f, 0.16f, 0.06f)),
            OcrField("压缩机导液开度",   "field_1_82", roi(0.26f, 0.22f, 0.16f, 0.06f))
        ))
    )

    private fun yorkScrewScreens(prefix: Int): List<ScreenTemplate> {
        val offset = if (prefix == 1) 0 else 30
        return listOf(
            ScreenTemplate("系统总览", listOf(
                OcrField("蒸发器进口水温", "field_3_${String.format("%02d", 1 + offset)}", roi(0.80f, 0.51f, 0.18f, 0.06f)),
                OcrField("蒸发器出口水温", "field_3_${String.format("%02d", 2 + offset)}", roi(0.80f, 0.46f, 0.18f, 0.06f)),
                OcrField("蒸发器蒸发压力", "field_3_${String.format("%02d", 5 + offset)}", roi(0.80f, 0.81f, 0.18f, 0.06f)),
                OcrField("蒸发器蒸发温度", "field_3_${String.format("%02d", 6 + offset)}", roi(0.80f, 0.86f, 0.18f, 0.06f)),
                OcrField("冷凝器进口水温", "field_3_${String.format("%02d", 8 + offset)}", roi(0.12f, 0.51f, 0.18f, 0.06f)),
                OcrField("冷凝器出口水温", "field_3_${String.format("%02d", 9 + offset)}", roi(0.12f, 0.46f, 0.18f, 0.06f)),
                OcrField("冷凝器冷凝压力", "field_3_${String.format("%02d", 12 + offset)}", roi(0.25f, 0.81f, 0.18f, 0.06f)),
                OcrField("冷凝器冷凝温度", "field_3_${String.format("%02d", 13 + offset)}", roi(0.25f, 0.86f, 0.18f, 0.06f)),
                OcrField("压缩机油压",     "field_3_${String.format("%02d", 14 + offset)}", roi(0.28f, 0.14f, 0.18f, 0.06f)),
                OcrField("压缩机油箱温度", "field_3_${String.format("%02d", 15 + offset)}", roi(0.28f, 0.18f, 0.18f, 0.06f)),
                OcrField("压缩机排口温度", "field_3_${String.format("%02d", 16 + offset)}", roi(0.80f, 0.13f, 0.18f, 0.06f)),
                OcrField("压缩机导液开度", "field_3_${String.format("%02d", 17 + offset)}", roi(0.80f, 0.28f, 0.18f, 0.06f))
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

    // =========================================================
    // Bug Fix 3：标签页名称精确匹配修正
    // 原问题：1号机房表单有5个标签页（交接班/螺杆机组/离心机组/板交/板交），
    //         3号机房有4个标签页（交接班/螺杆机/板交/操作记录）。
    //         WebView JS 注入使用 indexOf(tabName) > -1 做模糊匹配，
    //         "螺杆机" 会同时匹配 "螺杆机" 和 "螺杆机组"，
    //         "板交" 在1号机房有两个 tab 都能匹配（第4个先被命中，正好正确）。
    //         这部分逻辑无需改动，但 getTabName 返回的名称必须与实际 tab 文字严格一致：
    //         - 1号机房螺杆机组标签文字为 "螺杆机组" → 返回 "螺杆机组" ✓
    //         - 1号机房离心机组标签文字为 "离心机组" → 返回 "离心机组" ✓
    //         - 1号机房板交标签文字为    "板交"     → 返回 "板交"     ✓
    //         - 3号机房螺杆机标签文字为  "螺杆机"   → 返回 "螺杆机"   ✓
    //         - 3号机房板交标签文字为    "板交"     → 返回 "板交"     ✓
    //         当前代码已经正确，但 JS 端的 indexOf 精确度不够，见 WebViewActivity 修复。
    // =========================================================
    fun getTabName(template: DeviceTemplate): String {
        return when {
            template.isHeatExchanger                  -> "板交"
            template.machineId.startsWith("cent_")    -> "离心机组"
            template.roomId == 3                      -> "螺杆机"
            else                                      -> "螺杆机组"
        }
    }
}
