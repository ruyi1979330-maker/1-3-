package com.zhongshan.meterreader

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
 */
data class OcrTextBlock(val text: String, val y: Int, val x: Int)

object DeviceOcrStrategy {
    private val numberRegex = Regex("(-?\\d+(\\.\\d+)?)")

    fun extractData(ocrBlocks: List<OcrTextBlock>, rules: List<FieldRule>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val sortedBlocks = ocrBlocks.sortedWith(compareBy({ it.y }, { it.x }))

        for (rule in rules) {
            val anchorIndex = sortedBlocks.indexOfFirst { block ->
                rule.keywords.any { kw -> block.text.contains(kw, ignoreCase = true) }
            }

            if (anchorIndex != -1) {
                val anchorBlock = sortedBlocks[anchorIndex]
                val directMatch = numberRegex.find(anchorBlock.text)
                if (directMatch != null && !rule.searchBelow) {
                    result[rule.fieldId] = directMatch.value
                    continue
                }

                if (rule.searchBelow) {
                    var currentBelowCount = 0
                    var lastY = anchorBlock.y
                    
                    for (i in anchorIndex + 1 until sortedBlocks.size) {
                        val block = sortedBlocks[i]
                        val currentY = block.y
                        
                        if (currentY - lastY > 20) {
                            currentBelowCount++
                            lastY = currentY
                        }
                        if (currentBelowCount > rule.maxBelow) break

                        val match = numberRegex.find(block.text)
                        if (match != null) {
                            val value = match.value.toFloatOrNull()
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

    fun getYorkCentrifugalRules(machineId: String): List<FieldRule> = listOf(
        FieldRule("field_1_69", listOf("冷冻水温度", "冻水出水", "冷冻出水"), true, 5, "蒸发器 出水温度", "离心机组"),
        FieldRule("field_1_68", listOf("冷冻水返回", "冻水返回", "冷冻回水"), true, 5, "蒸发器 进水温度", "离心机组"),
        FieldRule("field_1_71", listOf("冷却水温度", "冷却出水"), true, 5, "冷凝器 出水温度", "离心机组"),
        FieldRule("field_1_70", listOf("冷却水返回", "冷却回水"), true, 5, "冷凝器 进水温度", "离心机组"),
        FieldRule("field_2_11", listOf("排气温度", "排气"), true, 3, "排气温度", "螺杆机组"),
        FieldRule("field_2_12", listOf("吸气温度", "吸气"), true, 3, "吸气温度", "螺杆机组")
    )

    fun totalScreens(machineId: String): Int = 3 
}
