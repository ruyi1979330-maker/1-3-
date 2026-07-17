// 文件名: TraneOcrStateManager.kt
package com.zhongshan.meterreader

import com.zhongshan.meterreader.DebugLogger
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
        DebugLogger.log("StateMach-Debug", "状态机初始化，必需字段: $requiredFields")
    }

    fun reset() {
        windowData.clear()
        lockedFields.clear()
    }

    fun submitFrame(data: Map<String, String>) {
        if (requiredFields.isEmpty()) return

        val validatedData = mutableMapOf<String, String>()
        for ((key, value) in data) {
            if (isValid(key, value)) {
                validatedData[key] = value
            } else {
                DebugLogger.log("StateMach-Debug", "字段校验失败被剔除: $key = $value")
            }
        }

        DebugLogger.log("StateMach-Debug", "接收帧数据 (有效): $validatedData")

        windowData.addLast(validatedData)
        if (windowData.size > WINDOW_SIZE) {
            windowData.removeFirst()
        }

        val voteCount = mutableMapOf<Pair<String, String>, Int>()
        for (frame in windowData) {
            for ((key, value) in frame) {
                val k = Pair(key, value)
                voteCount[k] = (voteCount[k] ?: 0) + 1
            }
        }

        for ((pair, count) in voteCount) {
            if (count >= SUCCESS_THRESHOLD) {
                if (lockedFields[pair.first] != pair.second) {
                    DebugLogger.log("StateMach-Debug", "字段已锁定: ${pair.first} = ${pair.second} (票数: $count)")
                }
                lockedFields[pair.first] = pair.second
            }
        }

        DebugLogger.log("StateMach-Debug", "当前已锁定字段数: ${lockedFields.size}/${requiredFields.size}, 锁定内容: $lockedFields")

        if (lockedFields.keys.containsAll(requiredFields)) {
            DebugLogger.log("StateMach-Debug", "所有必需字段已锁定，触发成功回调！")
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
