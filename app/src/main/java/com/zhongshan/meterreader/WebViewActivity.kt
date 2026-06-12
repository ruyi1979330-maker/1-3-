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
 * WebViewActivity — 重构版
 *
 * =====================================================================
 * 核心需求变更（来自截图图6反馈）：
 *   原来：APP自动切换标签页 → 等待1500ms → 自动填表
 *   问题：机房信号不稳定时，表单SPA路由可能未加载完成，
 *         导致"成功物理填入0个字段"，标签页还停在"交接班"
 *
 *   新方案：用户手动点击对应标签页 → APP检测到标签页切换 → 自动填表
 *
 * 实现原理：
 *   1. 页面加载完成后，注入一段JS监听代码（不自动点击tab，不自动填表）
 *   2. JS监听所有 li/a/button/div/span 的 click 事件（事件委托到 document）
 *   3. 当用户点击的元素文字匹配已知Tab名时（螺杆机组/离心机组/板交/螺杆机）
 *      → 等待1200ms（等SPA渲染完成）→ 自动执行填表
 *   4. 如果用户没有点击Tab，永远不会自动填表（不干扰操作）
 * =====================================================================
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        private const val WEB_LOAD_TIMEOUT_MS = 20_000L
        // 用户点击Tab后，等待SPA渲染完成的时间
        private const val TAB_RENDER_WAIT_MS = 1200
        private const val FIELD_FILL_INTERVAL_MS = 150
    }

    internal lateinit var binding: ActivityWebviewBinding
    private var targetUrl: String = ""
    private var fillJsPayload: String = ""

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val pageLoadGeneration = AtomicInteger(0)
    private val isFillNotificationFired = AtomicBoolean(false)
    private val isMonitorInjected = AtomicBoolean(false)

    private fun String.escapeJs(): String =
        replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")

    // =====================================================================
    // AndroidBridge：JS → Kotlin 回调
    // =====================================================================
    class SafeWebBridge(activity: WebViewActivity) {
        private val ref = WeakReference(activity)

        /** 用户点击了某个Tab，JS通知Kotlin */
        @JavascriptInterface
        fun onTabClicked(tabText: String) {
            ref.get()?.runOnUiThread {
                val act = ref.get() ?: return@runOnUiThread
                act.binding.tvStatusBanner.visibility = View.VISIBLE
                act.binding.tvStatusBanner.text = "已检测到点击「$tabText」，正在等待页面渲染后自动填表…"
                // 等待SPA渲染完成后执行填表
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!act.isFinishing && !act.isDestroyed) {
                        act.binding.webView.evaluateJavascript(act.fillJsPayload, null)
                    }
                }, TAB_RENDER_WAIT_MS.toLong())
            }
        }

        /** 填表完成回调 */
        @JavascriptInterface
        fun onFillComplete(count: Int) {
            ref.get()?.runOnUiThread {
                val act = ref.get() ?: return@runOnUiThread
                if (act.isFillNotificationFired.compareAndSet(false, true)) {
                    act.binding.tvStatusBanner.visibility = View.VISIBLE
                    act.binding.tvStatusBanner.text =
                        if (count > 0) "✅ 成功物理填入 $count 个字段！请人工核对后提交。"
                        else "⚠️ 填入0个字段，字段ID可能不匹配，请截图反馈。"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetUrl = intent.getStringExtra("EXTRA_URL") ?: ""
        val keys = intent.getStringArrayExtra("EXTRA_KEYS") ?: emptyArray()
        val values = intent.getStringArrayExtra("EXTRA_VALUES") ?: emptyArray()
        val pumpIds = intent.getStringArrayExtra("EXTRA_PUMP_IDS") ?: emptyArray()

        // 预编译填表 JS（不含 tab 切换逻辑）
        fillJsPayload = compileFillJs(keys, values, pumpIds)

        initWebView()
        if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
    }

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    private fun initWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        binding.webView.addJavascriptInterface(SafeWebBridge(this), "AndroidBridge")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                isFillNotificationFired.set(false)
                isMonitorInjected.set(false)
                binding.tvStatusBanner.visibility = View.VISIBLE
                binding.tvStatusBanner.text = "⏳ 表单加载中，加载完成后请手动点击对应标签页（螺杆机/板交等），APP 将自动填表。"
                binding.progressBar.visibility = View.VISIBLE

                val gen = pageLoadGeneration.incrementAndGet()
                timeoutHandler.postDelayed({
                    if (pageLoadGeneration.get() == gen) {
                        binding.progressBar.visibility = View.GONE
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
                val host = android.net.Uri.parse(url ?: "").host ?: ""
                if (host == "appflow.zs-hospital.sh.cn") {
                    // 注入Tab点击监听器（只注入一次）
                    if (isMonitorInjected.compareAndSet(false, true)) {
                        view?.evaluateJavascript(compileTabMonitorJs(), null)
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    pageLoadGeneration.incrementAndGet()
                    isMonitorInjected.set(false)
                    binding.progressBar.visibility = View.GONE
                    binding.layoutNetworkError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text = "表单加载失败，机房信号不稳定，请稍后重试。"
                    binding.tvStatusBanner.visibility = View.GONE
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val host = try { android.net.Uri.parse(error?.url ?: "").host } catch (e: Exception) { null }
                if (host == "appflow.zs-hospital.sh.cn") handler?.proceed() else handler?.cancel()
            }
        }

        binding.btnRetryNetwork.setOnClickListener {
            binding.layoutNetworkError.visibility = View.GONE
            isMonitorInjected.set(false)
            isFillNotificationFired.set(false)
            if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
        }
    }

    // =====================================================================
    // Tab 点击监听 JS
    // 使用事件委托监听整个 document 的 click 事件，
    // 当被点击的元素（或其父元素）文字匹配已知 Tab 名时，通知 AndroidBridge。
    // =====================================================================
    private fun compileTabMonitorJs(): String {
        // 所有已知的 Tab 名称列表
        val tabNames = listOf("螺杆机组", "离心机组", "板交", "螺杆机", "交接班", "操作记录")
        val tabNamesJs = tabNames.joinToString(",") { "'${it.escapeJs()}'" }

        return """
(function() {
  if (window.__tabMonitorInstalled) return;
  window.__tabMonitorInstalled = true;
  
  var knownTabs = [$tabNamesJs];
  
  document.addEventListener('click', function(e) {
    var target = e.target;
    // 向上遍历最多5层，找到包含Tab文字的元素
    for (var depth = 0; depth < 5 && target && target.tagName !== 'BODY'; depth++) {
      var txt = (target.innerText || target.textContent || '').trim();
      for (var i = 0; i < knownTabs.length; i++) {
        if (txt === knownTabs[i] || (txt.indexOf(knownTabs[i]) > -1 && txt.length <= knownTabs[i].length + 3)) {
          AndroidBridge.onTabClicked(knownTabs[i]);
          return;
        }
      }
      target = target.parentElement;
    }
  }, true);  // 使用捕获阶段，确保在SPA路由前拦截到事件
})();
        """.trimIndent()
    }

    // =====================================================================
    // 填表 JS（不含 Tab 切换逻辑，由 onTabClicked 触发后调用）
    // =====================================================================
    private fun compileFillJs(
        keys: Array<String>,
        values: Array<String>,
        pumpIds: Array<String>
    ): String {
        val sb = StringBuilder()
        sb.append("(function() {")
        sb.append("  var data = [];")
        for (i in keys.indices) {
            sb.append("  data.push({ k: '${keys[i].escapeJs()}', v: '${values[i].escapeJs()}' });")
        }
        sb.append("  var index = 0; var filledCount = 0;")
        sb.append("  function fillNext() {")
        sb.append("    if(index >= data.length) {")
        for (pumpId in pumpIds) {
            sb.append("      var chk = document.getElementById('$pumpId') || document.querySelector('[value=\"$pumpId\"],[name=\"$pumpId\"]');")
            sb.append("      if(chk && chk.type === 'checkbox') {")
            sb.append("        var cs = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'checked');")
            sb.append("        if(cs && cs.set) { cs.set.call(chk, true); } else { chk.checked = true; }")
            sb.append("        chk.dispatchEvent(new Event('change', { bubbles: true }));")
            sb.append("      }")
        }
        sb.append("      AndroidBridge.onFillComplete(filledCount); return;")
        sb.append("    }")
        sb.append("    var item = data[index];")
        sb.append("    var el = document.getElementById(item.k) || document.querySelector('[name=\"'+item.k+'\"]');")
        sb.append("    if(!el) {")
        sb.append("      var parts = item.k.indexOf('|') > -1 ? item.k.split('|') : [item.k, ''];")
        sb.append("      el = document.getElementById(parts[0]) || document.querySelector('[name=\"'+parts[0]+'\"]');")
        sb.append("      if(!el && parts[1]) {")
        sb.append("        var labels = document.querySelectorAll('label, div, span');")
        sb.append("        for(var j=0; j<labels.length; j++) {")
        sb.append("          if((labels[j].innerText||'').trim().indexOf(parts[1]) > -1) {")
        sb.append("            var par = labels[j].parentElement; var dep = 0;")
        sb.append("            while(par && par.tagName !== 'BODY' && dep < 5) {")
        sb.append("              var ins = par.querySelectorAll('input:not([type=\"radio\"]):not([type=\"checkbox\"]):not([type=\"hidden\"])');")
        sb.append("              if(ins.length > 0) { el = ins[0]; break; }")
        sb.append("              par = par.parentElement; dep++;")
        sb.append("            }")
        sb.append("            if(el) break;")
        sb.append("          }")
        sb.append("        }")
        sb.append("      }")
        sb.append("    }")
        sb.append("    if(el) {")
        sb.append("      el.focus();")
        sb.append("      var ds = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');")
        sb.append("      if(ds && ds.set) { ds.set.call(el, item.v); } else { el.value = item.v; }")
        sb.append("      el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'insertText', data: item.v }));")
        sb.append("      el.dispatchEvent(new Event('change', { bubbles: true }));")
        sb.append("      el.blur(); filledCount++;")
        sb.append("    }")
        sb.append("    index++; setTimeout(fillNext, $FIELD_FILL_INTERVAL_MS);")
        sb.append("  }")
        sb.append("  fillNext();")
        sb.append("})();")
        return sb.toString()
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        binding.webView.removeJavascriptInterface("AndroidBridge")
        binding.webView.apply {
            loadUrl("about:blank")
            stopLoading()
            clearHistory()
            destroy()
        }
        super.onDestroy()
    }
}
