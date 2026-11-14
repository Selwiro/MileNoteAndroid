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
import com.milenote.android.BuildConfig
import com.milenote.android.data.webdav.WebDavProblemKind
import com.milenote.android.i18n.tr
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
 * WebDAV 同步子页（设置 → WebDAV 同步）。
 *
 * 内容顺序（自上而下）：
 * 1. **连接状态**：已配置/未配置、上次同步时间、上次结果、远端文件存在性/大小、
 *    上传格式（加密/明文 JSON）与远端格式（加密/明文/未知）；右上角「清除连接」（二次确认）。
 * 2. **连接**：服务器文件完整地址、用户名、应用密码（已保存时留空不修改）、
 *    加密上传开关（可选，独立加密密码 + 确认密码，字段下显示行内校验错误）、允许 HTTP 开关；
 *    「保存并测试」/「清除连接」。
 * 3. **同步**（仅已配置时显示）：立即同步（智能比较）+ 上传覆盖 / 下载覆盖（均二次确认）；
 *    busy 时进度条 + 禁用按钮；错误/结果用 [MessageBar] 展示。
 * 4. **说明**：HTTPS、坚果云应用密码、远端目录需先创建、明文/加密格式与 Web 版互通等。
 *
 * 冲突时由 VM 设置 `conflict`，本页弹 AlertDialog 提供「用本地覆盖远端 / 用远端覆盖本地 /
 * 合并（只新增远端记录）」；「取消」只关闭弹层、保持现状（不调用 resolve）。
 * 远端已加密但本机没有密码时 VM 设置 `decryptPrompt`，本页弹密码输入层：确认后交由 VM 先本地
 * 验证再落库并重跑刚才的动作，错误（密码不对等）显示在弹层内。
 *
 * 本页不直接做网络请求，所有动作都转给 [MileNoteViewModel][com.milenote.android.ui.MileNoteViewModel]；
 * 文件地址、账号、密码仅用于当前会话与 VM 持久化，UI 不打印/记录密码。
 *
 * @param state VM 暴露的 WebDAV 状态。
 * @param onSaveConfig 保存连接配置（含可选的加密密码）；VM 成功后会自动做一次连接测试并更新状态。
 * @param onClearConfig 清除本机保存的连接信息（不影响远端文件）。
 * @param onSyncNow 智能同步（比较后上传或下载）。
 * @param onUpload 强制上传覆盖远端。
 * @param onDownloadReplace 强制下载覆盖本地。
 * @param onResolveConflict 解决冲突（保留本地 / 保留远端 / 合并）。
 * @param onDismissConflict 关闭冲突弹层（保持现状，不做合并/覆盖）。
 * @param onDismissMessage 关闭错误/结果提示。
 * @param onAutoSyncChange 切换自动同步。
 * @param onSubmitDecryptPassword 提交「远端账本已加密」弹层里的解密密码。
 * @param onCancelDecrypt 关闭解密密码弹层（保持现状）。
 */
@Composable
internal fun WebDavPage(
    state: WebDavUiState,
    onSaveConfig: (
        url: String,
        username: String,
        password: String,
        allowHttp: Boolean,
        encryptionEnabled: Boolean,
        encryptionPassword: String?,
    ) -> Unit,
    onClearConfig: () -> Unit,
    onSyncNow: () -> Unit,
    onUpload: () -> Unit,
    onDownloadReplace: () -> Unit,
    onResolveConflict: (WebDavConflictChoice) -> Unit,
    onDismissConflict: () -> Unit,
    onDismissMessage: () -> Unit,
    onAutoSyncChange: (Boolean) -> Unit,
    onSubmitDecryptPassword: (String) -> Unit,
    onCancelDecrypt: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    // 表单本地状态：以 VM 已保存值为初始；用户编辑只改本地，保存后才回写 VM。
    var url by remember(state.fileUrl) { mutableStateOf(state.fileUrl) }
    var username by remember(state.username) { mutableStateOf(state.username) }
    var allowHttp by remember(state.allowHttp) { mutableStateOf(state.allowHttp) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }

    // 加密上传：开关默认跟着已保存状态；已保存密码时两栏留空 = 不修改。
    var encryptionEnabled by remember(state.hasEncryptionPassword) { mutableStateOf(state.hasEncryptionPassword) }
    var encryptionPassword by remember { mutableStateOf("") }
    var confirmEncryptionPassword by remember { mutableStateOf("") }
    var encryptionPasswordVisible by remember { mutableStateOf(false) }
    var confirmEncryptionPasswordVisible by remember { mutableStateOf(false) }
    var encryptionPasswordError by remember { mutableStateOf<String?>(null) }
    var confirmEncryptionPasswordError by remember { mutableStateOf<String?>(null) }

    // 解密弹层里的输入：只在待办动作变化时重置，密码错误（error 变化）时保留用户已输入内容。
    var decryptPassword by remember(state.decryptPrompt?.pending) { mutableStateOf("") }
    var decryptPasswordVisible by remember { mutableStateOf(false) }

    var showClearConfirm by remember { mutableStateOf(false) }
    var overwriteAction by remember { mutableStateOf<WebDavOverwrite?>(null) }

    fun submitConfig(): Boolean {
        val trimmed = url.trim()
        val error = when {
            trimmed.isEmpty() -> tr("请填写服务器文件完整地址", "Enter the full server file URL")
            !(trimmed.startsWith("https://") || trimmed.startsWith("http://")) ->
                tr("地址需以 https:// 或 http:// 开头", "The address must start with https:// or http://")
            trimmed.startsWith("http://") && !BuildConfig.DEBUG ->
                tr("当前版本仅支持 HTTPS 连接（明文 HTTP 仅调试版可用）", "This build only supports HTTPS connections (plain HTTP is available in debug builds only)")
            trimmed.startsWith("http://") && !allowHttp ->
                tr("HTTP 连接需先开启下方「允许 HTTP（不安全）」", "Turn on “Allow HTTP (insecure)” below to use an HTTP connection")
            else -> null
        }
        urlError = error

        // 加密密码校验（开启加密时）：新密码长度 / 两次一致 / 开启但无可用密码。
        var encryptionError: String? = null
        var confirmError: String? = null
        if (encryptionEnabled) {
            when {
                encryptionPassword.isNotBlank() && encryptionPassword.length < 8 ->
                    encryptionError = tr("加密密码至少需要 8 个字符", "The encryption password must be at least 8 characters")
                encryptionPassword.isBlank() && !state.hasEncryptionPassword ->
                    encryptionError = tr("请设置加密密码", "Set an encryption password")
                else -> Unit
            }
            if (encryptionPassword.isNotBlank() && confirmEncryptionPassword != encryptionPassword) {
                confirmError = tr("两次输入的密码不一致", "The passwords do not match")
            }
        }
        encryptionPasswordError = encryptionError
        confirmEncryptionPasswordError = confirmError

        if (error != null || encryptionError != null || confirmError != null) return false
        onSaveConfig(
            trimmed,
            username.trim(),
            password,
            allowHttp,
            encryptionEnabled,
            encryptionPassword.takeIf { it.isNotBlank() },
        )
        password = ""
        encryptionPassword = ""
        confirmEncryptionPassword = ""
        return true
    }

    Column(verticalArrangement = Arrangement.spacedBy(MileNoteDimens.SectionGap)) {
        // ---------------- 状态 ----------------
        SectionCard(
            title = tr("连接状态", "Connection status"),
            subtitle = tr("支持前台自动同步；退出应用后不后台运行", "Auto sync runs in the foreground only; nothing runs after you leave the app"),
            action = if (state.configured) {
                {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = !state.busy,
                    ) {
                        Text(tr("清除连接", "Remove connection"), color = scheme.error, style = MaterialTheme.typography.labelLarge)
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
                    text = if (state.configured) tr("已配置", "Configured") else tr("未配置", "Not configured"),
                    status = if (state.configured) StatusKind.DONE else StatusKind.NORMAL,
                )
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = tr("处理中…", "Working…"),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (state.hasPassword) {
                    Text(
                        text = tr("密码已保存", "Password saved"),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            WebDavInfoRow(tr("上次同步", "Last sync"), formatSyncTime(state.lastSyncAt))
            WebDavInfoRow(tr("上次结果", "Last result"), state.lastResult ?: "—")
            WebDavInfoRow(tr("远端文件", "Remote file"), remoteSummary(state))
            WebDavInfoRow(
                tr("上传格式", "Upload format"),
                if (state.hasEncryptionPassword) {
                    tr("加密（AES-256-GCM）", "Encrypted (AES-256-GCM)")
                } else {
                    tr("明文 JSON", "Plain JSON")
                },
            )
            WebDavInfoRow(
                tr("远端格式", "Remote format"),
                when (state.remoteEncrypted) {
                    null -> tr("未知", "Unknown")
                    true -> tr("加密", "Encrypted")
                    false -> tr("明文", "Plain")
                },
            )
            if (state.configured && !state.fileUrl.isBlank()) {
                WebDavInfoRow(tr("地址", "Address"), state.fileUrl, maxLines = 2)
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

        // 上次同步异常（跨会话保留）：本会话没有新错误时也把原因显示出来，
        // 否则从「我的」看到「同步失败」进来却找不到解释（重启后内存里的 error 已清空）。
        if (state.error == null && state.problem != null) {
            val problem = state.problem
            MessageBar(
                message = if (problem.at > 0L) {
                    tr("%s（%s）", "%s (%s)", problem.message, formatSyncTime(problem.at))
                } else {
                    problem.message
                },
                kind = if (problem.kind == WebDavProblemKind.ERROR) MessageKind.ERROR else MessageKind.WARNING,
            )
        }

        // ---------------- 连接表单 ----------------
        SectionCard(
            title = tr("连接", "Connection"),
            subtitle = tr("WebDAV 上的账本文件完整地址（JSON）", "Full URL of the ledger file on WebDAV (JSON)"),
        ) {
            FormField(
                value = url,
                onValueChange = {
                    url = it
                    urlError = null
                },
                label = tr("服务器文件地址", "Server URL"),
                placeholder = tr("https://dav.jianguoyun.com/dav/里程志/账本.json", "https://dav.jianguoyun.com/dav/MileNote/ledger.json"),
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError,
            )
            Spacer(modifier = Modifier.size(12.dp))
            FormField(
                value = username,
                onValueChange = { username = it },
                label = tr("用户名", "Username"),
                placeholder = tr("WebDAV 登录账号", "WebDAV account"),
                singleLine = true,
            )
            Spacer(modifier = Modifier.size(12.dp))
            FormField(
                value = password,
                onValueChange = { password = it },
                label = tr("应用密码", "App password"),
                placeholder = if (state.hasPassword) tr("已保存（留空则不修改）", "Saved (leave empty to keep)") else tr("请输入应用密码", "Enter app password"),
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
                            contentDescription = if (passwordVisible) tr("隐藏密码", "Hide password") else tr("显示密码", "Show password"),
                            modifier = Modifier.size(20.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                },
            )
            Text(
                text = tr(
                    "建议使用服务商提供的应用密码：坚果云「账户安全 → 应用密钥 → 添加应用」生成，" +
                        "不要填写登录密码。密码仅保存在本机，不进入账本备份。",
                    "Use the app password from your provider (Jianguoyun: Account security → App keys → Add app). " +
                        "Do not enter your login password. The password stays on this device and is not included in ledger backups.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            // 账本加密（可选，默认关闭）：密码与 WebDAV 应用密码相互独立。
            Spacer(modifier = Modifier.size(8.dp))
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
            Spacer(modifier = Modifier.size(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("加密上传（推荐）", "Encrypt uploads (recommended)"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = tr(
                            "远端账本用 AES-256-GCM 加密；在另一台设备输入同一密码即可解密。",
                            "The remote ledger is encrypted with AES-256-GCM; enter the same password on another device to decrypt it.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = encryptionEnabled,
                    onCheckedChange = {
                        encryptionEnabled = it
                        encryptionPasswordError = null
                        confirmEncryptionPasswordError = null
                    },
                    enabled = !state.busy,
                )
            }
            if (encryptionEnabled) {
                Spacer(modifier = Modifier.size(12.dp))
                FormField(
                    value = encryptionPassword,
                    onValueChange = {
                        encryptionPassword = it
                        encryptionPasswordError = null
                        confirmEncryptionPasswordError = null
                    },
                    label = tr("加密密码", "Encryption password"),
                    placeholder = if (state.hasEncryptionPassword) {
                        tr("已保存（留空则不修改）", "Saved (leave empty to keep)")
                    } else {
                        tr("至少 8 个字符", "At least 8 characters")
                    },
                    singleLine = true,
                    isError = encryptionPasswordError != null,
                    supportingText = encryptionPasswordError,
                    visualTransformation = if (encryptionPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        IconButton(
                            onClick = { encryptionPasswordVisible = !encryptionPasswordVisible },
                            modifier = Modifier.size(MileNoteDimens.TouchTarget),
                        ) {
                            Icon(
                                imageVector = if (encryptionPasswordVisible) {
                                    MileNoteIcons.Visibility
                                } else {
                                    MileNoteIcons.Lock
                                },
                                contentDescription = if (encryptionPasswordVisible) tr("隐藏密码", "Hide password") else tr("显示密码", "Show password"),
                                modifier = Modifier.size(20.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                Spacer(modifier = Modifier.size(12.dp))
                FormField(
                    value = confirmEncryptionPassword,
                    onValueChange = {
                        confirmEncryptionPassword = it
                        confirmEncryptionPasswordError = null
                    },
                    label = tr("确认加密密码", "Confirm encryption password"),
                    placeholder = tr("再次输入加密密码", "Re-enter the encryption password"),
                    singleLine = true,
                    isError = confirmEncryptionPasswordError != null,
                    supportingText = confirmEncryptionPasswordError,
                    visualTransformation = if (confirmEncryptionPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        IconButton(
                            onClick = { confirmEncryptionPasswordVisible = !confirmEncryptionPasswordVisible },
                            modifier = Modifier.size(MileNoteDimens.TouchTarget),
                        ) {
                            Icon(
                                imageVector = if (confirmEncryptionPasswordVisible) {
                                    MileNoteIcons.Visibility
                                } else {
                                    MileNoteIcons.Lock
                                },
                                contentDescription = if (confirmEncryptionPasswordVisible) tr("隐藏密码", "Hide password") else tr("显示密码", "Show password"),
                                modifier = Modifier.size(20.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = tr(
                        "加密密码只保存在本机（Android Keystore 保护）；忘记后无法解密远端账本，请自行记牢。" +
                            "开启后下一次上传会把远端文件改写为加密格式；关闭不会自动改写远端，直到下次上传。",
                        "The encryption password is stored only on this device (protected by Android Keystore); if you forget it, the remote ledger cannot be decrypted, so keep it safe. " +
                            "Once enabled, the next upload rewrites the remote file in encrypted form; disabling does not rewrite the remote until the next upload.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }

            // 明文 HTTP 只在调试构建放行（release 的 network-security-config 禁止明文）。
            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.size(8.dp))
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
                Spacer(modifier = Modifier.size(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("允许 HTTP（不安全）", "Allow HTTP (insecure)"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = tr(
                                "仅建议自建局域网服务器；公网请使用 HTTPS，否则账本与密码可能被窃听。",
                                "Only recommended for a self-hosted LAN server; use HTTPS on the public internet or the ledger and password may be intercepted.",
                            ),
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
                    Text(tr("保存并测试", "Save & test"), maxLines = 1)
                }
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    enabled = state.configured && !state.busy,
                    shape = MileNoteShapeTokens.full,
                    modifier = Modifier.heightIn(min = MileNoteDimens.ButtonMinHeight),
                ) {
                    Text(tr("清除连接", "Remove connection"), maxLines = 1)
                }
            }
        }

        // ---------------- 同步操作 ----------------
        if (state.configured) {
            SectionCard(
                title = tr("同步", "Sync"),
                subtitle = tr("智能比较本地与远端，决定上传或下载", "Compares local and remote, then uploads or downloads"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("自动同步", "Auto sync"), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = tr(
                                "仅在应用打开期间运行：打开应用或本地修改后同步；退出应用后不再后台执行。两边都改过时会按记录自动合并，只有同一条记录在同一时刻被改才需要你确认。",
                                "Runs only while the app is open: on launch or after local changes; nothing runs in the background after you leave. Edits from both sides are merged record by record; you are only asked when the same record was edited at the same moment.",
                            ),
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
                    WebDavInfoRow(tr("上次自动同步", "Last auto sync"), formatSyncTime(state.lastAutoSyncAt))
                    WebDavInfoRow(tr("自动同步结果", "Auto sync result"), state.lastAutoSyncResult ?: "—")
                }
                Spacer(modifier = Modifier.size(8.dp))
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
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
                    Text(tr("立即同步", "Sync now"))
                }
                Text(
                    text = tr(
                        "说明：先比较本地账本与远端文件；只有本端有修改时上传，只有远端有修改时下载；" +
                            "两边都有修改时按记录自动合并（每条记录以后改的时间为准），只有同一条记录在同一时刻被改才需要你确认。",
                        "How it works: local and remote are compared first; only this device changed → upload, " +
                            "only the remote changed → download, both changed → merge record by record (later edit wins). " +
                            "You are only asked when the same record was edited at the same moment.",
                    ),
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
                        Text(tr("上传到远端（覆盖）", "Upload to remote (overwrite)"))
                    }
                    OutlinedButton(
                        onClick = { overwriteAction = WebDavOverwrite.DOWNLOAD },
                        enabled = !state.busy,
                        shape = MileNoteShapeTokens.full,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MileNoteDimens.ButtonMinHeight),
                    ) {
                        Text(tr("从远端下载（覆盖本地）", "Download from remote (overwrite local)"))
                    }
                }
                Text(
                    text = tr(
                        "这两个按钮不做比较，直接用本端覆盖对端；点击后会再次确认。",
                        "These two skip the comparison and overwrite the other side directly; you will be asked to confirm.",
                    ),
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
        SectionCard(title = tr("说明", "Notes")) {
            WebDavNote(tr("推荐使用 HTTPS。开启「允许 HTTP」仅建议自建局域网服务器，公网切勿使用明文 HTTP。", "HTTPS is recommended. “Allow HTTP” is only for a self-hosted LAN server; never use plain HTTP on the public internet."))
            WebDavNote(tr("坚果云：账户安全 → 添加应用 → 生成应用密码，用应用密码而不是登录密码。", "Jianguoyun: Account security → Add app → generate an app password; use the app password, not your login password."))
            WebDavNote(tr("目标文件夹需先在 WebDAV 服务中创建；应用只读写你填写的那个 JSON 文件。", "Create the target folder in your WebDAV service first; the app only reads and writes the JSON file you entered."))
            WebDavNote(tr("未开启加密时账本以明文 JSON 保存在远端，传输由 HTTPS 保护；建议开启加密上传。", "Without encryption the ledger is stored remotely as plain JSON, protected in transit by HTTPS; enabling encrypted uploads is recommended."))
            WebDavNote(tr("与 Web 版互通：本应用加密上传的文件与 Web 版「加密同步」格式相同，可直接互读；Web 的明文同步文件也能读取。", "Interoperable with the web version: files uploaded with encryption use the same format as the web version's encrypted sync and can be read by both; the web version's plain-sync files can also be read."))
            WebDavNote(tr("加密格式：AES-256-GCM（PBKDF2-SHA256 派生密钥，600000 次迭代）；加密密码只保存在本机 Android Keystore，不写入账本或备份。", "Encryption format: AES-256-GCM (PBKDF2-SHA256 key derivation, 600,000 iterations); the encryption password is stored only in the Android Keystore on this device and is never written into the ledger or backups."))
            WebDavNote(tr("自动同步开启后：只在应用打开期间，打开时或本地修改后自动同步；退出应用后不会保留后台任务。两边都有修改时会暂停并提示手动处理。", "With auto sync on: syncs only while the app is open, on launch or after local changes; no background task survives after you leave. It pauses and asks you to resolve when both sides changed."))
        }
    }

    // ---------------- 清除连接确认 ----------------
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            shape = MileNoteShapes.extraLarge,
            title = { Text(tr("清除 WebDAV 连接？", "Remove WebDAV connection?")) },
            text = {
                Text(
                    text = tr(
                        "将清除本机保存的服务器地址、用户名、应用密码与加密密码；远端文件不会被删除。" +
                            "已同步的本地账本保持不变。远端加密文件仍需用原密码解密。",
                        "This removes the server URL, username, app password and encryption password stored on this device. The remote file is not deleted, " +
                            "and the local ledger stays as it is. An encrypted remote file still requires the original password to decrypt.",
                    ),
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
                    Text(tr("清除", "Remove"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(tr("取消", "Cancel")) }
            },
        )
    }

    // ---------------- 覆盖确认 ----------------
    overwriteAction?.let { action ->
        val upload = action == WebDavOverwrite.UPLOAD
        AlertDialog(
            onDismissRequest = { overwriteAction = null },
            shape = MileNoteShapes.extraLarge,
            title = { Text(if (upload) tr("上传并覆盖远端？", "Upload and overwrite the remote?") else tr("下载并覆盖本地？", "Download and overwrite this device?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (upload) {
                            tr("将用本机当前账本覆盖远端 JSON 文件；远端原有内容会被替换，无法自动恢复。", "The remote JSON file will be replaced with the ledger on this device; the previous remote content cannot be restored automatically.")
                        } else {
                            tr("将用远端 JSON 文件覆盖本机账本；本机现有数据会被替换，建议先导出完整备份。", "The ledger on this device will be replaced with the remote JSON file; export a full backup first.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    // 防降级提示：远端是加密文件、本机却没有密码时，强制上传会把远端改写成明文。
                    if (upload && state.remoteEncrypted == true && !state.hasEncryptionPassword) {
                        Text(
                            text = tr(
                                "注意：远端当前是加密文件，本机没有保存加密密码。本次上传会把它改写为明文 JSON；" +
                                    "如需继续加密，请先在「连接」中开启加密上传并设置密码。",
                                "Note: the remote file is currently encrypted and this device has no encryption password. " +
                                    "This upload will rewrite it as plain JSON; to keep it encrypted, enable encrypted upload and set a password under Connection first.",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.error,
                        )
                    }
                }
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
                    Text(if (upload) tr("上传并覆盖", "Upload and overwrite") else tr("下载并覆盖", "Download and overwrite"))
                }
            },
            dismissButton = {
                TextButton(onClick = { overwriteAction = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }

    // ---------------- 冲突弹层 ----------------
    val conflict = state.conflict
    if (conflict != null) {
        AlertDialog(
            onDismissRequest = onDismissConflict,
            shape = MileNoteShapes.extraLarge,
            title = { Text(tr("本地与远端都有修改", "Local and remote both changed")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = tr(
                            "通常两边都改过时也会自动合并；这里出现说明同一条记录在两边都被改且时间相同，需要你确认。",
                            "Edits on both sides are normally merged automatically. This prompt means the same record was edited on both devices at the same time — please confirm.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    conflict.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.error,
                        )
                    }
                    ConflictOption(tr("合并（按记录自动合并）", "Merge (record by record)"), tr("按每条记录的修改时间合并，两边的新增与修改都保留", "Merge each record by its last edit time; additions and edits from both sides are kept")) {
                        onResolveConflict(WebDavConflictChoice.MERGE)
                    }
                    ConflictOption(tr("用本地覆盖远端", "Overwrite remote with local"), tr("上传本机账本，远端内容被替换", "Upload this device's ledger; remote content is replaced")) {
                        onResolveConflict(WebDavConflictChoice.KEEP_LOCAL)
                    }
                    ConflictOption(tr("用远端覆盖本地", "Overwrite local with remote"), tr("下载远端账本，本机内容被替换", "Download the remote ledger; local content is replaced")) {
                        onResolveConflict(WebDavConflictChoice.KEEP_REMOTE)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissConflict) {
                    Text(tr("取消（保持现状）", "Cancel (keep as is)"))
                }
            },
        )
    }

    // ---------------- 解密密码弹层（远端已加密、本机无密码） ----------------
    state.decryptPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onCancelDecrypt,
            shape = MileNoteShapes.extraLarge,
            title = { Text(tr("远端账本已加密", "The remote ledger is encrypted")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = tr(
                            "远端账本使用 AES-256-GCM 加密。请输入本机或另一台设备设置的加密密码；" +
                                "解密成功后会记住该密码并继续刚才的操作。",
                            "The remote ledger is encrypted with AES-256-GCM. Enter the encryption password set on this or another device; " +
                                "after a successful decryption the app remembers it and resumes the previous action.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    FormField(
                        value = decryptPassword,
                        onValueChange = { decryptPassword = it },
                        label = tr("加密密码", "Encryption password"),
                        placeholder = tr("至少 8 个字符", "At least 8 characters"),
                        singleLine = true,
                        visualTransformation = if (decryptPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailing = {
                            IconButton(
                                onClick = { decryptPasswordVisible = !decryptPasswordVisible },
                                modifier = Modifier.size(MileNoteDimens.TouchTarget),
                            ) {
                                Icon(
                                    imageVector = if (decryptPasswordVisible) {
                                        MileNoteIcons.Visibility
                                    } else {
                                        MileNoteIcons.Lock
                                    },
                                    contentDescription = if (decryptPasswordVisible) tr("隐藏密码", "Hide password") else tr("显示密码", "Show password"),
                                    modifier = Modifier.size(20.dp),
                                    tint = scheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                    prompt.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSubmitDecryptPassword(decryptPassword) },
                    enabled = decryptPassword.isNotBlank() && !state.busy,
                    shape = MileNoteShapeTokens.full,
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = scheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(tr("解密", "Decrypt"))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDecrypt) { Text(tr("取消", "Cancel")) }
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

/** 上次同步时间：本地时区 `MM.dd HH:mm`；为空显示「尚未同步」。 */
private fun formatSyncTime(millis: Long?): String {
    if (millis == null) return tr("尚未同步", "Not synced yet")
    return runCatching {
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(SyncTimeFormatter)
    }.getOrDefault("—")
}

/** 远端文件状态：存在（大小）/ 不存在 / 未知。 */
private fun remoteSummary(state: WebDavUiState): String = when (state.remoteExists) {
    null -> tr("未知（保存并测试后显示）", "Unknown (shown after save & test)")
    false -> tr("不存在（首次同步将创建）", "Missing (created on first sync)")
    true -> state.remoteSize?.let { tr("存在 · %s", "Exists · %s", formatFileSize(it)) } ?: tr("存在", "Exists")
}

/** 文件大小格式化：B / KB / MB。 */
private fun formatFileSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
