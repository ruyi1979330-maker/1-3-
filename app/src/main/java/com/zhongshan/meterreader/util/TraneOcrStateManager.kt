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
    // 触发比例：1.0 = 全部字段锁定才触发（向后兼容默认值）；
    // <1.0（如 0.8）= 已锁定字段比例达到该值即触发，用于字段较多的机组（约克单屏 13 字段）。
    private var triggerRatio: Float = 1.0f

    fun init(
        requiredFields: List<String>,
        callback: (Map<String, String>) -> Unit,
        triggerRatio: Float = 1.0f
    ) {
        this.requiredFields = requiredFields
        // 防御性约束：仅接受 (0,1]，避免传 0 或负数导致永远不触发
        this.triggerRatio = if (triggerRatio > 0f && triggerRatio <= 1f) triggerRatio else 1.0f
        this.onSuccessCallback = callback
        reset()
        DebugLogger.log("StateMach-Debug", "状态机初始化，必需字段数=${requiredFields.size}, 触发比例=${this.triggerRatio}, 必需字段: $requiredFields")
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

        // 触发判定：
        // - triggerRatio == 1.0：要求所有必需字段全部锁定（向后兼容，screw 机组沿用此行为）。
        // - triggerRatio < 1.0：已锁定字段数 / 必需字段数 >= triggerRatio 即触发，
        //   用于字段较多的机组（约克 13 字段单屏，OCR 难以保证全部稳定锁定）。
        // requiredFields 为空时不应触发（init 时不该为空，但作防御）。
        val shouldTrigger = if (requiredFields.isEmpty()) {
            false
        } else if (triggerRatio >= 1.0f) {
            lockedFields.keys.containsAll(requiredFields)
        } else {
            val lockedInRequired = requiredFields.count { it in lockedFields }
            lockedInRequired.toFloat() / requiredFields.size >= triggerRatio
        }

        if (shouldTrigger) {
            DebugLogger.log("StateMach-Debug", "触发条件已满足，触发成功回调！(已锁定=${lockedFields.size}/${requiredFields.size}, 比例=$triggerRatio)")
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
