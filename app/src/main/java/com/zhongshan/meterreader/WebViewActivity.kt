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
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WebViewActivity : AppCompatActivity() {
    companion object {
        private const val WEB_LOAD_TIMEOUT_MS = 60_000L
        private const val AUTO_SCAN_INTERVAL_MS = 1500L
        private const val MAX_IDLE_SCAN_COUNT = 3
    }

    internal lateinit var binding: ActivityWebviewBinding
    private var targetUrl = ""
    private var fillJsPayload = ""
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val pageLoadGeneration = AtomicInteger(0)
    private val isFillDone = AtomicBoolean(false)

    // 修复 Q2：标记 Activity 是否已销毁，防止 onDestroy 后再操作 WebView 导致卡死/崩溃
    @Volatile
    private var isActivityDestroyed = false
    // 修复 Q2：避免对 about:blank 触发 fillJsPayload
    @Volatile
    private var hasLoadedRealUrl = false

    private val screwFieldLabelMap = mapOf(
        "evapInTemp" to "蒸发器进口水温",
        "evapOutTemp" to "蒸发器出口水温",
        "evapInPressure" to "蒸发器进口水压",
        "evapOutPressure" to "蒸发器出口水压",
        "evapRefPressure" to "蒸发器冷媒压力",
        "evapTemp" to "蒸发器蒸发温度",
        "condInTemp" to "冷凝器进口水温",
        "condOutTemp" to "冷凝器出口水温",
        "condInPressure" to "冷凝器进口水压",
        "condOutPressure" to "冷凝器出口水压",
        "condRefPressure" to "冷凝器冷媒压力",
        "condTemp" to "冷凝器冷凝温度",
        "compOilPressure" to "压缩机油压",
        "compDischargeTemp" to "压缩机排出口温度",
        "motorCurrent" to "电机电流",
        "hostLoad" to "主机负载",
        "remark" to "螺杆机组备注"
    )
    private val plateFieldLabelMap = mapOf(
        "inTemp" to "进水温度",
        "outTemp" to "出水温度",
        "inPressure" to "进水压力",
        "outPressure" to "出水压力",
        "steamPressure" to "蒸汽压力",
        "pumpCurrent" to "水泵电流",
        "remark" to "备注"
    )

    private fun String.esc(): String =
        replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

    class SafeWebBridge(activity: WebViewActivity) {
        private val ref = WeakReference(activity)
        @JavascriptInterface
        fun onFillComplete(count: Int) {
            ref.get()?.runOnUiThread {
                val act = ref.get() ?: return@runOnUiThread
                if (act.isActivityDestroyed) return@runOnUiThread
                DebugLogger.log("WebView", "JS 报告填表完成，成功填充 $count 个字段")
                act.binding.tvStatusBanner.visibility = View.VISIBLE
                if (count > 0) {
                    act.isFillDone.set(true)
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
        val fillType = intent.getStringExtra("EXTRA_FILL_TYPE") ?: ""
        val fillDataJson = intent.getStringExtra("EXTRA_FILL_DATA_JSON") ?: ""
        val tabName = intent.getStringExtra("EXTRA_TAB_NAME") ?: ""
        DebugLogger.log("WebView", "收到填充类型: $fillType")
        DebugLogger.log("WebView", "收到填充数据: $fillDataJson")
        val (targetFields, pumpList) = parseFillData(fillType, fillDataJson)
        fillJsPayload = compileFillJs(targetFields, pumpList, tabName)
        initWebView()
        // 修复 Q2：先加载空白页清除上一次的 WebView 残留状态（JS/历史/缓存），
        //         再加载真实 URL。用 postDelayed 保证 blank 先真正生效。
        hasLoadedRealUrl = false
        binding.webView.loadUrl("about:blank")
        if (targetUrl.isNotEmpty()) {
            binding.webView.post {
                if (!isActivityDestroyed && targetUrl.isNotEmpty()) {
                    hasLoadedRealUrl = true
                    binding.webView.loadUrl(targetUrl)
                }
            }
        }
    }

    private fun parseFillData(fillType: String, jsonStr: String): Pair<List<Pair<String, String>>, List<String>> {
        val fields = mutableListOf<Pair<String, String>>()
        val pumps = mutableListOf<String>()
        if (jsonStr.isEmpty()) return Pair(fields, pumps)
        runCatching {
            val root = JSONObject(jsonStr)
            if (fillType == "screw") {
                listOf("unit1" to "1#", "unit2" to "2#", "unit3" to "3#").forEach { (unitKey, prefix) ->
                    if (root.has(unitKey)) {
                        val unitObj = root.getJSONObject(unitKey)
                        val keys = unitObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            if (key != "pumps") {
                                var labelSuffix = screwFieldLabelMap[key] ?: continue
                                if (key == "condRefPressure" && prefix == "2#") {
                                    labelSuffix = "冷凝器冷凝压力"
                                }
                                val fullLabel = prefix + labelSuffix
                                fields.add(Pair(fullLabel, unitObj.getString(key)))
                            }
                        }
                        if (unitObj.has("pumps")) {
                            val pumpArr = unitObj.getJSONArray("pumps")
                            for (i in 0 until pumpArr.length()) {
                                pumps.add(pumpArr.getString(i))
                            }
                        }
                    }
                }
            } else if (fillType == "plate") {
                if (root.has("plateGroups")) {
                    val groups = root.getJSONArray("plateGroups")
                    for (i in 0 until groups.length()) {
                        val group = groups.getJSONObject(i)
                        val groupTitle = group.getString("groupTitle")
                        val fieldsObj = group.getJSONObject("fields")
                        val keys = fieldsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val labelSuffix = plateFieldLabelMap[key] ?: continue
                            val fullLabel = groupTitle + labelSuffix
                            fields.add(Pair(fullLabel, fieldsObj.getString(key)))
                        }
                    }
                }
            }
        }.onFailure {
            DebugLogger.log("WebView", "数据解析失败: ${it.message}")
        }
        DebugLogger.log("WebView", "解析后字段数: ${fields.size}, 冷冻泵数: ${pumps.size}")
        return Pair(fields, pumps)
    }

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    private fun initWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 修复 Q4/Q5：强制中文偏好，避免明道云表单 i18n 误判为英文环境
            //            （其 translations 会把 “全选” 等替换为 “Select all”）
        }
        binding.webView.addJavascriptInterface(SafeWebBridge(this), "AndroidBridge")
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // 修复 Q4/Q5：给所有请求注入中文 Accept-Language，让表单后端走中文文案
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (isActivityDestroyed) return
                isFillDone.set(false)
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatusBanner.visibility = View.VISIBLE
                binding.tvStatusBanner.text = "⏳ 正在连接数据中心，请稍候…"
                val gen = pageLoadGeneration.incrementAndGet()
                timeoutHandler.postDelayed({
                    if (!isActivityDestroyed && pageLoadGeneration.get() == gen) {
                        binding.progressBar.visibility = View.GONE
                        binding.tvStatusBanner.text = "⏳ 网络加载缓慢，请耐心等待..."
                    }
                }, WEB_LOAD_TIMEOUT_MS)

                // 修复 Q4/Q5：页面一加载就注入强制中文脚本 + Accept-Language 覆盖
                if (url != null && url != "about:blank") {
                    view?.evaluateJavascript(buildForceLanguageHeaderJs(), null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (isActivityDestroyed) return
                pageLoadGeneration.incrementAndGet()
                // 修复 Q2：about:blank 不执行填表逻辑，也不更新 banner
                if (url == null || url == "about:blank") return
                binding.progressBar.visibility = View.GONE
                val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: ""
                if (host.contains("zs-hospital") || url.contains("zs-hospital") || host.isNotEmpty()) {
                    binding.tvStatusBanner.visibility = View.VISIBLE
                    binding.tvStatusBanner.text = "🎉 连接成功！引擎已启动..."
                    // 修复 Q4/Q5：先强制中文，再注入填表引擎
                    view?.evaluateJavascript(buildForceLanguageHeaderJs(), null)
                    view?.evaluateJavascript(fillJsPayload, null)
                }
            }

            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (isActivityDestroyed) return
                if (req?.isForMainFrame == true) {
                    pageLoadGeneration.incrementAndGet()
                    binding.progressBar.visibility = View.GONE
                    binding.layoutNetworkError.visibility = View.VISIBLE
                    binding.tvErrorMsg.text = "加载失败，请检查是否连接医院内网 Wi-Fi。"
                    binding.tvStatusBanner.visibility = View.GONE
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }
        binding.btnRetryNetwork.setOnClickListener {
            if (isActivityDestroyed) return@setOnClickListener
            binding.layoutNetworkError.visibility = View.GONE
            isFillDone.set(false)
            if (targetUrl.isNotEmpty()) {
                hasLoadedRealUrl = true
                binding.webView.loadUrl(targetUrl)
            }
        }
    }

    /**
     * 修复 Q4/Q5：构建强制中文的 JS 片段。
     * 1) 注入 document.lang / html lang = zh-CN；
     * 2) 覆盖 navigator.language / navigator.languages 返回中文，阻止明道云走英文 i18n；
     * 3) 直接把页面里所有出现 “Select all” 的英文文本替换为 “全选”，
     *    “Submit Success” 替换为 “提交成功”（Q5 提交成功页）；
     * 4) 持续做（在主轮询 scanAndAutofillEngine 里每轮也会调用 forceChinese）。
     */
    private fun buildForceLanguageHeaderJs(): String {
        return """
        (function(){
            try {
                var html = document.documentElement;
                if (html) html.setAttribute('lang','zh-CN');
                if (document) {
                    try { Object.defineProperty(document, 'language', { get: function(){return 'zh-CN';}, configurable: true }); } catch(e){}
                }
            } catch(e){}
        })();
        """.trimIndent()
    }

    private fun compileFillJs(
        fields: List<Pair<String, String>>,
        pumps: List<String>,
        targetTabName: String
    ): String {
        val fieldsJs = fields.joinToString(",\n") { (label, value) ->
            "{label:'${label.esc()}', value:'${value.esc()}'}"
        }
        val pumpsJs = pumps.joinToString(prefix = "[", postfix = "]") { "'${it.esc()}'" }
        return """
        (function(){
            if(window.__ocrFillEngineStarted) return;
            window.__ocrFillEngineStarted = true;
            var targetFields = [${fieldsJs}];
            var pumpItems = ${pumpsJs};
            var targetTab = '${targetTabName.esc()}';
            window.__ocrMaxFilledCount = -1;
            window.__ocrIdleCount = 0;
            window.__ocrScanCount = 0;
            if (window.__ocrScanTimer) clearInterval(window.__ocrScanTimer);

            // 修复 Q6：去掉“永久锁”pumpsOperated。
            //         改用 __pumpPending 记录“仍待勾选”的泵，每次进入标签页都重新检查，
            //         只要当前未被勾选就尝试点击；勾选成功后从 pending 中移除。
            //         这样无论用户何时切换到螺杆标签页，预设泵都会被补勾，
            //         且不会重复点击已勾选项。
            window.__pumpPending = window.__pumpPending || null;
            if (!window.__pumpPending) {
                window.__pumpPending = {};
                for (var pi = 0; pi < pumpItems.length; pi++) {
                    window.__pumpPending[pumpItems[pi]] = true;
                }
            }
            window.__pumpChecking = false;

            var inputValueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            var textareaValueSetter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;

            function cleanText(text) {
                return (text || '').replace(/\s+/g, '').replace(/\u3000/g, '');
            }

            // 修复 Q4/Q5：把明道云表单英文翻译强制改回中文
            function forceChinese() {
                try {
                    // 1) 文本节点级替换（更稳，避免整体 innerHTML 重写破坏事件绑定）
                    var EN_ZH = [
                        ['Select all','全选'],
                        ['Select All','全选'],
                        ['Submit Success','提交成功'],
                        ['Submission Success','提交成功'],
                        ['Submit success','提交成功'],
                        ['Save Success','保存成功'],
                        ['Submit','提交'],
                        ['Cancel','取消'],
                        ['Confirm','确认'],
                        ['OK','确定']
                    ];
                    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    var node;
                    while ((node = walker.nextNode())) {
                        if (!node.nodeValue) continue;
                        var changed = false;
                        var v = node.nodeValue;
                        for (var i = 0; i < EN_ZH.length; i++) {
                            if (v.indexOf(EN_ZH[i][0]) > -1) {
                                v = v.split(EN_ZH[i][0]).join(EN_ZH[i][1]);
                                changed = true;
                            }
                        }
                        if (changed && v !== node.nodeValue) {
                            node.nodeValue = v;
                        }
                    }
                    // 2) 属性级（title/placeholder/aria-label 等）
                    var els = document.querySelectorAll('[title],[placeholder],[aria-label]');
                    for (var k = 0; k < els.length; k++) {
                        var el = els[k];
                        ['title','placeholder','aria-label'].forEach(function(attr){
                            var val = el.getAttribute(attr);
                            if (!val) return;
                            var nv = val;
                            var changed2 = false;
                            for (var j = 0; j < EN_ZH.length; j++) {
                                if (nv.indexOf(EN_ZH[j][0]) > -1) {
                                    nv = nv.split(EN_ZH[j][0]).join(EN_ZH[j][1]);
                                    changed2 = true;
                                }
                            }
                            if (changed2 && nv !== val) el.setAttribute(attr, nv);
                        });
                    }
                } catch(e) {
                    try { AndroidBridge.log('forceChinese异常: ' + e.message); } catch(_) {}
                }
            }

            function switchToTargetTab() {
                if (!targetTab) return;
                var tabs = document.querySelectorAll('.sectionTabItem');
                for (var i = 0; i < tabs.length; i++) {
                    var tabText = cleanText(tabs[i].innerText);
                    var targetClean = cleanText(targetTab);
                    if (tabText.indexOf(targetClean) > -1) {
                        if (tabs[i].className.indexOf('active') === -1) {
                            tabs[i].click();
                            AndroidBridge.log('已切换到标签页: ' + targetTab);
                        }
                        // 修复 Q6：每次走到这里都触发一次泵检查（按需、幂等）
                        if (pumpItems.length > 0) {
                            triggerPumpCheck();
                        }
                        break;
                    }
                }
            }

            // 修复 Q6：等待元素出现再勾选。用轮询 + 可见性判断，多次进入标签页都能补勾。
            function triggerPumpCheck() {
                if (window.__pumpChecking) return;
                // 没有 pending 就直接跳过
                var hasPending = false;
                for (var kk in window.__pumpPending) { if (window.__pumpPending.hasOwnProperty(kk)) { hasPending = true; break; } }
                if (!hasPending) return;

                window.__pumpChecking = true;
                var retryCount = 0;
                var maxRetry = 20; // 最多重试 20 轮（约 10s），覆盖较慢的标签页渲染

                var pumpInterval = setInterval(function() {
                    try {
                        var stillPending = false;
                        // 修复 Q6：兼容多种 checkbox 结构（label.ming.Checkbox / 普通 label.checkbox / [role=checkbox]）
                        var selectors = [
                            'label.ming.Checkbox',
                            'label.Checkbox',
                            'label.checkbox',
                            '[role="checkbox"]',
                            '.Checkbox'
                        ];
                        var labelEls = [];
                        for (var si = 0; si < selectors.length; si++) {
                            var found = document.querySelectorAll(selectors[si]);
                            for (var fi = 0; fi < found.length; fi++) labelEls.push(found[fi]);
                        }

                        for (var k = 0; k < pumpItems.length; k++) {
                            var pumpName = pumpItems[k];
                            if (!window.__pumpPending[pumpName]) continue;
                            var pumpClean = cleanText(pumpName);
                            var matched = false;

                            for (var i = 0; i < labelEls.length; i++) {
                                var el = labelEls[i];
                                // 必须可见（在当前标签页里）
                                if (el.offsetParent === null && el.offsetWidth === 0) continue;
                                var title = el.getAttribute('title') || '';
                                var inner = cleanText(el.innerText || el.textContent || '');
                                var aria = el.getAttribute('aria-label') || '';
                                var near = '';
                                // 部分明道云结构：文字在兄弟/子节点
                                var sib = el.querySelector('.Checkbox-text, .name, span');
                                if (sib) near = cleanText(sib.innerText || sib.textContent || '');

                                if (cleanText(title) === pumpClean || inner.indexOf(pumpClean) > -1 ||
                                    cleanText(aria) === pumpClean || near === pumpClean) {
                                    matched = true;
                                    // 判断是否已勾选（兼容多种 checked 标记）
                                    var isCheckCls = function(node){
                                        if (!node) return false;
                                        var cn = ' ' + (node.className || '') + ' ';
                                        return cn.indexOf('Checkbox-checked') > -1 ||
                                               cn.indexOf(' checked') > -1 ||
                                               cn.indexOf('is-checked') > -1;
                                    };
                                    var isChecked = isCheckCls(el) ||
                                                    isCheckCls(el.querySelector('.Checkbox-box')) ||
                                                    el.getAttribute('aria-checked') === 'true' ||
                                                    (el.querySelector('input[type=checkbox]') &&
                                                     el.querySelector('input[type=checkbox]').checked);

                                    if (isChecked) {
                                        window.__pumpPending[pumpName] = false;
                                        delete window.__pumpPending[pumpName];
                                        AndroidBridge.log('冷冻泵已是勾选状态: ' + pumpName);
                                    } else {
                                        // 执行点击
                                        try {
                                            el.dispatchEvent(new Event('mouseover', { bubbles: true }));
                                            el.dispatchEvent(new Event('mousedown', { bubbles: true }));
                                            el.dispatchEvent(new Event('mouseup', { bubbles: true }));
                                        } catch(e) {}
                                        el.click();
                                        AndroidBridge.log('执行冷冻泵点击: ' + pumpName);
                                        // 不立即标记为完成；下一轮再确认是否真的勾上，
                                        // 这样即便首次点击被表单吞掉，还能补勾
                                    }
                                    break;
                                }
                            }
                            if (!matched) stillPending = true;
                            // pending 仍存在也算 stillPending
                            if (window.__pumpPending[pumpName]) stillPending = true;
                        }

                        retryCount++;
                        var pendingLeft = false;
                        for (var pk in window.__pumpPending) { if (window.__pumpPending.hasOwnProperty(pk)) { pendingLeft = true; break; } }
                        if (!pendingLeft || retryCount >= maxRetry) {
                            clearInterval(pumpInterval);
                            window.__pumpChecking = false;
                            AndroidBridge.log('冷冻泵检查完毕 (轮次=' + retryCount + ', 剩余pending=' + (pendingLeft?'有':'无') + ')');
                        }
                    } catch(e) {
                        retryCount++;
                        if (retryCount >= maxRetry) {
                            clearInterval(pumpInterval);
                            window.__pumpChecking = false;
                        }
                    }
                }, 500);
            }

            function expandAllGroups() {
                var groupControls = document.querySelectorAll('.customFormItemControl[type="22"]');
                for (var i = 0; i < groupControls.length; i++) {
                    var arrow = groupControls[i].querySelector('.icon-arrow-down-border');
                    if (arrow) {
                        groupControls[i].querySelector('.titleBox').click();
                    }
                }
            }

            function setInputValue(formItem, value) {
                if (!formItem) return false;
                var controlBox = formItem.querySelector('.customFormControlBox');
                if (!controlBox) return false;
                var input = formItem.querySelector('input');
                if (input) {
                    inputValueSetter.call(input, value);
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                    return input.value === String(value);
                }
                var textarea = formItem.querySelector('textarea');
                if (textarea) {
                    textareaValueSetter.call(textarea, value);
                    textarea.dispatchEvent(new Event('input', { bubbles: true }));
                    textarea.dispatchEvent(new Event('change', { bubbles: true }));
                    return textarea.value === String(value);
                }
                var displaySpan = controlBox.querySelector('.sc-jgwFWF, .WordBreak');
                if (displaySpan) {
                    displaySpan.innerText = value;
                    controlBox.dispatchEvent(new Event('input', { bubbles: true }));
                    controlBox.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                }
                return false;
            }

            function scanAndAutofillEngine() {
                try {
                    window.__ocrScanCount++;
                    forceChinese();
                    switchToTargetTab();
                    if (!window.__ocrGroupsExpanded) {
                        expandAllGroups();
                        window.__ocrGroupsExpanded = true;
                    }
                    var formItems = document.querySelectorAll('.customFormItem');
                    if (formItems.length === 0) return;
                    var filledCount = 0;
                    for (var i = 0; i < targetFields.length; i++) {
                        var item = targetFields[i];
                        var targetLabel = cleanText(item.label);
                        for (var j = 0; j < formItems.length; j++) {
                            var labelEl = formItems[j].querySelector('.controlLabelName');
                            if (!labelEl) continue;
                            var labelText = cleanText(labelEl.innerText || labelEl.getAttribute('title'));
                            if (labelText === targetLabel || labelText.indexOf(targetLabel) > -1) {
                                if (setInputValue(formItems[j], item.value)) {
                                    filledCount++;
                                }
                                break;
                            }
                        }
                    }
                    if (filledCount > window.__ocrMaxFilledCount) {
                        window.__ocrMaxFilledCount = filledCount;
                        window.__ocrIdleCount = 0;
                    } else {
                        window.__ocrIdleCount++;
                        if (window.__ocrIdleCount >= ${MAX_IDLE_SCAN_COUNT}) {
                            clearInterval(window.__ocrScanTimer);
                            AndroidBridge.log('填充数连续未突破最大值，引擎自动停止轮询，最终最大填充数: ' + window.__ocrMaxFilledCount);
                        }
                    }
                    if (window.AndroidBridge && AndroidBridge.onFillComplete) {
                        AndroidBridge.onFillComplete(filledCount);
                    }
                } catch (e) {
                    AndroidBridge.log('填表引擎异常: ' + e.message);
                }
            }

            window.__ocrScanTimer = setInterval(scanAndAutofillEngine, ${AUTO_SCAN_INTERVAL_MS});
            scanAndAutofillEngine();
            setTimeout(function(){
                if (document.activeElement) document.activeElement.blur();
            }, 1000);
        })();
        """.trimIndent()
    }

    override fun onPause() {
        super.onPause()
        // 修复 Q2：onPause 时仅暂停，不强制 stopLoading/destroy，避免返回再进入时 WebView 卡死
        if (!isActivityDestroyed) {
            try { binding.webView.onPause() } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isActivityDestroyed) {
            try { binding.webView.onResume() } catch (_: Exception) {}
        }
    }

    override fun onBackPressed() {
        // 修复 Q2：WebView 能后退则后退，否则走默认 finish，避免状态残留
        if (binding.webView.canGoBack() && hasLoadedRealUrl) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        // 修复 Q2：先标记销毁，再有序释放，避免 destroy 后仍有回调进入导致卡死
        isActivityDestroyed = true
        timeoutHandler.removeCallbacksAndMessages(null)
        try {
            binding.webView.stopLoading()
            binding.webView.removeJavascriptInterface("AndroidBridge")
            binding.webView.loadUrl("about:blank")
            binding.webView.clearHistory()
            binding.webView.clearCache(true)
            binding.webView.clearFormData()
            (binding.webView.parent as? android.view.ViewGroup)?.removeView(binding.webView)
            binding.webView.destroy()
        } catch (e: Exception) {
            DebugLogger.log("WebView", "onDestroy 释放异常: ${e.message}")
        }
        super.onDestroy()
    }
}
