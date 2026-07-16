// ====================文件名：PresetManager.kt 完整可直接复制代码====================
// 项目：医院特灵冷水机组OCR抄表APP
// 修改版本：V2.0 一级菜单固定冷冻泵直选重构
// 改动标记：所有新增/修改逻辑标注 //【本次重构改动点】 与 //【本次新增-约克】
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
    // 旧版8泵全量列表，保留用于向下兼容，不删除
    val availablePumps = listOf(
        "1号冷冻泵", "2号冷冻泵", "3号冷冻泵", "4号冷冻泵",
        "5号冷冻泵", "6号冷冻泵", "7号冷冻泵", "8号冷冻泵"
    )
    val allItems = listOf(
        PresetItem("1号特灵-蒸发器进口水压", "screw_evap_in_p", "field_1_03|蒸发器进口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-蒸发器出口水压", "screw_evap_out_p", "field_1_04|蒸发器出口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-冷凝器进口水压", "screw_cond_in_p", "field_1_10|冷凝器进口水压", "0.45", "trane_screw"),
        PresetItem("1号特灵-冷凝器出口水压", "screw_cond_out_p", "field_1_11|冷凝器出口水压", "0.45", "trane_screw"),
        PresetItem("1#机组冷冻泵", "screw_1_pumps", "pumps_screw_1", "7号冷冻泵,8号冷冻泵", "trane_screw"),
        PresetItem("2#机组冷冻泵", "screw_2_pumps", "pumps_screw_2", "5号冷冻泵,6号冷冻泵", "trane_screw"),
        PresetItem("3#机组冷冻泵", "screw_3_pumps", "pumps_screw_3", "3号冷冻泵,4号冷冻泵", "trane_screw"),

        // 【本次新增-约克】独立预设分组 york_screw：进出水压(4项) + 电机电压(1项)
        // 红线：与特灵预设完全隔离，使用 york_ 前缀，绝不复用特灵的 storageKey / formFieldId。
        // formFieldId 中 fieldId 使用 york_ 语义 key（与 OCR 输出/WebView 标签 key 对齐）。
        PresetItem("约克-蒸发器进口水压", "york_evap_in_p",  "evapInPressure|蒸发器进口水压",   "0.45", "york_screw"),
        PresetItem("约克-蒸发器出口水压", "york_evap_out_p", "evapOutPressure|蒸发器出口水压",  "0.45", "york_screw"),
        PresetItem("约克-冷凝器进口水压", "york_cond_in_p",  "condInPressure|冷凝器进口水压",   "0.45", "york_screw"),
        PresetItem("约克-冷凝器出口水压", "york_cond_out_p", "condOutPressure|冷凝器出口水压",  "0.45", "york_screw"),
        PresetItem("约克-电机电压",       "york_motor_v",    "motorVoltage|电机电压",           "380",  "york_screw")
    )
    private val machineToGroup = mapOf(
        "screw_1" to "trane_screw", "screw_2" to "trane_screw", "screw_3" to "trane_screw",
        // 【本次新增-约克】york_1 / york_2 映射到 "york_screw"
        "york_1" to "york_screw", "york_2" to "york_screw"
    )
    // 【本次重构改动点】固定泵组映射关系，全局唯一配置入口
    // 后期如需调整泵号绑定，仅需修改此映射，页面自动适配，禁止在Activity中写死泵号
    val fixedPumpMapping = mapOf(
        "screw_1_pumps" to listOf("7号冷冻泵", "8号冷冻泵"),
        "screw_2_pumps" to listOf("5号冷冻泵", "6号冷冻泵"),
        "screw_3_pumps" to listOf("3号冷冻泵", "4号冷冻泵")
    )
    private lateinit var prefs: SharedPreferences
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        allItems.forEach {
            if (!prefs.contains(it.storageKey))
                editor.putString(it.storageKey, it.defaultValue)
        }
        // 【本次重构改动点】旧数据兼容迁移逻辑
        // 旧版允许在8台泵中任意勾选，新版仅保留属于该机组固定泵的勾选项
        // 过滤掉不属于固定泵映射的历史勾选数据，确保升级后行内复选框仅展示有效勾选
        fixedPumpMapping.forEach { (storageKey, fixedPumps) ->
            val raw = prefs.getString(storageKey, null)
            if (raw != null) {
                val oldPumps = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                // 仅保留属于该机组固定泵列表中的勾选项，过滤无关泵号
                val validPumps = oldPumps.filter { fixedPumps.contains(it) }
                editor.putString(storageKey, validPumps.joinToString(","))
            }
        }
        editor.apply()
    }
    fun getPresetsForMachine(machineId: String): Map<String, String> {
        val group = machineToGroup[machineId] ?: return emptyMap()
        // 【本次新增-约克】约克预设使用独立语义 key，不做 fieldId 数字偏移。
        val isYork = machineId.startsWith("york")
        val offset = when (machineId) {
            "screw_2"   -> 30
            "screw_3"   -> 50
            else -> 0
        }
        return allItems.filter { it.machineGroup == group }
            .mapNotNull { item ->
                var actualFieldId = item.formFieldId
                if (!isYork && offset > 0) {
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
    // 旧版多选泵读取方法，保留用于向下兼容，不删除
    fun getPumps(storageKey: String, defaultValue: String): List<String> {
        val raw = prefs.getString(storageKey, defaultValue) ?: defaultValue
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    // 旧版多选泵保存方法，保留用于向下兼容，不删除
    fun savePumps(storageKey: String, pumps: List<String>) {
        prefs.edit().putString(storageKey, pumps.joinToString(",")).apply()
    }
    // 【本次重构改动点】获取指定机组绑定的固定泵列表
    // 页面渲染冷冻泵行时调用此方法，仅返回该机组专属的2台泵
    fun getFixedPumpsForItem(storageKey: String): List<String> {
        return fixedPumpMapping[storageKey] ?: emptyList()
    }
    // 【本次重构改动点】判断指定泵是否已勾选
    // 行内复选框渲染时调用，回显历史勾选状态
    fun isPumpSelected(storageKey: String, pumpName: String): Boolean {
        // 兼容旧版存储：如果该storageKey从未写入过，使用默认值
        val defaultValue = allItems.find { it.storageKey == storageKey }?.defaultValue ?: ""
        val currentPumps = getPumps(storageKey, defaultValue)
        return currentPumps.contains(pumpName)
    }
    // 【本次重构改动点】设置单个泵的勾选状态，实时持久化保存
    // 行内复选框状态变更时调用，勾选/取消勾选后立即写入本地缓存，无需二次确认
    fun setPumpSelected(storageKey: String, pumpName: String, isSelected: Boolean) {
        val defaultValue = allItems.find { it.storageKey == storageKey }?.defaultValue ?: ""
        val currentPumps = getPumps(storageKey, defaultValue).toMutableList()
        if (isSelected && !currentPumps.contains(pumpName)) {
            // 勾选：添加泵到列表
            currentPumps.add(pumpName)
        } else if (!isSelected) {
            // 取消勾选：从列表中移除泵
            currentPumps.remove(pumpName)
        }
        // 立即持久化保存
        savePumps(storageKey, currentPumps)
    }
}
