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
        // 轮询扫描网页 DOM 的间隔时间（毫秒），500ms 既能保证切换标签秒填，又完全不占用 CPU 性能
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
                    act.binding.tvStatusBanner.text = "✅ 自动填表引擎已就绪！成功填入 $count 个字段，请核对。"
                } else {
                    act.binding.tvStatusBanner.text = "⏳ 等待表单或新标签页渲染中，识别数据已就绪…"
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

        // 编译全新的“自适应双向注入引擎”
        fillJsPayload = compileFillJs(keys, values, pumpIds)
        initWebView()
        if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
    }

    @SuppressLint("SetJavaScriptEnabled","WebViewClientOnReceivedSslError")
    private fun initWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled  = true
            domStorageEnabled  = true
            mixedContentMode   = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // 放开混用，防止内网图片或脚本走 http 导致无法渲染
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
                    
                    // 页面框架一好，立刻注入核心轮询守护脚本，彻底抛弃原来不靠谱的点击拦截器
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
                handler?.proceed() // 强行信任医院内网所有未认证或过期的 SSL 证书
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
        sb.append("if(window.__ocrFillEngineStarted) return;") // 防止重复注入守护线程
        sb.append("window.__ocrFillEngineStarted = true;")
        
        // 拦截并魔改全局 Number.prototype.toFixed
        // 当我们自动填表派发 change/blur 事件时，强行短路网页前端的四舍五入逻辑！
        sb.append("""
            if(!window.__toFixedHooked){
                window.__toFixedHooked = true;
                var originalToFixed = Number.prototype.toFixed;
                Number.prototype.toFixed = function(digits){
                    if(window.__ocr_bypass_rounding){
                        return this.toString(); 
                    }
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
        // 统一 DOM 探测算法
        function findInput(item){
            var el = document.getElementById(item.id) || document.querySelector('[name="'+item.id+'"]');
            if(el) return el;
            if(!item.label) return null;
            
            var cleanLabel = item.label.replace(/\s+/g, '');
            var allElements = document.querySelectorAll('input:not([type=hidden]):not([type=radio]):not([type=checkbox]), textarea');
            
            // 扫描包含 label 文本的上下级节点进行兜底穿透定位
            var allNodes = document.querySelectorAll('*');
            for(var i=0; i<allNodes.length; i++){
                var node = allNodes[i];
                if(node.children.length > 0 && node.tagName !== 'LABEL' && node.tagName !== 'TD' && node.tagName !== 'TH') continue;
                var txt = (node.innerText || node.textContent || '').replace(/\s+/g, '');
                if(txt.indexOf(cleanLabel) >= 0){
                    var parent = node;
                    for(var j=0; j<8; j++){
                        if(!parent || parent.tagName === 'BODY') break;
                        var input = parent.querySelector('input:not([type=hidden]):not([type=radio]):not([type=checkbox]), textarea');
                        if(input) return input;
                        parent = parent.parentElement;
                    }
                }
            }
            return null;
        }

        // 高级注入函数：完美破解 Vue/React 拦截和四舍五入
        function setVal(el, v){
            // 【保护机制1】：如果抄表员当前正把光标聚焦在这个输入框上手工修改，APP 绝对不去覆盖他！
            if (document.activeElement === el) return;

            // 【破解四舍五入1】：扫描破坏 Element UI 组件内部限制
            try {
                var vNode = el;
                for(var k=0; k<4 && vNode; k++) {
                    if(vNode.__vue__) {
                        if(vNode.__vue__.precision !== undefined) vNode.__vue__.precision = 4; // 强行扩大组件精度到4位，破除四舍五入
                        if(vNode.__vue__.max !== undefined) vNode.__vue__.max = 999999;
                        if(vNode.__vue__.min !== undefined) vNode.__vue__.min = -999999;
                    }
                    vNode = vNode.parentElement;
                }
            } catch(e){}

            // 破除 HTML5 原生限制
            el.setAttribute('step', 'any');
            el.removeAttribute('maxlength');

            // 突破现代前端框架的双向绑定劫持
            var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
            var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
            if(setter){ setter.call(el, v); } else { el.value = v; }
            
            // 【破解四舍五入2】：开启瞬时全局绕过锁
            window.__ocr_bypass_rounding = true;

            // 完美模拟一整套标准人类键盘输入事件流，触发网页框架的状态更新更新
            el.dispatchEvent(new Event('focus', {bubbles:true}));
            el.dispatchEvent(new Event('input', {bubbles: true}));
            el.dispatchEvent(new Event('change', {bubbles: true}));
            el.dispatchEvent(new Event('blur', {bubbles:true}));
            
            // 关闭瞬时锁，恢复网页其余正常业务
            window.__ocr_bypass_rounding = false;

            // 【保护机制2】：标记此 DOM 已被 APP 处理完完毕
            el.setAttribute('data-ocr-filled', 'true');
        }

        // 全局核心轮询扫描引擎
        window.__ocrLastFilledCount = -1;
        function scanAndAutofillEngine(){
            var currentFilled = 0;
            
            // 1. 处理文本输入框
            for(var i=0; i<data.length; i++){
                var item = data[i];
                var el = findInput(item);
                if(el){
                    // 只有当这个元素没有被 APP 自动填写过，且用户没有把光标放在上面时，才允许填入
                    if(el.getAttribute('data-ocr-filled') !== 'true'){
                        if (document.activeElement !== el) {
                            setVal(el, item.v);
                        }
                    }
                    if(el.getAttribute('data-ocr-filled') === 'true'){
                        currentFilled++;
                    }
                }
            }
            
            // 2. 处理泵、阀门复选框
        """)
        
        for (pid in pumpIds) {
            sb.append("""
            var chk=document.getElementById('${pid.esc()}')||document.querySelector('[value="${pid.esc()}"],[name="${pid.esc()}"]');
            if(chk && chk.type==='checkbox'){
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
            // 3. 跨进程通知：当检测到填入总数发生变化（比如用户切换了标签页，老框消失了，新框被秒填了）
            if(currentFilled !== window.__ocrLastFilledCount){
                window.__ocrLastFilledCount = currentFilled;
                if(window.AndroidBridge && window.AndroidBridge.onFillComplete){
                    AndroidBridge.onFillComplete(currentFilled);
                }
            }
        }

        // 启动守护：每 500ms 自动扫描一次。
        // 当抄表人员点击网页上任何标签页时，新标签的输入框一加载出来，就会在 500ms 内瞬间被自动填满！
        // 且由于防覆盖和聚焦保护机制，抄表员手工修改任何内容都绝不会被这个守护进程干扰和覆盖！
        setInterval(scanAndAutofillEngine, $AUTO_SCAN_INTERVAL_MS);
        scanAndAutofillEngine(); // 立即执行首次初筛
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
