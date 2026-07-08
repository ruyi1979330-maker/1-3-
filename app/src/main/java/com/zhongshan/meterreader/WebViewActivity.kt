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
        private const val WEB_LOAD_TIMEOUT_MS = 45_000L // 基础超时拉长到45秒，适配弱网
        private const val PROGRESS_STALL_TIMEOUT_MS = 12_000L // 进度12秒不动才判超时
        private const val AUTO_SCAN_INTERVAL_MS = 1500L
        private const val MAX_IDLE_SCAN_COUNT = 3
    }

    internal lateinit var binding: ActivityWebviewBinding
    private var targetUrl = ""
    private var fillJsPayload = ""
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val pageLoadGeneration = AtomicInteger(0)
    private val isFillDone = AtomicBoolean(false)
    private var lastProgressTime = 0L
    private var lastProgress = 0

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
                                val labelSuffix = screwFieldLabelMap[key] ?: continue
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

        // 监听加载进度，进度在走就不超时
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress > lastProgress) {
                    lastProgress = newProgress
                    lastProgressTime = System.currentTimeMillis()
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                isFillDone.set(false)
                lastProgress = 0
                lastProgressTime = System.currentTimeMillis()
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatusBanner.visibility = View.VISIBLE
                binding.tvStatusBanner.text = "⏳ 正在连接数据中心，请稍候…"
                val gen = pageLoadGeneration.incrementAndGet()

                // 双层超时校验：总时长45秒兜底 + 进度12秒不动判死
                val checkTimeout = object : Runnable {
                    override fun run() {
                        if (pageLoadGeneration.get() != gen) return
                        val now = System.currentTimeMillis()
                        val stallTime = now - lastProgressTime

                        if (stallTime >= PROGRESS_STALL_TIMEOUT_MS || now - lastProgressTime >= WEB_LOAD_TIMEOUT_MS) {
                            binding.progressBar.visibility = View.GONE
                            binding.webView.stopLoading()
                            binding.layoutNetworkError.visibility = View.VISIBLE
                            binding.tvErrorMsg.text = "网络加载超时，请检查信号后重试。"
                            binding.tvStatusBanner.visibility = View.GONE
                        } else {
                            timeoutHandler.postDelayed(this, 2000)
                        }
                    }
                }
                timeoutHandler.postDelayed(checkTimeout, 2000)
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
        val totalExpected = fields.size + pumps.size

        return """
        (function(){
            if(window.__ocrFillEngineStarted) return;
            window.__ocrFillEngineStarted = true;
            
            var targetFields = [${fieldsJs}];
            var pumpItems = ${pumpsJs};
            var targetTab = '${targetTabName.esc()}';
            var totalExpected = ${totalExpected};
            var maxFilledCount = 0;
            var idleScanCount = 0;
            var scanTimer = null;
            var domReady = false;
            
            var inputValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            var textareaValueSetter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;

            function cleanText(text) {
                return (text || '').replace(/\s+/g, '').replace(/\u3000/g, '');
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
                        break;
                    }
                }
            }

            function expandAllGroups() {
                var groupControls = document.querySelectorAll('.customFormItemControl[type="22"]');
                for (var i = 0; i < groupControls.length; i++) {
                    var arrow = groupControls[i].querySelector('.headerArrow .icon-arrow-down-border');
                    if (arrow) {
                        groupControls[i].querySelector('.titleBox').click();
                    }
                }
            }

            function triggerLazyRender() {
                window.scrollTo(0, document.body.scrollHeight);
                setTimeout(function(){ window.scrollTo(0, 0); }, 100);
            }

            function waitDomStable(callback) {
                var prevCount = 0;
                var stableRound = 0;
                var elapsed = 0;
                var checkGap = 100;
                var maxWait = 2000;

                function check() {
                    var inputs = document.querySelectorAll('.customFormItem input, .customFormItem textarea');
                    var curCount = inputs.length;
                    if (curCount === prevCount && curCount > 0) {
                        stableRound++;
                        if (stableRound >= 2) {
                            domReady = true;
                            callback();
                            return;
                        }
                    } else {
                        stableRound = 0;
                        prevCount = curCount;
                    }
                    elapsed += checkGap;
                    if (elapsed >= maxWait) {
                        domReady = true;
                        callback();
                        return;
                    }
                    setTimeout(check, checkGap);
                }
                check();
            }

            function setInputValue(formItem, value) {
                if (!formItem) return false;
                var controlBox = formItem.querySelector('.customFormControlBox');
                if (!controlBox) return false;
                controlBox.click();

                var input = formItem.querySelector('input');
                if (input) {
                    inputValueSetter.call(input, value);
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                    input.dispatchEvent(new Event('blur', { bubbles: true }));
                    return input.value === String(value);
                }

                var textarea = formItem.querySelector('textarea');
                if (textarea) {
                    textareaValueSetter.call(textarea, value);
                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    textarea.dispatchEvent(new Event('change', { bubbles: true }));
                    textarea.dispatchEvent(new Event('blur', { bubbles: true }));
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

            function checkPump(pumpName) {
                var labels = document.querySelectorAll('label.ming.Checkbox');
                var pumpClean = cleanText(pumpName);
                for (var i = 0; i < labels.length; i++) {
                    var title = labels[i].getAttribute('title') || '';
                    if (cleanText(title) === pumpClean) {
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

            function scanAndAutofillEngine() {
                try {
                    if (!domReady) return;
                    
                    var filledCount = 0;
                    var formItems = document.querySelectorAll('.customFormItem');
                    if (formItems.length === 0) return;

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

                    // 修复：冷冻泵勾选仅执行一次，避免轮询反复点击导致状态翻转
                    if (!window.__pumpCheckDone) {
                        for (var k = 0; k < pumpItems.length; k++) {
                            if (checkPump(pumpItems[k])) {
                                filledCount++;
                                AndroidBridge.log('已勾选冷冻泵: ' + pumpItems[k]);
                            }
                        }
                        window.__pumpCheckDone = true;
                    }

                    if (filledCount > maxFilledCount) {
                        maxFilledCount = filledCount;
                        idleScanCount = 0;
                        if (window.AndroidBridge && AndroidBridge.onFillComplete) {
                            AndroidBridge.onFillComplete(filledCount);
                        }
                    } else {
                        idleScanCount++;
                    }

                    if (idleScanCount >= ${MAX_IDLE_SCAN_COUNT} || maxFilledCount >= totalExpected) {
                        clearInterval(scanTimer);
                        AndroidBridge.log('填充稳定，引擎自动停止轮询，最终填充数: ' + maxFilledCount);
                    }
                } catch (e) {
                    AndroidBridge.log('填表引擎异常: ' + e.message);
                }
            }

            switchToTargetTab();
            expandAllGroups();
            triggerLazyRender();
            waitDomStable(function(){
                scanAndAutofillEngine();
                scanTimer = setInterval(scanAndAutofillEngine, ${AUTO_SCAN_INTERVAL_MS});
            });

            setTimeout(function(){ 
                if (document.activeElement) document.activeElement.blur(); 
            }, 1500);
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
