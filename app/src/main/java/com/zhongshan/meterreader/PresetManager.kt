package com.zhongshan.meterreader

import android.content.Context
import android.content.SharedPreferences

object PresetManager {
    private lateinit var sp: SharedPreferences

    // 四个独立水压预设
    private const val KEY_EVAP_IN_PRESSURE = "evap_in_pressure"
    private const val KEY_EVAP_OUT_PRESSURE = "evap_out_pressure"
    private const val KEY_COND_IN_PRESSURE = "cond_in_pressure"
    private const val KEY_COND_OUT_PRESSURE = "cond_out_pressure"
    // 冷冻泵预设
    private const val KEY_SELECTED_PUMPS = "selected_pumps"

    fun init(context: Context) {
        sp = context.getSharedPreferences("meter_preset", Context.MODE_PRIVATE)
    }

    // 蒸发器进口水压
    fun getEvapInPressure(): String {
        return sp.getString(KEY_EVAP_IN_PRESSURE, "0.45") ?: "0.45"
    }

    fun setEvapInPressure(value: String) {
        sp.edit().putString(KEY_EVAP_IN_PRESSURE, value).apply()
    }

    // 蒸发器出口水压
    fun getEvapOutPressure(): String {
        return sp.getString(KEY_EVAP_OUT_PRESSURE, "0.45") ?: "0.45"
    }

    fun setEvapOutPressure(value: String) {
        sp.edit().putString(KEY_EVAP_OUT_PRESSURE, value).apply()
    }

    // 冷凝器进口水压
    fun getCondInPressure(): String {
        return sp.getString(KEY_COND_IN_PRESSURE, "0.45") ?: "0.45"
    }

    fun setCondInPressure(value: String) {
        sp.edit().putString(KEY_COND_IN_PRESSURE, value).apply()
    }

    // 冷凝器出口水压
    fun getCondOutPressure(): String {
        return sp.getString(KEY_COND_OUT_PRESSURE, "0.45") ?: "0.45"
    }

    fun setCondOutPressure(value: String) {
        sp.edit().putString(KEY_COND_OUT_PRESSURE, value).apply()
    }

    // 冷冻泵勾选集合
    fun getSelectedPumps(): Set<String> {
        return sp.getStringSet(KEY_SELECTED_PUMPS, emptySet()) ?: emptySet()
    }

    fun setSelectedPumps(pumps: Set<String>) {
        sp.edit().putStringSet(KEY_SELECTED_PUMPS, pumps).apply()
    }
}
