package com.jet.downloader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.jetDataStore: DataStore<Preferences> by preferencesDataStore(name = "jet_settings")

data class DownloadPreferences(
    val defaultQuality: String = "vid-1080",
    val saveSubtitles: Boolean = false,
    val maxDownloadTasks: Int = 3,
    val speedLimitKbps: Int = 0,
    val allowMobileData: Boolean = true
)

class PreferencesManager(private val context: Context) {

    companion object {
        private val LEGACY_DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val CLIPBOARD_DETECT_KEY = booleanPreferencesKey("clipboard_detect")
        private val DEFAULT_QUALITY_KEY = stringPreferencesKey("download_default_quality")
        private val SAVE_SUBTITLES_KEY = booleanPreferencesKey("download_save_subtitles")
        private val MAX_DOWNLOAD_TASKS_KEY = intPreferencesKey("download_max_tasks")
        private val SPEED_LIMIT_KEY = intPreferencesKey("download_speed_limit_kbps")
        private val ALLOW_MOBILE_DATA_KEY = booleanPreferencesKey("download_mobile_data")

        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
    }

    val themeModeFlow: Flow<String> = context.jetDataStore.data.map { prefs ->
        prefs[THEME_MODE_KEY]
            ?: prefs[LEGACY_DARK_MODE_KEY]?.let { if (it) THEME_DARK else THEME_LIGHT }
            ?: THEME_SYSTEM
    }

    val clipboardDetectFlow: Flow<Boolean> = context.jetDataStore.data.map { prefs ->
        prefs[CLIPBOARD_DETECT_KEY] ?: true
    }

    val downloadPreferencesFlow: Flow<DownloadPreferences> = context.jetDataStore.data.map { prefs ->
        DownloadPreferences(
            defaultQuality = prefs[DEFAULT_QUALITY_KEY] ?: "vid-1080",
            saveSubtitles = prefs[SAVE_SUBTITLES_KEY] ?: false,
            maxDownloadTasks = (prefs[MAX_DOWNLOAD_TASKS_KEY] ?: 3).coerceIn(1, 5),
            speedLimitKbps = (prefs[SPEED_LIMIT_KEY] ?: 0).coerceAtLeast(0),
            allowMobileData = prefs[ALLOW_MOBILE_DATA_KEY] ?: true
        )
    }

    suspend fun migrateLegacyThemePreference(): String {
        var selectedMode = THEME_SYSTEM
        context.jetDataStore.edit { prefs ->
            selectedMode = prefs[THEME_MODE_KEY]
                ?: prefs[LEGACY_DARK_MODE_KEY]?.let { if (it) THEME_DARK else THEME_LIGHT }
                ?: THEME_SYSTEM
            prefs[THEME_MODE_KEY] = selectedMode
            prefs.remove(LEGACY_DARK_MODE_KEY)
        }
        return selectedMode
    }

    suspend fun setThemeMode(mode: String) {
        require(mode == THEME_SYSTEM || mode == THEME_DARK || mode == THEME_LIGHT) {
            "Unsupported theme mode: $mode"
        }
        context.jetDataStore.edit { prefs ->
            prefs[THEME_MODE_KEY] = mode
            prefs.remove(LEGACY_DARK_MODE_KEY)
        }
    }

    suspend fun setClipboardDetect(enabled: Boolean) {
        context.jetDataStore.edit { prefs -> prefs[CLIPBOARD_DETECT_KEY] = enabled }
    }

    suspend fun setDownloadPreference(key: String, value: String) {
        context.jetDataStore.edit { prefs ->
            when (key) {
                "defaultQuality" -> {
                    require(value in setOf("vid-360", "vid-480", "vid-720", "vid-1080", "vid-2k", "vid-4k", "aud-fast", "aud-128", "aud-320"))
                    prefs[DEFAULT_QUALITY_KEY] = value
                }
                "saveSubtitles" -> prefs[SAVE_SUBTITLES_KEY] = value.toBooleanStrict()
                "maxDownloadTasks" -> prefs[MAX_DOWNLOAD_TASKS_KEY] = value.toInt().coerceIn(1, 5)
                "speedLimitKbps" -> prefs[SPEED_LIMIT_KEY] = value.toInt().coerceIn(0, 100_000)
                "allowMobileData" -> prefs[ALLOW_MOBILE_DATA_KEY] = value.toBooleanStrict()
            }
        }
    }

    suspend fun getDownloadPreferences(): DownloadPreferences = downloadPreferencesFlow.first()
}
