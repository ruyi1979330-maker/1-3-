package com.zhongshan.meterreader.data

/**
 * DeviceTemplate — 精简版（移除 screens/ROI 相关字段）
 *
 * screens 和 RoiRegion 已由 DeviceOcrStrategy 全图文字匹配方案替代，
 * 不再需要预设坐标。
 */
data class DeviceTemplate(
    val displayName: String,
    val machineId: String,
    val roomId: Int,
    val formUrl: String,
    val pumpFieldIds: List<String> = emptyList(),
    val isHeatExchanger: Boolean = false
)
