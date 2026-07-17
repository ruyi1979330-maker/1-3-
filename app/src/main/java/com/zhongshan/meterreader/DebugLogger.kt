package com.zhongshan.meterreader

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object DebugLogger {
    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun log(tag: String, msg: String) {
        val time = dateFormat.format(Date())
        val line = "[$time] [$tag] $msg"
        logBuilder.appendLine(line)
        // 同时输出到 logcat，方便实时调试
        android.util.Log.d("OCR_Debug", line)
    }

    fun saveAndShare(context: Context) {
        val dir = context.cacheDir
        val file = File(dir, "ocr_debug_${System.currentTimeMillis()}.txt")
        file.writeText(logBuilder.toString())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "导出调试日志"))
    }
}
