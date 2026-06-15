package com.zhongshan.meterreader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        private const val FIELD_FILL_INTERVAL = 120
    }

    internal lateinit var binding: ActivityWebviewBinding
    private var targetUrl   = ""
    private var fillJsPayload = ""

    private val timeoutHandler      = Handler(Looper.getMainLooper())
    private val pageLoadGeneration  = AtomicInteger(0)
    private val isFillDone          = AtomicBoolean(false)
    private val isMonitorInjected   = AtomicBoolean(false)

    private fun String.esc() =
        replace("\\","\\\\").replace("'","\\'").replace("\n","\\n").replace("\r","")

    // =====================================================================
    // JS → Kotlin 回调桥
    // =====================================================================
    class SafeWebBridge(activity: WebViewActivity) {
        private val ref = WeakReference(activity)

        @JavascriptInterface
        fun onTabClicked(tabText: String) {
            ref.get()?.runOnUiThread {
                val act = ref.get() ?: return@runOnUiThread
                act.binding.tvStatusBanner.visibility = View.VISIBLE
                act.binding.tvStatusBanner.text = "⏳ 检测到「$tabText」被点击，正在等待渲染后自动填表…"
                
                // 【修复】：阶梯式多次重试，应对 SPA 异步渲染延迟
                val delays = listOf(800L, 1500L, 2500L)
                for (delay in delays) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!act.isFinishing && !act.isDestroyed && !act.isFillDone.get()) {
                            act.isFillDone.set(false) // 重置状态以允许重试
                            act.binding.webView.evaluateJavascript(act.fillJsPayload, null)
                        }
                    }, delay)
                }
            }
        }

        @JavascriptInterface
        fun onFillComplete(count: Int) {
            ref.get()?.runOnUiThread {
                val act = ref.get() ?: return@runOnUiThread
                if (act.isFillDone.compareAndSet(false, true)) {
                    act.binding.tvStatusBanner.visibility = View.VISIBLE
                    act.binding.tvStatusBanner.text =
                        if (count > 0) "✅ 成功填入 $count 个字段！请人工核对后提交。"
                        else           "⚠️ 填入0个字段。请确保当前标签页内容已完全加载。"
                }
            }
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
            mixedContentMode   = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        binding.webView.addJavascriptInterface(SafeWebBridge(this), "AndroidBridge")

        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                isFillDone.set(false)
                isMonitorInjected.set(false)
                binding.progressBar.visibility      = View.VISIBLE
                binding.tvStatusBanner.visibility   = View.VISIBLE
                binding.tvStatusBanner.text = "⏳ 表单加载中，请稍候…"

                val gen = pageLoadGeneration.incrementAndGet()
                timeoutHandler.postDelayed({
                    if (pageLoadGeneration.get() == gen) {
                        binding.progressBar.visibility    = View.GONE
                        binding.webView.stopLoading()
                        binding.layoutNetworkError.visibility = View.VISIBLE
                        binding.tvErrorMsg.text = "网络超时，机房信号不稳定，请稍后重试。"
                        binding.tvStatusBanner.visibility = View.GONE
                    }
                }, WEB_LOAD_TIMEOUT_MS)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoadGeneration.incrementAndGet()
                binding.progressBar.visibility = View.GONE
                val host = runCatching { android.net.Uri.parse(url ?: "").host }.getOrNull() ?: ""
                if (host == "appflow.zs-hospital.sh.cn") {
                    binding.tvStatusBanner.visibility = View.VISIBLE
                    binding.tvStatusBanner.text = "✅ 表单已加载！请手动点击上方对应标签页，APP 将自动填表。"

                    if (isMonitorInjected.compareAndSet(false, true)) {
                        view?.evaluateJavascript(compileTabMonitorJs(), null)
                    }
                }
            }

            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame == true) {
                    pageLoadGeneration.incrementAndGet()
                    isMonitorInjected.set(false)
                    binding.progressBar.visibility        = View.GONE
                    binding.layoutNetworkError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text               = "表单加载失败，请检查网络后重试。"
                    binding.tvStatusBanner.visibility     = View.GONE
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val host = runCatching { android.net.Uri.parse(error?.url ?: "").host }.getOrNull()
                if (host == "appflow.zs-hospital.sh.cn") handler?.proceed() else handler?.cancel()
            }
        }

        binding.btnRetryNetwork.setOnClickListener {
            binding.layoutNetworkError.visibility = View.GONE
            isMonitorInjected.set(false)
            isFillDone.set(false)
            if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
        }
    }

    // =====================================================================
    // Tab 点击监听 JS + MutationObserver
    // =====================================================================
    private fun compileTabMonitorJs(): String {
        val tabs = listOf("螺杆机组","离心机组","板交","螺杆机","交接班","操作记录")
        val tabsJs = tabs.joinToString(",") { "'${it.esc()}'" }
        return """
(function(){
  if(window.__tabMonitor) return;
  window.__tabMonitor = true;
  var knownTabs = [$tabsJs];
  function onDocClick(e){
    var t = e.target;
    for(var d=0; d<6 && t && t.tagName!=='BODY'; d++){
      var txt = (t.innerText||t.textContent||'').trim();
      for(var i=0; i<knownTabs.length; i++){
        if(txt===knownTabs[i]||(txt.indexOf(knownTabs[i])>-1&&txt.length<=knownTabs[i].length+3)){
          AndroidBridge.onTabClicked(knownTabs[i]); return;
        }
      }
      t = t.parentElement;
    }
  }
  document.addEventListener('click', onDocClick, true);
  
  // 监听 DOM 变化，应对标签页切换时的局部刷新
  var debounceTimer = null;
  var observer = new MutationObserver(function(mutations) {
    var hasChange = mutations.some(function(m) { return m.addedNodes.length > 2; });
    if (hasChange) {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(function() {
        if (window.__fillForm) window.__fillForm();
      }, 800);
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });
})();
        """.trimIndent()
    }

    // =====================================================================
    // 填表 JS — 终极修复版
    // =====================================================================
    private fun compileFillJs(keys: Array<String>, values: Array<String>, pumpIds: Array<String>): String {
        val sb = StringBuilder()
        sb.append("(function(){")
        sb.append("var data=[];")
        for (i in keys.indices) {
            val parts = keys[i].split("|")
            val fid   = parts[0].esc()
            val label = if (parts.size > 1) parts[1].esc() else ""
            val v     = values[i].esc()
            sb.append("data.push({id:'$fid',label:'$label',v:'$v'});")
        }
        sb.append("""
var idx=0, filled=0;
function findInput(item){
  var el = document.getElementById(item.id) || document.querySelector('[name="'+item.id+'"]');
  if(el) return el;
  if(!item.label) return null;
  
  // 【修复】：去除空格和换行，解决 "1#蒸发器进口 水温" 匹配失败问题
  var cleanLabel = item.label.replace(/\s+/g, '');
  var nodes = document.querySelectorAll('label,span,div,p,td,th,.title,.label');
  for(var i=0;i<nodes.length;i++){
    var txt=(nodes[i].innerText||nodes[i].textContent||'').replace(/\s+/g, '');
    if(txt.indexOf(cleanLabel)<0) continue;
    
    var par=nodes[i].parentElement; var dep=0;
    while(par && par.tagName!=='BODY' && dep<8){
      var ins=par.querySelector('input:not([type=radio]):not([type=checkbox]):not([type=hidden]),textarea');
      if(ins) return ins;
      par=par.parentElement; dep++;
    }
  }
  return null;
}

function setVal(el, v){
  // 【修复】：精准获取对应标签的 native setter，破解 React/Vue 填值免疫
  var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
  if(setter){ setter.call(el,v); } else { el.value=v; }
  
  el.dispatchEvent(new Event('focus', {bubbles:true}));
  el.dispatchEvent(new InputEvent('input', {bubbles:true, cancelable:true, inputType:'insertText', data:v}));
  el.dispatchEvent(new Event('change', {bubbles:true}));
  el.dispatchEvent(new Event('blur', {bubbles:true}));
}

window.__fillForm = function(){
  idx=0; filled=0;
  fillNext();
};

function fillNext(){
  if(idx>=data.length){
""")
        for (pid in pumpIds) {
            sb.append("""
    var chk=document.getElementById('$pid')||document.querySelector('[value="$pid"],[name="$pid"]');
    if(chk&&chk.type==='checkbox'){
      var cs=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'checked');
      if(cs&&cs.set){cs.set.call(chk,true);}else{chk.checked=true;}
      chk.dispatchEvent(new Event('change',{bubbles:true}));
    }
""")
        }
        sb.append("""
    AndroidBridge.onFillComplete(filled); return;
  }
  var item=data[idx];
  var el=findInput(item);
  if(el){ el.focus(); setVal(el,item.v); el.blur(); filled++; }
  idx++; setTimeout(fillNext,$FIELD_FILL_INTERVAL);
}
window.__fillForm();
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
