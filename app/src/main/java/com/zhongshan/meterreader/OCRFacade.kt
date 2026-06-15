package com.zhongshan.meterreader

import android.content.Context
import android.content.Intent
// 请保留您原有的其他 import (如 MLKit 的 Text, OcrEngine 等)
import org.json.JSONArray
import org.json.JSONObject

class OCRFacade(private val context: Context) {
    
    // ... 您原有的初始化和其他代码 ...

    /**
     * 处理 OCR 识别结果
     * 注意：参数类型请根据您实际的 MLKit 回调类型调整（如 Text.Line 或自定义包装类）
     */
    fun processOcrResult(ocrLines: List<Any>, machineId: String) {
        // 【核心修复】：针对 MLKit 的 boundingBox 进行安全反射/类型转换提取坐标
        val textBlocks = ocrLines.mapNotNull { line ->
            try {
                // 尝试获取 text 属性
                val text = line.javaClass.getMethod("getText").invoke(line) as? String ?: return@mapNotNull null
                
                // 尝试获取 boundingBox (MLKit 标准属性)
                var y = 0
                var x = 0
                try {
                    val bbox = line.javaClass.getMethod("getBoundingBox").invoke(line)
                    if (bbox != null) {
                        y = bbox.javaClass.getMethod("top").invoke(bbox) as? Int ?: 0
                        x = bbox.javaClass.getMethod("left").invoke(bbox) as? Int ?: 0
                    }
                } catch (e: Exception) {
                    // 兜底：如果不是 MLKit 标准类，尝试直接获取 y/x 或 top/left
                    y = try { line.javaClass.getMethod("getTop").invoke(line) as? Int ?: 0 } catch (ex: Exception) { 0 }
                    x = try { line.javaClass.getMethod("getLeft").invoke(line) as? Int ?: 0 } catch (ex: Exception) { 0 }
                }

                OcrTextBlock(text = text, y = y, x = x)
            } catch (e: Exception) {
                null
            }
        }

        val rules = DeviceOcrStrategy.getYorkCentrifugalRules(machineId)
        val extractedData = DeviceOcrStrategy.extractData(textBlocks, rules)

        if (extractedData.isNotEmpty()) {
            val jsonArray = JSONArray()
            for (rule in rules) {
                val value = extractedData[rule.fieldId]
                if (value != null) {
                    val jsonObj = JSONObject().apply {
                        put("fieldId", rule.fieldId)
                        put("value", value)
                        put("formLabel", rule.formLabel)
                        put("tabName", rule.tabName)
                    }
                    jsonArray.put(jsonObj)
                }
            }
            
            // 跳转 WebViewActivity (注意：此处去掉了 .ui 子包)
            val intent = Intent(context, WebViewActivity::class.java).apply {
                putExtra("URL", "https://your-form-system-url.com/machine/1") // 替换为真实URL
                putExtra("FORM_DATA", jsonArray.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
