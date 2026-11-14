package com.milenote.android.data.webdav

import com.milenote.android.domain.backup.MergeSummary
import com.milenote.android.i18n.tr

/**
 * 同步结果文案（自动同步与手动「合并」共用，避免两处措辞不一致）。
 *
 * 文案里只出现次数与短标签；详细裁决原因见设计稿
 * `work/docs/design/webdav-multi-device-sync-20261114.md` §4.4。
 */
object SyncMessages {

    /** 合并成功：「已自动合并：新增 2、更新 1、删除 1（…）」 */
    fun merged(summary: MergeSummary): String {
        val base = tr(
            "已自动合并：新增 %d、更新 %d、删除 %d",
            "Merged automatically: %d added, %d updated, %d deleted",
            summary.added,
            summary.updated,
            summary.deleted,
        )
        val notes = buildList {
            if (summary.deleteVsEditKept > 0) {
                add(
                    tr(
                        "（%d 条记录一边删除一边修改，已保留修改）",
                        " (%d record(s) deleted on one side and edited on the other — edits kept)",
                        summary.deleteVsEditKept,
                    ),
                )
            }
            if (summary.localWinsWithoutBase > 0) {
                add(
                    tr(
                        "（首次合并，%d 条同一条记录以本机为准）",
                        " (first merge — %d conflicting record(s) kept from this device)",
                        summary.localWinsWithoutBase,
                    ),
                )
            }
            if (summary.remindersChanged > 0) {
                add(
                    tr(
                        "（含 %d 条保养提醒）",
                        " (including %d service reminder(s))",
                        summary.remindersChanged,
                    ),
                )
            }
        }
        return base + notes.joinToString("")
    }

    /** 无法自动裁决：只有「同一条记录两边都改且时间戳相同」才会走到这里。 */
    fun conflict(summary: MergeSummary): String {
        val label = summary.conflicts.firstOrNull()?.label
        return if (label.isNullOrEmpty()) {
            tr(
                "两边都改了同一条记录（%d 条需确认），已暂停自动同步，请到 设置 → WebDAV 同步 处理",
                "The same record was edited on both devices (%d need review) — auto sync paused, resolve it in Settings → WebDAV sync",
                summary.conflicts.size,
            )
        } else {
            tr(
                "「%s」在两边都被改过且时间相同，已暂停自动同步，请到 设置 → WebDAV 同步 处理",
                "\"%s\" was edited on both devices at the same time — auto sync paused, resolve it in Settings → WebDAV sync",
                label,
            )
        }
    }
}
