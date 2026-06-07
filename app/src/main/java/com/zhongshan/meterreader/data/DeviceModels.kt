package com.zhongshan.meterreader.data

data class RoiRegion(
    val xPercent: Float,
    val yPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float
)

data class OcrField(
    val label: String,
    val formFieldId: String,
    val roi: RoiRegion
)

data class ScreenTemplate(
    val screenName: String,
    val fields: List<OcrField>
)

data class DeviceTemplate(
    val displayName: String,
    val machineId: String,
    val roomId: Int,
    val formUrl: String,
    val screens: List<ScreenTemplate>,
    val pumpFieldIds: List<String> = emptyList(),
    val isHeatExchanger: Boolean = false
)