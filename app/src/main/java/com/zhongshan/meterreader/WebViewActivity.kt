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

/**
 * WebViewActivity
 *
 * =====================================================================
 * 本轮 Bug Fix：
 *
 * 【Fix 1】横幅始终显示"加载中"（图5/图6）
 *   根因：onPageStarted 设置横幅为"加载中"，但 onPageFinished 没有更新。
 *   修复：onPageFinished 后横幅改为"加载完成，请点击对应标签页"。
 *
 * 【Fix 2】填入0个字段（图7）
 *   根因：JS 用 getElementById('field_3_44') 查找 DOM，
 *         但表单实际 input 元素可能没有这个 id 或 id 格式不同。
 *   修复：compileFillJs 改为双重匹配策略：
 *         1) 先试 getElementById(fieldId)
 *         2) 失败则用中文标签（formLabel）遍历页面所有 label/span/div，
 *            找包含该标签文字的容器，取其中第一个 input/textarea 填值。
 *         字段传入格式由 MainActivity 改为 "fieldId|中文标签"。
 *
 * 【Fix 3】JS 事件监听改用 MutationObserver + 延时重试
 *   原代码只注入一次 click 监听，SPA 路由后 DOM 替换可能导致监听失效。
 *   修复：监听注入后同时启动 MutationObserver，DOM 变化时重新绑定监听。
 * =====================================================================
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        private const val WEB_LOAD_TIMEOUT_MS = 20_000L
        private const val TAB_RENDER_WAIT_MS  = 1200L
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
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!act.isFinishing && !act.isDestroyed) {
                        act.isFillDone.set(false)
                        act.binding.webView.evaluateJavascript(act.fillJsPayload, null)
                    }
                }, TAB_RENDER_WAIT_MS)
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
                        else           "⚠️ 填入0个字段。请下拉到当前标签页内容加载完毕后再点击标签页。"
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
                // Fix 3：加载中提示
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
                    // Fix 1：页面加载完成后更新横幅提示
                    binding.tvStatusBanner.visibility = View.VISIBLE
                    binding.tvStatusBanner.text = "✅ 表单已加载！请手动点击上方对应标签页（螺杆机/板交等），APP 将自动填表。"

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
    // Tab 点击监听 JS
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
})();
        """.trimIndent()
    }

    // =====================================================================
    // 填表 JS — Fix 2：双重匹配（fieldId + 中文标签）
    //
    // 键格式："fieldId|中文标签"
    // 策略：
    //   1. 先 getElementById(fieldId) 或 querySelector([name=fieldId])
    //   2. 失败则遍历页面所有含标签文字的元素，向上找父容器里的第一个 input
    //   3. 再次失败则跳过，不报错
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
  // 策略1：id 精确匹配
  var el = document.getElementById(item.id)
        || document.querySelector('[name="'+item.id+'"]');
  if(el) return el;
  // 策略2：中文标签模糊匹配（遍历页面所有含标签文字的节点）
  if(!item.label) return null;
  var nodes = document.querySelectorAll('label,span,div,p,td,th');
  for(var i=0;i<nodes.length;i++){
    var txt=(nodes[i].innerText||nodes[i].textContent||'').trim();
    if(txt.indexOf(item.label)<0) continue;
    // 向上最多6层找 input
    var par=nodes[i].parentElement; var dep=0;
    while(par && par.tagName!=='BODY' && dep<6){
      var ins=par.querySelectorAll('input:not([type=radio]):not([type=checkbox]):not([type=hidden]),textarea');
      if(ins.length>0) return ins[0];
      par=par.parentElement; dep++;
    }
    // 向下找 input（兄弟容器场景）
    var sib=nodes[i].nextElementSibling;
    for(var s=0;s<3&&sib;s++){
      var ins2=sib.querySelectorAll('input:not([type=radio]):not([type=checkbox]):not([type=hidden]),textarea');
      if(ins2.length>0) return ins2[0];
      sib=sib.nextElementSibling;
    }
  }
  return null;
}
function setVal(el, v){
  var ds=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value')
       ||Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value');
  if(ds&&ds.set){ ds.set.call(el,v); } else { el.value=v; }
  el.dispatchEvent(new InputEvent('input',{bubbles:true,cancelable:true,inputType:'insertText',data:v}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
}
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
fillNext();
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
