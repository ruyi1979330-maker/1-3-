package com.zhongshan.meterreader

import android.app.Application
import android.util.Log
import com.zhongshan.meterreader.data.RoiConfigManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class MeterReaderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PresetManager.init(this)
        RecognitionResultHolder.init(this)
        // 【新增】：初始化 ROI 坐标配置管理器，防止运行时空指针崩溃
        RoiConfigManager.init(this)
        setupGlobalCrashHandler()
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logDir = File(filesDir, "CrashLogs")
                if (!logDir.exists()) logDir.mkdirs()

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                File(logDir, "CRASH_$timestamp.txt").writeText(
                    "Thread: ${thread.name}\n\nStacktrace:\n${
                        StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
                    }"
                )

                // 保留最新 20 条崩溃日志
                logDir.listFiles()
                    ?.filter { it.name.startsWith("CRASH_") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(20)
                    ?.forEach { it.delete() }

            } catch (e: Exception) {
                Log.e("MeterReaderApp", "崩溃日志写入失败", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
                    ?: kotlin.system.exitProcess(1)
            }
        }
    }
}
