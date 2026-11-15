package com.milenote.android.ui.screens.settings

import com.milenote.android.data.webdav.WebDavFailureCategory
import com.milenote.android.data.webdav.WebDavProblemKind
import com.milenote.android.i18n.tr

/**
 * 「我的 → 数据概览」状态行里的同步口径。
 *
 * @property text 同步短标签（未开启 / 已开启 / 失败 / 冲突 / 需密码）。
 * @property warn 是否需要警示色（最近一次同步异常时为 true）。
 */
data class MineSyncStatus(
    val text: String,
    val warn: Boolean,
)

/**
 * 「我的 → 数据概览」状态行 = 备份口径 + 同步口径。
 *
 * @property text 一行合并文案（`上次备份 … · 同步…`）。
 * @property warn 备份缺失/超期或同步异常时整行转警示色。
 */
data class MineStatusLine(
    val text: String,
    val warn: Boolean,
)

/**
 * 同步短标签：把持久化的异常类别折算成「一个词」，详情留给 WebDAV 同步页。
 *
 * 未配置 = 没开同步（不算异常）；已配置且上次同步正常 = 「同步已开启」；
 * 上次同步失败 / 冲突 / 缺加密密码 = 对应的异常短标签 + 警示色。
 * 文案在渲染时按当前语言取，不沿用异常发生时的语言。
 *
 * @param configured 是否已配置 WebDAV（有地址与密码）。
 * @param problemKind 最近一次同步异常类别；null = 正常。
 * @param failureCategory 失败的归一类别（UX6）：失败时把「该去改什么」写进短标签，
 *   避免「我的」页只有一句「同步失败」，要点进去才知道是密码错还是断网。
 */
fun mineSyncStatus(
    configured: Boolean,
    problemKind: WebDavProblemKind?,
    failureCategory: WebDavFailureCategory? = null,
): MineSyncStatus = when {
    !configured -> MineSyncStatus(text = tr("同步未开启", "Sync off"), warn = false)
    problemKind == null -> MineSyncStatus(text = tr("同步已开启", "Sync on"), warn = false)
    problemKind == WebDavProblemKind.CONFLICT ->
        MineSyncStatus(text = tr("同步冲突", "Sync conflict"), warn = true)
    problemKind == WebDavProblemKind.NEEDS_PASSWORD ->
        MineSyncStatus(text = tr("同步需密码", "Sync needs password"), warn = true)
    else -> MineSyncStatus(text = syncFailureLabel(failureCategory), warn = true)
}

/** 失败短标签：五类归一（UX6）。 */
private fun syncFailureLabel(category: WebDavFailureCategory?): String = when (category) {
    WebDavFailureCategory.AUTH -> tr("同步失败·认证", "Sync failed: auth")
    WebDavFailureCategory.PERMISSION -> tr("同步失败·权限", "Sync failed: permission")
    WebDavFailureCategory.NOT_FOUND -> tr("同步失败·文件不存在", "Sync failed: not found")
    WebDavFailureCategory.NETWORK -> tr("同步失败·网络", "Sync failed: network")
    WebDavFailureCategory.ENCRYPTION -> tr("同步失败·加密密码", "Sync failed: encryption")
    else -> tr("同步失败", "Sync failed")
}

/**
 * 合并状态行：备份与同步用 `·` 隔开，任一异常即整行警示（与原来「尚未备份 / 已 N 天未备份」同一口径）。
 *
 * @param backupText 备份口径文案（尚未备份 / 上次备份 … / 已 N 天未备份）。
 * @param backupWarn 备份是否异常。
 * @param sync 同步口径（见 [mineSyncStatus]）。
 */
fun mineStatusLine(backupText: String, backupWarn: Boolean, sync: MineSyncStatus): MineStatusLine =
    MineStatusLine(text = "$backupText · ${sync.text}", warn = backupWarn || sync.warn)
