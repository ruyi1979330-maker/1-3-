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

    // 核心修复：坚决移除冷媒压力，新增全部水压和电压。使用 "预估ID|表单中文名" 格式确保容错。
    val allItems = listOf(
        PresetItem("1号特灵-蒸发器进口水压", "screw_evap_in_p", "field_1_03|蒸发器进水口", "0.45", "trane_screw"),
        PresetItem("1号特灵-蒸发器出口水压", "screw_evap_out_p", "field_1_04|蒸发器出水口", "0.45", "trane_screw"),
        PresetItem("1号特灵-冷凝器进口水压", "screw_cond_in_p", "field_1_10|冷凝器进水口", "0.45", "trane_screw"),
        PresetItem("1号特灵-冷凝器出口水压", "screw_cond_out_p", "field_1_11|冷凝器出水口", "0.45", "trane_screw"),

        PresetItem("1号约克离心-蒸发器进口水压", "cent_evap_in_p", "field_1_90|蒸发器进口", "0.45", "york_cent"),
        PresetItem("1号约克离心-蒸发器出口水压", "cent_evap_out_p", "field_1_91|蒸发器出口", "0.45", "york_cent"),
        PresetItem("1号约克离心-冷凝器进口水压", "cent_cond_in_p", "field_1_92|冷凝器进口", "0.45", "york_cent"),
        PresetItem("1号约克离心-冷凝器出口水压", "cent_cond_out_p", "field_1_93|冷凝器出口", "0.45", "york_cent"),
        PresetItem("1号约克离心-电机电压", "cent_voltage", "field_1_84|电机电压", "380", "york_cent"),

        PresetItem("3号约克螺杆-蒸发器进口水压", "screw3_evap_in_p", "field_3_03|蒸发器进口", "0.45", "york_screw3"),
        PresetItem("3号约克螺杆-蒸发器出口水压", "screw3_evap_out_p", "field_3_04|蒸发器出口", "0.45", "york_screw3"),
        PresetItem("3号约克螺杆-冷凝器进口水压", "screw3_cond_in_p", "field_3_10|冷凝器进口", "0.45", "york_screw3"),
        PresetItem("3号约克螺杆-冷凝器出口水压", "screw3_cond_out_p", "field_3_11|冷凝器出口", "0.45", "york_screw3"),
        PresetItem("3号约克螺杆-电机电压(1#)", "screw3_volt1", "field_3_19|电机电压", "380", "york_screw3"),
        PresetItem("3号约克螺杆-电机电压(2#)", "screw3_volt2", "field_3_49|电机电压", "380", "york_screw3")
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
                    // 处理带有中文后缀的 ID 偏移
                    val parts = actualFieldId.split("|")
                    val rawId = parts[0]
                    val idParts = rawId.split("_")
                    if (idParts.size == 3) {
                        val num = idParts[2].toIntOrNull()
                        if (num != null) {
                            val newId = "${idParts[0]}_${idParts[1]}_${String.format("%02d", num + offset)}"
                            actualFieldId = if (parts.size > 1) "$newId|${parts[1]}" else newId
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
