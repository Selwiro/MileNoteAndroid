package com.milenote.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.WebDavConflictChoice
import com.milenote.android.ui.WebDavUiState
import com.milenote.android.ui.components.FormField
import com.milenote.android.ui.components.MessageBar
import com.milenote.android.ui.components.MessageKind
import com.milenote.android.ui.components.SectionCard
import com.milenote.android.ui.components.StatusKind
import com.milenote.android.ui.components.StatusPill
import com.milenote.android.ui.icons.MileNoteIcons
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapeTokens
import com.milenote.android.ui.theme.MileNoteShapes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 同步时间显示格式（本地时区）：`MM.dd HH:mm`。 */
private val SyncTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.dd HH:mm")

/** 需要用户二次确认的覆盖动作。 */
private enum class WebDavOverwrite { UPLOAD, DOWNLOAD }

/**
 * WebDAV 同步子页（设置 → 数据管理 → WebDAV 同步）。
 *
 * 内容顺序（自上而下）：
 * 1. **连接状态**：已配置/未配置、上次同步时间、上次结果、远端文件存在性/大小；
 *    右上角「清除连接」（二次确认）。
 * 2. **连接**：服务器文件完整地址、用户名、应用密码（已保存时留空不修改）、允许 HTTP 开关；
 *    「保存并测试」/「清除连接」。
 * 3. **同步**（仅已配置时显示）：立即同步（智能比较）+ 上传覆盖 / 下载覆盖（均二次确认）；
 *    busy 时进度条 + 禁用按钮；错误/结果用 [MessageBar] 展示。
 * 4. **说明**：HTTPS、坚果云应用密码、远端目录需先创建、账本 JSON 明文存储等。
 *
 * 冲突时由 VM 设置 `conflict`，本页弹 AlertDialog 提供「用本地覆盖远端 / 用远端覆盖本地 /
 * 合并（只新增远端记录）」；「取消」只关闭弹层、保持现状（不调用 resolve）。
 *
 * 本页不直接做网络请求，所有动作都转给 [MileNoteViewModel][com.milenote.android.ui.MileNoteViewModel]；
 * 文件地址、账号、密码仅用于当前会话与 VM 持久化，UI 不打印/记录密码。
 *
 * @param state VM 暴露的 WebDAV 状态。
 * @param onSaveConfig 保存连接配置；VM 成功后会自动做一次连接测试并更新状态。
 * @param onClearConfig 清除本机保存的连接信息（不影响远端文件）。
 * @param onSyncNow 智能同步（比较后上传或下载）。
 * @param onUpload 强制上传覆盖远端。
 * @param onDownloadReplace 强制下载覆盖本地。
 * @param onResolveConflict 解决冲突（保留本地 / 保留远端 / 合并）。
 * @param onDismissConflict 关闭冲突弹层（保持现状，不做合并/覆盖）。
 * @param onDismissMessage 关闭错误/结果提示。
 */
@Composable
internal fun WebDavPage(
    state: WebDavUiState,
    onSaveConfig: (url: String, username: String, password: String, allowHttp: Boolean) -> Unit,
    onClearConfig: () -> Unit,
    onSyncNow: () -> Unit,
    onUpload: () -> Unit,
    onDownloadReplace: () -> Unit,
    onResolveConflict: (WebDavConflictChoice) -> Unit,
    onDismissConflict: () -> Unit,
    onDismissMessage: () -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    // 表单本地状态：以 VM 已保存值为初始；用户编辑只改本地，保存后才回写 VM。
    var url by remember(state.fileUrl) { mutableStateOf(state.fileUrl) }
    var username by remember(state.username) { mutableStateOf(state.username) }
    var allowHttp by remember(state.allowHttp) { mutableStateOf(state.allowHttp) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }

    var showClearConfirm by remember { mutableStateOf(false) }
    var overwriteAction by remember { mutableStateOf<WebDavOverwrite?>(null) }

    fun submitConfig(): Boolean {
        val trimmed = url.trim()
        val error = when {
            trimmed.isEmpty() -> "请填写服务器文件完整地址"
            !(trimmed.startsWith("https://") || trimmed.startsWith("http://")) ->
                "地址需以 https:// 或 http:// 开头"
            trimmed.startsWith("http://") && !allowHttp ->
                "HTTP 连接需先开启下方「允许 HTTP（不安全）」"
            else -> null
        }
        urlError = error
        if (error != null) return false
        onSaveConfig(trimmed, username.trim(), password, allowHttp)
        password = ""
        return true
    }

    Column(verticalArrangement = Arrangement.spacedBy(MileNoteDimens.SectionGap)) {
        // ---------------- 状态 ----------------
        SectionCard(
            title = "连接状态",
            subtitle = "支持前台自动同步；退出应用后不后台运行",
            action = if (state.configured) {
                {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = !state.busy,
                    ) {
                        Text("清除连接", color = scheme.error, style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                null
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    text = if (state.configured) "已配置" else "未配置",
                    status = if (state.configured) StatusKind.DONE else StatusKind.NORMAL,
                )
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "处理中…",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.hasPassword) {
                    Text(
                        text = "密码已保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            WebDavInfoRow("上次同步", formatSyncTime(state.lastSyncAt))
            WebDavInfoRow("上次结果", state.lastResult ?: "—")
            WebDavInfoRow("远端文件", remoteSummary(state))
            if (state.configured && !state.fileUrl.isBlank()) {
                WebDavInfoRow("地址", state.fileUrl, maxLines = 2)
            }
        }

        // 错误提示常驻状态卡下方：未配置时「保存并测试」失败也能看到。
        state.error?.let { error ->
            MessageBar(
                message = error,
                kind = MessageKind.ERROR,
                onDismiss = onDismissMessage,
            )
        }

        // ---------------- 连接表单 ----------------
        SectionCard(
            title = "连接",
            subtitle = "WebDAV 上的账本文件完整地址（JSON）",
        ) {
            FormField(
                value = url,
                onValueChange = {
                    url = it
                    urlError = null
                },
                label = "服务器文件地址",
                placeholder = "https://dav.jianguoyun.com/dav/里程志/账本.json",
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError,
            )
            Spacer(modifier = Modifier.size(12.dp))
            FormField(
                value = username,
                onValueChange = { username = it },
                label = "用户名",
                placeholder = "WebDAV 登录账号",
                singleLine = true,
            )
            Spacer(modifier = Modifier.size(12.dp))
            FormField(
                value = password,
                onValueChange = { password = it },
                label = "应用密码",
                placeholder = if (state.hasPassword) "已保存（留空则不修改）" else "请输入应用密码",
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailing = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        modifier = Modifier.size(MileNoteDimens.TouchTarget),
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                MileNoteIcons.Visibility
                            } else {
                                MileNoteIcons.Lock
                            },
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            modifier = Modifier.size(20.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                },
            )
            Text(
                text = "建议使用服务商提供的应用密码：坚果云「账户安全 → 应用密钥 → 添加应用」生成，" +
                    "不要填写登录密码。密码仅保存在本机，不进入账本备份。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.size(8.dp))
            HorizontalDivider(color = scheme.outlineVariant)
            Spacer(modifier = Modifier.size(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("允许 HTTP（不安全）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "仅建议自建局域网服务器；公网请使用 HTTPS，否则账本与密码可能被窃听。",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = allowHttp,
                    onCheckedChange = {
                        allowHttp = it
                        urlError = null
                    },
                    enabled = !state.busy,
                )
            }

            Spacer(modifier = Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    // VM 内部：校验/持久化 → 自动 testConnection → 更新 busy/error/lastResult。
                    onClick = { submitConfig() },
                    enabled = !state.busy,
                    shape = MileNoteShapeTokens.full,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MileNoteDimens.ButtonMinHeight),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = scheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("保存并测试", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    enabled = state.configured && !state.busy,
                    shape = MileNoteShapeTokens.full,
                    modifier = Modifier.heightIn(min = MileNoteDimens.ButtonMinHeight),
                ) {
                    Text("清除连接", maxLines = 1)
                }
            }
        }

        // ---------------- 同步操作 ----------------
        if (state.configured) {
            SectionCard(
                title = "同步",
                subtitle = "智能比较本地与远端，决定上传或下载",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动同步", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "仅在应用打开期间运行：打开应用或本地修改后同步；退出应用后不再后台执行。冲突时暂停自动处理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.autoSyncEnabled,
                        onCheckedChange = onAutoSyncChange,
                        enabled = state.configured && !state.busy,
                    )
                }
                if (state.autoSyncEnabled) {
                    Spacer(modifier = Modifier.size(8.dp))
                    WebDavInfoRow("上次自动同步", formatSyncTime(state.lastAutoSyncAt))
                    WebDavInfoRow("自动同步结果", state.lastAutoSyncResult ?: "—")
                }
                Spacer(modifier = Modifier.size(8.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(modifier = Modifier.size(8.dp))
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Button(
                    onClick = onSyncNow,
                    enabled = !state.busy,
                    shape = MileNoteShapeTokens.full,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MileNoteDimens.ButtonMinHeight),
                ) {
                    Text("立即同步")
                }
                Text(
                    text = "说明：先比较本地账本与远端文件；只有本端有修改时上传，只有远端有修改时下载；" +
                        "两边都有修改时弹出冲突选项，由你决定保留哪一边。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { overwriteAction = WebDavOverwrite.UPLOAD },
                        enabled = !state.busy,
                        shape = MileNoteShapeTokens.full,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MileNoteDimens.ButtonMinHeight),
                    ) {
                        Text("上传到远端（覆盖）")
                    }
                    OutlinedButton(
                        onClick = { overwriteAction = WebDavOverwrite.DOWNLOAD },
                        enabled = !state.busy,
                        shape = MileNoteShapeTokens.full,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MileNoteDimens.ButtonMinHeight),
                    ) {
                        Text("从远端下载（覆盖本地）")
                    }
                }
                Text(
                    text = "这两个按钮不做比较，直接用本端覆盖对端；点击后会再次确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )

                if (state.error == null && state.lastResult != null) {
                    Spacer(modifier = Modifier.size(8.dp))
                    MessageBar(
                        message = state.lastResult.orEmpty(),
                        kind = MessageKind.INFO,
                        onDismiss = onDismissMessage,
                    )
                }
            }
        }

        // ---------------- 说明 ----------------
        SectionCard(title = "说明") {
            WebDavNote("推荐使用 HTTPS。开启「允许 HTTP」仅建议自建局域网服务器，公网切勿使用明文 HTTP。")
            WebDavNote("坚果云：账户安全 → 添加应用 → 生成应用密码，用应用密码而不是登录密码。")
            WebDavNote("目标文件夹需先在 WebDAV 服务中创建；应用只读写你填写的那个 JSON 文件。")
            WebDavNote("账本以 JSON 明文保存在远端，传输由 HTTPS 保护；建议放在私有目录，不要把直链分享给他人。")
            WebDavNote("自动同步开启后：只在应用打开期间，打开时或本地修改后自动同步；退出应用后不会保留后台任务。两边都有修改时会暂停并提示手动处理。")
        }
    }

    // ---------------- 清除连接确认 ----------------
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = MileNoteShapes.extraLarge,
            title = { Text("清除 WebDAV 连接？") },
            text = {
                Text(
                    text = "将清除本机保存的服务器地址、用户名与应用密码；远端文件不会被删除。" +
                        "已同步的本地账本保持不变。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearConfig()
                    },
                    shape = MileNoteShapeTokens.full,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = scheme.error,
                        contentColor = scheme.onError,
                    ),
                ) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    // ---------------- 覆盖确认 ----------------
    overwriteAction?.let { action ->
        val upload = action == WebDavOverwrite.UPLOAD
        AlertDialog(
            onDismissRequest = { overwriteAction = null },
            shape = MileNoteShapes.extraLarge,
            title = { Text(if (upload) "上传并覆盖远端？" else "下载并覆盖本地？") },
            text = {
                Text(
                    text = if (upload) {
                        "将用本机当前账本覆盖远端 JSON 文件；远端原有内容会被替换，无法自动恢复。"
                    } else {
                        "将用远端 JSON 文件覆盖本机账本；本机现有数据会被替换，建议先导出完整备份。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        overwriteAction = null
                        if (upload) onUpload() else onDownloadReplace()
                    },
                    shape = MileNoteShapeTokens.full,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = scheme.error,
                        contentColor = scheme.onError,
                    ),
                ) {
                    Text(if (upload) "上传并覆盖" else "下载并覆盖")
                }
            },
            dismissButton = {
                TextButton(onClick = { overwriteAction = null }) { Text("取消") }
            },
        )
    }

    // ---------------- 冲突弹层 ----------------
    val conflict = state.conflict
    if (conflict != null) {
        AlertDialog(
            onDismissRequest = onDismissConflict,
            shape = MileNoteShapes.extraLarge,
            title = { Text("本地与远端都有修改") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "本地账本与远端文件在上次同步后都发生了变化，请选择保留方式：" +
                            "「合并」只把远端新增记录并入本地，不删除本地数据。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    ConflictOption("用本地覆盖远端", "上传本机账本，远端内容被替换") {
                        onResolveConflict(WebDavConflictChoice.KEEP_LOCAL)
                    }
                    ConflictOption("用远端覆盖本地", "下载远端账本，本机内容被替换") {
                        onResolveConflict(WebDavConflictChoice.KEEP_REMOTE)
                    }
                    ConflictOption("合并（只新增远端记录）", "保留本地并追加远端新增记录") {
                        onResolveConflict(WebDavConflictChoice.MERGE)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissConflict) {
                    Text("取消（保持现状）")
                }
            },
        )
    }
}

/** WebDAV 子页的信息行（标签 88dp + 值）。 */
@Composable
private fun WebDavInfoRow(label: String, value: String, maxLines: Int = 1) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 说明条目（圆点 + 文案）。 */
@Composable
private fun WebDavNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 冲突弹层里的一个操作项（标题 + 说明）。 */
@Composable
private fun ConflictOption(title: String, detail: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = MileNoteShapeTokens.full,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MileNoteDimens.ButtonMinHeight),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 工具：状态摘要 / 时间 / 大小
// ---------------------------------------------------------------------------------------------

/**
 * 数据管理页「WebDAV 同步」行使用的状态摘要。
 *
 * 无「本地有未同步修改」标记（VM 未暴露 dirty 状态），因此用「未配置 / 上次同步时间 / 已配置但尚未同步」表达。
 */
internal fun webDavStatusSummary(state: WebDavUiState): String = when {
    !state.configured -> "未配置 · 点按填写服务器与账号"
    state.busy -> "处理中…"
    state.lastSyncAt != null -> "上次同步：${formatSyncTime(state.lastSyncAt)}"
    else -> "已配置 · 尚未同步"
}

/** 上次同步时间：本地时区 `MM.dd HH:mm`；为空显示「尚未同步」。 */
private fun formatSyncTime(millis: Long?): String {
    if (millis == null) return "尚未同步"
    return runCatching {
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(SyncTimeFormatter)
    }.getOrDefault("—")
}

/** 远端文件状态：存在（大小）/ 不存在 / 未知。 */
private fun remoteSummary(state: WebDavUiState): String = when (state.remoteExists) {
    null -> "未知（保存并测试后显示）"
    false -> "不存在（首次同步将创建）"
    true -> state.remoteSize?.let { "存在 · ${formatFileSize(it)}" } ?: "存在"
}

/** 文件大小格式化：B / KB / MB。 */
private fun formatFileSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
