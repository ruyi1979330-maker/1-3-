package com.zhongshan.meterreader.util

/**
 * 互审最终版：滑动窗口多帧一致性状态机
 * 特性：字段独立计数 + 3/5窗口命中规则 + 业务前置校验
 */
class TraneOcrStateManager(
    private val templateConfig: MeterTemplateConfig,
    private val windowSize: Int = 5,
    private val minHitCount: Int = 3
) {
    private val fieldHistory = mutableMapOf<String, MutableList<String>>()
    private val requiredFields = templateConfig.roiList.map { it.fieldKey }.toSet()

    /**
     * 推入一帧识别结果
     * @return true=所有字段已达成稳定共识，可触发成功
     */
    fun pushFrame(frameResult: Map<String, String>): Boolean {
        for (field in requiredFields) {
            val value = frameResult[field] ?: continue
            // 业务校验前置，无效值不进入投票
            if (!isValueValid(field, value)) continue
            
            val history = fieldHistory.getOrPut(field) { mutableListOf() }
            history.add(value)
            if (history.size > windowSize) history.removeAt(0)
        }

        return requiredFields.all { field ->
            val history = fieldHistory[field] ?: return@all false
            if (history.size < minHitCount) return@all false
            history.groupingBy { it }.eachCount().maxOf { it.value } >= minHitCount
        }
    }

    /** 获取最终稳定结果 */
    fun getFinalResult(): Map<String, String> {
        return requiredFields.associateWith { field ->
            val history = fieldHistory[field] ?: return@associateWith ""
            history.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
        }.filterValues { it.isNotEmpty() }
    }

    /** 重置状态，重新识别 */
    fun reset() {
        fieldHistory.clear()
    }

    private fun isValueValid(field: String, value: String): Boolean {
        val num = value.toFloatOrNull() ?: return false
        val range = templateConfig.validRangeMap[field] ?: return num >= 0f
        return num in range
    }
}
