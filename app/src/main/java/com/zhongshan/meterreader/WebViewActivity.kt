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
	import org.json.JSONArray
	import org.json.JSONObject
	import java.lang.ref.WeakReference
	import java.util.concurrent.atomic.AtomicBoolean
	import java.util.concurrent.atomic.AtomicInteger
	class WebViewActivity : AppCompatActivity() {
	    companion object {
	        private const val WEB_LOAD_TIMEOUT_MS = 60_000L
	        private const val AUTO_SCAN_INTERVAL_MS = 1500L
	        private const val MAX_IDLE_SCAN_COUNT = 3
	    }
	    internal lateinit var binding: ActivityWebviewBinding
	    private var targetUrl = ""
	    private var fillJsPayload = ""
	    private val timeoutHandler = Handler(Looper.getMainLooper())
	    private val pageLoadGeneration = AtomicInteger(0)
	    private val isFillDone = AtomicBoolean(false)
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
	    class SafeWebBridge(activity: WebViewActivity) {
	        private val ref = WeakReference(activity)
	        @JavascriptInterface
	        fun onFillComplete(count: Int) {
	            ref.get()?.runOnUiThread {
	                val act = ref.get() ?: return@runOnUiThread
	                DebugLogger.log("WebView", "JS 报告填表完成，成功填充 $count 个字段")
	                act.binding.tvStatusBanner.visibility = View.VISIBLE
	                if (count > 0) {
	                    act.isFillDone.set(true)
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
	        val (targetFields, pumpList) = parseFillData(fillType, fillDataJson)
	        fillJsPayload = compileFillJs(targetFields, pumpList, tabName)
	        initWebView()
	        // 修复Q2：每次进入时加载空白页清除残留状态
	        binding.webView.loadUrl("about:blank")
	        if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
	    }
	    private fun parseFillData(fillType: String, jsonStr: String): Pair<List<Pair<String, String>>, List<String>> {
	        val fields = mutableListOf<Pair<String, String>>()
	        val pumps = mutableListOf<String>()
	        if (jsonStr.isEmpty()) return Pair(fields, pumps)
	        runCatching {
	            val root = JSONObject(jsonStr)
	            if (fillType == "screw") {
	                listOf("unit1" to "1#", "unit2" to "2#", "unit3" to "3#").forEach { (unitKey, prefix) ->
	                    if (root.has(unitKey)) {
	                        val unitObj = root.getJSONObject(unitKey)
	                        val keys = unitObj.keys()
	                        while (keys.hasNext()) {
	                            val key = keys.next()
	                            if (key != "pumps") {
	                                var labelSuffix = screwFieldLabelMap[key] ?: continue
	                                if (key == "condRefPressure" && prefix == "2#") {
	                                    labelSuffix = "冷凝器冷凝压力"
	                                }
	                                val fullLabel = prefix + labelSuffix
	                                fields.add(Pair(fullLabel, unitObj.getString(key)))
	                            }
	                        }
	                        if (unitObj.has("pumps")) {
	                            val pumpArr = unitObj.getJSONArray("pumps")
	                            for (i in 0 until pumpArr.length()) {
	                                pumps.add(pumpArr.getString(i))
	                            }
	                        }
	                    }
	                }
	            } else if (fillType == "plate") {
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
	                        binding.tvStatusBanner.text = "⏳ 网络加载缓慢，请耐心等待..."
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
	    private fun compileFillJs(
	        fields: List<Pair<String, String>>,
	        pumps: List<String>,
	        targetTabName: String
	    ): String {
	        val fieldsJs = fields.joinToString(",\n") { (label, value) ->
	            "{label:'${label.esc()}', value:'${value.esc()}'}"
	        }
	        val pumpsJs = pumps.joinToString(prefix = "[", postfix = "]") { "'${it.esc()}'" }
	        return """
	        (function(){
	            if(window.__ocrFillEngineStarted) return;
	            window.__ocrFillEngineStarted = true;
	            var targetFields = [${fieldsJs}];
	            var pumpItems = ${pumpsJs};
	            var targetTab = '${targetTabName.esc()}';
	            window.__ocrMaxFilledCount = -1;
	            window.__ocrIdleCount = 0;
	            window.__ocrScanCount = 0;
	            if (window.__ocrScanTimer) clearInterval(window.__ocrScanTimer);
	            var pumpsOperated = {};
	            var inputValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
	            var textareaValueSetter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
	            function cleanText(text) {
	                return (text || '').replace(/\s+/g, '').replace(/\u3000/g, '');
	            }
	            function forceChinese() {
	                var spans = document.querySelectorAll('span, button, div');
	                for(var i=0; i<spans.length; i++) {
	                    var text = spans[i].innerText;
	                    if(text && text.trim() === 'Select all') {
	                        spans[i].innerText = '全选';
	                    }
	                }
	                var html = document.body.innerHTML;
	                if (html.indexOf('Submit Success') > -1 || html.indexOf('submit success') > -1) {
	                    document.body.innerHTML = html.replace(/Submit Success/gi, '提交成功');
	                }
	            }
	            function switchToTargetTab() {
	                if (!targetTab) return;
	                var tabs = document.querySelectorAll('.sectionTabItem');
	                for (var i = 0; i < tabs.length; i++) {
	                    var tabText = cleanText(tabs[i].innerText);
	                    var targetClean = cleanText(targetTab);
	                    if (tabText.indexOf(targetClean) > -1) {
	                        if (tabs[i].className.indexOf('active') === -1) {
	                            tabs[i].click();
	                            AndroidBridge.log('已切换到标签页: ' + targetTab);
	                        }
	                        if (pumpItems.length > 0) {
	                            triggerPumpCheck();
	                        }
	                        break;
	                    }
	                }
	            }
	            var isPumpChecking = false;
	            function triggerPumpCheck() {
	                if (isPumpChecking) return;
	                isPumpChecking = true;
	                var retryCount = 0;
	                var maxRetry = 10;
	                var pumpInterval = setInterval(function() {
	                    var allDone = true;
	                    for (var k = 0; k < pumpItems.length; k++) {
	                        var pumpName = pumpItems[k];
	                        if (pumpsOperated[pumpName]) continue;
	                        var labels = document.querySelectorAll('label.ming.Checkbox');
	                        var pumpClean = cleanText(pumpName);
	                        var found = false;
	                        for (var i = 0; i < labels.length; i++) {
	                            if (labels[i].offsetParent === null) continue; 
	                            var title = labels[i].getAttribute('title') || '';
	                            if (cleanText(title) === pumpClean) {
	                                found = true;
	                                var checkbox = labels[i].querySelector('.Checkbox-box');
	                                if (checkbox && checkbox.className.indexOf('Checkbox-checked') > -1) {
	                                    pumpsOperated[pumpName] = true;
	                                    AndroidBridge.log('冷冻泵已勾选: ' + pumpName);
	                                } else {
	                                    pumpsOperated[pumpName] = true;
	                                    AndroidBridge.log('执行冷冻泵点击: ' + pumpName);
	                                    try {
	                                        labels[i].dispatchEvent(new Event('mouseover', { bubbles: true }));
	                                        labels[i].dispatchEvent(new Event('mousedown', { bubbles: true }));
	                                        labels[i].dispatchEvent(new Event('mouseup', { bubbles: true }));
	                                    } catch(e) {}
	                                    labels[i].click();
	                                }
	                                break;
	                            }
	                        }
	                        if (!found) {
	                            allDone = false;
	                        }
	                    }
	                    retryCount++;
	                    if (allDone || retryCount >= maxRetry) {
	                        clearInterval(pumpInterval);
	                        isPumpChecking = false;
	                        AndroidBridge.log('冷冻泵检查完毕');
	                    }
	                }, 500);
	            }
	            function expandAllGroups() {
	                var groupControls = document.querySelectorAll('.customFormItemControl[type="22"]');
	                for (var i = 0; i < groupControls.length; i++) {
	                    var arrow = groupControls[i].querySelector('.icon-arrow-down-border');
	                    if (arrow) {
	                        groupControls[i].querySelector('.titleBox').click();
	                    }
	                }
	            }
	            function setInputValue(formItem, value) {
	                if (!formItem) return false;
	                var controlBox = formItem.querySelector('.customFormControlBox');
	                if (!controlBox) return false;
	                var input = formItem.querySelector('input');
	                if (input) {
	                    inputValueSetter.call(input, value);
	                    input.dispatchEvent(new Event('input', { bubbles: true }));
	                    input.dispatchEvent(new Event('change', { bubbles: true }));
	                    return input.value === String(value);
	                }
	                var textarea = formItem.querySelector('textarea');
	                if (textarea) {
	                    textareaValueSetter.call(textarea, value);
	                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
	                    textarea.dispatchEvent(new Event('change', { bubbles: true }));
	                    return textarea.value === String(value);
	                }
	                var displaySpan = controlBox.querySelector('.sc-jgwFWF, .WordBreak');
	                if (displaySpan) {
	                    displaySpan.innerText = value;
	                    controlBox.dispatchEvent(new Event('input', { bubbles: true }));
	                    controlBox.dispatchEvent(new Event('change', { bubbles: true }));
	                    return true;
	                }
	                return false;
	            }
	            function scanAndAutofillEngine() {
	                try {
	                    window.__ocrScanCount++;
	                    forceChinese();
	                    switchToTargetTab();
	                    if (!window.__ocrGroupsExpanded) {
	                        expandAllGroups();
	                        window.__ocrGroupsExpanded = true;
	                    }
	                    var formItems = document.querySelectorAll('.customFormItem');
	                    if (formItems.length === 0) return;
	                    var filledCount = 0;
	                    for (var i = 0; i < targetFields.length; i++) {
	                        var item = targetFields[i];
	                        var targetLabel = cleanText(item.label);
	                        var found = false;
	                        for (var j = 0; j < formItems.length; j++) {
	                            var labelEl = formItems[j].querySelector('.controlLabelName');
	                            if (!labelEl) continue;
	                            var labelText = cleanText(labelEl.innerText || labelEl.getAttribute('title'));
	                            if (labelText === targetLabel || labelText.indexOf(targetLabel) > -1) {
	                                if (setInputValue(formItems[j], item.value)) {
	                                    filledCount++;
	                                }
	                                found = true;
	                                break;
	                            }
	                        }
	                    }
	                    if (filledCount > window.__ocrMaxFilledCount) {
	                        window.__ocrMaxFilledCount = filledCount;
	                        window.__ocrIdleCount = 0;
	                    } else {
	                        window.__ocrIdleCount++;
	                        if (window.__ocrIdleCount >= ${MAX_IDLE_SCAN_COUNT}) {
	                            clearInterval(window.__ocrScanTimer);
	                            AndroidBridge.log('填充数连续未突破最大值，引擎自动停止轮询，最终最大填充数: ' + window.__ocrMaxFilledCount);
	                        }
	                    }
	                    if (window.AndroidBridge && AndroidBridge.onFillComplete) {
	                        AndroidBridge.onFillComplete(filledCount);
	                    }
	                } catch (e) {
	                    AndroidBridge.log('填表引擎异常: ' + e.message);
	                }
	            }
	            window.__ocrScanTimer = setInterval(scanAndAutofillEngine, ${AUTO_SCAN_INTERVAL_MS});
	            scanAndAutofillEngine();
	            setTimeout(function(){ 
	                if (document.activeElement) document.activeElement.blur(); 
	            }, 1000);
	        })();
	        """.trimIndent()
	    }
	    override fun onDestroy() {
	        timeoutHandler.removeCallbacksAndMessages(null)
	        binding.webView.removeJavascriptInterface("AndroidBridge")
	        // 修复Q2：确保资源彻底释放
	        binding.webView.apply { loadUrl("about:blank"); stopLoading(); clearHistory(); destroy() }
	        super.onDestroy()
	    }
	}
