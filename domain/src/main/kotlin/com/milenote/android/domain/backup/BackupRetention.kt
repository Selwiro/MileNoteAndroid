package com.milenote.android.domain.backup

import java.time.LocalDate

/**
 * 固定目录备份（F4）的文件名规则与保留策略。
 *
 * 只认本应用写出的文件：`里程志备份-YYYY-MM-DD.json`。清理时**只删**匹配这条规则的文件，
 * 用户放在同一目录里的其他文件一律不动——这是「一键备份到固定目录」的安全底线。
 */

/** 备份文件名前缀。 */
const val BACKUP_FILE_PREFIX: String = "里程志备份-"

/** 备份文件名后缀。 */
const val BACKUP_FILE_SUFFIX: String = ".json"

/** 默认保留份数。 */
const val BACKUP_FILE_KEEP_DEFAULT: Int = 7

/** 界面上可选的保留份数。 */
val BACKUP_FILE_KEEP_OPTIONS: List<Int> = listOf(3, 7, 14, 30)

/** 某一天的备份文件名，如 `里程志备份-2026-11-15.json`（与「导出完整备份」同名规则）。 */
fun backupFileName(date: LocalDate): String = "$BACKUP_FILE_PREFIX$date$BACKUP_FILE_SUFFIX"

private val BACKUP_FILE_NAME = Regex("""^里程志备份-(\d{4})-(\d{2})-(\d{2})\.json$""")

/** 是否是本应用写出的备份文件名（不含临时文件 / 用户自己的 JSON；日期必须合法）。 */
fun isBackupFileName(name: String): Boolean = backupDate(name) != null

/**
 * 按文件名里的日期保留最近 [keep] 份，返回需要删除的文件名（**从旧到新**，方便逐条删）。
 *
 * - 只处理 [isBackupFileName] 通过的文件，其他文件不返回；
 * - 同一日期重复只算一份（同名文件本来就会被覆盖）；
 * - `keep` 小于 1 时按 1 处理：永远保留最新一份。
 */
fun backupsToDelete(names: List<String>, keep: Int): List<String> {
    val dated = names
        .mapNotNull { name -> backupDate(name)?.let { date -> name to date } }
        .distinctBy { it.first }
        .sortedByDescending { it.second }
    return dated.drop(keep.coerceAtLeast(1)).map { it.first }.reversed()
}

/** 从文件名解析日期；不匹配或日期非法返回 null。 */
fun backupDate(name: String): LocalDate? {
    val match = BACKUP_FILE_NAME.matchEntire(name) ?: return null
    return runCatching {
        LocalDate.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
    }.getOrNull()
}
