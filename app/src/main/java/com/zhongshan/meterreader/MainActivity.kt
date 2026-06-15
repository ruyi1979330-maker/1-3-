package com.zhongshan.meterreader

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
// ... 其他 import ...

class MainActivity : AppCompatActivity() {
    
    // ... 原有代码 ...

    private fun openWebViewForFilling(url: String, formDataJson: String) {
        // 【修复点】：直接使用 WebViewActivity，因为它们在同一个包下
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra("URL", url)
            putExtra("FORM_DATA", formDataJson)
        }
        startActivity(intent)
    }
}
