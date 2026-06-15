package com.zhongshan.meterreader

import kotlin.math.abs

/**
 * 字段规则定义
 */
data class FieldRule(
    val fieldId: String,
    val keywords: List<String>,
    val searchBelow: Boolean,
    val maxBelow: Int,
    val formLabel: String,
    val tabName: String = "通用"
)

/**
 * 解耦的 OCR 文本块数据结构
 * 避免直接依赖特定 OCR 引擎的 OcrLine 实现，防止编译错误
 */
data class OcrTextBlock(val text: String, val y: Int, val x: Int)

object DeviceOcrStrategy {
    // 数值提取正则：支持负数和小数
    private val numberRegex = Regex("(-?\\d+(\\.\\d+)?)")

    /**
     * 核心提取逻辑：基于空间坐标的 OCR 结果解析
     */
    fun extractData(ocrBlocks: List<OcrTextBlock>, rules: List<FieldRule>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // 1. 严格按 Y 坐标（上到下），其次 X 坐标（左到右）排序
        val sortedBlocks = ocrBlocks.sortedWith(compareBy({ it.y }, { it.x }))

        for (rule in rules) {
            // 2. 查找包含关键词的锚点块
            val anchorIndex = sortedBlocks.indexOfFirst { block ->
                rule.keywords.any { kw -> block.text.contains(kw, ignoreCase = true) }
            }

            if (anchorIndex != -1) {
                val anchorBlock = sortedBlocks[anchorIndex]
                
                // 尝试直接从锚点文本中提取数字
                val directMatch = numberRegex.find(anchorBlock.text)
                if (directMatch != null && !rule.searchBelow) {
                    result[rule.fieldId] = directMatch.value
                    continue
                }

                // 3. 向下搜索策略 (searchBelow)
                if (rule.searchBelow) {
                    var currentBelowCount = 0
                    var lastY = anchorBlock.y
                    
                    for (i in anchorIndex + 1 until sortedBlocks.size) {
                        val block = sortedBlocks[i]
                        val currentY = block.y
                        
                        // 判定是否换行：Y坐标差距大于20（可根据实际图片分辨率调整）
                        if (currentY - lastY > 20) {
                            currentBelowCount++
                            lastY = currentY
                        }
                        
                        // 超过最大向下搜索行数，终止
                        if (currentBelowCount > rule.maxBelow) break

                        // 提取数字
                        val match = numberRegex.find(block.text)
                        if (match != null) {
                            val value = match.value.toFloatOrNull()
                            // 简单过滤异常值（如温度不可能超过300或低于-100）
                            if (value != null && value in -100f..300f) {
                                result[rule.fieldId] = match.value
                                break
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * 获取特定机房的规则列表（示例：约克离心机/螺杆机）
     */
    fun getYorkCentrifugalRules(machineId: String): List<FieldRule> = listOf(
        // 离心机组
        FieldRule("field_1_69", listOf("冷冻水温度", "冻水出水", "冷冻出水"), true, 5, "蒸发器 出水温度", "离心机组"),
        FieldRule("field_1_68", listOf("冷冻水返回", "冻水返回", "冷冻回水"), true, 5, "蒸发器 进水温度", "离心机组"),
        FieldRule("field_1_71", listOf("冷却水温度", "冷却出水"), true, 5, "冷凝器 出水温度", "离心机组"),
        FieldRule("field_1_70", listOf("冷却水返回", "冷却回水"), true, 5, "冷凝器 进水温度", "离心机组"),
        // 螺杆机组
        FieldRule("field_2_11", listOf("排气温度", "排气"), true, 3, "排气温度", "螺杆机组"),
        FieldRule("field_2_12", listOf("吸气温度", "吸气"), true, 3, "吸气温度", "螺杆机组")
    )

    fun totalScreens(machineId: String): Int = 3 
}
