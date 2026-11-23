package com.localdrop.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localdrop.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "localdrop_prefs")

data class TransferRecord(
    val id: String,
    val isSender: Boolean,
    val peerName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val speedBps: Long,
    val timestampMs: Long,
    val success: Boolean,
    val summaryPath: String = "",
)

data class AppSettings(
    val deviceId: String,
    val deviceName: String,
    val destinationPath: String, // vide = Download/LocalDrop par défaut
    val themeMode: ThemeMode,
)

class AppPreferences(private val context: Context) {

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val DEST_PATH = stringPreferencesKey("dest_path")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HISTORY = stringPreferencesKey("history_json")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val id = prefs[Keys.DEVICE_ID] ?: generateAndSaveId()
        val name = prefs[Keys.DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: Build.MODEL.take(30)
        val dest = prefs[Keys.DEST_PATH].orEmpty()
        val theme = runCatching {
            ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.Dark.name)
        }.getOrDefault(ThemeMode.Dark)
        AppSettings(deviceId = id, deviceName = name, destinationPath = dest, themeMode = theme)
    }

    val history: Flow<List<TransferRecord>> = context.dataStore.data.map { prefs ->
        parseHistory(prefs[Keys.HISTORY].orEmpty())
    }

    private suspend fun generateAndSaveId(): String {
        val created = UUID.randomUUID().toString().take(8)
        context.dataStore.edit { if (it[Keys.DEVICE_ID] == null) it[Keys.DEVICE_ID] = created }
        return created
    }

    suspend fun setDeviceName(name: String) {
        val clean = name.trim().take(30)
        if (clean.isNotEmpty()) {
            context.dataStore.edit { it[Keys.DEVICE_NAME] = clean }
        }
    }

    suspend fun setDestinationPath(path: String) {
        context.dataStore.edit { it[Keys.DEST_PATH] = path.trim() }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun addHistory(record: TransferRecord) {
        context.dataStore.edit { prefs ->
            val current = parseHistory(prefs[Keys.HISTORY].orEmpty())
            val updated = (listOf(record) + current).take(100)
            prefs[Keys.HISTORY] = serializeHistory(updated)
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { it.remove(Keys.HISTORY) }
    }

    private fun parseHistory(raw: String): List<TransferRecord> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                TransferRecord(
                    id = o.optString("id"),
                    isSender = o.optBoolean("isSender"),
                    peerName = o.optString("peerName"),
                    fileCount = o.optInt("fileCount"),
                    totalBytes = o.optLong("totalBytes"),
                    speedBps = o.optLong("speedBps"),
                    timestampMs = o.optLong("timestampMs"),
                    success = o.optBoolean("success"),
                    summaryPath = o.optString("summaryPath"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeHistory(list: List<TransferRecord>): String {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("isSender", r.isSender)
                    put("peerName", r.peerName)
                    put("fileCount", r.fileCount)
                    put("totalBytes", r.totalBytes)
                    put("speedBps", r.speedBps)
                    put("timestampMs", r.timestampMs)
                    put("success", r.success)
                    put("summaryPath", r.summaryPath)
                }
            )
        }
        return arr.toString()
    }
}
