package com.zhongshan.meterreader

import android.content.Context
import android.content.SharedPreferences

object PresetManager {
    private const val PREFS_NAME = "preset_values"

    // 可用冷冻泵列表（按项目实际需求调整）
    val availablePumps = listOf("1", "2", "3", "4")

    data class PresetItem(
        val label: String,
        val storageKey: String,
        val formFieldId: String,
        val defaultValue: String,
        val machineGroup: String
    )

    // 预设项列表，新增冷冻泵勾选项
    val allItems = listOf(
        PresetItem("1号特灵-蒸发器进口水压", "screw_evap_in_p", "field_1_03|蒸发器进口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-蒸发器出口水压", "screw_evap_out_p", "field_1_04|蒸发器出口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-冷凝器进口水压", "screw_cond_in_p", "field_1_10|冷凝器进口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-冷凝器出口水压", "screw_cond_out_p", "field_1_11|冷凝器出口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-冷冻泵", "screw_pumps", "pumps", "1,2", "trane_screw")  // 新增
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

    // ---- 基础读写 ----
    fun getPresetValue(storageKey: String, defaultValue: String): String {
        return prefs.getString(storageKey, defaultValue) ?: defaultValue
    }

    fun updatePreset(storageKey: String, newValue: String) {
        prefs.edit().putString(storageKey, newValue).apply()
    }

    // ---- 螺杆水压专用获取方法 ----
    fun getEvapInPressure()  = getPresetValue("screw_evap_in_p", "0.45")
    fun getEvapOutPressure() = getPresetValue("screw_evap_out_p", "0.45")
    fun getCondInPressure()  = getPresetValue("screw_cond_in_p", "0.45")
    fun getCondOutPressure() = getPresetValue("screw_cond_out_p", "0.45")

    // ---- 冷冻泵选择 ----
    // 获取选中的泵号（返回 List<String>，内部存储为逗号分隔）
    fun getSelectedPumps(): List<String> {
        val str = prefs.getString("screw_pumps", "1,2") ?: "1,2"
        return str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // 根据 storageKey 获取泵列表（用于设置界面）
    fun getPumps(storageKey: String, default: String): List<String> {
        val str = prefs.getString(storageKey, default) ?: default
        return str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // 保存泵列表
    fun savePumps(storageKey: String, pumps: List<String>) {
        prefs.edit().putString(storageKey, pumps.joinToString(",")).apply()
    }

    // 为旧接口提供兼容（可选）
    fun getPresetsForMachine(machineId: String): Map<String, String> {
        val group = machineToGroup[machineId] ?: return emptyMap()
        val offset = when (machineId) {
            "screw_2" -> 30
            "screw_3" -> 50
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
}
