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
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WebViewActivity : AppCompatActivity() {
    companion object {
        private const val WEB_LOAD_TIMEOUT_MS = 20_000L
        private const val AUTO_SCAN_INTERVAL_MS = 1500L
    }

    internal lateinit var binding: ActivityWebviewBinding
    private var targetUrl = ""
    private var fillJsPayload = ""
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val pageLoadGeneration = AtomicInteger(0)
    private val isFillDone = AtomicBoolean(false)

    // 螺杆机组字段 -> 中文标签映射
    private val screwFieldLabelMap = mapOf(
        "evapInTemp" to "蒸发器进口水温",
        "evapOutTemp" to "蒸发器出口水温",
        "evapInPressure" to "蒸发器进口水压",
        "evapOutPressure" to "蒸发器出口水压",
        "evapRefPressure" to "蒸发器冷媒压力",
        "evapTemp" to "蒸发器蒸发温度",
        "condInTemp" to "冷凝器进口水温",
        "condOutTemp" to "冷凝器出口水温",
        "condInPressure" to "冷凝器进口水压",
        "condOutPressure" to "冷凝器出口水压",
        "condRefPressure" to "冷凝器冷媒压力",
        "condTemp" to "冷凝器冷凝温度",
        "compOilPressure" to "压缩机油压",
        "compDischargeTemp" to "压缩机排出口温度",
        "motorCurrent" to "电机电流",
        "hostLoad" to "主机负载",
        "remark" to "螺杆机组备注"
    )

    // 板交字段 -> 中文标签映射
    private val plateFieldLabelMap = mapOf(
        "inTemp" to "进水温度",
        "outTemp" to "出水温度",
        "inPressure" to "进水压力",
        "outPressure" to "出水压力",
        "steamPressure" to "蒸汽压力",
        "pumpCurrent" to "水泵电流",
        "remark" to "备注"
    )

    private fun String.esc(): String =
        replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace(" ", "")

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

        @JavascriptInterface
        fun domLog(msg: String) {
            Log.d("WebView-DOM", msg)
            DebugLogger.log("WebView-DOM", msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        WebView.setWebContentsDebuggingEnabled(true)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetUrl = intent.getStringExtra("EXTRA_URL") ?: ""
        val fillType = intent.getStringExtra("EXTRA_FILL_TYPE") ?: ""
        val fillDataJson = intent.getStringExtra("EXTRA_FILL_DATA_JSON") ?: ""
        val tabName = intent.getStringExtra("EXTRA_TAB_NAME") ?: ""

        DebugLogger.log("WebView", "收到填充类型: $fillType")
        DebugLogger.log("WebView", "收到填充数据: $fillDataJson")

        // 解析数据生成扁平化标签-值列表
        val (targetFields, pumpList) = parseFillData(fillType, fillDataJson)
        fillJsPayload = compileFillJs(targetFields, pumpList, tabName)

        initWebView()
        if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
    }

    /**
     * 解析结构化JSON，转换为扁平化的「标签-值」列表 + 冷冻泵列表
     */
    private fun parseFillData(fillType: String, jsonStr: String): Pair<List<Pair<String, String>>, List<String>> {
        val fields = mutableListOf<Pair<String, String>>()
        val pumps = mutableListOf<String>()

        if (jsonStr.isEmpty()) return Pair(fields, pumps)

        runCatching {
            val root = JSONObject(jsonStr)

            if (fillType == "screw") {
                // 解析螺杆机组数据：unit1 / unit2 / unit3
                listOf("unit1" to "1#", "unit2" to "2#", "unit3" to "3#").forEach { (unitKey, prefix) ->
                    if (root.has(unitKey)) {
                        val unitObj = root.getJSONObject(unitKey)
                        val keys = unitObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key == "pumps") {
                                val pumpArr = unitObj.getJSONArray("pumps")
                                for (i in 0 until pumpArr.length()) {
                                    pumps.add(pumpArr.getString(i))
                                }
                            } else {
                                val labelSuffix = screwFieldLabelMap[key] ?: continue
                                val fullLabel = prefix + labelSuffix
                                fields.add(Pair(fullLabel, unitObj.getString(key)))
                            }
                        }
                    }
                }
            } else if (fillType == "plate") {
                // 解析板交数据：plateGroups
                if (root.has("plateGroups")) {
                    val groups = root.getJSONArray("plateGroups")
                    for (i in 0 until groups.length()) {
                        val group = groups.getJSONObject(i)
                        val groupTitle = group.getString("groupTitle")
                        val fieldsObj = group.getJSONObject("fields")
                        val keys = fieldsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val labelSuffix = plateFieldLabelMap[key] ?: continue
                            val fullLabel = groupTitle + labelSuffix
                            fields.add(Pair(fullLabel, fieldsObj.getString(key)))
                        }
                    }
                }
            }
        }.onFailure {
            DebugLogger.log("WebView", "数据解析失败: ${it.message}")
        }

        DebugLogger.log("WebView", "解析后字段数: ${fields.size}, 冷冻泵数: ${pumps.size}")
        return Pair(fields, pumps)
    }

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    private fun initWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        binding.webView.addJavascriptInterface(SafeWebBridge(this), "AndroidBridge")
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                isFillDone.set(false)
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatusBanner.visibility = View.VISIBLE
                binding.tvStatusBanner.text = "⏳ 正在连接数据中心，请稍候…"
                val gen = pageLoadGeneration.incrementAndGet()
                timeoutHandler.postDelayed({
                    if (pageLoadGeneration.get() == gen) {
                        binding.progressBar.visibility = View.GONE
                        binding.webView.stopLoading()
                        binding.layoutNetworkError.visibility = View.VISIBLE
                        binding.tvErrorMsg.text = "网络超时，请检查信号后重试。"
                        binding.tvStatusBanner.visibility = View.GONE
                    }
                }, WEB_LOAD_TIMEOUT_MS)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoadGeneration.incrementAndGet()
                binding.progressBar.visibility = View.GONE
                val host = runCatching { android.net.Uri.parse(url ?: "").host }.getOrNull() ?: ""
                if (host.contains("zs-hospital") || url?.contains("zs-hospital") == true || host.isNotEmpty()) {
                    binding.tvStatusBanner.visibility = View.VISIBLE
                    binding.tvStatusBanner.text = "🎉 连接成功！引擎已启动..."
                    view?.evaluateJavascript(fillJsPayload, null)
                }
            }

            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame == true) {
                    pageLoadGeneration.incrementAndGet()
                    binding.progressBar.visibility = View.GONE
                    binding.layoutNetworkError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text = "加载失败，请检查是否连接医院内网 Wi-Fi。"
                    binding.tvStatusBanner.visibility = View.GONE
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }
        binding.btnRetryNetwork.setOnClickListener {
            binding.layoutNetworkError.visibility = View.GONE
            isFillDone.set(false)
            if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
        }
    }

    /**
     * 编译新版填表JS：基于标签文本匹配 + 真实输入框赋值 + 事件触发
     */
    private fun compileFillJs(
        fields: List<Pair<String, String>>,
        pumps: List<String>,
        targetTabName: String
    ): String {
        // 构造字段数据JS数组
        val fieldsJs = fields.joinToString(",\n") { (label, value) ->
            "{label:'${label.esc()}', value:'${value.esc()}'}"
        }
        // 构造冷冻泵JS数组
        val pumpsJs = pumps.joinToString(prefix = "[", postfix = "]") { "'${it.esc()}'" }

        return """
        (function(){
            if(window.__ocrFillEngineStarted) return;
            window.__ocrFillEngineStarted = true;
            
            var targetFields = [${fieldsJs}];
            var pumpItems = ${pumpsJs};
            var targetTab = '${targetTabName.esc()}';
            window.__ocrLastFilledCount = -1;

            // 去除所有空白字符，用于精准匹配
            function cleanText(text) {
                return (text || '').replace(/\s+/g, '').replace(/\u3000/g, '');
            }

            // 切换到目标标签页
            function switchToTargetTab() {
                if (!targetTab) return;
                var tabs = document.querySelectorAll('.sectionTabItem');
                for (var i = 0; i < tabs.length; i++) {
                    var tabText = cleanText(tabs[i].innerText);
                    if (tabText.indexOf(cleanText(targetTab)) > -1) {
                        if (tabs[i].className.indexOf('active') === -1) {
                            tabs[i].click();
                            AndroidBridge.log('已切换到标签页: ' + targetTab);
                        }
                        break;
                    }
                }
            }

            // 展开所有折叠分组（确保内部表单项渲染）
            function expandAllGroups() {
                var groupHeaders = document.querySelectorAll('.customFormItem[type="22"]');
                for (var i = 0; i < groupHeaders.length; i++) {
                    var arrow = groupHeaders[i].querySelector('.headerArrow');
                    if (arrow) {
                        // 未展开状态点击展开
                        if (arrow.querySelector('.icon-arrow-down-border') || !groupHeaders[i].classList.contains('expanded')) {
                            groupHeaders[i].click();
                        }
                    }
                }
            }

            // 给自定义输入框赋值并触发事件
            function setInputValue(controlBox, value) {
                if (!controlBox) return false;
                
                // 先点击激活输入框，让内部渲染真实input
                controlBox.click();
                
                // 查找真实input元素
                var input = controlBox.querySelector('input');
                if (input) {
                    input.value = value;
                    // 触发React/Vue数据绑定所需事件
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                    input.dispatchEvent(new Event('blur', { bubbles: true }));
                    return true;
                }

                // 查找textarea（备注字段）
                var textarea = controlBox.querySelector('textarea');
                if (textarea) {
                    textarea.value = value;
                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    textarea.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                }

                // 兜底：直接修改显示文本（不保证提交成功，但兼容极端情况）
                var displaySpan = controlBox.querySelector('.sc-jgwFWF, .WordBreak');
                if (displaySpan) {
                    displaySpan.innerText = value;
                    controlBox.dispatchEvent(new Event('input', { bubbles: true }));
                    controlBox.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                }
                return false;
            }

            // 勾选冷冻泵复选框
            function checkPump(pumpName) {
                var labels = document.querySelectorAll('label.ming.Checkbox');
                for (var i = 0; i < labels.length; i++) {
                    var title = labels[i].getAttribute('title') || '';
                    if (cleanText(title) === cleanText(pumpName)) {
                        var box = labels[i].querySelector('.Checkbox-box');
                        if (box && box.className.indexOf('Checkbox-checked') === -1) {
                            labels[i].click();
                            return true;
                        }
                        return false;
                    }
                }
                return false;
            }

            // 主扫描填表引擎
            function scanAndAutofillEngine() {
                try {
                    switchToTargetTab();
                    expandAllGroups();
                    
                    var filledCount = 0;
                    var formItems = document.querySelectorAll('.customFormItem');
                    if (formItems.length === 0) return;

                    // 填充普通数值/文本字段
                    for (var i = 0; i < targetFields.length; i++) {
                        var item = targetFields[i];
                        var targetLabel = cleanText(item.label);
                        var found = false;

                        for (var j = 0; j < formItems.length; j++) {
                            var labelEl = formItems[j].querySelector('.controlLabelName');
                            if (!labelEl) continue;
                            var labelText = cleanText(labelEl.innerText || labelEl.getAttribute('title'));
                            
                            if (labelText === targetLabel) {
                                var controlBox = formItems[j].querySelector('.customFormControlBox');
                                if (setInputValue(controlBox, item.value)) {
                                    filledCount++;
                                    AndroidBridge.log('已填充: ' + item.label + ' = ' + item.value);
                                }
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            AndroidBridge.log('未找到字段: ' + item.label);
                        }
                    }

                    // 勾选冷冻泵
                    for (var k = 0; k < pumpItems.length; k++) {
                        if (checkPump(pumpItems[k])) {
                            filledCount++;
                            AndroidBridge.log('已勾选冷冻泵: ' + pumpItems[k]);
                        }
                    }

                    // 数量变化时通知原生
                    if (filledCount !== window.__ocrLastFilledCount) {
                        window.__ocrLastFilledCount = filledCount;
                        if (window.AndroidBridge && AndroidBridge.onFillComplete) {
                            AndroidBridge.onFillComplete(filledCount);
                        }
                    }
                } catch (e) {
                    AndroidBridge.log('填表引擎异常: ' + e.message);
                }
            }

            // 启动轮询，等待DOM渲染完成后自动填充
            setInterval(scanAndAutofillEngine, ${AUTO_SCAN_INTERVAL_MS});
            scanAndAutofillEngine();
            
            // 1秒后收起键盘
            setTimeout(function(){ 
                if (document.activeElement) document.activeElement.blur(); 
            }, 1000);
        })();
        """.trimIndent()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        binding.webView.removeJavascriptInterface("AndroidBridge")
        binding.webView.apply { loadUrl("about:blank"); stopLoading(); clearHistory(); destroy() }
        super.onDestroy()
    }
}
