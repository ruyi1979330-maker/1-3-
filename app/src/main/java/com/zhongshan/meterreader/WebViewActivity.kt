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
	import java.util.concurrent.atomic.AtomicBoolean
	import java.util.concurrent.atomic.AtomicInteger
	class WebViewActivity : AppCompatActivity() {
	    companion object {
	        private const val WEB_LOAD_TIMEOUT_MS = 20_000L
	        private const val AUTO_SCAN_INTERVAL_MS = 1500L
	    }
	    internal lateinit var binding: ActivityWebviewBinding
	    private var targetUrl   = ""
	    private var fillJsPayload = ""
	    private val timeoutHandler      = Handler(Looper.getMainLooper())
	    private val pageLoadGeneration  = AtomicInteger(0)
	    private val isFillDone          = AtomicBoolean(false)
	    private fun String.esc() =
	        replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
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
	        val keys    = intent.getStringArrayExtra("EXTRA_KEYS")    ?: emptyArray()
	        val values  = intent.getStringArrayExtra("EXTRA_VALUES")  ?: emptyArray()
	        val pumpIds = intent.getStringArrayExtra("EXTRA_PUMP_IDS")?: emptyArray()
	        // ===== 关键日志：看看到底收到了什么数据 =====
	        DebugLogger.log("WebView", "收到的 Intent 数据: url=$targetUrl")
	        DebugLogger.log("WebView", "收到的 keys: ${keys.toList()}")
	        DebugLogger.log("WebView", "收到的 values: ${values.toList()}")
	        DebugLogger.log("WebView", "收到的 pumpIds: ${pumpIds.toList()}")
	        // ==========================================
	        val (mergedKeys, mergedValues) = mergePumpData(keys, values, pumpIds)
	        fillJsPayload = compileFillJs(mergedKeys, mergedValues, pumpIds)
	        initWebView()
	        if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
	    }
	    private fun mergePumpData(keys: Array<String>, values: Array<String>, pumpIds: Array<String>): Pair<Array<String>, Array<String>> {
	        val mergedKeys = mutableListOf<String>()
	        val mergedValues = mutableListOf<String>()
	        for (i in keys.indices) {
	            mergedKeys.add(keys[i])
	            mergedValues.add(values[i])
	        }
	        for (pid in pumpIds) {
	            mergedKeys.add("__pump__$pid")
	            mergedValues.add("true")
	        }
	        return Pair(mergedKeys.toTypedArray(), mergedValues.toTypedArray())
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
	                isFillDone.set(false)
	                binding.progressBar.visibility      = View.VISIBLE
	                binding.tvStatusBanner.visibility   = View.VISIBLE
	                binding.tvStatusBanner.text = "⏳ 正在连接数据中心，请稍候…"
	                val gen = pageLoadGeneration.incrementAndGet()
	                timeoutHandler.postDelayed({
	                    if (pageLoadGeneration.get() == gen) {
	                        binding.progressBar.visibility    = View.GONE
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
	            isFillDone.set(false)
	            if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
	        }
	    }
	    private fun compileFillJs(keys: Array<String>, values: Array<String>, pumpIds: Array<String>): String {
	        val sb = StringBuilder()
	        sb.append("(function(){\n")
	        sb.append("if(window.__ocrFillEngineStarted) return;\n")
	        sb.append("window.__ocrFillEngineStarted = true;\n")
	        sb.append("var targetData = [];\n")
	        val pumpItems = mutableListOf<String>()
	        for (i in keys.indices) {
	            val key = keys[i]
	            if (key.startsWith("__pump__")) {
	                pumpItems.add(key.removePrefix("__pump__"))
	                continue
	            }
	            val parts = key.split("|")
	            val fid   = parts[0].esc()
	            val label = if (parts.size > 1) parts[1].replace(" ", "").esc() else ""
	            val v     = values[i].esc()
	            sb.append("targetData.push({id:'$fid', label:'$label', v:'$v'});\n")
	        }
	        val pumpArrayStr = pumpItems.joinToString(prefix = "[", postfix = "]") { "'${it.esc()}'" }
	        sb.append("var pumpItems = $pumpArrayStr;\n")
	        sb.append("""
	            function getExpectedLabel(item) {
	                var prefix = '';
	                var m = item.id.match(/field_1_(\d+)/);
	                if (m) {
	                    var num = parseInt(m[1]);
	                    if (num <= 20) prefix = '1#';
	                    else if (num <= 40) prefix = '2#';
	                    else if (num <= 60) prefix = '3#';
	                }
	                return prefix + item.label;
	            }
	            function clickTabIfNeeded() {
	                if (targetData.length === 0) return;
	                var needTab = null;
	                var firstLabel = targetData[0].label || "";
	                if(firstLabel.indexOf("板交") > -1 || firstLabel.indexOf("水汀") > -1 || firstLabel.indexOf("楼") > -1){
	                    needTab = "板交";
	                } else {
	                    needTab = "螺杆机组";
	                }
	                var tabs = document.querySelectorAll('.sectionTabItem');
	                for(var i=0; i<tabs.length; i++){
	                    var text = (tabs[i].innerText || '').trim();
	                    if(text.indexOf(needTab) > -1) {
	                        var isActive = tabs[i].className.indexOf('active') > -1;
	                        if(!isActive) {
	                            tabs[i].click();
	                            AndroidBridge.log('已自动点击切换到 Tab: ' + text);
	                        }
	                        return;
	                    }
	                }
	            }
	            window.__ocrLastFilledCount = -1;
	            function scanAndAutofillEngine(){
	                try {
	                    clickTabIfNeeded();
	                    var currentFilled = 0;
	                    var formItems = document.querySelectorAll('.customFormItem');
	                    if (formItems.length === 0) return;
	                    for(var i=0; i<targetData.length; i++){
	                        var item = targetData[i];
	                        var expectedLabel = getExpectedLabel(item);
	                        var found = false;
	                        for(var j=0; j<formItems.length; j++){
	                            var labelDiv = formItems[j].querySelector('.controlLabelName');
	                            if(!labelDiv) continue;
	                            var labelText = (labelDiv.innerText || '').replace(/\s+/g, '').replace(/\u3000/g, '');
	                            if(labelText === expectedLabel) {
	                                found = true;
	                                var controlBox = formItems[j].querySelector('.customFormControlBox');
	                                if(controlBox) {
	                                    var spanVal = controlBox.querySelector('span.sc-jgwFWF') || controlBox.querySelector('span.WordBreak');
	                                    if(spanVal) {
	                                        spanVal.innerText = item.v;
	                                        controlBox.dispatchEvent(new Event('input', { bubbles: true }));
	                                        controlBox.dispatchEvent(new Event('change', { bubbles: true }));
	                                        controlBox.dispatchEvent(new Event('blur', { bubbles: true }));
	                                        AndroidBridge.log('已填入: ' + expectedLabel + ' = ' + item.v);
	                                        currentFilled++;
	                                    }
	                                }
	                                break;
	                            }
	                        }
	                        if(!found) {
	                            AndroidBridge.log('未找到匹配项: ' + expectedLabel + ' (原始: ' + item.label + ')');
	                        }
	                    }
	                    // 冷冻泵勾选
	                    for(var k=0; k<pumpItems.length; k++){
	                        var pumpName = pumpItems[k];
	                        var pumpLabels = document.querySelectorAll('label.ming.Checkbox');
	                        for(var m=0; m<pumpLabels.length; m++){
	                            var pTitle = pumpLabels[m].getAttribute('title') || '';
	                            if(pTitle === pumpName) {
	                                var checkboxBox = pumpLabels[m].querySelector('.Checkbox-box');
	                                if(checkboxBox && checkboxBox.className.indexOf('Checkbox-checked') === -1 && pumpLabels[m].getAttribute('data-ocr-filled') !== 'true') {
	                                    pumpLabels[m].click();
	                                    pumpLabels[m].setAttribute('data-ocr-filled', 'true');
	                                    AndroidBridge.log('已勾选: ' + pumpName);
	                                }
	                                currentFilled++;
	                            }
	                        }
	                    }
	                    if(currentFilled !== window.__ocrLastFilledCount){
	                        window.__ocrLastFilledCount = currentFilled;
	                        if(window.AndroidBridge && window.AndroidBridge.onFillComplete){
	                            AndroidBridge.onFillComplete(currentFilled);
	                        }
	                    }
	                } catch(e) {
	                    AndroidBridge.log('填表引擎异常: ' + e.message);
	                }
	            }
	            setInterval(scanAndAutofillEngine, $AUTO_SCAN_INTERVAL_MS);
	            scanAndAutofillEngine();
	            setTimeout(function(){ if(document.activeElement) document.activeElement.blur(); }, 1000);
	        })();
	        """)
	        return sb.toString()
	    }
	    override fun onDestroy() {
	        timeoutHandler.removeCallbacksAndMessages(null)
	        binding.webView.removeJavascriptInterface("AndroidBridge")
	        binding.webView.apply { loadUrl("about:blank"); stopLoading(); clearHistory(); destroy() }
	        super.onDestroy()
	    }
	}
