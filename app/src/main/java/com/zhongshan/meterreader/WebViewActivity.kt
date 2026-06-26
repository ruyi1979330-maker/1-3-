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
        private const val AUTO_SCAN_INTERVAL_MS = 500L
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

        fillJsPayload = compileFillJs(keys, values, pumpIds)
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

        // ==========================================
        // 补回：全选 CSS 汉化修复 (完全保留原汁原味)
        // ==========================================
        sb.append("""
            var style=document.createElement('style');
            style.innerHTML=`
                .el-table__header-wrapper .el-checkbox__label,
                .el-table__header .el-checkbox__label {
                    visibility: hidden; position: relative;
                }
                .el-table__header-wrapper .el-checkbox__label:after,
                .el-table__header .el-checkbox__label:after {
                    content: '全选'; visibility: visible !important;
                    position: absolute; left: 0; top: 0;
                    white-space: nowrap; color: #606266;
                }
                label[title='Select All'] .Checkbox-text,
                label[title='Select All'] .Font13 {
                    visibility: hidden; font-size: 0;
                }
                label[title='Select All'] { position: relative; }
                label[title='Select All']::after {
                    content: '全选'; visibility: visible;
                    position: absolute; left: 20px; top: 50%;
                    transform: translateY(-50%);
                    white-space: nowrap; font-size: 13px; color: #606266;
                }
            `;
            document.head.appendChild(style);
        """)

        // ==========================================
        // 数据注入与填表引擎
        // ==========================================
        sb.append("var targetData = [];\n")
        for (i in keys.indices) {
            val parts = keys[i].split("|")
            val fid   = parts[0].esc()
            val label = if (parts.size > 1) parts[1].esc() else ""
            val v     = values[i].esc()
            sb.append("targetData.push({id:'$fid', label:'$label', v:'$v'});\n")
        }

        // 核心执行逻辑 (Native Setter 穿透)
        sb.append("""
            function getLabelText(item) {
                return item.label.replace(/\s+/g, '').replace(/[（(].*?[)）]/g, '');
            }
            
            function getLabelVariants(label) {
                var variants = [label];
                if(label.indexOf('进水') > -1) variants.push(label.replace('进水','进口'));
                if(label.indexOf('进口') > -1) variants.push(label.replace('进口','进水'));
                if(label.indexOf('出水') > -1) variants.push(label.replace('出水','出口'));
                if(label.indexOf('出口') > -1) variants.push(label.replace('出口','出水'));
                if(label.indexOf('返回') > -1) variants.push(label.replace('返回','回水'));
                if(label.indexOf('回水') > -1) variants.push(label.replace('回水','返回'));
                return variants;
            }
            
            function isValidInput(el){
                if(!el) return false;
                var tag = (el.tagName || '').toUpperCase();
                if(tag !== 'INPUT' && tag !== 'TEXTAREA') return false;
                var type = (el.type || '').toLowerCase();
                if(['checkbox', 'radio', 'hidden', 'button', 'submit', 'file'].indexOf(type) > -1) return false;
                if(el.readOnly || el.disabled) return false;
                if(el.closest && el.closest('thead')) return false;
                if(el.offsetWidth === 0 && el.offsetHeight === 0) return false;
                return true;
            }

            function findInput(item){
                var el = document.getElementById(item.id) || document.querySelector('[name="'+item.id+'"]');
                if(el && isValidInput(el)) return el;
                if(!item.label) return null;
                
                var cleanLabel = getLabelText(item);
                var variants = getLabelVariants(cleanLabel);
                
                var formItems = document.querySelectorAll('.customFormItem');
                for(var i=0; i<formItems.length; i++){
                    var labelDiv = formItems[i].querySelector('.customFormItemLabel .controlLabelName');
                    if(!labelDiv) continue;
                    var labelText = (labelDiv.innerText || labelDiv.textContent || '').replace(/\s+/g, '');
                    for(var v=0; v<variants.length; v++){
                        if(labelText.indexOf(variants[v]) > -1 || variants[v].indexOf(labelText) > -1){
                            var input = formItems[i].querySelector('input[type="text"], input[type="number"], input:not([type])');
                            if(input && isValidInput(input)) return input;
                        }
                    }
                }
                
                var allLabels = document.querySelectorAll('.controlLabelName, label, .el-form-item__label');
                for(var j=0; j<allLabels.length; j++){
                    var lbl = allLabels[j];
                    var lblText = (lbl.innerText || lbl.textContent || '').replace(/\s+/g, '');
                    for(var v=0; v<variants.length; v++){
                        if(lblText.indexOf(variants[v]) > -1 || variants[v].indexOf(lblText) > -1){
                            var container = lbl.parentElement;
                            while(container && container.tagName !== 'BODY') {
                                var input = container.querySelector('input[type="text"], input[type="number"], input:not([type])');
                                if(input && isValidInput(input)) return input;
                                container = container.parentElement;
                            }
                        }
                    }
                }
                return null;
            }

            // ★ 核心黑科技：绕过 Vue/React 数据劫持的 Native Setter ★
            function triggerNativeSetter(el, valStr) {
                el.removeAttribute('readonly');
                el.removeAttribute('disabled');
                
                var valueSetter = Object.getOwnPropertyDescriptor(el, 'value') && Object.getOwnPropertyDescriptor(el, 'value').set;
                var prototype = Object.getPrototypeOf(el);
                var prototypeValueSetter = Object.getOwnPropertyDescriptor(prototype, 'value') && Object.getOwnPropertyDescriptor(prototype, 'value').set;

                if (prototypeValueSetter && valueSetter !== prototypeValueSetter) {
                    prototypeValueSetter.call(el, valStr);
                } else if (valueSetter) {
                    valueSetter.call(el, valStr);
                } else {
                    el.value = valStr;
                }

                el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                
                if (el.__vue__) {
                    try {
                        el.__vue__.${'$'}emit('input', valStr);
                        el.__vue__.${'$'}emit('change', valStr);
                    } catch(e){}
                }
            }

            function forceSetValue(el, v) {
                var valStr = v.toString();
                el.dispatchEvent(new Event('focus', { bubbles: true }));
                triggerNativeSetter(el, valStr);
                el.dispatchEvent(new Event('blur', { bubbles: true }));
                el.setAttribute('data-ocr-filled', valStr);
            }

            window.__ocrLastFilledCount = -1;
            
            function scanAndAutofillEngine(){
                var currentFilled = 0;
                
                for(var i=0; i<targetData.length; i++){
                    var item = targetData[i];
                    var el = findInput(item);
                    if(el){
                        var valStr = item.v.toString();
                        var currentVal = el.value ? el.value.toString() : '';
                        
                        if (currentVal !== valStr && el.getAttribute('data-ocr-filled') !== valStr) {
                            forceSetValue(el, item.v);
                        }
                        
                        if (el.value.toString() === valStr || el.getAttribute('data-ocr-filled') === valStr) {
                            currentFilled++;
                        }
                    }
                }
        """)

        // 注入复选框状态判定 (Pump IDs)
        for (pid in pumpIds) {
            val safePid = pid.esc()
            sb.append("""
                var chk = document.getElementById('$safePid') || document.querySelector('[value="$safePid"], [name="$safePid"]');
                if(chk && chk.type === 'checkbox' && chk.offsetWidth > 0){
                    if(!chk.closest('thead') && 
                       !chk.closest('.el-table__header-wrapper') &&
                       chk.id.indexOf('check-all') === -1) {
                       
                        if(chk.getAttribute('data-ocr-filled') !== 'true'){
                            var cs = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'checked');
                            if(cs && cs.set) { 
                                cs.set.call(chk, true); 
                            } else { 
                                chk.checked = true; 
                            }
                            chk.dispatchEvent(new Event('change', { bubbles: true }));
                            chk.setAttribute('data-ocr-filled', 'true');
                        }
                        currentFilled++;
                    }
                }
            """)
        }

        // 闭环通知
        sb.append("""
                if(currentFilled !== window.__ocrLastFilledCount){
                    window.__ocrLastFilledCount = currentFilled;
                    if(window.AndroidBridge && window.AndroidBridge.onFillComplete){
                        AndroidBridge.onFillComplete(currentFilled);
                    }
                }
            }

            setInterval(scanAndAutofillEngine, $AUTO_SCAN_INTERVAL_MS);
            scanAndAutofillEngine();
            
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
