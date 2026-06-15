package com.zhongshan.meterreader

import com.zhongshan.meterreader.util.OCREngine.OcrLine
// ... 其他原有 import ...

class OCRFacade {
    
    // ... 原有代码 ...

    fun processOcrResult(ocrLines: List<OcrLine>, machineId: String) {
        // 【修复点 1】：将底层的 OcrLine 映射为解耦的 OcrTextBlock
        // 注意：请根据您 OcrLine 的实际属性名调整 y 和 x 的获取方式
        // 常见属性：line.y, line.top, line.boundingBox.top, line.frame.top
        val textBlocks = ocrLines.map { line ->
            OcrTextBlock(
                text = line.text,
                // 安全获取 Y 坐标：优先尝试 boundingBox.top，其次尝试 y，兜底为 0
                y = try { line.boundingBox?.top ?: line.y } catch (e: Exception) { 0 },
                x = try { line.boundingBox?.left ?: line.x } catch (e: Exception) { 0 }
            )
        }

        val rules = DeviceOcrStrategy.getYorkCentrifugalRules(machineId)
        
        // 【修复点 2】：传入转换后的 textBlocks
        val extractedData = DeviceOcrStrategy.extractData(textBlocks, rules)

        if (extractedData.isNotEmpty()) {
            // ... 原有的 JSON 组装与 WebView 跳转逻辑 ...
            val jsonArray = org.json.JSONArray()
            for (rule in rules) {
                val value = extractedData[rule.fieldId]
                if (value != null) {
                    val jsonObj = org.json.JSONObject().apply {
                        put("fieldId", rule.fieldId)
                        put("value", value)
                        put("formLabel", rule.formLabel)
                        put("tabName", rule.tabName)
                    }
                    jsonArray.put(jsonObj)
                }
            }
            
            // 跳转 WebView (确保 WebViewActivity 的包名也是 com.zhongshan.meterreader)
            // val intent = Intent(context, WebViewActivity::class.java)...
        }
    }
}
