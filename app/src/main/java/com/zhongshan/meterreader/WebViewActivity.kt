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
        // 【修复1】：填表间隔拉长到 800ms，让前端防抖和校验有充足时间跑完，彻底解决覆盖问题
        private const val FIELD_FILL_INTERVAL = 800L 
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

    class SafeWebBridge(activity: WebViewActivity) {
        private val ref = WeakReference(activity)

        @JavascriptInterface
        fun onTabClicked(tabText: String) {
            ref.get()?.runOnUiThread {
                val act = ref.get() ?: return@runOnUiThread
                act.binding.tvStatusBanner.visibility = View.VISIBLE
                act.binding.tvStatusBanner.text = "⏳ 检测到「$tabText」被点击，正在等待渲染后自动填表…"
                
                // 【修复2】：移除了以前那种 1s, 2s, 3.5s 多次触发填表的死循环
                // 只在点击时触发一次即可，减少冲突
                act.binding.webView.evaluateJavascript(act.fillJsPayload, null)
            }
        }

        @JavascriptInterface
        fun onFillComplete(count: Int) {
            ref.get()?.runOnUiThread {
                val act = ref.get() ?: return@runOnUiThread
                if (act.isFillDone.compareAndSet(false, true)) {
                    act.binding.tvStatusBanner.visibility = View.VISIBLE
                    act.binding.tvStatusBanner.text =
                        if (count > 0) "✅ 成功填入 $count 个字段！人工核对无误后提交。"
                        else           "⚠️ 填入0个字段，可能表单未加载完全。"
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
                if (host == "appflow.zs-hospital.sh.cn" || url?.contains("zs-hospital") == true) {
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
                handler?.proceed() // 医院内网证书可能有问题，直接放行
            }
        }

        binding.btnRetryNetwork.setOnClickListener {
            binding.layoutNetworkError.visibility = View.GONE
            isMonitorInjected.set(false)
            isFillDone.set(false)
            if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
        }
    }

    private fun compileTabMonitorJs(): String {
        // 注意：你提到的 1号机房有5个标签，3号机房有4个标签
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
})();
        """.trimIndent()
    }

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
  
  var cleanLabel = item.label.replace(/\s+/g, '');
  var allElements = document.querySelectorAll('*');
  for(var i=0; i<allElements.length; i++){
    var node = allElements[i];
    if(node.children.length > 0 && node.tagName !== 'LABEL' && node.tagName !== 'TD' && node.tagName !== 'TH') continue;
    
    var txt = (node.innerText || node.textContent || '').replace(/\s+/g, '');
    if(txt.indexOf(cleanLabel) >= 0){
      var parent = node;
      for(var j=0; j<10; j++){
        parent = parent.parentElement;
        if(!parent || parent.tagName === 'BODY') break;
        
        var cls = parent.className || '';
        if(cls.indexOf('form-item') > -1 || cls.indexOf('form-group') > -1 || 
           cls.indexOf('list-item') > -1 || cls.indexOf('cell') > -1 || parent.tagName === 'TR'){
          var input = parent.querySelector('input:not([type=hidden]):not([type=radio]):not([type=checkbox]), textarea');
          if(input) return input;
        }
      }
    }
  }
  return null;
}

function setVal(el, v){
  el.focus();
  el.value = '';
  el.dispatchEvent(new Event('input', {bubbles: true}));
  
  var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
  
  setTimeout(function() {
    if(setter){ setter.call(el, v); } else { el.value = v; }
    el.dispatchEvent(new Event('focus', {bubbles:true}));
    el.dispatchEvent(new KeyboardEvent('keydown', {bubbles:true}));
    el.dispatchEvent(new KeyboardEvent('keypress', {bubbles:true}));
    el.dispatchEvent(new InputEvent('input', {bubbles:true, cancelable:true, inputType:'insertText', data:v}));
    el.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
    // 【修复3】：触发失焦事件，强制表单完成该输入框的校验
    el.dispatchEvent(new Event('blur', {bubbles:true}));
  }, 50);
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
  if(el){ 
    setVal(el, item.v); 
    filled++; 
  } else {
    if(window.AndroidBridge && window.AndroidBridge.log) {
        AndroidBridge.log('未找到输入框: ' + item.label);
    }
  }
  idx++; 
  setTimeout(fillNext, $FIELD_FILL_INTERVAL);
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
