package com.example.ocrapp.strategy

import android.graphics.Rect
import kotlin.math.abs

/**
 * 字段规则定义
 * @param fieldId 字段唯一标识
 * @param keywords OCR 识别关键词变体
 * @param searchBelow 是否向下搜索数值
 * @param maxBelow 向下搜索的最大行数（基于Y坐标判定）
 * @param formLabel 表单页面上的中文标签（用于 JS 模糊匹配 DOM）
 * @param tabName 所属标签页名称（用于过滤填表）
 */
data class FieldRule(
    val fieldId: String,
    val keywords: List<String>,
    val searchBelow: Boolean,
    val maxBelow: Int,
    val formLabel: String,
    val tabName: String = "通用"
)

object DeviceOcrStrategy {

    // 数值提取正则：支持负数和小数
    private val numberRegex = Regex("(-?\\d+(\\.\\d+)?)")

    /**
     * 核心提取逻辑：基于空间坐标的 OCR 结果解析
     */
    fun extractData(ocrBlocks: List<OcrBlock>, rules: List<FieldRule>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        // 1. 严格按 Y 坐标（上到下），其次 X 坐标（左到右）排序
        val sortedBlocks = ocrBlocks.sortedWith(compareBy({ it.bbox.top }, { it.bbox.left }))

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
                    var lastY = anchorBlock.bbox.top
                    
                    for (i in anchorIndex + 1 until sortedBlocks.size) {
                        val block = sortedBlocks[i]
                        val currentY = block.bbox.top
                        
                        // 判定是否换行：Y坐标差距大于平均行高的一半（假设行高约30-50，阈值设为20）
                        if (currentY - lastY > 20) {
                            currentBelowCount++
                            lastY = currentY
                        }
                        
                        // 超过最大向下搜索行数，终止
                        if (currentBelowCount > rule.maxBelow) break

                        // 提取数字
                        val match = numberRegex.find(block.text)
                        if (match != null) {
                            // 简单过滤异常值（如温度不可能超过200或低于-100，可根据业务调整）
                            val value = match.value.toFloatOrNull()
                            if (value != null && value in -100f..200f) {
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
     * 获取特定机房的规则列表（示例：约克离心机）
     */
    fun getYorkCentrifugalRules(machineId: String): List<FieldRule> = listOf(
        // 冷冻水侧
        FieldRule("field_1_69", listOf("冷冻水温度", "冻水出水", "冷冻出水"), true, 5, "蒸发器 出水温度", "离心机组"),
        FieldRule("field_1_68", listOf("冷冻水返回", "冻水返回", "冷冻回水"), true, 5, "蒸发器 进水温度", "离心机组"),
        // 冷却水侧
        FieldRule("field_1_71", listOf("冷却水温度", "冷却出水"), true, 5, "冷凝器 出水温度", "离心机组"),
        FieldRule("field_1_70", listOf("冷却水返回", "冷却回水"), true, 5, "冷凝器 进水温度", "离心机组"),
        // 螺杆机示例
        FieldRule("field_2_11", listOf("排气温度", "排气"), true, 3, "排气温度", "螺杆机组"),
        FieldRule("field_2_12", listOf("吸气温度", "吸气"), true, 3, "吸气温度", "螺杆机组")
    )

    fun totalScreens(machineId: String): Int = 3 // 示例
}

// 模拟 OCR 块数据结构
data class OcrBlock(val text: String, val bbox: Rect)
