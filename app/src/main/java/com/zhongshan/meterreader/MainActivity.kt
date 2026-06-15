package com.example.ocrapp.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.ocrapp.strategy.DeviceOcrStrategy
import com.example.ocrapp.strategy.OcrBlock
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private var currentScreenIndex = 0
    private val machineId = "machine_01" // 示例

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView(R.layout.activity_main)
    }

    /**
     * 处理 OCR 识别结果并跳转表单
     */
    fun processOcrResult(ocrBlocks: List<OcrBlock>) {
        val rules = DeviceOcrStrategy.getYorkCentrifugalRules(machineId)
        val extractedData = DeviceOcrStrategy.extractData(ocrBlocks, rules)

        if (extractedData.isNotEmpty()) {
            // 构建传递给 WebView 的 JSON 数据
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

            // 跳转 WebView 进行填表
            val intent = Intent(this, WebViewActivity::class.java).apply {
                putExtra("URL", "https://your-form-system-url.com/machine/1")
                putExtra("FORM_DATA", jsonArray.toString())
            }
            startActivity(intent)

            // 处理多屏采集逻辑
            val totalScreens = DeviceOcrStrategy.totalScreens(machineId)
            if (currentScreenIndex < totalScreens - 1) {
                currentScreenIndex++
                Toast.makeText(this, "第${currentScreenIndex}/${totalScreens}屏采集成功，请拍下一屏", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "设备全部数据已采集完成！", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "未识别到有效数据，请调整拍摄角度", Toast.LENGTH_SHORT).show()
        }
    }
}
