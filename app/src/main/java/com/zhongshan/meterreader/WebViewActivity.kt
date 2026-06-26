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

        // 允许 Chrome 远程调试 WebView（发布时请移除或加条件判断）
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
        sb.append("(function(){")
        sb.append("if(window.__ocrFillEngineStarted) return;")
        sb.append("window.__ocrFillEngineStarted = true;")

        // 全选按钮 CSS 修复（自定义 Checkbox + Element UI 兼容）
        sb.append("""
            var style=document.createElement('style');
            style.innerHTML=`
                /* Element UI 全选 */
                .el-table__header-wrapper .el-checkbox__label,
                .el-table__header .el-checkbox__label {
                    visibility: hidden;
                    position: relative;
                }
                .el-table__header-wrapper .el-checkbox__label:after,
                .el-table__header .el-checkbox__label:after {
                    content: '全选';
                    visibility: visible !important;
                    position: absolute;
                    left: 0;
                    top: 0;
                    white-space: nowrap;
                    color: #606266;
                }
                /* 自定义 ming Checkbox 全选 */
                label[title='Select All'] .Checkbox-text,
                label[title='Select All'] .Font13 {
                    visibility: hidden;
                    font-size: 0;
                }
                label[title='Select All'] {
                    position: relative;
                }
                label[title='Select All']::after {
                    content: '全选';
                    visibility: visible;
                    position: absolute;
                    left: 20px;
                    top: 50%;
                    transform: translateY(-50%);
                    white-space: nowrap;
                    font-size: 13px;
                    color: #606266;
                }
            `;
            document.head.appendChild(style);
        """)

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
        // 获取表单标签文本（去除空格和括号内容）
        function getLabelText(item) {
            return item.label.replace(/\s+/g, '').replace(/[（(].*?[)）]/g, '');
        }
        
        // 生成标签变体（同义词）
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
        
        function findInput(item){
            // 首先尝试 id/name
            var el = document.getElementById(item.id) || document.querySelector('[name="'+item.id+'"]');
            if(el && isValidInput(el)) return el;
            
            if(!item.label) return null;
            
            var cleanLabel = getLabelText(item);
            var variants = getLabelVariants(cleanLabel);
            
            // 遍历自定义表单项 .customFormItem
            var formItems = document.querySelectorAll('.customFormItem');
            for(var i=0; i<formItems.length; i++){
                var formItem = formItems[i];
                var labelDiv = formItem.querySelector('.customFormItemLabel .controlLabelName');
                if(!labelDiv) continue;
                var labelText = (labelDiv.innerText || labelDiv.textContent || '').replace(/\s+/g, '');
                
                var matched = false;
                for(var v=0; v<variants.length; v++){
                    if(labelText.indexOf(variants[v]) > -1 || variants[v].indexOf(labelText) > -1){
                        matched = true;
                        break;
                    }
                }
                
                if(matched){
                    var input = formItem.querySelector('input[type="text"], input[type="number"], input:not([type])');
                    if(input && isValidInput(input)) return input;
                }
            }
            
            // 宽泛兜底：只根据标签文字查找
            var allLabels = document.querySelectorAll('.controlLabelName');
            for(var j=0; j<allLabels.length; j++){
                var lbl = allLabels[j];
                var lblText = (lbl.innerText || lbl.textContent || '').replace(/\s+/g, '');
                var matched = false;
                for(var v=0; v<variants.length; v++){
                    if(lblText.indexOf(variants[v]) > -1 || variants[v].indexOf(lblText) > -1){
                        matched = true;
                        break;
                    }
                }
                if(matched){
                    var formItem = lbl.closest('.customFormItem');
                    if(formItem){
                        var input = formItem.querySelector('input[type="text"], input[type="number"], input:not([type])');
                        if(input && isValidInput(input)) return input;
                    }
                }
            }
            
            return null;
        }

        function isValidInput(el){
            if(!el) return false;
            var tag = (el.tagName || '').toUpperCase();
            if(tag !== 'INPUT' && tag !== 'TEXTAREA') return false;
            var type = (el.type || '').toLowerCase();
            if(type === 'checkbox' || type === 'radio' || type === 'hidden' || type === 'button' || type === 'submit' || type === 'file') return false;
            if(el.readOnly || el.disabled) return false;
            if(el.closest && el.closest('thead')) return false;
            if(el.offsetWidth === 0 || el.offsetHeight === 0) return false;
            return true;
        }

        function setVal(el, v){
            if(document.activeElement === el){
                el.blur();
            }

            var numericV = Number(v);
            var finalV = isNaN(numericV) ? v : numericV;

            try {
                var p = el;
                while (p && !p.__vue__) { p = p.parentElement; }
                if (p && p.__vue__) {
                    var comp = p.__vue__;
                    if(comp.hasOwnProperty('value')) comp.value = finalV;
                    if(typeof comp.setCurrentValue === 'function') comp.setCurrentValue(finalV);
                    if(typeof comp.handleInput === 'function') comp.handleInput(finalV);
                    if(comp.${'$'}emit){
                        comp.${'$'}emit('input', finalV);
                        comp.${'$'}emit('change', finalV);
                    }
                }
            } catch(e){}

            try {
                var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                if(setter){ setter.call(el, finalV); } else { el.value = finalV; }
                
                window.__ocr_bypass_rounding = true;
                el.dispatchEvent(new Event('focus', {bubbles:true}));
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                el.dispatchEvent(new Event('blur', {bubbles:true}));
                window.__ocr_bypass_rounding = false;
            } catch(e){}
            el.setAttribute('data-ocr-filled', v);
        }

        window.__ocrLastFilledCount = -1;
        function scanAndAutofillEngine(){
            var currentFilled = 0;
            for(var i=0; i<data.length; i++){
                var item = data[i];
                var el = findInput(item);
                if(el){
                    var needsFill = (el.value !== item.v && el.value !== String(Number(item.v)));
                    if(needsFill){
                        setVal(el, item.v);
                    }
                    if(el.value === item.v || el.value === String(Number(item.v)) || el.getAttribute('data-ocr-filled') === item.v){
                        currentFilled++;
                    }
                }
            }
        """)

        // 泵/阀门复选框过滤
        for (pid in pumpIds) {
            sb.append("""
            var chk=document.getElementById('${pid.esc()}')||document.querySelector('[value="${pid.esc()}"],[name="${pid.esc()}"]');
            if(chk && chk.type==='checkbox' && chk.offsetWidth > 0){
                if(!chk.closest('thead') && 
                   !chk.closest('.el-table__header') &&
                   !chk.closest('.el-table__header-wrapper') &&
                   chk.getAttribute('is-indeterminate') === null &&
                   chk.className.indexOf('el-table__header') === -1 &&
                   chk.id.indexOf('select-all') === -1 && 
                   chk.className.indexOf('select-all') === -1 &&
                   chk.id.indexOf('check-all') === -1 && 
                   chk.className.indexOf('check-all') === -1){
                    if(chk.getAttribute('data-ocr-filled') !== 'true'){
                        var cs=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'checked');
                        if(cs&&cs.set){cs.set.call(chk,true);}else{chk.checked=true;}
                        chk.dispatchEvent(new Event('change',{bubbles:true}));
                        chk.setAttribute('data-ocr-filled', 'true');
                    }
                    currentFilled++;
                }
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
