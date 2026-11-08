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
        val currency = stringPreferencesKey("currency")
        val distanceUnit = stringPreferencesKey("distance_unit")
        val volumeUnit = stringPreferencesKey("volume_unit")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            darkMode = prefs[Keys.darkMode]?.let { name -> DarkMode.entries.firstOrNull { it.name == name } }
                ?: DarkMode.SYSTEM,
            webDavAutoSync = prefs[Keys.webDavAutoSync] ?: false,
            language = prefs[Keys.language]
                ?.let { name -> com.milenote.android.i18n.AppLanguage.entries.firstOrNull { it.name == name } }
                ?: com.milenote.android.i18n.AppLanguage.SYSTEM,
            currency = prefs[Keys.currency]
                ?.let { name -> com.milenote.android.i18n.CurrencyUnit.entries.firstOrNull { it.name == name } },
            distanceUnit = prefs[Keys.distanceUnit]
                ?.let { name -> com.milenote.android.i18n.DistanceUnit.entries.firstOrNull { it.name == name } }
                ?: com.milenote.android.i18n.DistanceUnit.KM,
            volumeUnit = prefs[Keys.volumeUnit]
                ?.let { name -> com.milenote.android.i18n.VolumeUnit.entries.firstOrNull { it.name == name } }
                ?: com.milenote.android.i18n.VolumeUnit.LITER,
        )
    }

    suspend fun setDarkMode(mode: DarkMode) = edit { it[Keys.darkMode] = mode.name }

    suspend fun setWebDavAutoSync(enabled: Boolean) = edit { it[Keys.webDavAutoSync] = enabled }

    suspend fun setLanguage(language: com.milenote.android.i18n.AppLanguage) =
        edit { it[Keys.language] = language.name }

    /** 货币单位；null 表示跟随界面语言（中文元 / 英文美元）。 */
    suspend fun setCurrency(currency: com.milenote.android.i18n.CurrencyUnit?) = edit { prefs ->
        if (currency == null) prefs.remove(Keys.currency) else prefs[Keys.currency] = currency.name
    }

    suspend fun setDistanceUnit(unit: com.milenote.android.i18n.DistanceUnit) =
        edit { it[Keys.distanceUnit] = unit.name }

    suspend fun setVolumeUnit(unit: com.milenote.android.i18n.VolumeUnit) =
        edit { it[Keys.volumeUnit] = unit.name }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { prefs -> block(prefs) }
    }
}
