package com.milenote.android.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "milenote-settings")

/** 轻量键值设置存储（深浅色/WebDAV 自动同步）。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val darkMode = stringPreferencesKey("dark_mode")
        val webDavAutoSync = booleanPreferencesKey("webdav_auto_sync")
        val language = stringPreferencesKey("language")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            darkMode = prefs[Keys.darkMode]?.let { name -> DarkMode.entries.firstOrNull { it.name == name } }
                ?: DarkMode.SYSTEM,
            webDavAutoSync = prefs[Keys.webDavAutoSync] ?: false,
            language = prefs[Keys.language]
                ?.let { name -> com.milenote.android.i18n.AppLanguage.entries.firstOrNull { it.name == name } }
                ?: com.milenote.android.i18n.AppLanguage.SYSTEM,
        )
    }

    suspend fun setDarkMode(mode: DarkMode) = edit { it[Keys.darkMode] = mode.name }

    suspend fun setWebDavAutoSync(enabled: Boolean) = edit { it[Keys.webDavAutoSync] = enabled }

    suspend fun setLanguage(language: com.milenote.android.i18n.AppLanguage) =
        edit { it[Keys.language] = language.name }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { prefs -> block(prefs) }
    }
}
