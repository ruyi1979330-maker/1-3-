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
	        // 专用于输出 DOM 调试信息的接口
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
	                    // 优先注入 DOM 轮询探测脚本，它会等待表单渲染完毕后抓取真实结构
	                    view?.evaluateJavascript(getDomDebugJs(), null)
	                    // 继续执行原有填表逻辑（它内部也有轮询，不干扰调试）
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
	    // 升级版：动态轮询等待 SPA 渲染完毕，然后抓取真实 DOM 结构
	    private fun getDomDebugJs(): String {
	        return """
	            (function() {
	                if (window.__domRadarStarted) return;
	                window.__domRadarStarted = true;
	                var attempts = 0;
	                var maxAttempts = 20; // 最多等待 10 秒 (20 * 500ms)
	                AndroidBridge.domLog('DOM 雷达已启动，正在等待表单渲染...');
	                var timer = setInterval(function() {
	                    attempts++;
	                    var inputs = document.querySelectorAll('input, textarea, select');
	                    AndroidBridge.domLog('雷达扫描第 ' + attempts + ' 次，发现表单元素: ' + inputs.length + ' 个');
	                    // 如果找到表单元素，或者超时了，就开始抓取并停止雷达
	                    if (inputs.length > 0 || attempts >= maxAttempts) {
	                        clearInterval(timer);
	                        var logMsg = '\n=== 真实 DOM 抓取开始 ===\n';
	                        // 1. 抓取页面前 3000 个字符的 HTML，看整体结构
	                        var bodyHtml = document.body ? document.body.innerHTML : '无 body';
	                        if (bodyHtml.length > 3000) bodyHtml = bodyHtml.substring(0, 3000) + '... [已截断]';
	                        logMsg += '--- Body HTML 片段 ---\n' + bodyHtml + '\n\n';
	                        // 2. 抓取所有 Input/Textarea 的核心属性
	                        logMsg += '--- 表单元素详情 ---\n';
	                        for (var i = 0; i < inputs.length; i++) {
	                            var el = inputs[i];
	                            logMsg += 'Element[' + i + ']: <' + el.tagName + '> id="' + (el.id||'') + '" name="' + (el.name||'') + '" type="' + (el.type||'') + '" class="' + (el.className||'') + '" placeholder="' + (el.placeholder||'') + '"\n';
	                        }
	                        // 3. 抓取所有疑似 Tab 的元素
	                        var tabs = document.querySelectorAll('[class*="tab"], [class*="Tab"], [role="tab"], li, .el-tabs__item');
	                        logMsg += '\n--- 疑似 Tab 元素详情 ---\n';
	                        var tabCount = 0;
	                        for (var j = 0; j < tabs.length; j++) {
	                            var t = tabs[j];
	                            var text = (t.innerText || t.textContent || '').trim();
	                            if (text.length > 0 && text.length < 30) {
	                                logMsg += 'Tab[' + tabCount + ']: text="' + text + '" class="' + (t.className||'') + '"\n';
	                                tabCount++;
	                                if (tabCount >= 30) break;
	                            }
	                        }
	                        logMsg += '=== 真实 DOM 抓取结束 ===';
	                        AndroidBridge.domLog(logMsg);
	                    }
	                }, 500);
	            })();
	        """
	    }
	    private fun compileFillJs(keys: Array<String>, values: Array<String>, pumpIds: Array<String>): String {
	        val sb = StringBuilder()
	        sb.append("(function(){\n")
	        sb.append("if(window.__ocrFillEngineStarted) return;\n")
	        sb.append("window.__ocrFillEngineStarted = true;\n")
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
	        val pumpItems = mutableListOf<Pair<String, String>>()
	        for (i in keys.indices) {
	            val key = keys[i]
	            if (key.startsWith("__pump__")) {
	                pumpItems.add(Pair(key.removePrefix("__pump__"), values[i]))
	                continue
	            }
	            val parts = key.split("|")
	            val fid   = parts[0].esc()
	            val label = if (parts.size > 1) parts[1].esc() else ""
	            val v     = values[i].esc()
	            sb.append("targetData.push({id:'$fid', label:'$label', v:'$v'});\n")
	        }
	        sb.append("""
	            var dominantMachinePrefix = null;
	            var prefixCount = {};
	            for(var i=0; i<targetData.length; i++){
	                var m = targetData[i].label.match(/^(\\d+#)/);
	                if(m){
	                    var p = m[1];
	                    prefixCount[p] = (prefixCount[p]||0)+1;
	                }
	            }
	            var maxCount = 0;
	            for(var p in prefixCount){
	                if(prefixCount[p] > maxCount){
	                    maxCount = prefixCount[p];
	                    dominantMachinePrefix = p;
	                }
	            }
	            AndroidBridge.log('主流机组前缀: ' + dominantMachinePrefix);
	        """)
	        sb.append("""
	            function getLabelText(item) {
	                var t = item.label.replace(/\\s+/g, '')
	                                   .replace(/[（(].*?[)）]/g, '')
	                                   .replace(/[【\\[].*?[\\]】]/g, '');
	                t = t.replace(/[：:]/g, '').replace(/[，,]/g, '');
	                return t;
	            }
	            function getLabelVariants(label) {
	                var base = getLabelText({label: label});
	                var variants = [base];
	                if(base.indexOf('进水') > -1) variants.push(base.replace('进水','进口'));
	                if(base.indexOf('进口') > -1) variants.push(base.replace('进口','进水'));
	                if(base.indexOf('出水') > -1) variants.push(base.replace('出水','出口'));
	                if(base.indexOf('出口') > -1) variants.push(base.replace('出口','出水'));
	                if(base.indexOf('返回') > -1) variants.push(base.replace('返回','回水'));
	                if(base.indexOf('回水') > -1) variants.push(base.replace('回水','返回'));
	                if(base.indexOf('冷媒') > -1) variants.push(base.replace('冷媒','制冷剂'));
	                if(base.indexOf('制冷剂') > -1) variants.push(base.replace('制冷剂','冷媒'));
	                if(base.indexOf('压力') > -1) variants.push(base.replace('压力','压强'));
	                if(base.indexOf('温度') > -1) variants.push(base.replace('温度','温'));
	                if(base.indexOf('排口') > -1) { variants.push(base.replace('排口','排出口')); variants.push(base.replace('排口','排出')); }
	                if(base.indexOf('排出口') > -1) { variants.push(base.replace('排出口','排口')); variants.push(base.replace('排出口','排出')); }
	                if(base.indexOf('排出') > -1) { variants.push(base.replace('排出','排口')); variants.push(base.replace('排出','排出口')); }
	                if(base.indexOf('导液开度') > -1) variants.push(base.replace('导液开度','滑阀位置'));
	                if(base.indexOf('滑阀位置') > -1) variants.push(base.replace('滑阀位置','导液开度'));
	                if(base.indexOf('主机负载') > -1) variants.push(base.replace('主机负载','%RLA'));
	                if(base.indexOf('油压') > -1 && base.indexOf('油压差') === -1) variants.push(base.replace('油压','油压差'));
	                if(base.indexOf('油压差') > -1) variants.push(base.replace('油压差','油压'));
	                if(base.indexOf('油箱温度') > -1) { variants.push(base.replace('油箱温度','油槽温度')); variants.push(base.replace('油箱温度','油温')); }
	                if(base.indexOf('油槽温度') > -1) { variants.push(base.replace('油槽温度','油箱温度')); variants.push(base.replace('油槽温度','油温')); }
	                if(base.indexOf('油温') > -1) { variants.push(base.replace('油温','油箱温度')); variants.push(base.replace('油温','油槽温度')); }
	                if(base.indexOf('蒸发温度') > -1) variants.push(base.replace('蒸发温度','蒸发器饱和温度'));
	                if(base.indexOf('饱和温度') > -1) {
	                    if(base.indexOf('蒸发') > -1) variants.push(base.replace('蒸发器饱和温度','蒸发温度'));
	                    if(base.indexOf('冷凝') > -1) variants.push(base.replace('冷凝器饱和温度','冷凝温度'));
	                }
	                if(base.indexOf('冷凝温度') > -1) variants.push(base.replace('冷凝温度','冷凝器饱和温度'));
	                if(base.indexOf('电机电流') > -1) variants.push(base.replace('电机电流','电流'));
	                if(base.indexOf('排出口温度') > -1) variants.push(base.replace('排出口温度','排出端冷剂温度'));
	                if(!(/^\\d+#/).test(base) && dominantMachinePrefix){
	                    variants.push(dominantMachinePrefix + base);
	                    var withPrefix = [];
	                    for(var v=0; v<variants.length; v++){
	                        if(!(/^\\d+#/).test(variants[v])){
	                            withPrefix.push(dominantMachinePrefix + variants[v]);
	                        }
	                    }
	                    variants = variants.concat(withPrefix);
	                }
	                var unique = [];
	                for(var i=0; i<variants.length; i++){
	                    if(unique.indexOf(variants[i]) === -1) unique.push(variants[i]);
	                }
	                return unique;
	            }
	            function isValidInput(el){
	                if(!el) return false;
	                var tag = (el.tagName || '').toUpperCase();
	                if(tag !== 'INPUT' && tag !== 'TEXTAREA') return false;
	                var type = (el.type || '').toLowerCase();
	                if(['checkbox', 'radio', 'hidden', 'button', 'submit', 'file'].indexOf(type) > -1) return false;
	                if(el.readOnly || el.disabled) return false;
	                if(el.offsetWidth === 0 && el.offsetHeight === 0) return false;
	                return true;
	            }
	            function extractMachinePrefix(text) {
	                var m = text.match(/^(\\d+#)/);
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
	                var variants = getLabelVariants(item.label);
	                var machinePrefix = extractMachinePrefix(cleanLabel) || dominantMachinePrefix;
	                var coreLabel = machinePrefix && cleanLabel.indexOf(machinePrefix)===0 ? cleanLabel.substring(machinePrefix.length) : cleanLabel;
	                AndroidBridge.log('查找: ' + item.label + ' core=' + coreLabel + ' prefix=' + machinePrefix);
	                var allInputs = document.querySelectorAll('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])');
	                for(var i=0; i<allInputs.length; i++){
	                    var inp = allInputs[i];
	                    if(!isValidInput(inp)) continue;
	                    var ph = (inp.placeholder || '').replace(/\\s+/g, '');
	                    if(ph) {
	                        for(var v=0; v<variants.length; v++){
	                            if(ph.indexOf(variants[v]) > -1 || variants[v].indexOf(ph) > -1) {
	                                AndroidBridge.log('placeholder匹配: ' + item.label);
	                                return inp;
	                            }
	                        }
	                    }
	                }
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
	                                var firstCell = row.cells ? row.cells[0] : null;
	                                var rowIdentifier = firstCell ? (firstCell.innerText || firstCell.textContent || '').replace(/\\s+/g, '') : (row.innerText || row.textContent || '').replace(/\\s+/g, '');
	                                if(rowIdentifier.indexOf(machinePrefix) === -1) continue;
	                            }
	                            var cells = row.cells;
	                            if(cells && cells[colIndex]){
	                                var input = cells[colIndex].querySelector('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])');
	                                if(input && isValidInput(input)) {
	                                    AndroidBridge.log('表格定位: ' + item.label);
	                                    return input;
	                                }
	                            }
	                        }
	                    }
	                }
	                var textElements = document.querySelectorAll('span, td, div, p, label, .el-form-item__label');
	                for(var i=0; i<textElements.length; i++){
	                    var node = textElements[i];
	                    var text = (node.innerText || node.textContent || '').replace(/\\s+/g, '');
	                    if(!text) continue;
	                    for(var v=0; v<variants.length; v++){
	                        if(text.indexOf(variants[v]) > -1 || variants[v].indexOf(text) > -1){
	                            var input = node.querySelector('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])');
	                            if(input && isValidInput(input)) { AndroidBridge.log('自身查找: ' + item.label); return input; }
	                            var sibling = node.nextElementSibling;
	                            while(sibling){
	                                input = sibling.querySelector('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])') || (sibling.tagName==='INPUT'?sibling:null);
	                                if(input && isValidInput(input)) { AndroidBridge.log('兄弟元素: ' + item.label); return input; }
	                                sibling = sibling.nextElementSibling;
	                            }
	                            var parent = node.parentElement;
	                            var depth = 0;
	                            while(parent && depth < 4 && parent.tagName !== 'BODY'){
	                                var pSibling = parent.nextElementSibling;
	                                while(pSibling){
	                                    input = pSibling.querySelector('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])') || (pSibling.tagName==='INPUT'?pSibling:null);
	                                    if(input && isValidInput(input)) { AndroidBridge.log('父级兄弟: ' + item.label); return input; }
	                                    pSibling = pSibling.nextElementSibling;
	                                }
	                                var parentInputs = parent.querySelectorAll('input:not([data-ocr-filled]), textarea:not([data-ocr-filled])');
	                                for(var k=0; k<parentInputs.length; k++){
	                                    if(isValidInput(parentInputs[k])) { AndroidBridge.log('父级容器: ' + item.label); return parentInputs[k]; }
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
	        for (pumpItem in pumpItems) {
	            val safeId = pumpItem.first.esc()
	            sb.append("""
	                var chk = document.getElementById('$safeId') || document.querySelector('[value="$safeId"], [name="$safeId"]');
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
