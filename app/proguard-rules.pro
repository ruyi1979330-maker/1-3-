# ===== 原有规则 =====
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.zhongshan.meterreader.data.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# ===== 新增 1：Gson TypeToken 反射序列化 =====
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ===== 新增 2：Kotlin 协程调度器 =====
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# ===== 新增 3：WebView JS Bridge =====
-keepclassmembers class com.zhongshan.meterreader.WebViewActivity$SafeWebBridge {
    @android.webkit.JavascriptInterface <methods>;
}