package com.jet.downloader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.jetDataStore: DataStore<Preferences> by preferencesDataStore(name = "jet_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val DARK_MODE_KEY = booleanPreferencesKey("dark_mode")
        val CLIPBOARD_DETECT_KEY = booleanPreferencesKey("clipboard_detect")
    }

    val darkModeFlow: Flow<Boolean> = context.jetDataStore.data.map { prefs ->
        prefs[DARK_MODE_KEY] ?: false
    }

    val clipboardDetectFlow: Flow<Boolean> = context.jetDataStore.data.map { prefs ->
        prefs[CLIPBOARD_DETECT_KEY] ?: true
    }

    suspend fun setDarkMode(enabled: Boolean) {
        context.jetDataStore.edit { prefs ->
            prefs[DARK_MODE_KEY] = enabled
        }
    }

    suspend fun setClipboardDetect(enabled: Boolean) {
        context.jetDataStore.edit { prefs ->
            prefs[CLIPBOARD_DETECT_KEY] = enabled
        }
    }
}
