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
	        private const val AUTO_SCAN_INTERVAL_MS = 1500L // 间隔改长，给网页渲染时间
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
	        val pumpItems = mutableListOf<Pair<String, String>>()
	        for (i in keys.indices) {
	            val key = keys[i]
	            if (key.startsWith("__pump__")) {
	                pumpItems.add(Pair(key.removePrefix("__pump__"), values[i]))
	                continue
	            }
	            val parts = key.split("|")
	            val fid   = parts[0].esc()
	            // 表单里的 title 是 "1#蒸发器进口 水温"，这里把可能的多余空格去掉，提高匹配率
	            val label = if (parts.size > 1) parts[1].replace(" ", "").esc() else ""
	            val v     = values[i].esc()
	            sb.append("targetData.push({id:'$fid', label:'$label', v:'$v'});\n")
	        }
	        // --- 自动切换标签页逻辑 ---
	        sb.append("""
	            function clickTabIfNeeded() {
	                if (targetData.length === 0) return;
	                var needTab = null;
	                var firstLabel = targetData[0].label || "";
	                if(firstLabel.indexOf("板交") > -1 || firstLabel.indexOf("水汀") > -1 || firstLabel.indexOf("楼") > -1){
	                    needTab = "板交";
	                } else if (firstLabel.indexOf("蒸发器") > -1 || firstLabel.indexOf("冷凝器") > -1 || firstLabel.indexOf("压缩机") > -1 || firstLabel.indexOf("主机") > -1 || firstLabel.indexOf("电机") > -1 || firstLabel.indexOf("油压") > -1) {
	                    needTab = "螺杆机组";
	                }
	                if (needTab) {
	                    // 根据真实 HTML，使用 .sectionTabItem
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
	            }
	        """)
	        // --- 核心填表逻辑 ---
	        sb.append("""
	            window.__ocrLastFilledCount = -1;
	            window.__lastPrintTime = 0;
	            function scanAndAutofillEngine(){
	                // 1. 尝试切换 Tab
	                clickTabIfNeeded();
	                var currentFilled = 0;
	                // 2. 遍历所有需要填写的字段
	                for(var i=0; i<targetData.length; i++){
	                    var item = targetData[i];
	                    // 表单里的 title 包含空格，如 "1#蒸发器进口 水温"，我们需要匹配这个
	                    var cleanLabel = item.label;
	                    // 查找对应的表单项容器
	                    var formItems = document.querySelectorAll('.customFormItem');
	                    for(var j=0; j<formItems.length; j++){
	                        var labelDiv = formItems[j].querySelector('.controlLabelName');
	                        if(!labelDiv) continue;
	                        var labelText = (labelDiv.innerText || labelDiv.textContent || '').replace(/\\s+/g, '');
	                        if(labelText === cleanLabel) {
	                            // 找到匹配的表单项了！
	                            var controlBox = formItems[j].querySelector('.customFormControlBox');
	                            if(controlBox) {
	                                // 检查是否已经填过
	                                var spanVal = controlBox.querySelector('span.sc-jgwFWF') || controlBox.querySelector('span.WordBreak');
	                                if(spanVal) {
	                                    var currentText = (spanVal.innerText || '').trim();
	                                    if(currentText === item.v) {
	                                        currentFilled++;
	                                        continue;
	                                    }
	                                    // 如果是 "请填写数值" 或 "请填写文本内容"，说明是空的，需要填
	                                    if(currentText.indexOf('请填写') > -1 || currentText !== item.v) {
	                                        spanVal.innerText = item.v;
	                                        // 触发事件让框架感知
	                                        controlBox.dispatchEvent(new Event('input', { bubbles: true }));
	                                        controlBox.dispatchEvent(new Event('change', { bubbles: true }));
	                                        controlBox.dispatchEvent(new Event('blur', { bubbles: true }));
	                                        AndroidBridge.log('填入值: ' + item.label + ' = ' + item.v);
	                                        currentFilled++;
	                                    }
	                                }
	                            }
	                            break;
	                        }
	                    }
	                }
	        """)
	        // 3. 处理冷冻泵勾选框
	        for (pumpItem in pumpItems) {
	            val safeName = pumpItem.first.esc()
	            sb.append("""
	                // 勾选冷冻泵: $safeName
	                var pumpLabels = document.querySelectorAll('label.ming.Checkbox');
	                for(var k=0; k<pumpLabels.length; k++){
	                    var pTitle = pumpLabels[k].getAttribute('title') || '';
	                    if(pTitle === '$safeName') {
	                        var checkboxBox = pumpLabels[k].querySelector('.Checkbox-box');
	                        // 如果未勾选，且没有 ocr 填充标记，则点击
	                        if(checkboxBox && checkboxBox.className.indexOf('Checkbox-checked') === -1 && pumpLabels[k].getAttribute('data-ocr-filled') !== 'true') {
	                            pumpLabels[k].click();
	                            pumpLabels[k].setAttribute('data-ocr-filled', 'true');
	                            AndroidBridge.log('已勾选: $safeName');
	                        }
	                        currentFilled++;
	                    }
	                }
	            """)
	        }
	        sb.append("""
	                // 如果没填全，每 3 秒打印一次当前页面的 div 详情供调试
	                if (currentFilled < targetData.length) {
	                    if (!window.__lastPrintTime || Date.now() - window.__lastPrintTime > 3000) {
	                        window.__lastPrintTime = Date.now();
	                        var boxes = document.querySelectorAll('.customFormControlBox span.sc-jgwFWF, .customFormControlBox span.WordBreak');
	                        var logMsg = '\n=== 当前可见数值框 (共 ' + boxes.length + ' 个) ===\n';
	                        for (var m = 0; m < boxes.length && m < 15; m++) {
	                            var el = boxes[m];
	                            logMsg += 'Value[' + m + ']: "' + (el.innerText||'') + '"\n';
	                        }
	                        AndroidBridge.domLog(logMsg);
	                    }
	                }
	                if(currentFilled !== window.__ocrLastFilledCount){
	                    window.__ocrLastFilledCount = currentFilled;
	                    if(window.AndroidBridge && window.AndroidBridge.onFillComplete){
	                        AndroidBridge.onFillComplete(currentFilled);
	                    }
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
