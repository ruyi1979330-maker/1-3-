package com.example.ocrapp.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvBanner: TextView
    private var formDataJson: String = "[]"
    private var currentActiveTab: String = "交接班" // 默认标签页

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContentView(R.layout.activity_webview) // 假设布局
        
        tvBanner = findViewById(android.R.id.title) // 假设的横幅控件
        webView = findViewById(android.R.id.content) // 假设的WebView控件

        val url = intent.getStringExtra("URL") ?: ""
        formDataJson = intent.getStringExtra("FORM_DATA") ?: "[]"

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        
        // 注入 Android 桥接对象
        webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                tvBanner.text = "表单加载中，请稍候..."
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tvBanner.text = "加载完成，请点击对应标签页自动填表"
                // 注入核心 JS 逻辑
                injectCoreJs(view)
            }
        }
        webView.loadUrl(url)
    }

    private fun injectCoreJs(view: WebView?) {
        val jsCode = compileFillJs(formDataJson)
        view?.evaluateJavascript(jsCode, null)
    }

    /**
     * 编译并生成注入的 JS 代码
     */
    private fun compileFillJs(jsonData: String): String {
        return """
        (function() {
            // 1. 将数据挂载到 window 供后续调用
            window.__FORM_DATA__ = $jsonData;
            window.__FILLED_COUNT__ = 0;

            // 2. 兼容 React/Vue 等现代前端框架的填值函数
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
                element.dispatchEvent(new Event('blur', { bubbles: true }));
            }

            // 3. 核心填表逻辑
            window.fillFormData = function() {
                let data = window.__FORM_DATA__;
                if (!data || data.length === 0) return;
                
                let filledCount = 0;
                data.forEach(item => {
                    let filled = false;
                    
                    // 策略 A: 通过 ID 或 Name 精确查找
                    let el = document.getElementById(item.fieldId) || document.querySelector('[name="' + item.fieldId + '"]');
                    if (el && el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
                        setNativeValue(el, item.value);
                        filled = true;
                    }
                    
                    // 策略 B: 通过中文标签 (formLabel) 模糊查找
                    if (!filled && item.formLabel) {
                        let labels = document.querySelectorAll('label, span, div, td, th, p');
                        for (let lbl of labels) {
                            if (lbl.innerText && lbl.innerText.includes(item.formLabel)) {
                                // 向上查找常见的表单容器
                                let parent = lbl.closest('.form-group, .el-form-item, .ant-form-item, .list-item, tr, .van-cell, div[class*="item"]');
                                if (parent) {
                                    let input = parent.querySelector('input, textarea, select');
                                    if (input) {
                                        setNativeValue(input, item.value);
                                        filled = true;
                                        break;
                                    }
                                }
                                // 如果容器没找到，尝试找兄弟节点
                                let sibling = lbl.nextElementSibling;
                                while(sibling && !filled) {
                                    let input = sibling.querySelector('input, textarea, select') || (sibling.tagName === 'INPUT' ? sibling : null);
                                    if (input) {
                                        setNativeValue(input, item.value);
                                        filled = true;
                                    }
                                    sibling = sibling.nextElementSibling;
                                }
                            }
                        }
                    }
                    if (filled) filledCount++;
                });
                
                window.__FILLED_COUNT__ = filledCount;
                if (filledCount > 0) {
                    window.AndroidBridge.showToast('成功填入 ' + filledCount + ' 个字段');
                }
            };

            // 4. 监听标签页点击 (事件委托，防止 SPA 路由导致监听失效)
            document.addEventListener('click', function(e) {
                let target = e.target;
                while(target && target !== document.body) {
                    let text = target.innerText ? target.innerText.trim() : '';
                    // 匹配一号/三号机房的标签页名称
                    let knownTabs = ['交接班', '螺杆机组', '离心机组', '板交', '螺杆机', '操作记录'];
                    let matchedTab = knownTabs.find(tab => text.includes(tab));
                    
                    if (matchedTab) {
                        window.AndroidBridge.onTabClicked(matchedTab);
                        // 多次延时重试，应对 SPA 异步渲染延迟
                        setTimeout(() => window.fillFormData(), 300);
                        setTimeout(() => window.fillFormData(), 800);
                        setTimeout(() => window.fillFormData(), 1500);
                        break;
                    }
                    target = target.parentElement;
                }
            }, true); // 使用捕获阶段

            // 5. MutationObserver 监听 DOM 变化 (标签页切换导致 DOM 替换时触发)
            let debounceTimer = null;
            const observer = new MutationObserver((mutations) => {
                let hasSignificantChange = mutations.some(m => m.addedNodes.length > 2);
                if (hasSignificantChange) {
                    clearTimeout(debounceTimer);
                    debounceTimer = setTimeout(() => window.fillFormData(), 500);
                }
            });
            observer.observe(document.body, { childList: true, subtree: true });

            // 初始加载时尝试填一次（针对默认标签页）
            setTimeout(() => window.fillFormData(), 1000);
        })();
        """.trimIndent()
    }

    /**
     * JS 桥接接口
     */
    inner class WebAppInterface {
        @JavascriptInterface
        fun onTabClicked(tabName: String) {
            runOnUiThread {
                currentActiveTab = tabName
                tvBanner.text = "当前标签页：$tabName (正在自动填表...)"
            }
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
