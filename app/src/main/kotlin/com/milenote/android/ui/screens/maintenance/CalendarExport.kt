package com.milenote.android.ui.screens.maintenance

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.milenote.android.domain.calc.Dates
import com.milenote.android.domain.model.Reminder
import com.milenote.android.i18n.tr
import com.milenote.android.platform.FileExporter
import com.milenote.android.platform.LedgerExports

/** 等待用户选择保存位置的一次 ICS 导出。 */
private data class PendingIcs(val fileName: String, val bytes: ByteArray, val count: Int)

/**
 * 单条提醒导出日历（Web「导出日历提醒」，`calendar()`）。
 *
 * 流程：`LedgerExports.ics(reminder)` 生成与 Web 字段完全一致的 ICS →
 * SAF `CreateDocument("text/calendar")` 选保存位置 → [FileExporter.writeTo] 写入；
 * 写入失败时兜底走 [FileExporter.shareText]（分享到日历/文件应用），仍失败则中文 Toast。
 *
 * 仅对未完成且有有效 `dueDate` 的提醒展示入口（由调用方判断），本方法内再兜一层。
 *
 * @return 传入提醒即触发导出的动作。
 */
@Composable
internal fun rememberCalendarExportAction(): (Reminder) -> Unit {
    val context = LocalContext.current
    val save = rememberCalendarSaver()
    return { reminder ->
        val ics = LedgerExports.ics(reminder)
        if (ics == null || !Dates.isValid(reminder.dueDate)) {
            Toast.makeText(
                context,
                tr("该提醒没有有效到期日期，无法导出日历", "This reminder has no valid due date, so it cannot be exported."),
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            save(
                PendingIcs(
                    fileName = LedgerExports.icsFileName(reminder),
                    bytes = ics.toByteArray(Charsets.UTF_8),
                    count = 1,
                ),
            )
        }
    }
}

/**
 * 一次性导出多条提醒（未完成且有到期日期的合成一个 ICS）。
 *
 * 入口在保养计划卡；没有可导出项时给出提示，不生成空文件。
 *
 * @return 传入提醒列表即触发导出的动作。
 */
@Composable
internal fun rememberCalendarBatchExportAction(): (List<Reminder>) -> Unit {
    val context = LocalContext.current
    val save = rememberCalendarSaver()
    return { reminders ->
        val ics = LedgerExports.icsAll(reminders)
        val count = reminders.count { !it.done && Dates.isValid(it.dueDate) }
        if (ics == null || count == 0) {
            Toast.makeText(
                context,
                tr("没有可导出的提醒（需有到期日期且未完成）", "No reminders to export — they need a due date and must be open."),
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            save(
                PendingIcs(
                    fileName = LedgerExports.icsAllFileName(),
                    bytes = ics.toByteArray(Charsets.UTF_8),
                    count = count,
                ),
            )
        }
    }
}

/** 公共保存流程：SAF 保存 → 失败兜底分享 → 提示（单条 / 批量共用）。 */
@Composable
private fun rememberCalendarSaver(): (PendingIcs) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingIcs?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        val target = pending
        pending = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val saved = runCatching { FileExporter.writeTo(context, uri, target.bytes) }.isSuccess
        if (saved) {
            Toast.makeText(
                context,
                if (target.count > 1) {
                    tr("已导出 %d 条提醒，可导入到系统日历", "%d reminders exported. You can import them into your calendar app.", target.count)
                } else {
                    tr("日历文件已保存，可导入到系统日历", "Calendar file saved. You can import it into your calendar app.")
                },
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        val shared = runCatching {
            FileExporter.shareText(context, target.fileName, "text/calendar", target.bytes)
        }.isSuccess
        Toast.makeText(
            context,
            if (shared) {
                tr("已打开分享，可发送到日历或文件应用", "Share sheet opened — send to a calendar or file app.")
            } else {
                tr("日历文件导出失败，请稍后重试", "Calendar export failed. Try again later.")
            },
            Toast.LENGTH_LONG,
        ).show()
    }
    return { pendingIcs ->
        pending = pendingIcs
        launcher.launch(pendingIcs.fileName)
    }
}
