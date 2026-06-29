	package com.zhongshan.meterreader
	import com.zhongshan.meterreader.data.DeviceTemplate
	object TemplateManager {
	    private const val URL_ROOM_1 = "https://appflow.zs-hospital.sh.cn/public/form/dae1b4dba6f94b2bb9ed1d2f4d33b0bc"
	    private const val URL_ROOM_3 = "https://appflow.zs-hospital.sh.cn/public/form/1efd9996ea034ce2b2bc792551c4c6b5"
	    // 板交关键字映射
	    private val plateMaps = mapOf(
	        1 to mapOf(
	            "1号板交"    to "bj1_0",
	            "3号板交"    to "bj1_1",
	            "备用板交"   to "bj1_2",
	            "1号楼板交"  to "bj1_0",
	            "3号楼板交"  to "bj1_1",
	            "10号1#板交" to "bj1_3",
	            "10号2#板交" to "bj1_4",
	            "10号楼1#"   to "bj1_3",
	            "10号楼2#"   to "bj1_4",
	            "10号1#"     to "bj1_3",
	            "10号2#"     to "bj1_4",
	            "1号楼"      to "bj1_0",
	            "3号楼"      to "bj1_1",
	            "备用"       to "bj1_2",
	            "水汀"       to "bj1_5"
	        ),
	        3 to mapOf(
	            "1#板交" to "bj3_0",
	            "2#板交" to "bj3_1",
	            "1#"     to "bj3_0",
	            "2#"     to "bj3_1"
	        )
	    )
	    fun getPlateKeywordMap(roomId: Int): Map<String, String> =
	        plateMaps[roomId] ?: emptyMap()
	    // 设备列表 (精简：仅保留特灵与板交)
	    val allTemplates = listOf(
	        DeviceTemplate("1号机房 特灵螺杆1#", "screw_1",   1, URL_ROOM_1, pumpFieldIds = listOf("pump_7", "pump_8")),
	        DeviceTemplate("1号机房 特灵螺杆2#", "screw_2",   1, URL_ROOM_1, pumpFieldIds = listOf("pump_5", "pump_6")),
	        DeviceTemplate("1号机房 特灵螺杆3#", "screw_3",   1, URL_ROOM_1, pumpFieldIds = listOf("pump_3", "pump_4")),
	        DeviceTemplate("1号机房 板交(全组)", "hx1_all",   1, URL_ROOM_1, isHeatExchanger = true),
	        DeviceTemplate("3号机房 板交(全组)", "hx3_all",   3, URL_ROOM_3, isHeatExchanger = true)
	    )
	    fun findById(machineId: String): DeviceTemplate? =
	        allTemplates.firstOrNull { it.machineId == machineId }
	    fun getTabName(template: DeviceTemplate): String = when {
	        template.isHeatExchanger               -> "板交"
	        else                                   -> "螺杆机组"
	    }
	}
