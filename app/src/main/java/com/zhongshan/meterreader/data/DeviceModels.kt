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
