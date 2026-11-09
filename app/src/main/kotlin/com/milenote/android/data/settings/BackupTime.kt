package com.milenote.android.data.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 备份时间显示格式：固定 4 位年 + 24 小时制 `yyyy-MM-dd HH:mm`。
 *
 * 纯函数（不读 [com.milenote.android.i18n.I18n]）：两种语言共用同一时间格式，
 * 时区显式传入以便单测；界面调用时用默认的 [ZoneId.systemDefault]。
 */
private val backupTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/** 把 epoch 毫秒按 [zone] 格式化成 `yyyy-MM-dd HH:mm`。 */
fun formatBackupAt(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    backupTimeFormatter.format(Instant.ofEpochMilli(millis).atZone(zone))
