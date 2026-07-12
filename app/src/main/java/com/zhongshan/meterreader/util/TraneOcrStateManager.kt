	// 文件名: TraneOcrStateManager.kt
	package com.zhongshan.meterreader
	import java.util.LinkedList
	object TraneOcrStateManager {
	    private const val WINDOW_SIZE = 5
	    private const val SUCCESS_THRESHOLD = 3
	    private val windowData = LinkedList<Map<String, String>>()
	    private val lockedFields = mutableMapOf<String, String>()
	    private var onSuccessCallback: ((Map<String, String>) -> Unit)? = null
	    private var requiredFields: List<String> = emptyList()
	    fun init(requiredFields: List<String>, callback: (Map<String, String>) -> Unit) {
	        this.requiredFields = requiredFields
	        this.onSuccessCallback = callback
	        reset()
	    }
	    fun reset() {
	        windowData.clear()
	        lockedFields.clear()
	    }
	    fun submitFrame(data: Map<String, String>) {
	        if (requiredFields.isEmpty()) return
	        // 1. 业务前置校验
	        val validatedData = mutableMapOf<String, String>()
	        for ((key, value) in data) {
	            if (isValid(key, value)) {
	                validatedData[key] = value
	            }
	        }
	        // 2. 加入滑动窗口
	        windowData.addLast(validatedData)
	        if (windowData.size > WINDOW_SIZE) {
	            windowData.removeFirst()
	        }
	        // 3. 统计并检查锁定状态
	        val voteCount = mutableMapOf<Pair<String, String>, Int>()
	        for (frame in windowData) {
	            for ((key, value) in frame) {
	                val k = Pair(key, value)
	                voteCount[k] = (voteCount[k] ?: 0) + 1
	            }
	        }
	        for ((pair, count) in voteCount) {
	            if (count >= SUCCESS_THRESHOLD) {
	                lockedFields[pair.first] = pair.second
	            }
	        }
	        // 4. 检查是否全部必需字段已锁定
	        if (lockedFields.keys.containsAll(requiredFields)) {
	            onSuccessCallback?.invoke(lockedFields.toMap())
	            reset()
	        }
	    }
	    private fun isValid(key: String, value: String): Boolean {
	        val match = Regex("""\d{1,4}(\.\d{1,2})?""").find(value) ?: return false
	        if (match.value != value) return false
	        val num = value.toFloatOrNull() ?: return false
	        val label = if (key.contains("|")) key.split("|")[1] else ""
	        return when {
	            label.contains("温度") -> num in 0f..150f
	            label.contains("压力") -> num in 0f..4000f
	            label.contains("电流") -> num in 0f..2000f
	            label.contains("负载") || label.contains("%RLA") -> num in 0f..150f
	            else -> true
	        }
	    }
	}
