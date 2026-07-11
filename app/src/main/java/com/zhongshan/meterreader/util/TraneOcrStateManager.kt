// 文件名: TraneOcrStateManager.kt
package com.zhongshan.meterreader.util

import com.zhongshan.meterreader.DebugLogger

class TraneOcrStateManager(
    private val requiredFieldIds: List<String>,
    private val onSuccess: (Map<String, String>) -> Unit
) {
    private val windowSize = 5
    private val lockThreshold = 3
    
    private val slidingWindows = mutableMapOf<String, MutableList<String>>()
    private val lockedResults = mutableMapOf<String, String>()

    fun processFrameResults(frameData: Map<String, String>) {
        var hasNewLock = false

        for ((fieldId, rawValue) in frameData) {
            if (lockedResults.containsKey(fieldId)) continue 

            val cleanValue = extractAndValidate(fieldId, rawValue)
            if (cleanValue != null) {
                val window = slidingWindows.getOrPut(fieldId) { mutableListOf() }
                window.add(cleanValue)
                if (window.size > windowSize) {
                    window.removeAt(0)
                }

                val valueCounts = window.groupingBy { it }.eachCount()
                val bestValue = valueCounts.maxByOrNull { it.value }
                
                if (bestValue != null && bestValue.value >= lockThreshold) {
                    lockedResults[fieldId] = bestValue.key
                    hasNewLock = true
                    DebugLogger.log("StateManager", "字段锁定 [$fieldId] -> ${bestValue.key}")
                }
            }
        }

        if (hasNewLock) {
            checkCompletion()
        }
    }

    private fun extractAndValidate(fieldId: String, rawValue: String): String? {
        val normalizedText = rawValue.replace(",", ".").replace(":", ".")
        val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(normalizedText) ?: return null
        val valueStr = match.value
        val floatVal = valueStr.toFloatOrNull() ?: return null

        val isTemp = fieldId.contains("Temp") || fieldId.contains("温度") || fieldId.contains("水温")
        val isPressure = fieldId.contains("Pressure") || fieldId.contains("压力") || fieldId.contains("油压")
        val isCurrent = fieldId.contains("Current") || fieldId.contains("电流")
        val isLoad = fieldId.contains("Load") || fieldId.contains("负载") || fieldId.contains("RLA")

        when {
            isTemp -> if (floatVal !in -20f..150f) return null
            isPressure -> if (floatVal !in 0f..4000f) return null
            isCurrent -> if (floatVal !in 0f..2000f) return null
            isLoad -> if (floatVal !in 0f..150f) return null
            else -> if (floatVal !in 0f..9999f) return null
        }
        return valueStr
    }

    private fun checkCompletion() {
        val allLocked = requiredFieldIds.all { lockedResults.containsKey(it) }
        if (allLocked && requiredFieldIds.isNotEmpty()) {
            onSuccess(lockedResults.toMap())
            reset()
        }
    }

    fun reset() {
        slidingWindows.clear()
        lockedResults.clear()
    }
}
