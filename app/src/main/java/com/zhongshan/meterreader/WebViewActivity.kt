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

        // 全选 CSS 修复
        sb.append("""
            var style=document.createElement('style');
            style.innerHTML=`
                .el-table__header-wrapper .el-checkbox__label,
                .el-table__header .el-checkbox__label { visibility: hidden; position: relative; }
                .el-table__header-wrapper .el-checkbox__label:after,
                .el-table__header .el-checkbox__label:after {
                    content: '全选'; visibility: visible !important;
                    position: absolute; left: 0; top: 0;
                    white-space: nowrap; color: #606266;
                }
                label[title='Select All'] .Checkbox-text,
                label[title='Select All'] .Font13 { visibility: hidden; font-size: 0; }
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

        sb.append("var targetData = [];\n")
        for (i in keys.indices) {
            val parts = keys[i].split("|")
            val fid   = parts[0].esc()
            val label = if (parts.size > 1) parts[1].esc() else ""
            val v     = values[i].esc()
            sb.append("targetData.push({id:'$fid', label:'$label', v:'$v'});\n")
        }

        sb.append("""
            function getLabelText(item) {
                return item.label.replace(/\\s+/g, '').replace(/[（(].*?[)）]/g, '');
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
                if(el.closest && (el.closest('thead') || el.closest('.el-table__header-wrapper'))) return false;
                if(el.offsetWidth === 0 && el.offsetHeight === 0) return false;
                return true;
            }

            // 从标签中提取机组号（如 "1#蒸发器进口水温" -> "1#"）
            function extractMachinePrefix(label) {
                var m = label.match(/^(\\d+#)/);
                return m ? m[1] : null;
            }

            function findInput(item){
                var el = document.getElementById(item.id) || document.querySelector('[name="'+item.id+'"]');
                if(el && isValidInput(el)) {
                    AndroidBridge.log('通过ID/Name找到输入框: ' + item.label);
                    return el;
                }
                if(!item.label) return null;

                var cleanLabel = getLabelText(item);
                var variants = getLabelVariants(cleanLabel);
                var machinePrefix = extractMachinePrefix(cleanLabel);
                var coreLabel = machinePrefix ? cleanLabel.substring(machinePrefix.length) : cleanLabel;

                // 1. placeholder 嗅探
                var allInputs = document.querySelectorAll('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])');
                for(var i=0; i<allInputs.length; i++){
                    var inp = allInputs[i];
                    if(!isValidInput(inp)) continue;
                    var ph = (inp.placeholder || '').replace(/\\s+/g, '');
                    if(ph) {
                        for(var v=0; v<variants.length; v++){
                            if(ph.indexOf(variants[v]) > -1) {
                                AndroidBridge.log('通过placeholder找到输入框: ' + item.label);
                                return inp;
                            }
                        }
                    }
                }

                // 2. 表格列映射（带机组行定位）
                var allTables = document.querySelectorAll('table, .el-table');
                for(var t=0; t<allTables.length; t++){
                    var table = allTables[t];
                    var ths = table.querySelectorAll('th');
                    var colIndex = -1;
                    for(var h=0; h<ths.length; h++){
                        var thText = (ths[h].innerText || ths[h].textContent || '').replace(/\\s+/g, '');
                        for(var v=0; v<variants.length; v++){
                            if(thText.indexOf(variants[v]) > -1 || variants[v].indexOf(thText) > -1){
                                colIndex = ths[h].cellIndex;
                                break;
                            }
                        }
                        if(colIndex > -1) break;
                    }
                    if(colIndex > -1){
                        var rows = table.querySelectorAll('tbody tr, .el-table__row');
                        for(var r=0; r<rows.length; r++){
                            var row = rows[r];
                            if(machinePrefix){
                                // 需要匹配机组号：检查该行第一个单元格或整行文本是否包含机组前缀
                                var firstCell = row.cells ? row.cells[0] : null;
                                var rowIdentifier = firstCell ? (firstCell.innerText || firstCell.textContent || '').replace(/\\s+/g, '') : (row.innerText || row.textContent || '').replace(/\\s+/g, '');
                                if(rowIdentifier.indexOf(machinePrefix) === -1) continue; // 跳过不匹配的行
                            }
                            var cells = row.cells;
                            if(cells && cells[colIndex]){
                                var input = cells[colIndex].querySelector('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])');
                                if(input && isValidInput(input)) {
                                    AndroidBridge.log('通过表格列映射找到输入框: ' + item.label);
                                    return input;
                                }
                            }
                        }
                    }
                }

                // 3. 全局邻近搜索（不使用data-ocr-filled过滤以支持重新定位）
                var textElements = document.querySelectorAll('span, td, div, p, label');
                for(var i=0; i<textElements.length; i++){
                    var node = textElements[i];
                    if(node.children.length > 3) continue;
                    var text = (node.innerText || node.textContent || '').replace(/\\s+/g, '');
                    if(!text) continue;
                    for(var v=0; v<variants.length; v++){
                        if(text.indexOf(variants[v]) > -1 || variants[v].indexOf(text) > -1){
                            var input = node.querySelector('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])');
                            if(input && isValidInput(input)) {
                                AndroidBridge.log('通过文本邻近找到输入框: ' + item.label);
                                return input;
                            }
                            var sibling = node.nextElementSibling;
                            while(sibling){
                                input = sibling.querySelector('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])') || (sibling.tagName==='INPUT'?sibling:null);
                                if(input && isValidInput(input)) {
                                    AndroidBridge.log('通过兄弟元素找到输入框: ' + item.label);
                                    return input;
                                }
                                sibling = sibling.nextElementSibling;
                            }
                            var parent = node.parentElement;
                            var depth = 0;
                            while(parent && depth < 3 && parent.tagName !== 'BODY'){
                                var pSibling = parent.nextElementSibling;
                                while(pSibling){
                                    input = pSibling.querySelector('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])') || (pSibling.tagName==='INPUT'?pSibling:null);
                                    if(input && isValidInput(input)) {
                                        AndroidBridge.log('通过父级兄弟找到输入框: ' + item.label);
                                        return input;
                                    }
                                    pSibling = pSibling.nextElementSibling;
                                }
                                var parentInputs = parent.querySelectorAll('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])');
                                for(var k=0; k<parentInputs.length; k++){
                                    if(isValidInput(parentInputs[k])) {
                                        AndroidBridge.log('通过父级容器找到输入框: ' + item.label);
                                        return parentInputs[k];
                                    }
                                }
                                parent = parent.parentElement;
                                depth++;
                            }
                        }
                    }
                }
                AndroidBridge.log('未找到输入框: ' + item.label + ' (ID: ' + item.id + ')');
                return null;
            }

            function triggerNativeSetter(el, valStr) {
                el.removeAttribute('readonly');
                el.removeAttribute('disabled');
                var proto = Object.getPrototypeOf(el);
                var protoSetter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                var ownSetter = Object.getOwnPropertyDescriptor(el, 'value')?.set;
                if (protoSetter && ownSetter !== protoSetter) {
                    protoSetter.call(el, valStr);
                } else if (ownSetter) {
                    ownSetter.call(el, valStr);
                } else {
                    el.value = valStr;
                }
                el.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                el.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
            }

            function getNativeValue(el) {
                var proto = Object.getPrototypeOf(el);
                var getter = Object.getOwnPropertyDescriptor(proto, 'value')?.get;
                if (getter) { return getter.call(el); }
                return el.value;
            }

            function forceSetValue(el, v) {
                var valStr = v.toString();
                el.dispatchEvent(new Event('focus', { bubbles: true }));
                triggerNativeSetter(el, valStr);
                el.dispatchEvent(new Event('blur', { bubbles: true }));
                el.setAttribute('data-ocr-filled', valStr);
            }

            function isPageReady() {
                return document.querySelectorAll('.el-loading-mask, .ant-spin-container, .loading-mask').length === 0;
            }

            window.__ocrLastFilledCount = -1;
            
            function scanAndAutofillEngine(){
                if (!isPageReady()) return;
                var currentFilled = 0;
                for(var i=0; i<targetData.length; i++){
                    var item = targetData[i];
                    var el = findInput(item);
                    if(el){
                        var valStr = item.v.toString();
                        var nativeVal = getNativeValue(el);
                        var currentVal = nativeVal ? nativeVal.toString() : '';
                        if (currentVal !== valStr && el.getAttribute('data-ocr-filled') !== valStr) {
                            forceSetValue(el, item.v);
                            AndroidBridge.log('填入值: ' + item.label + ' = ' + valStr);
                        }
                        if (nativeVal?.toString() === valStr || el.getAttribute('data-ocr-filled') === valStr) {
                            currentFilled++;
                        }
                    }
                }
        """)

        // 复选框注入
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
                            if(cs && cs.set) { cs.set.call(chk, true); } else { chk.checked = true; }
                            chk.dispatchEvent(new Event('change', { bubbles: true }));
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
