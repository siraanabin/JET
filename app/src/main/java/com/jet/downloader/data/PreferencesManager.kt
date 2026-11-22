package com.jet.downloader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.jetDataStore: DataStore<Preferences> by preferencesDataStore(name = "jet_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val LEGACY_DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val CLIPBOARD_DETECT_KEY = booleanPreferencesKey("clipboard_detect")

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
        context.jetDataStore.edit { prefs ->
            prefs[CLIPBOARD_DETECT_KEY] = enabled
        }
    }
}
