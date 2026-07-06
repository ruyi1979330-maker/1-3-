// 文件名: PresetManager.kt
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
        PresetItem("1号特灵-蒸发器进口水压", "screw_evap_in_p", "field_1_03|蒸发器进口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-蒸发器出口水压", "screw_evap_out_p", "field_1_04|蒸发器出口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-冷凝器进口水压", "screw_cond_in_p", "field_1_10|冷凝器进口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-冷凝器出口水压", "screw_cond_out_p", "field_1_11|冷凝器出口水压", "0.45", "trane_screw"),
        // 冷冻泵预勾选：逗号分隔的泵名，可在预设设置中自由编辑
        PresetItem("1#机组冷冻泵", "screw_1_pumps", "pumps_screw_1", "7号冷冻泵,8号冷冻泵", "trane_screw"),
        PresetItem("2#机组冷冻泵", "screw_2_pumps", "pumps_screw_2", "5号冷冻泵,6号冷冻泵", "trane_screw"),
        PresetItem("3#机组冷冻泵", "screw_3_pumps", "pumps_screw_3", "3号冷冻泵,4号冷冻泵", "trane_screw")
    )

    private val machineToGroup = mapOf(
        "screw_1" to "trane_screw", "screw_2" to "trane_screw", "screw_3" to "trane_screw"
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
            else -> 0
        }
        return allItems.filter { it.machineGroup == group }
            .mapNotNull { item ->
                var actualFieldId = item.formFieldId
                if (offset > 0) {
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
