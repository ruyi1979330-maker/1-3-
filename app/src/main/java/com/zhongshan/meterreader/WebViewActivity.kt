package com.zhongshan.meterreader

import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class WebViewActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var fillDataJson = ""
    private var fillType = ""
    private var tabName = ""
    private var lastFillCount = -1
    private var stableCount = 0
    private val MAX_STABLE_COUNT = 2
    private val POLL_INTERVAL = 1500L
    private val FILL_TIMEOUT = 30000L
    private var startTime = 0L
    private var isFillFinished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        val url = intent.getStringExtra("EXTRA_URL") ?: ""
        fillType = intent.getStringExtra("EXTRA_FILL_TYPE") ?: "screw"
        fillDataJson = intent.getStringExtra("EXTRA_FILL_DATA_JSON") ?: ""
        tabName = intent.getStringExtra("EXTRA_TAB_NAME") ?: ""

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("WebView", "页面加载完成，开始准备填充")
                startTime = System.currentTimeMillis()
                startFillPolling()
            }
        }

        if (url.isNotEmpty()) {
            webView.loadUrl(url)
        }
    }

    private fun startFillPolling() {
        if (isFillFinished) return
        webView.postDelayed({
            if (isFillFinished) return@postDelayed
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= FILL_TIMEOUT) {
                Log.d("WebView", "填充超时，停止轮询")
                isFillFinished = true
                return@postDelayed
            }
            executeFillJs()
            startFillPolling()
        }, POLL_INTERVAL)
    }

    private fun executeFillJs() {
        val js = buildFillJs()
        webView.evaluateJavascript(js) { result ->
            try {
                val count = result?.replace("\"", "")?.toIntOrNull() ?: 0
                Log.d("WebView", "本次填充数: $count")
                if (count == lastFillCount) {
                    stableCount++
                    if (stableCount >= MAX_STABLE_COUNT) {
                        isFillFinished = true
                        Log.d("WebView", "填充稳定，引擎自动停止轮询，最终填充数: $count")
                    }
                } else {
                    stableCount = 0
                    lastFillCount = count
                }
            } catch (e: Exception) {
                Log.e("WebView", "解析填充结果失败", e)
            }
        }
    }

    private fun buildFillJs(): String {
        return when (fillType) {
            "screw" -> buildScrewFillJs()
            "plate" -> buildPlateFillJs()
            else -> buildScrewFillJs()
        }
    }

    private fun buildScrewFillJs(): String {
        return """
        (function() {
            try {
                const fillData = $fillDataJson;
                const unitData = fillData.unit1 || {};
                const unitPrefix = "1#";
                let filledCount = 0;

                const fieldMap = {
                    evapInTemp: "蒸发器进口水温",
                    evapOutTemp: "蒸发器出口水温",
                    evapTemp: "蒸发器蒸发温度",
                    evapRefPressure: "蒸发器冷媒压力",
                    evapInPressure: "蒸发器进口水压",
                    evapOutPressure: "蒸发器出口水压",
                    condInTemp: "冷凝器进口水温",
                    condOutTemp: "冷凝器出口水温",
                    condTemp: "冷凝器冷凝温度",
                    condRefPressure: "冷凝器冷媒压力",
                    condInPressure: "冷凝器进口水压",
                    condOutPressure: "冷凝器出口水压",
                    compOilPressure: "压缩机油压",
                    compDischargeTemp: "压缩机排出口温度",
                    hostLoad: "主机负载",
                    motorCurrent: "电机电流",
                    remark: "螺杆机组备注"
                };

                // 匹配所有非复选/单选的输入框，兼容无type属性的默认输入框
                const inputs = document.querySelectorAll("input:not([type='checkbox']):not([type='radio']), textarea");
                
                for (const key in fieldMap) {
                    if (!unitData.hasOwnProperty(key)) continue;
                    const fieldName = unitPrefix + fieldMap[key];
                    for (let i = 0; i < inputs.length; i++) {
                        const el = inputs[i];
                        // 扩大父节点查找范围，兼容table、各类UI框架表单
                        const parent = el.closest("tr, td, li, .form-item, .field-item, .ant-form-item, .el-form-item, label, div");
                        if (!parent) continue;
                        const labelText = parent.textContent.trim();
                        if (labelText.indexOf(fieldName) !== -1) {
                            if (el.value !== unitData[key]) {
                                el.value = unitData[key];
                                el.dispatchEvent(new Event('input', { bubbles: true }));
                                el.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                            filledCount++;
                            break;
                        }
                    }
                }

                // 冷冻泵勾选：仅执行一次，避免轮询反复点击导致状态翻转
                const pumps = unitData.pumps || [];
                if (!window.__screwPumpsChecked && pumps.length > 0) {
                    let pumpCheckedCount = 0;
                    pumps.forEach(pumpName => {
                        let hasChecked = false;
                        const labels = document.querySelectorAll("label");
                        for (let i = 0; i < labels.length; i++) {
                            const label = labels[i];
                            const text = label.textContent.trim();
                            if (text === pumpName || text.indexOf(pumpName) !== -1) {
                                const checkbox = label.querySelector("input[type='checkbox']");
                                if (checkbox) {
                                    if (!checkbox.checked) {
                                        label.click();
                                    }
                                    hasChecked = true;
                                    break;
                                }
                            }
                        }
                        if (!hasChecked) {
                            const allCheckboxes = document.querySelectorAll("input[type='checkbox']");
                            for (let i = 0; i < allCheckboxes.length; i++) {
                                const cb = allCheckboxes[i];
                                const parentText = cb.parentElement ? cb.parentElement.textContent.trim() : "";
                                if (parentText.indexOf(pumpName) !== -1) {
                                    if (!cb.checked) {
                                        cb.checked = true;
                                        cb.dispatchEvent(new Event('change', { bubbles: true }));
                                        cb.dispatchEvent(new Event('input', { bubbles: true }));
                                    }
                                    hasChecked = true;
                                    break;
                                }
                            }
                        }
                        if (hasChecked) {
                            pumpCheckedCount++;
                        }
                    });
                    window.__screwPumpsChecked = true;
                    filledCount += pumpCheckedCount;
                }

                return filledCount;
            } catch(e) {
                console.error("螺杆填充脚本出错", e);
                return 0;
            }
        })();
        """.trimIndent()
    }

    private fun buildPlateFillJs(): String {
        return """
        (function() {
            try {
                const fillData = $fillDataJson;
                const groups = fillData.plateGroups || [];
                let filledCount = 0;

                // 匹配所有非复选/单选的输入框，兼容无type属性的默认输入框
                const inputs = document.querySelectorAll("input:not([type='checkbox']):not([type='radio']), textarea");

                groups.forEach(group => {
                    const groupTitle = group.groupTitle || "";
                    const fields = group.fields || {};

                    const fieldNameMap = {
                        inTemp: "进水温度",
                        outTemp: "出水温度",
                        inPressure: "进水压力",
                        outPressure: "出水压力",
                        steamPressure: "蒸汽压力",
                        pumpCurrent: "水泵电流",
                        remark: "备注"
                    };

                    for (const key in fieldNameMap) {
                        if (!fields.hasOwnProperty(key)) continue;
                        const fullFieldName = groupTitle + fieldNameMap[key];
                        for (let i = 0; i < inputs.length; i++) {
                            const el = inputs[i];
                            // 扩大父节点查找范围，兼容table、各类UI框架表单
                            const parent = el.closest("tr, td, li, .form-item, .field-item, .ant-form-item, .el-form-item, label, div");
                            if (!parent) continue;
                            const labelText = parent.textContent.trim();
                            if (labelText.indexOf(fullFieldName) !== -1) {
                                if (el.value !== fields[key]) {
                                    el.value = fields[key];
                                    el.dispatchEvent(new Event('input', { bubbles: true }));
                                    el.dispatchEvent(new Event('change', { bubbles: true }));
                                }
                                filledCount++;
                                break;
                            }
                        }
                    }
                });

                return filledCount;
            } catch(e) {
                console.error("板交填充脚本出错", e);
                return 0;
            }
        })();
        """.trimIndent()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }
}
