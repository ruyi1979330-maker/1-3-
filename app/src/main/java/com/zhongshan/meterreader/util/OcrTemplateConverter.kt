package com.zhongshan.meterreader.util

import com.zhongshan.meterreader.DeviceOcrStrategy
import com.zhongshan.meterreader.data.DeviceTemplate

object OcrTemplateConverter {

    fun convert(template: DeviceTemplate, screenIndex: Int): MeterTemplateConfig {
        // 从 DeviceOcrStrategy 获取当前屏的相对 ROI 配置
        val roiRelativeList = DeviceOcrStrategy.getRelativeRois(template.machineId, screenIndex)

        val roiConfigs = roiRelativeList.map { roi ->
            OcrRoiConfig(
                fieldKey = roi.fieldId,
                fieldLabel = roi.label,
                relativeRect = OcrRoiConfig.RectF(
                    left = roi.xStartPct,
                    top = roi.yStartPct,
                    right = roi.xEndPct,
                    bottom = roi.yEndPct
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
