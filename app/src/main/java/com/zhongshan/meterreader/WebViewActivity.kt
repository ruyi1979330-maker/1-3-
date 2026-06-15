package com.zhongshan.meterreader

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvBanner: TextView
    private var formDataJson: String = "[]"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 请确保您的布局文件名正确，这里假设是 activity_web_view 或 activity_webview
        setContentView(R.layout.activity_webview) 
        
        // 请确保控件 ID 与 XML 中一致
        tvBanner = findViewById(R.id.tv_status_banner) 
        webView = findViewById(R.id.webview) 

        val url = intent.getStringExtra("URL") ?: ""
        formDataJson = intent.getStringExtra("FORM_DATA") ?: "[]"

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                tvBanner.text = "表单加载中，请稍候..."
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tvBanner.text = "✅ 表单已加载！请手动点击上方对应标签页，APP 将自动填表。"
                injectCoreJs(view)
            }
        }
        webView.loadUrl(url)
    }

    private fun injectCoreJs(view: WebView?) {
        val jsCode = compileFillJs(formDataJson)
        view?.evaluateJavascript(jsCode, null)
    }

    private fun compileFillJs(jsonData: String): String {
        return """
        (function() {
            window.__FORM_DATA__ = $jsonData;
            
            function setNativeValue(element, value) {
                const valueSetter = Object.getOwnPropertyDescriptor(element, 'value').set;
                const prototype = Object.getPrototypeOf(element);
                const prototypeValueSetter = Object.getOwnPropertyDescriptor(prototype, 'value').set;
                if (valueSetter && valueSetter !== prototypeValueSetter) {
                    prototypeValueSetter.call(element, value);
                } else {
                    if(valueSetter) valueSetter.call(element, value);
                    else element.value = value;
                }
                element.dispatchEvent(new Event('input', { bubbles: true }));
                element.dispatchEvent(new Event('change', { bubbles: true }));
            }

            window.fillFormData = function() {
                let data = window.__FORM_DATA__;
                if (!data || data.length === 0) return;
                let filledCount = 0;
                data.forEach(item => {
                    let filled = false;
                    let el = document.getElementById(item.fieldId) || document.querySelector('[name="' + item.fieldId + '"]');
                    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                        setNativeValue(el, item.value);
                        filled = true;
                    }
                    if (!filled && item.formLabel) {
                        let labels = document.querySelectorAll('label, span, div, td, th, p');
                        for (let lbl of labels) {
                            if (lbl.innerText && lbl.innerText.includes(item.formLabel)) {
                                let parent = lbl.closest('.form-group, .el-form-item, .ant-form-item, .list-item, tr, .van-cell, div[class*="item"]');
                                if (parent) {
                                    let input = parent.querySelector('input, textarea');
                                    if (input) { setNativeValue(input, item.value); filled = true; break; }
                                }
                            }
                        }
                    }
                    if (filled) filledCount++;
                });
                if (filledCount > 0) window.AndroidBridge.showToast('成功填入 ' + filledCount + ' 个字段');
            };

            document.addEventListener('click', function(e) {
                let target = e.target;
                while(target && target !== document.body) {
                    let text = target.innerText ? target.innerText.trim() : '';
                    let knownTabs = ['交接班', '螺杆机组', '离心机组', '板交', '螺杆机', '操作记录'];
                    let matchedTab = knownTabs.find(tab => text.includes(tab));
                    if (matchedTab) {
                        window.AndroidBridge.onTabClicked(matchedTab);
                        setTimeout(() => window.fillFormData(), 300);
                        setTimeout(() => window.fillFormData(), 800);
                        setTimeout(() => window.fillFormData(), 1500);
                        break;
                    }
                    target = target.parentElement;
                }
            }, true);

            let debounceTimer = null;
            const observer = new MutationObserver((mutations) => {
                let hasSignificantChange = mutations.some(m => m.addedNodes.length > 2);
                if (hasSignificantChange) {
                    clearTimeout(debounceTimer);
                    debounceTimer = setTimeout(() => window.fillFormData(), 500);
                }
            });
            observer.observe(document.body, { childList: true, subtree: true });
            setTimeout(() => window.fillFormData(), 1000);
        })();
        """.trimIndent()
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onTabClicked(tabName: String) {
            runOnUiThread { tvBanner.text = "当前标签页：$tabName (正在自动填表...)" }
        }

        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread {
                Toast.makeText(this@WebViewActivity, msg, Toast.LENGTH_SHORT).show()
                tvBanner.text = "填表完成：$msg"
            }
        }
    }
}
