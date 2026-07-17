package com.zhongshan.meterreader

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DebugLogger {
    // 防止相机流模式下日志无限累积导致 OOM：达到上限时丢弃最旧的一半，仅保留近期日志。
    // 单位为字符数，约 1MB 上限（崩溃导出时也足以定位问题）。
    private const val MAX_LOG_CHARS = 1_000_000
    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, msg: String) {
        val time = dateFormat.format(Date())
        val line = "[$time] [$tag] $msg"
        synchronized(logBuilder) {
            logBuilder.appendLine(line)
            // 超过上限时裁剪：保留后半部分，并在开头追加截断标记
            if (logBuilder.length > MAX_LOG_CHARS) {
                val keep = logBuilder.substring(logBuilder.length - (MAX_LOG_CHARS / 2))
                logBuilder.setLength(0)
                logBuilder.append("[日志已裁剪，仅保留最近半段]\n")
                logBuilder.append(keep)
            }
        }
        // 同时输出到 logcat，方便实时调试
        android.util.Log.d("OCR_Debug", line)
    }

    fun saveAndShare(context: Context) {
        val dir = context.cacheDir
        val file = File(dir, "ocr_debug_${System.currentTimeMillis()}.txt")
        val snapshot = synchronized(logBuilder) { logBuilder.toString() }
        file.writeText(snapshot)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "导出调试日志"))
    }
}
