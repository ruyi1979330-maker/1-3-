package com.zhongshan.meterreader.util

class TraneOcrStateManager(
    private val templateConfig: MeterTemplateConfig,
    private val windowSize: Int = 5,
    private val minHitCount: Int = 3
) {
    private val fieldHistory = mutableMapOf<String, MutableList<String>>()
    private val requiredFields = templateConfig.roiList.map { it.fieldKey }.toSet()

    fun pushFrame(frameResult: Map<String, String>): Boolean {
        for (field in requiredFields) {
            val value = frameResult[field] ?: continue
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

    fun getFinalResult(): Map<String, String> {
        return requiredFields.associateWith { field ->
            val history = fieldHistory[field] ?: return@associateWith ""
            history.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
        }.filterValues { it.isNotEmpty() }
    }

    fun reset() {
        fieldHistory.clear()
    }

    private fun isValueValid(field: String, value: String): Boolean {
        val num = value.toFloatOrNull() ?: return false
        val range = templateConfig.validRangeMap[field] ?: return num >= 0f
        return num in range
    }
}
