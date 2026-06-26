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
        replace("\\","\\\\").replace("'","\\'").replace("\n","\\n").replace("\r","")

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

    @SuppressLint("SetJavaScriptEnabled","WebViewClientOnReceivedSslError")
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
                    binding.tvStatusBanner.text = "🎉 连接成功！随意切换任意标签页，引擎将自动填充。"
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
        sb.append("(function(){")
        sb.append("if(window.__ocrFillEngineStarted) return;")
        sb.append("window.__ocrFillEngineStarted = true;")
        
        // 破除原生四舍五入限制
        sb.append("""
            if(!window.__toFixedHooked){
                window.__toFixedHooked = true;
                var originalToFixed = Number.prototype.toFixed;
                Number.prototype.toFixed = function(digits){
                    if(window.__ocr_bypass_rounding){ return this.toString(); }
                    return originalToFixed.apply(this, arguments);
                };
            }
        """)

        sb.append("var data=[];")
        for (i in keys.indices) {
            val parts = keys[i].split("|")
            val fid   = parts[0].esc()
            val label = if (parts.size > 1) parts[1].esc() else ""
            val v     = values[i].esc()
            sb.append("data.push({id:'$fid',label:'$label',v:'$v'});")
        }
        
        sb.append("""
        function findInput(item){
            var el = document.getElementById(item.id) || document.querySelector('[name="'+item.id+'"]');
            if(el && isValidInput(el)) return el;
            if(!item.label) return null;
            
            var cleanLabel = item.label.replace(/\s+/g, '').replace(/（.*?）|\(.*?\)/g, ''); 
            
            var machinePrefix = null;
            var coreMetric = cleanLabel;
            // 优化前缀匹配：兼容“离心机1#”、“机组2”等所有变体
            var matchPrefix = cleanLabel.match(/(?:机组|离心机|螺杆机)?(\d+)(?:#|号|机)/);
            if(matchPrefix){
                machinePrefix = matchPrefix[1];
                coreMetric = cleanLabel.replace(matchPrefix[0], '');
            }

            // 【策略 A】：多机组动态网格穿透
            if(machinePrefix){
                var tables = document.querySelectorAll('table, .el-table');
                for(var t=0; t<tables.length; t++){
                    var table = tables[t];
                    var colIndex = -1;
                    var ths = table.querySelectorAll('th');
                    for(var h=0; h<ths.length; h++){
                        var thTxt = (ths[h].innerText || ths[h].textContent || '').replace(/\s+/g, '');
                        if(thTxt.indexOf(coreMetric) > -1 || coreMetric.indexOf(thTxt) > -1){
                            colIndex = h;
                            break;
                        }
                    }
                    if(colIndex > -1){
                        var rows = table.querySelectorAll('tbody tr, .el-table__row');
                        for(var r=0; r<rows.length; r++){
                            var row = rows[r];
                            var rowTxt = (row.innerText || row.textContent || '').replace(/\s+/g, '');
                            var isTargetRow = rowTxt.indexOf(machinePrefix + '#') > -1 || 
                                              rowTxt.indexOf(machinePrefix + '号') > -1 || 
                                              rowTxt.indexOf('机组' + machinePrefix) > -1 ||
                                              (row.cells && row.cells[0] && (row.cells[0].innerText || '').replace(/\s+/g, '').indexOf(machinePrefix) > -1);
                            
                            if(isTargetRow){
                                var cells = row.querySelectorAll('td');
                                if(cells[colIndex]){
                                    var inp = cells[colIndex].querySelector('input, textarea');
                                    if(inp && isValidInput(inp)) return inp;
                                }
                            }
                        }
                    }
                }
            }

            // 【策略 B】：扁平化表单兜底
            var allNodes = document.querySelectorAll('*');
            for(var i=0; i<allNodes.length; i++){
                var node = allNodes[i];
                if(node.children.length > 0 && node.tagName !== 'LABEL' && node.tagName !== 'TD' && node.tagName !== 'TH') continue;
                
                var txt = (node.innerText || node.textContent || '').replace(/\s+/g, '');
                if(txt.indexOf(cleanLabel) >= 0 || (machinePrefix && txt.indexOf(coreMetric) >= 0)){
                    var parent = node;
                    for(var j=0; j<8; j++){
                        if(!parent || parent.tagName === 'BODY') break;
                        if(parent.tagName === 'TR' && parent.closest('thead')) break;
                        var input = parent.querySelector('input, textarea');
                        if(input && isValidInput(input)) return input;
                        parent = parent.parentElement;
                    }
                }
            }
            return null;
        }

        // 强力过滤隔离防护：彻底根治英文“Select All”崩溃问题，绝对不碰按钮、复选框、下拉框
        function isValidInput(el){
            if(!el) return false;
            var tag = (el.tagName || '').toUpperCase();
            if(tag !== 'INPUT' && tag !== 'TEXTAREA') return false;
            var type = (el.type || '').toLowerCase();
            if(type === 'checkbox' || type === 'radio' || type === 'hidden' || type === 'button' || type === 'submit' || type === 'file') return false;
            if(el.readOnly || el.disabled) return false;
            if(el.closest && (el.closest('thead') || el.closest('.el-table__header') || el.closest('.el-select'))) return false;
            if(el.offsetWidth === 0 || el.offsetHeight === 0) return false;
            return true;
        }

        function setVal(el, v){
            if (document.activeElement === el) return; // 用户正在打字时不覆盖
            
            // 解决约克离心机 el-input-number 类型校验失败问题：智能强转为 Number
            var numericV = Number(v);
            var finalV = isNaN(numericV) ? v : numericV;

            // 1. 深度注入 Vue 组件体系
            try {
                var p = el;
                while (p && !p.__vue__) { p = p.parentElement; }
                if (p && p.__vue__) {
                    var comp = p.__vue__;
                    // 【注意】已彻底移除修改 precision/max/min 的逻辑，防止破坏 Vue 响应式引发英文 Locale 崩溃
                    if(comp.hasOwnProperty('value')) comp.value = finalV;
                    if(typeof comp.setCurrentValue === 'function') comp.setCurrentValue(finalV);
                    if(typeof comp.handleInput === 'function') comp.handleInput(finalV);
                    
                    if(comp.${'$'}emit){
                        comp.${'$'}emit('input', finalV);
                        comp.${'$'}emit('change', finalV);
                    }
                }
            } catch(e){}

            // 2. 原生 HTML5 赋值
            try {
                el.removeAttribute('maxlength');
                var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                if(setter){ setter.call(el, v); } else { el.value = v; }
                
                window.__ocr_bypass_rounding = true;
                el.dispatchEvent(new Event('focus', {bubbles:true}));
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                el.dispatchEvent(new Event('blur', {bubbles:true}));
                window.__ocr_bypass_rounding = false;
            } catch(e){}

            // 标记已填值，用于比对防 Vue 还原
            el.setAttribute('data-ocr-filled', v);
        }

        window.__ocrLastFilledCount = -1;
        function scanAndAutofillEngine(){
            var currentFilled = 0;
            for(var i=0; i<data.length; i++){
                var item = data[i];
                var el = findInput(item);
                if(el){
                    // 彻底解决【点击表单/切标签页后不填】的问题
                    // 实时比对：如果 DOM 真实值被 Vue 刷掉了，或者根本没填，强制复写！
                    var currentDomVal = el.value;
                    var needsFill = (currentDomVal !== item.v && currentDomVal !== String(Number(item.v)));
                    
                    if(needsFill){
                        if (document.activeElement !== el) { 
                            setVal(el, item.v); 
                        }
                    }
                    
                    // 校验是否真确填入
                    var updatedDomVal = el.value;
                    if(updatedDomVal === item.v || updatedDomVal === String(Number(item.v)) || el.getAttribute('data-ocr-filled') === item.v){ 
                        currentFilled++; 
                    }
                }
            }
            
            // 泵、阀门复选框快速通道（已移除 colonial 拼写错误导致的异常选择器）
        """)
        
        for (pid in pumpIds) {
            sb.append("""
            var chk=document.getElementById('${pid.esc()}')||document.querySelector('[value="${pid.esc()}"],[name="${pid.esc()}"]');
            if(chk && chk.type==='checkbox' && chk.offsetWidth > 0){
                if(chk.getAttribute('data-ocr-filled') !== 'true'){
                    var cs=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'checked');
                    if(cs&&cs.set){cs.set.call(chk,true);}else{chk.checked=true;}
                    chk.dispatchEvent(new Event('change',{bubbles:true}));
                    chk.setAttribute('data-ocr-filled', 'true');
                }
                currentFilled++;
            }
            """)
        }
        
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
