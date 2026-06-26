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
        // 核心轮询引擎扫描间隔（毫秒），500ms 既能保证切页秒填，又实现 0 功耗挂载
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
                    act.binding.tvStatusBanner.text = "✅ 自动填表引擎就绪！成功秒填 $count 个数据字段。"
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
                binding.tvStatusBanner.text = "⏳ 正在安全连接医院数据中心，请稍候…"

                val gen = pageLoadGeneration.incrementAndGet()
                timeoutHandler.postDelayed({
                    if (pageLoadGeneration.get() == gen) {
                        binding.progressBar.visibility    = View.GONE
                        binding.webView.stopLoading()
                        binding.layoutNetworkError.visibility = View.VISIBLE
                        binding.tvErrorMsg.text = "机房无线信号不稳定或网络超时，请重试。"
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
                    binding.tvStatusBanner.text = "🎉 连接成功！数据已托管，切换任意标签页均可自动秒填表。"
                    // 框架就绪，注入全自适应核心守护进程
                    view?.evaluateJavascript(fillJsPayload, null)
                }
            }

            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame == true) {
                    pageLoadGeneration.incrementAndGet()
                    binding.progressBar.visibility        = View.GONE
                    binding.layoutNetworkError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text               = "院内表单加载失败，请检查是否连接医院内网 Wi-Fi。"
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
        
        // 全局精准截断劫持：破除网页内置的四舍五入
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
        // 工业级高精度 DOM 探测算法
        function findInput(item){
            var el = document.getElementById(item.id) || document.querySelector('[name="'+item.id+'"]');
            if(el && isValidInput(el)) return el;
            if(!item.label) return null;
            
            var cleanLabel = item.label.replace(/\s+/g, '').replace(/（.*?）|\(.*?\)/g, ''); // 清洗单位
            
            // 提取前缀（例如将 "1#蒸发器进口水温" 拆解为机组 "1" 与核心指标 "蒸发器进口水温"）
            var machinePrefix = null;
            var coreMetric = cleanLabel;
            var matchPrefix = cleanLabel.match(/^(\d+)(#|号)/);
            if(matchPrefix){
                machinePrefix = matchPrefix[1];
                coreMetric = cleanLabel.substring(matchPrefix[0].length);
            }

            // 【策略 A】：网格表格交叉定位法（针对特灵螺杆机等多机组动态表格）
            if(machinePrefix){
                var tables = document.querySelectorAll('table, .el-table');
                for(var t=0; t<tables.length; t++){
                    var table = tables[t];
                    var colIndex = -1;
                    var ths = table.querySelectorAll('th');
                    // 搜寻对应的指标列
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
                            // 校验当前行是否隶属于对应的机组编号（支持 1#、1号、机组1 等变体）
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

            // 【策略 B】：扁平层级表单穿透定位法（兜底常规容器）
            var allNodes = document.querySelectorAll('*');
            for(var i=0; i<allNodes.length; i++){
                var node = allNodes[i];
                if(node.children.length > 0 && node.tagName !== 'LABEL' && node.tagName !== 'TD' && node.tagName !== 'TH') continue;
                
                var txt = (node.innerText || node.textContent || '').replace(/\s+/g, '');
                if(txt.indexOf(cleanLabel) >= 0 || (machinePrefix && txt.indexOf(coreMetric) >= 0)){
                    var parent = node;
                    for(var j=0; j<8; j++){
                        if(!parent || parent.tagName === 'BODY') break;
                        // 严禁误伤表头
                        if(parent.tagName === 'TR' && parent.closest('thead')) break;
                        
                        var input = parent.querySelector('input, textarea');
                        if(input && isValidInput(input)) return input;
                        parent = parent.parentElement;
                    }
                }
            }
            return null;
        }

        // 强力过滤隔离防护：防止对只读框、全选复选框、下拉框进行错误输入，彻底根治英文 Select All 崩溃
        function isValidInput(el){
            if(!el) return false;
            if(el.type === 'checkbox' || el.type === 'radio' || el.type === 'hidden') return false;
            if(el.readOnly || el.disabled) return false;
            // 排除表头中的控制件
            if(el.closest && (el.closest('thead') || el.closest('.el-table__header') || el.closest('.el-select'))) return false;
            // 排除隐藏克隆节点（防止多轨冲突）
            if(el.offsetWidth === 0 || el.offsetHeight === 0) return false;
            return true;
        }

        // 核心注入：直接入侵劫持 Vue 数据绑定，攻克约克离心机预设值复原问题
        function setVal(el, v){
            if (document.activeElement === el) return; // 抄表员手打时退避保护

            // 1. 深度入侵 Vue 数据链路
            try {
                var p = el;
                while (p && !p.__vue__) { p = p.parentElement; }
                if (p && p.__vue__) {
                    var comp = p.__vue__;
                    // 放开 Element UI 计数器组件的最大/最小/精度死锁限制
                    if(comp.precision !== undefined) comp.precision = 4;
                    if(comp.max !== undefined) comp.max = 999999;
                    if(comp.min !== undefined) comp.min = -999999;
                    
                    // 强行同步更新 Vue 组件核心双向状态，阻止其回滚到预设值
                    if(typeof comp.setCurrentValue === 'function') comp.setCurrentValue(v);
                    if(typeof comp.handleInput === 'function') comp.handleInput(v);
                    if(comp.value !== undefined) comp.value = v;
                    if(comp.$emit){
                        comp.$emit('input', v);
                        comp.$emit('change', v);
                    }
                }
            } catch(e){}

            // 2. 原生 HTML5 双重赋值兜底
            try {
                el.setAttribute('step', 'any');
                el.removeAttribute('maxlength');
                var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                if(setter){ setter.call(el, v); } else { el.value = v; }
                
                window.__ocr_bypass_rounding = true;
                // 按工业标准人类行为激活事件序列
                el.dispatchEvent(new Event('focus', {bubbles:true}));
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                el.dispatchEvent(new Event('blur', {bubbles:true}));
                window.__ocr_bypass_rounding = false;
            } catch(e){}

            el.setAttribute('data-ocr-filled', 'true');
        }

        // 全局扫描守护核心引擎
        window.__ocrLastFilledCount = -1;
        function scanAndAutofillEngine(){
            var currentFilled = 0;
            for(var i=0; i<data.length; i++){
                var item = data[i];
                var el = findInput(item);
                if(el){
                    if(el.getAttribute('data-ocr-filled') !== 'true'){
                        if (document.activeElement !== el) { setVal(el, item.v); }
                    }
                    if(el.getAttribute('data-ocr-filled') === 'true'){ currentFilled++; }
                }
            }
            
            // 泵、阀门复选框快速通道
        """)
        
        for (pid in pumpIds) {
            sb.append("""
            var chk=document.getElementById('${pid.esc()}')||document.querySelector('[value="${pid.esc()} colonial"],[name="${pid.esc()}"]');
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
            // 实时感知切页状态：只有当自动填写总量发生改变（例如切换了机组标签页导致老输入框消失新输入框诞生时），才会触发进程级 Banner 更新
            if(currentFilled !== window.__ocrLastFilledCount){
                window.__ocrLastFilledCount = currentFilled;
                if(window.AndroidBridge && window.AndroidBridge.onFillComplete){
                    AndroidBridge.onFillComplete(currentFilled);
                }
            }
        }

        // 启动轮询守护，切换任意标签页、任意按钮，均可在 500ms 内完美捕获秒填！
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
