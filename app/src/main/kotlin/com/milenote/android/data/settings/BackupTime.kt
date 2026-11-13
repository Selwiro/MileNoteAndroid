package com.milenote.android.data.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 备份超期阈值（天）：超过这么多天没备份就提示「建议现在备份一次」。 */
const val BACKUP_OVERDUE_DAYS: Int = 30

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

/**
 * 备份距今多少个日历日（今天 = 0、昨天 = 1）。
 *
 * 纯函数（不读语言/系统时间），时区显式传入便于单测；按 [zone] 的**日历日**差计算，
 * 避免「23 小时前」在不同时区/跨零点时被算成 0 天或 1 天。「我的」页用它显示「今天 / 昨天 / N 天前」。
 *
 * @param lastBackupAt 上次备份时刻（epoch millis）；null = 尚未备份。
 * @param now 当前时刻（epoch millis）。
 * @param zone 计算日历日差用的时区。
 * @return 天数；未备份返回 null。未来时间（时钟回拨）按 0 处理。
 */
fun backupAgeDays(
    lastBackupAt: Long?,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): Int? {
    if (lastBackupAt == null) return null
    val from = Instant.ofEpochMilli(lastBackupAt).atZone(zone).toLocalDate()
    val to = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(from, to).toInt().coerceAtLeast(0)
}

/**
 * 备份超期天数：未备份返回 null（走「尚未备份」的既有提示），
 * 距上次备份 ≥ [threshold] 个日历日时返回天数，否则 null。
 *
 * 纯函数、时区可注入；「我的」页与「备份与还原」页共用这一个阈值，
 * 不引入通知 / 后台任务（只做页面内的静态提醒）。
 */
fun backupOverdueDays(
    lastBackupAt: Long?,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    threshold: Int = BACKUP_OVERDUE_DAYS,
): Int? = backupAgeDays(lastBackupAt, now, zone)?.takeIf { it >= threshold }
