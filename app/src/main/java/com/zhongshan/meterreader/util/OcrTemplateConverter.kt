package com.zhongshan.meterreader.util

import com.zhongshan.meterreader.data.DeviceTemplate

object OcrTemplateConverter {

    fun convert(template: DeviceTemplate, screenIndex: Int): MeterTemplateConfig {
        val roiConfigs = template.relativeConfigs
            .filter { it.screenIndex == screenIndex }
            .map { fieldConfig ->
                OcrRoiConfig(
                    fieldKey = fieldConfig.fieldKey,
                    fieldLabel = fieldConfig.label,
                    relativeRect = OcrRoiConfig.RectF(
                        left = fieldConfig.relativeLeft,
                        top = fieldConfig.relativeTop,
                        right = fieldConfig.relativeRight,
                        bottom = fieldConfig.relativeBottom
                    )
                )
            }

        val validRangeMap = roiConfigs.associate { config ->
            config.fieldKey to getValidRange(config.fieldLabel)
        }

        return MeterTemplateConfig(
            templateId = "${template.machineId}_screen_$screenIndex",
            roiList = roiConfigs,
            validRangeMap = validRangeMap
        )
    }

    private fun getValidRange(label: String): ClosedFloatingPointRange<Float> {
        return when {
            label.contains("温度") -> -10f..150f
            label.contains("压力") -> 0f..600f
            label.contains("电流") -> 0f..200f
            label.contains("负载") -> 0f..100f
            else -> 0f..999f
        }
    }
}
