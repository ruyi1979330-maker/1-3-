package com.zhongshan.meterreader.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class RoiBox(
    val xPercent: Float, // 相对图片宽度的百分比 0~1
    val yPercent: Float, // 相对图片高度的百分比 0~1
    val wPercent: Float,
    val hPercent: Float,
    val fieldId: String, // 对应表单的 field_1_xx
    val label: String    // 用于显示的名称，如 "蒸发器进水温度"
)

// 此单例专门用于管理“打点标定”的坐标配置
object RoiConfigManager {
    private const val PREFS_NAME = "roi_configs"
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveRoiConfigs(machineId: String, screenIndex: Int, rois: List<RoiBox>) {
        val key = "ROI_${machineId}_${screenIndex}"
        prefs.edit().putString(key, gson.toJson(rois)).apply()
    }

    fun getRoiConfigs(machineId: String, screenIndex: Int): List<RoiBox> {
        val key = "ROI_${machineId}_${screenIndex}"
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<RoiBox>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) { emptyList() }
    }
}
