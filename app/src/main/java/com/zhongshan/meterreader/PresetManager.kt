package com.zhongshan.meterreader

import android.content.Context
import android.content.SharedPreferences

object PresetManager {
    private lateinit var sp: SharedPreferences

    // 原有预设key（保留不动）
    private const val KEY_PRESSURE_PRESET = "pressure_preset"
    private const val KEY_VOLTAGE_PRESET = "voltage_preset"
    // 新增：冷冻泵预设key
    private const val KEY_SELECTED_PUMPS = "selected_pumps"

    fun init(context: Context) {
        sp = context.getSharedPreferences("meter_preset", Context.MODE_PRIVATE)
    }

    // ========== 原有压力/电压预设（完全保留） ==========
    fun getPressurePreset(): String {
        return sp.getString(KEY_PRESSURE_PRESET, "0.45") ?: "0.45"
    }

    fun setPressurePreset(value: String) {
        sp.edit().putString(KEY_PRESSURE_PRESET, value).apply()
    }

    fun getVoltagePreset(): String {
        return sp.getString(KEY_VOLTAGE_PRESET, "380") ?: "380"
    }

    fun setVoltagePreset(value: String) {
        sp.edit().putString(KEY_VOLTAGE_PRESET, value).apply()
    }

    // ========== 新增：冷冻泵预设存取 ==========
    fun getSelectedPumps(): Set<String> {
        return sp.getStringSet(KEY_SELECTED_PUMPS, emptySet()) ?: emptySet()
    }

    fun setSelectedPumps(pumps: Set<String>) {
        sp.edit().putStringSet(KEY_SELECTED_PUMPS, pumps).apply()
    }
}
