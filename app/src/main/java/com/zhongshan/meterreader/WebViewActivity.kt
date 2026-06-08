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
                    view?.evaluateJavascript(injectJsPayload, null)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    pageLoadGeneration.incrementAndGet()
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
        sb.append("  var tabs = document.querySelectorAll('li, a, button, div, span');")
        // 模糊匹配标签页文本
        sb.append("  for(var i=0; i<tabs.length; i++) { if(tabs[i].innerText && tabs[i].innerText.trim().indexOf('$tabName') > -1) { tabs[i].click(); break; } }")

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
        
        // 核心修复：DOM穿透查找逻辑（解析 ID|中文名）
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
        sb.append("  }, 400);")
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
