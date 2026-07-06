// 文件名: WebViewActivity.kt
package com.zhongshan.meterreader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import com.zhongshan.meterreader.databinding.ActivityWebviewBinding
import java.lang.ref.WeakReference

class WebViewActivity : AppCompatActivity() {

    companion object {
        private const val WEB_LOAD_TIMEOUT_MS = 20_000L

        // 螺杆机组填充脚本（基于用户提供的DOM方案 + 异步Tab切换）
        private const val JS_FILL_SCREW = """
(function() {
    function findFormItemByLabel(labelTitle) {
        var items = document.querySelectorAll('.customFormItem');
        for (var i = 0; i < items.length; i++) {
            var label = items[i].querySelector('.controlLabelName[title="' + labelTitle + '"]');
            if (label) return items[i];
        }
        return null;
    }

    function isCheckboxChecked(checkboxLabel) {
        return checkboxLabel.querySelector('.Checkbox-box .icon-ok') !== null;
    }

    // 等待元素出现
    function waitForElement(selector, timeout, callback) {
        var start = Date.now();
        function check() {
            var el = document.querySelector(selector);
            if (el) { callback(el); return; }
            if (Date.now() - start < timeout) {
                setTimeout(check, 200);
            } else {
                callback(null);
            }
        }
        check();
    }

    function switchTabAsync(tabTitle, callback) {
        var tabSelector = '.sectionTabItem[title="' + tabTitle + '"]';
        waitForElement(tabSelector, 5000, function(tab) {
            if (!tab) {
                callback(false);
                return;
            }
            if (!tab.classList.contains('active')) tab.click();
            // 再等一小段时间确保内容渲染
            setTimeout(function() {
                waitForElement('.customFormItem', 5000, function(el) {
                    callback(el != null);
                });
            }, 200);
        });
    }

    function fillNumberInput(labelTitle, value) {
        var formItem = findFormItemByLabel(labelTitle);
        if (!formItem) return false;
        var inputBox = formItem.querySelector('.customFormControlBox');
        if (!inputBox) return false;
        inputBox.click();
        var input = formItem.querySelector('input');
        if (!input) {
            var textSpan = inputBox.querySelector('.ellipsis:not(.Font13)');
            if (textSpan) {
                textSpan.textContent = value;
                textSpan.classList.remove('Gray_bd');
                return true;
            }
            return false;
        }
        input.value = value;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
        input.blur();
        return true;
    }

    function checkPumpByGroup(groupTitle, pumpNames) {
        var groupItem = findFormItemByLabel(groupTitle);
        if (!groupItem) return 0;
        var count = 0;
        for (var i = 0; i < pumpNames.length; i++) {
            if (pumpNames[i] === '全选') continue;
            var checkbox = groupItem.querySelector('label.ming.Checkbox[title="' + pumpNames[i] + '"]');
            if (checkbox && !isCheckboxChecked(checkbox)) {
                checkbox.click();
                count++;
            }
        }
        return count;
    }

    function fillTextarea(labelTitle, text) {
        var formItem = findFormItemByLabel(labelTitle);
        if (!formItem) return false;
        var textarea = formItem.querySelector('textarea');
        if (!textarea) return false;
        textarea.value = text;
        textarea.dispatchEvent(new Event('input', { bubbles: true }));
        textarea.dispatchEvent(new Event('change', { bubbles: true }));
        return true;
    }

    function doFill(data, result) {
        if (data.operator) {
            var formItem = findFormItemByLabel('螺杆机组操作员');
            if (formItem) {
                var selectInput = formItem.querySelector('input');
                if (selectInput) {
                    selectInput.value = data.operator;
                    selectInput.dispatchEvent(new Event('input', { bubbles: true }));
                    result.success.push('螺杆机组操作员');
                } else result.failed.push('螺杆机组操作员');
            } else result.failed.push('螺杆机组操作员');
        }

        var units = [
            { no: 1, prefix: '1#', data: data.unit1 },
            { no: 2, prefix: '2#', data: data.unit2 },
            { no: 3, prefix: '3#', data: data.unit3 }
        ];
        var fields = [
            { key: 'evapInTemp', suffix: '蒸发器进口 水温' },
            { key: 'evapOutTemp', suffix: '蒸发器出口 水温' },
            { key: 'evapInPressure', suffix: '蒸发器进口 水压' },
            { key: 'evapOutPressure', suffix: '蒸发器出口 水压' },
            { key: 'evapRefPressure', suffix: '蒸发器 冷媒压力' },
            { key: 'evapTemp', suffix: '蒸发器 蒸发温度' },
            { key: 'condInTemp', suffix: '冷凝器进口 水温' },
            { key: 'condOutTemp', suffix: '冷凝器出口 水温' },
            { key: 'condInPressure', suffix: '冷凝器进口 水压' },
            { key: 'condOutPressure', suffix: '冷凝器出口 水压' },
            { key: 'condRefPressure', suffix: '冷凝器 冷媒压力' },
            { key: 'condTemp', suffix: '冷凝器 冷凝温度' },
            { key: 'compOilPressure', suffix: '压缩机 油压' },
            { key: 'compDischargeTemp', suffix: '压缩机 排出口温度' },
            { key: 'motorCurrent', suffix: '电机电流' },
            { key: 'hostLoad', suffix: ' 主机负载' }
        ];

        for (var u = 0; u < units.length; u++) {
            var unit = units[u];
            if (!unit.data) continue;
            for (var f = 0; f < fields.length; f++) {
                var field = fields[f];
                var val = unit.data[field.key];
                if (val === undefined || val === null) continue;
                var labelTitle = unit.prefix + field.suffix;
                if (fillNumberInput(labelTitle, val)) {
                    result.success.push(labelTitle);
                } else {
                    result.failed.push(labelTitle);
                }
            }
            if (unit.data.pumps && Array.isArray(unit.data.pumps)) {
                var groupTitle = unit.prefix + '机组冷冻泵';
                result.pumpsChecked += checkPumpByGroup(groupTitle, unit.data.pumps);
            }
            if (unit.data.remark) {
                var remarkTitle = unit.prefix + '螺杆机组备注';
                if (fillTextarea(remarkTitle, unit.data.remark)) {
                    result.success.push(remarkTitle);
                } else {
                    result.failed.push(remarkTitle);
                }
            }
        }
        if (window.AndroidBridge && window.AndroidBridge.onFillComplete) {
            AndroidBridge.onFillComplete(result.success.length);
        }
    }

    window.fillScrewUnitForm = function(data) {
        var result = { success: [], failed: [], pumpsChecked: 0 };
        switchTabAsync('螺杆机组（特灵）', function(ok) {
            if (ok) {
                doFill(data, result);
            } else {
                result.failed.push('切换标签页失败');
                if (window.AndroidBridge && window.AndroidBridge.onFillComplete) {
                    AndroidBridge.onFillComplete(0);
                }
            }
        });
        return JSON.stringify(result);
    };
})();
"""

        // 板交填充脚本（同样基于DOM方案）
        private const val JS_FILL_PLATE = """
(function() {
    function findFormItemByLabel(labelTitle) {
        var items = document.querySelectorAll('.customFormItem');
        for (var i = 0; i < items.length; i++) {
            var label = items[i].querySelector('.controlLabelName[title="' + labelTitle + '"]');
            if (label) return items[i];
        }
        return null;
    }

    function waitForElement(selector, timeout, callback) {
        var start = Date.now();
        function check() {
            var el = document.querySelector(selector);
            if (el) { callback(el); return; }
            if (Date.now() - start < timeout) {
                setTimeout(check, 200);
            } else {
                callback(null);
            }
        }
        check();
    }

    function switchTabAsync(tabTitle, callback) {
        var tabSelector = '.sectionTabItem[title="' + tabTitle + '"]';
        waitForElement(tabSelector, 5000, function(tab) {
            if (!tab) {
                callback(false);
                return;
            }
            if (!tab.classList.contains('active')) tab.click();
            setTimeout(function() {
                waitForElement('.customFormItem', 5000, function(el) {
                    callback(el != null);
                });
            }, 200);
        });
    }

    function fillNumberInput(labelTitle, value) {
        var formItem = findFormItemByLabel(labelTitle);
        if (!formItem) return false;
        var inputBox = formItem.querySelector('.customFormControlBox');
        if (!inputBox) return false;
        inputBox.click();
        var input = formItem.querySelector('input');
        if (!input) {
            var textSpan = inputBox.querySelector('.ellipsis:not(.Font13)');
            if (textSpan) {
                textSpan.textContent = value;
                textSpan.classList.remove('Gray_bd');
                return true;
            }
            return false;
        }
        input.value = value;
        input.dispatchEvent(new Event('input', { bubbles: true }));
        input.dispatchEvent(new Event('change', { bubbles: true }));
        input.blur();
        return true;
    }

    function fillTextarea(labelTitle, text) {
        var formItem = findFormItemByLabel(labelTitle);
        if (!formItem) return false;
        var textarea = formItem.querySelector('textarea');
        if (!textarea) return false;
        textarea.value = text;
        textarea.dispatchEvent(new Event('input', { bubbles: true }));
        textarea.dispatchEvent(new Event('change', { bubbles: true }));
        return true;
    }

    function doFill(data, result) {
        var groups = data.plateGroups;
        if (!groups || !Array.isArray(groups)) return;
        for (var g = 0; g < groups.length; g++) {
            var group = groups[g];
            if (!group.groupTitle || !group.fields) continue;
            var fields = group.fields;
            var map = {
                'inTemp': ' 进水温度',
                'outTemp': ' 出水温度',
                'inPressure': ' 进水压力',
                'outPressure': ' 出水压力',
                'steamPressure': ' 蒸汽压力',
                'pumpCurrent': ' 水泵电流'
            };
            for (var key in map) {
                if (fields[key] !== null && fields[key] !== undefined) {
                    var title = group.groupTitle + map[key];
                    if (fillNumberInput(title, fields[key])) {
                        result.success.push(title);
                    } else {
                        result.failed.push(title);
                    }
                }
            }
            if (fields.remark) {
                var remarkTitle = group.groupTitle + ' 备注';
                if (fillTextarea(remarkTitle, fields.remark)) {
                    result.success.push(remarkTitle);
                } else {
                    result.failed.push(remarkTitle);
                }
            }
        }
        if (window.AndroidBridge && window.AndroidBridge.onFillComplete) {
            AndroidBridge.onFillComplete(result.success.length);
        }
    }

    window.fillPlateForm = function(data) {
        var result = { success: [], failed: [] };
        switchTabAsync('板交', function(ok) {
            if (ok) {
                doFill(data, result);
            } else {
                result.failed.push('切换标签页失败');
                if (window.AndroidBridge && window.AndroidBridge.onFillComplete) {
                    AndroidBridge.onFillComplete(0);
                }
            }
        });
        return JSON.stringify(result);
    };
})();
"""
    }

    internal lateinit var binding: ActivityWebviewBinding
    private var targetUrl   = ""
    private var fillJsPayload = ""
    private var fillDataJson: String? = null
    private var fillScriptType: String? = null

    private val timeoutHandler = Handler(Looper.getMainLooper())

    class SafeWebBridge(activity: WebViewActivity) {
        private val ref = WeakReference(activity)
        @JavascriptInterface
        fun onFillComplete(count: Int) {
            ref.get()?.runOnUiThread {
                val act = ref.get() ?: return@runOnUiThread
                DebugLogger.log("WebView", "JS 报告填表完成，成功填充 $count 个字段")
                act.binding.tvStatusBanner.visibility = View.VISIBLE
                if (count > 0) {
                    act.binding.tvStatusBanner.text = "✅ 自动填表引擎就绪！已成功秒填 $count 个数据。"
                } else {
                    act.binding.tvStatusBanner.text = "⏳ 等待表单或对应机组标签页渲染中，数据已托管…"
                }
            }
        }
        @JavascriptInterface
        fun log(msg: String) {
            Log.d("WebViewJS", msg)
            DebugLogger.log("WebView-JS", msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        WebView.setWebContentsDebuggingEnabled(true)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetUrl = intent.getStringExtra("EXTRA_URL") ?: ""
        fillDataJson = intent.getStringExtra("EXTRA_FILL_DATA_JSON")
        fillScriptType = intent.getStringExtra("EXTRA_FILL_TYPE")

        if (fillDataJson == null) {
            val keys    = intent.getStringArrayExtra("EXTRA_KEYS")    ?: emptyArray()
            val values  = intent.getStringArrayExtra("EXTRA_VALUES")  ?: emptyArray()
            val pumpIds = intent.getStringArrayExtra("EXTRA_PUMP_IDS")?: emptyArray()
            fillJsPayload = compileFillJs(keys, values, pumpIds)
        }

        initWebView()
        if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
    }

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    private fun initWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled  = true
            domStorageEnabled  = true
            mixedContentMode   = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        binding.webView.addJavascriptInterface(SafeWebBridge(this), "AndroidBridge")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility      = View.VISIBLE
                binding.tvStatusBanner.visibility   = View.VISIBLE
                binding.tvStatusBanner.text = "⏳ 正在连接数据中心，请稍候…"
                timeoutHandler.postDelayed({
                    binding.progressBar.visibility    = View.GONE
                    binding.webView.stopLoading()
                    binding.layoutNetworkError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text = "网络超时，请检查信号后重试。"
                    binding.tvStatusBanner.visibility = View.GONE
                }, WEB_LOAD_TIMEOUT_MS)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                timeoutHandler.removeCallbacksAndMessages(null)
                binding.progressBar.visibility = View.GONE
                val host = runCatching { android.net.Uri.parse(url ?: "").host }.getOrNull() ?: ""
                if (host.contains("zs-hospital") || url?.contains("zs-hospital") == true || host.isNotEmpty()) {
                    binding.tvStatusBanner.visibility = View.VISIBLE
                    binding.tvStatusBanner.text = "🎉 连接成功！引擎已启动..."

                    if (fillDataJson != null) {
                        val jsScript = when (fillScriptType) {
                            "screw" -> JS_FILL_SCREW
                            "plate" -> JS_FILL_PLATE
                            else -> JS_FILL_SCREW
                        }
                        view?.evaluateJavascript(jsScript, null)
                        view?.postDelayed({
                            val functionName = if (fillScriptType == "plate") "fillPlateForm" else "fillScrewUnitForm"
                            val jsCall = "$functionName($fillDataJson)"
                            view.evaluateJavascript(jsCall) { result ->
                                DebugLogger.log("WebView", "填充触发: $result")
                            }
                        }, 1500)
                    } else {
                        view?.evaluateJavascript(fillJsPayload, null)
                    }
                }
            }

            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame == true) {
                    timeoutHandler.removeCallbacksAndMessages(null)
                    binding.progressBar.visibility        = View.GONE
                    binding.layoutNetworkError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text               = "加载失败，请检查是否连接医院内网 Wi-Fi。"
                    binding.tvStatusBanner.visibility     = View.GONE
                }
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }

        binding.btnRetryNetwork.setOnClickListener {
            binding.layoutNetworkError.visibility = View.GONE
            if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
        }
    }

    private fun compileFillJs(keys: Array<String>, values: Array<String>, pumpIds: Array<String>): String {
        val sb = StringBuilder()
        sb.append("(function(){\n")
        sb.append("if(window.__ocrFillEngineLoaded) return;\n")
        sb.append("window.__ocrFillEngineLoaded = true;\n")
        sb.append("})();")
        return sb.toString()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        binding.webView.removeJavascriptInterface("AndroidBridge")
        binding.webView.apply { loadUrl("about:blank"); stopLoading(); clearHistory(); destroy() }
        super.onDestroy()
    }
}
