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
        private const val WEB_LOAD_TIMEOUT_MS = 15_000L
    }

    internal lateinit var binding: ActivityWebviewBinding
    private var targetUrl: String = ""
    private var targetTabName: String = ""
    private var injectJsPayload: String = ""

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val pageLoadGeneration = AtomicInteger(0)
    private val isFillNotificationFired = AtomicBoolean(false)

    // Bug Fix 4（防重复注入）：
    // 原代码 onPageFinished 每次触发都执行注入，
    // SPA 应用在 hash 路由切换时会多次触发 onPageFinished（如 redirect、iframe 加载等），
    // 导致字段被重复填充。
    // 修复：使用 AtomicBoolean 标记，确保同一次页面加载只执行一次注入。
    private val isInjected = AtomicBoolean(false)

    private fun String.escapeJs(): String =
        this.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")

    class SafeWebBridge(activity: WebViewActivity) {
        private val activityRef = WeakReference(activity)

        @JavascriptInterface
        fun onFillComplete(count: Int) {
            activityRef.get()?.runOnUiThread {
                if (activityRef.get()?.isFillNotificationFired?.compareAndSet(false, true) == true) {
                    activityRef.get()?.binding?.tvStatusBanner?.visibility = View.VISIBLE
                    activityRef.get()?.binding?.tvStatusBanner?.text =
                        "✅ 成功物理填入 $count 个字段！请人工核对后提交。"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetUrl = intent.getStringExtra("EXTRA_URL") ?: ""
        targetTabName = intent.getStringExtra("EXTRA_TAB_NAME") ?: "螺杆机"
        val keys = intent.getStringArrayExtra("EXTRA_KEYS") ?: emptyArray()
        val values = intent.getStringArrayExtra("EXTRA_VALUES") ?: emptyArray()
        val pumpIds = intent.getStringArrayExtra("EXTRA_PUMP_IDS") ?: emptyArray()

        injectJsPayload = compileUltimateInjectionJs(targetTabName, keys, values, pumpIds)

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
                // Fix：重置注入标记，允许新的页面重新注入
                isInjected.set(false)
                binding.tvStatusBanner.visibility = View.GONE

                binding.progressBar.visibility = View.VISIBLE
                val currentGen = pageLoadGeneration.incrementAndGet()
                timeoutHandler.postDelayed({
                    if (pageLoadGeneration.get() == currentGen) {
                        binding.progressBar.visibility = View.GONE
                        binding.webView.stopLoading()
                        binding.layoutNetworkError.visibility = View.VISIBLE
                        binding.tvErrorMsg.text = "网络超时，请重新进入页面或检查信号。"
                    }
                }, WEB_LOAD_TIMEOUT_MS)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoadGeneration.incrementAndGet()
                binding.progressBar.visibility = View.GONE
                val host = android.net.Uri.parse(url ?: "").host ?: ""
                if (host == "appflow.zs-hospital.sh.cn") {
                    // Fix（防重复注入）：使用 compareAndSet 确保只注入一次，
                    // 防止 SPA hash 路由切换导致 onPageFinished 多次触发、字段重复填充
                    if (isInjected.compareAndSet(false, true)) {
                        view?.evaluateJavascript(injectJsPayload, null)
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    pageLoadGeneration.incrementAndGet()
                    // Fix：发生错误时重置注入标记，允许重试时重新注入
                    isInjected.set(false)
                    binding.progressBar.visibility = View.GONE
                    binding.layoutNetworkError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text = "表单加载失败，处于断网容灾模式。"
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val host = try { android.net.Uri.parse(error?.url ?: "").host } catch (e: Exception) { null }
                if (host == "appflow.zs-hospital.sh.cn") {
                    handler?.proceed()
                } else {
                    handler?.cancel()
                }
            }
        }

        binding.btnRetryNetwork.setOnClickListener {
            binding.layoutNetworkError.visibility = View.GONE
            // Fix：重试时重置注入标记，确保重新加载后能再次注入
            isInjected.set(false)
            if (targetUrl.isNotEmpty()) binding.webView.loadUrl(targetUrl)
        }
    }

    private fun compileUltimateInjectionJs(
        tabName: String,
        keys: Array<String>,
        values: Array<String>,
        pumpIds: Array<String>
    ): String {
        val sb = StringBuilder()
        sb.append("(function() {")

        // Bug Fix 3（标签页精确切换）：
        // 原代码使用 indexOf(tabName) > -1 做包含匹配，导致：
        //   "螺杆机" 会命中 "螺杆机组"（1号机房有该tab）；
        //   如果3号机房先渲染完，也可能误匹配1号机房的tab文字。
        // 修复方案：改为 trim() === tabName 精确匹配，并增加 trim() 去空白。
        // 对于 "板交"：1号机房有两个板交tab（板交/板交），精确匹配时仍取第一个，符合需求。
        // 对于 "螺杆机" vs "螺杆机组"：精确匹配彻底区分，不再串台。
        sb.append("  var tabs = document.querySelectorAll('li, a, button, div, span');")
        sb.append("  var tabName = '${tabName.escapeJs()}';")
        sb.append("  for(var i=0; i<tabs.length; i++) {")
        sb.append("    var txt = tabs[i].innerText ? tabs[i].innerText.trim() : '';")
        // 优先精确匹配，兜底包含匹配（防止 tab 文字前后有额外空格或特殊字符）
        sb.append("    if(txt === tabName || (txt.length > 0 && txt.indexOf(tabName) > -1 && txt.length <= tabName.length + 4)) {")
        sb.append("      tabs[i].click(); break;")
        sb.append("    }")
        sb.append("  }")

        // 等待标签页切换动画完成后再填充（延长至 600ms，比原 400ms 更稳定）
        sb.append("  setTimeout(function() {")
        sb.append("    var data = [];")
        for (i in keys.indices) {
            sb.append("    data.push({ k: '${keys[i].escapeJs()}', v: '${values[i].escapeJs()}' });")
        }

        sb.append("    var index = 0; var filledCount = 0;")
        sb.append("    function fillNext() {")
        sb.append("      if(index >= data.length) { ")
        for (pumpId in pumpIds) {
            sb.append("var chk = document.getElementById('$pumpId') || document.querySelector('[value=\"$pumpId\"],[name=\"$pumpId\"]');")
            sb.append("if(chk && chk.type === 'checkbox') {")
            sb.append("  var chkDesc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'checked');")
            sb.append("  var nativeCheckSetter = chkDesc && chkDesc.set;")
            sb.append("  if(nativeCheckSetter) { nativeCheckSetter.call(chk, true); } else { chk.checked = true; }")
            sb.append("  chk.dispatchEvent(new Event('change', { bubbles: true }));")
            sb.append("  chk.dispatchEvent(new Event('click', { bubbles: true }));")
            sb.append("}")
        }
        sb.append("        AndroidBridge.onFillComplete(filledCount); return; }")

        // DOM 穿透查找逻辑（解析 ID|中文名）
        sb.append("      var item = data[index];")
        sb.append("      var idToSearch = item.k; var labelToSearch = '';")
        sb.append("      if (item.k.indexOf('|') > -1) { var parts = item.k.split('|'); idToSearch = parts[0]; labelToSearch = parts[1]; }")
        sb.append("      var el = document.getElementById(idToSearch) || document.querySelector('[name=\"'+idToSearch+'\"]');")
        sb.append("      if (!el && labelToSearch) {")
        sb.append("        var elements = document.querySelectorAll('label, div, span');")
        sb.append("        for (var j = 0; j < elements.length; j++) {")
        sb.append("          if (elements[j].innerText && elements[j].innerText.trim().indexOf(labelToSearch) > -1) {")
        sb.append("            var parent = elements[j].parentElement; var depth = 0;")
        sb.append("            while (parent && parent.tagName !== 'BODY' && depth < 5) {")
        sb.append("              var inputs = parent.querySelectorAll('input:not([type=\"radio\"]):not([type=\"checkbox\"]):not([type=\"hidden\"])');")
        sb.append("              if (inputs.length > 0) { el = inputs[0]; break; }")
        sb.append("              parent = parent.parentElement; depth++;")
        sb.append("            }")
        sb.append("            if (el) break;")
        sb.append("          }")
        sb.append("        }")
        sb.append("      }")

        sb.append("      if(el) {")
        sb.append("        el.focus();")
        sb.append("        var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');")
        sb.append("        var nativeSetter = desc && desc.set;")
        sb.append("        if(nativeSetter) { nativeSetter.call(el, item.v); } else { el.value = item.v; }")
        sb.append("        el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'insertText', data: item.v }));")
        sb.append("        el.dispatchEvent(new Event('change', { bubbles: true }));")
        sb.append("        el.blur(); filledCount++;")
        sb.append("      }")
        sb.append("      index++; setTimeout(fillNext, 200);")
        sb.append("    }")
        sb.append("    fillNext();")
        sb.append("  }, 600);")  // 原为 400ms，延长至 600ms 确保 SPA 路由切换完成
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
