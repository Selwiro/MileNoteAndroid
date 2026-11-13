package com.milenote.android.data.webdav

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.webDavSyncDataStore by preferencesDataStore(name = "milenote-webdav-sync")

/**
 * 同步状态持久化（`milenote-webdav-sync`）。
 *
 * 与配置分开存放，便于「配置变更时只清同步状态」以及后续按账号多份配置扩展。
 */
class WebDavSyncStateStore(private val context: Context) {

    private object Keys {
        val lastEtag = stringPreferencesKey("last_etag")
        val lastLocalHash = stringPreferencesKey("last_local_hash")
        val lastRemoteHash = stringPreferencesKey("last_remote_hash")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastAutoSyncAt = longPreferencesKey("last_auto_sync_at")
        val lastAutoSyncResult = stringPreferencesKey("last_auto_sync_result")
        val lastProblemKind = stringPreferencesKey("last_problem_kind")
        val lastProblemMessage = stringPreferencesKey("last_problem_message")
        val lastProblemAt = longPreferencesKey("last_problem_at")
    }

    /** 当前同步状态；从未同步过时返回全空 [WebDavSyncState]。 */
    val state: Flow<WebDavSyncState> = context.webDavSyncDataStore.data.map { prefs ->
        WebDavSyncState(
            lastEtag = prefs[Keys.lastEtag],
            lastLocalHash = prefs[Keys.lastLocalHash],
            lastRemoteHash = prefs[Keys.lastRemoteHash],
            lastSyncAt = prefs[Keys.lastSyncAt],
            lastAutoSyncAt = prefs[Keys.lastAutoSyncAt],
            lastAutoSyncResult = prefs[Keys.lastAutoSyncResult],
            lastProblemKind = prefs[Keys.lastProblemKind],
            lastProblemMessage = prefs[Keys.lastProblemMessage],
            lastProblemAt = prefs[Keys.lastProblemAt],
        )
    }

    /** 整体覆盖保存。 */
    suspend fun save(state: WebDavSyncState) {
        context.webDavSyncDataStore.edit { prefs ->
            state.lastEtag?.let { prefs[Keys.lastEtag] = it } ?: prefs.remove(Keys.lastEtag)
            state.lastLocalHash?.let { prefs[Keys.lastLocalHash] = it } ?: prefs.remove(Keys.lastLocalHash)
            state.lastRemoteHash?.let { prefs[Keys.lastRemoteHash] = it } ?: prefs.remove(Keys.lastRemoteHash)
            state.lastSyncAt?.let { prefs[Keys.lastSyncAt] = it } ?: prefs.remove(Keys.lastSyncAt)
            state.lastAutoSyncAt?.let { prefs[Keys.lastAutoSyncAt] = it } ?: prefs.remove(Keys.lastAutoSyncAt)
            state.lastAutoSyncResult?.let { prefs[Keys.lastAutoSyncResult] = it } ?: prefs.remove(Keys.lastAutoSyncResult)
            state.lastProblemKind?.let { prefs[Keys.lastProblemKind] = it } ?: prefs.remove(Keys.lastProblemKind)
            state.lastProblemMessage?.let { prefs[Keys.lastProblemMessage] = it } ?: prefs.remove(Keys.lastProblemMessage)
            state.lastProblemAt?.let { prefs[Keys.lastProblemAt] = it } ?: prefs.remove(Keys.lastProblemAt)
        }
    }

    /** 清空（切换服务器 / 清除配置时调用）。 */
    suspend fun clear() {
        context.webDavSyncDataStore.edit { prefs -> prefs.clear() }
    }
}
