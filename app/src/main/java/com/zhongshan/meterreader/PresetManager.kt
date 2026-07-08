package com.zhongshan.meterreader

import android.content.Context
import android.content.SharedPreferences

object PresetManager {
    private lateinit var sp: SharedPreferences

    private const val KEY_PRESSURE_PRESET = "pressure_preset"
    private const val KEY_VOLTAGE_PRESET = "voltage_preset"
    private const val KEY_SELECTED_PUMPS = "selected_pumps"

    fun init(context: Context) {
        sp = context.getSharedPreferences("meter_preset", Context.MODE_PRIVATE)
    }

    // 压力预设
    fun getPressurePreset(): String {
        return sp.getString(KEY_PRESSURE_PRESET, "0.45") ?: "0.45"
    }

    fun setPressurePreset(value: String) {
        sp.edit().putString(KEY_PRESSURE_PRESET, value).apply()
    }

    // 电压预设
    fun getVoltagePreset(): String {
        return sp.getString(KEY_VOLTAGE_PRESET, "380") ?: "380"
    }

    fun setVoltagePreset(value: String) {
        sp.edit().putString(KEY_VOLTAGE_PRESET, value).apply()
    }

    // 冷冻泵勾选集合
    fun getSelectedPumps(): Set<String> {
        return sp.getStringSet(KEY_SELECTED_PUMPS, emptySet()) ?: emptySet()
    }

    fun setSelectedPumps(pumps: Set<String>) {
        sp.edit().putStringSet(KEY_SELECTED_PUMPS, pumps).apply()
    }
}
