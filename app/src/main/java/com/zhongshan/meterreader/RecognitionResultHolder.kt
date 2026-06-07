package com.zhongshan.meterreader

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object RecognitionResultHolder {
    private const val PREFS_NAME = "ocr_result_cache"
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private val mutex = Mutex()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun saveFieldsForMachine(machineId: String, newData: Map<String, String>) {
        mutex.withLock {
            val key = "MACHINE_$machineId"
            val rawJson = prefs.getString(key, null)
            val existingData = mutableMapOf<String, String>()

            if (rawJson != null) {
                try {
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    val parsed: Map<String, String>? = gson.fromJson(rawJson, type)
                    if (parsed != null) {
                        existingData.putAll(parsed)
                    }
                } catch (e: Exception) {
                    val backupKey = "CORRUPTED_${machineId}_${System.currentTimeMillis()}"
                    prefs.edit().putString(backupKey, rawJson).apply()
                    Log.e("ResultHolder", "历史数据损坏已备份至 $backupKey", e)
                }
            }

            existingData.putAll(newData)
            prefs.edit().putString(key, gson.toJson(existingData)).apply()
        }
    }

    suspend fun getFieldsForMachine(machineId: String): Map<String, String> = mutex.withLock {
        val json = prefs.getString("MACHINE_$machineId", null) ?: return@withLock emptyMap()
        return@withLock try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun clearMachineData(machineId: String) = mutex.withLock {
        prefs.edit().remove("MACHINE_$machineId").apply()
    }

    suspend fun clearAll() = mutex.withLock {
        prefs.edit().clear().apply()
    }
}