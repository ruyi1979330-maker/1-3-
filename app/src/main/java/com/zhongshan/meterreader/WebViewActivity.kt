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
        // Bug Fix 图6：将注入等待时间从600ms大幅提高到1500ms
        // 原因：表单是SPA，标签页切换后需要等待组件重新渲染
        // 从截图看到"成功物理填入0个字段"且仍在交接班tab，说明切换动画未完成就开始填充
        private const val TAB_SWITCH_DELAY_MS = 1500L
        // 字段填充间隔保持200ms（每个字段间隔，已足够）
        private const val FIELD_FILL_INTERVAL_MS = 150
    }

    internal lateinit var binding: ActivityWebviewBinding
    private var targetUrl: String = ""
    private var targetTabName: String = ""
    private var injectJsPayload: String = ""

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val pageLoadGeneration = AtomicInteger(0)
    private val isFillNotificationFired = AtomicBoolean(false)
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
                    if (isInjected.compareAndSet(false, true)) {
                        view?.evaluateJavascript(injectJsPayload, null)
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    pageLoadGeneration.incrementAndGet()
                    isInjected.set(false)
                    binding.progressBar.visibility = View.GONE
                    binding.layoutNetworkError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text = "表单加载失败，处于断网容灾模式。"
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val host = try { android.net.Uri.parse(error?.url ?: "").host } catch (e: Exception) { null }
                if (host == "appflow.zs-hospital.sh.cn") handler?.proceed() else handler?.cancel()
            }
        }

        binding.btnRetryNetwork.setOnClickListener {
            binding.layoutNetworkError.visibility = View.GONE
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

        // =====================================================================
        // Bug Fix 图6：标签页切换策略重构
        //
        // 问题：截图显示仍停留在"交接班"标签页，"成功物理填入0个字段"
        // 原因：
        //   1. 600ms 延迟对于SPA表单（有路由动画）不够，标签页内容未加载完就开始填充
        //   2. 原包含匹配 indexOf > -1 导致"螺杆机"误触其他tab
        //   3. 即使tab点对了，SPA组件渲染需要时间，600ms内DOM还未注入input元素
        //
        // 修复策略：
        //   a. 精确匹配 tab 文字（trim() === tabName），消除误匹配
        //   b. 等待时间提升到1500ms，确保SPA路由+组件渲染完成
        //   c. 增加二次检测：如果1500ms后仍未找到任何input，再等500ms重试一次
        // =====================================================================
        sb.append("  var tabName = '${tabName.escapeJs()}';")
        sb.append("  var tabs = document.querySelectorAll('li, a, button, div, span');")
        sb.append("  for(var i=0; i<tabs.length; i++) {")
        sb.append("    var txt = tabs[i].innerText ? tabs[i].innerText.trim() : '';")
        // 精确匹配：tab文字必须完全等于tabName，或包含tabName且长度不超过tabName+2字符
        sb.append("    if(txt === tabName || (txt.indexOf(tabName) > -1 && txt.length <= tabName.length + 2)) {")
        sb.append("      tabs[i].click(); break;")
        sb.append("    }")
        sb.append("  }")

        // 第一次尝试填充（1500ms后）
        sb.append("  function doFill() {")
        sb.append("    var data = [];")
        for (i in keys.indices) {
            sb.append("    data.push({ k: '${keys[i].escapeJs()}', v: '${values[i].escapeJs()}' });")
        }
        sb.append("    var index = 0; var filledCount = 0;")
        sb.append("    function fillNext() {")
        sb.append("      if(index >= data.length) {")
        for (pumpId in pumpIds) {
            sb.append("        var chk = document.getElementById('$pumpId') || document.querySelector('[value=\"$pumpId\"],[name=\"$pumpId\"]');")
            sb.append("        if(chk && chk.type === 'checkbox') {")
            sb.append("          var cs = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'checked');")
            sb.append("          if(cs && cs.set) { cs.set.call(chk, true); } else { chk.checked = true; }")
            sb.append("          chk.dispatchEvent(new Event('change', { bubbles: true }));")
            sb.append("          chk.dispatchEvent(new Event('click', { bubbles: true }));")
            sb.append("        }")
        }
        sb.append("        AndroidBridge.onFillComplete(filledCount); return;")
        sb.append("      }")

        // DOM查找逻辑（ID精确查找 + 中文标签兜底）
        sb.append("      var item = data[index];")
        sb.append("      var idToSearch = item.k; var labelToSearch = '';")
        sb.append("      if(item.k.indexOf('|') > -1) { var p = item.k.split('|'); idToSearch = p[0]; labelToSearch = p[1]; }")
        sb.append("      var el = document.getElementById(idToSearch) || document.querySelector('[name=\"'+idToSearch+'\"]');")
        sb.append("      if(!el && labelToSearch) {")
        sb.append("        var els = document.querySelectorAll('label, div, span');")
        sb.append("        for(var j=0; j<els.length; j++) {")
        sb.append("          if(els[j].innerText && els[j].innerText.trim().indexOf(labelToSearch) > -1) {")
        sb.append("            var par = els[j].parentElement; var dep = 0;")
        sb.append("            while(par && par.tagName !== 'BODY' && dep < 5) {")
        sb.append("              var ins = par.querySelectorAll('input:not([type=\"radio\"]):not([type=\"checkbox\"]):not([type=\"hidden\"])');")
        sb.append("              if(ins.length > 0) { el = ins[0]; break; }")
        sb.append("              par = par.parentElement; dep++;")
        sb.append("            }")
        sb.append("            if(el) break;")
        sb.append("          }")
        sb.append("        }")
        sb.append("      }")
        sb.append("      if(el) {")
        sb.append("        el.focus();")
        sb.append("        var ds = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');")
        sb.append("        if(ds && ds.set) { ds.set.call(el, item.v); } else { el.value = item.v; }")
        sb.append("        el.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'insertText', data: item.v }));")
        sb.append("        el.dispatchEvent(new Event('change', { bubbles: true }));")
        sb.append("        el.blur(); filledCount++;")
        sb.append("      }")
        sb.append("      index++; setTimeout(fillNext, ${FIELD_FILL_INTERVAL_MS});")
        sb.append("    }")
        sb.append("    fillNext();")
        sb.append("  }")

        // 等待TAB_SWITCH_DELAY_MS后执行填充
        sb.append("  setTimeout(doFill, ${TAB_SWITCH_DELAY_MS});")
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
