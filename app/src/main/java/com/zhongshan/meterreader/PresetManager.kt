package com.zhongshan.meterreader

import android.content.Context
import android.content.SharedPreferences

object PresetManager {
    private const val PREFS_NAME = "preset_values"

    data class PresetItem(
        val label: String,
        val storageKey: String,
        val formFieldId: String,
        val defaultValue: String,
        val machineGroup: String
    )

    val allItems = listOf(
        PresetItem("1号特灵-蒸发器冷媒压力", "screw_evap_p", "field_1_05", "269.0", "trane_screw"),
        PresetItem("1号特灵-冷凝器冷媒压力", "screw_cond_p", "field_1_12", "686.0", "trane_screw"),
        PresetItem("1号约克离心-蒸发器冷媒压力", "cent_evap_p", "field_1_78", "413.0", "york_cent"),
        PresetItem("1号约克离心-冷凝器冷媒压力", "cent_cond_p", "field_1_77", "415.0", "york_cent"),
        PresetItem("1号约克离心-电机电压", "cent_voltage", "field_1_84", "380", "york_cent"),
        PresetItem("3号约克螺杆-蒸发器冷媒压力", "screw3_evap_p", "field_3_05", "468.0", "york_screw3"),
        PresetItem("3号约克螺杆-冷凝器冷媒压力", "screw3_cond_p", "field_3_12", "1035.0", "york_screw3"),
        PresetItem("3号约克螺杆-电机电压(1#)", "screw3_volt1", "field_3_19", "380", "york_screw3"),
        PresetItem("3号约克螺杆-电机电压(2#)", "screw3_volt2", "field_3_49", "380", "york_screw3")
    )

    private val machineToGroup = mapOf(
        "screw_1" to "trane_screw", "screw_2" to "trane_screw", "screw_3" to "trane_screw",
        "cent_1" to "york_cent", "screw_3_1" to "york_screw3", "screw_3_2" to "york_screw3"
    )

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        allItems.forEach {
            if (!prefs.contains(it.storageKey))
                editor.putString(it.storageKey, it.defaultValue)
        }
        editor.apply()
    }

    fun getPresetsForMachine(machineId: String): Map<String, String> {
        val group = machineToGroup[machineId] ?: return emptyMap()

        val offset = when (machineId) {
            "screw_2"   -> 30
            "screw_3"   -> 50
            "screw_3_2" -> 30
            else -> 0
        }

        return allItems.filter { it.machineGroup == group }
            .mapNotNull { item ->
                if (machineId == "screw_3_1" && item.storageKey == "screw3_volt2") return@mapNotNull null
                if (machineId == "screw_3_2" && item.storageKey == "screw3_volt1") return@mapNotNull null

                var actualFieldId = item.formFieldId
                if (offset > 0 && item.storageKey != "screw3_volt2" && item.storageKey != "screw3_volt1") {
                    val parts = actualFieldId.split("_")
                    if (parts.size == 3) {
                        val num = parts[2].toIntOrNull()
                        if (num != null) {
                            actualFieldId = "${parts[0]}_${parts[1]}_${String.format("%02d", num + offset)}"
                        }
                    }
                }

                val value = prefs.getString(item.storageKey, item.defaultValue) ?: item.defaultValue
                actualFieldId to value
            }.toMap()
    }

    fun updatePreset(storageKey: String, newValue: String) {
        prefs.edit().putString(storageKey, newValue).apply()
    }

    fun getPresetValue(storageKey: String, defaultValue: String): String {
        return prefs.getString(storageKey, defaultValue) ?: defaultValue
    }
}