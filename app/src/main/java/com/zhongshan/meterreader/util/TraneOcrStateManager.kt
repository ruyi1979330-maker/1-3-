	// ====================文件名：TraneOcrStateManager.kt 完整可直接复制代码====================
	// 项目：医院特灵冷水机组OCR抄表APP
	// 修改版本：V2.0 一级菜单固定冷冻泵直选重构
	// 改动标记：所有新增/修改逻辑标注 //【本次重构改动点】
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
	    // 【本次重构改动点】V2.0兼容性说明
	    // 本类负责OCR抄表字段识别与锁定，不直接读取冷冻泵勾选配置
	    // 冷冻泵预设配置由 PresetManager.getPumps() / getPresetsForMachine() 提供
	    // V2.0重构后这两个接口签名与返回格式完全不变，上层OCR抄表业务无感知
	    // TraneOcrStateManager 的 requiredFieldIds 和 onSuccess 回调机制保持不变
	    // 无需任何功能性代码修改，仅添加兼容性注释标记
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
