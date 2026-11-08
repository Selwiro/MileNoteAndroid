package com.milenote.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.milenote.android.data.share.SharedImport
import com.milenote.android.data.settings.AppSettings
import com.milenote.android.data.settings.DarkMode
import com.milenote.android.i18n.AppLanguage
import com.milenote.android.i18n.I18n
import com.milenote.android.i18n.tr
import com.milenote.android.navigation.AppShortcut
import com.milenote.android.navigation.MileNoteApp
import com.milenote.android.ui.MileNoteViewModel
import com.milenote.android.ui.theme.AppTheme
import com.milenote.android.ui.theme.MileNoteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as MileNoteApplication).container
        handleShareIntent(intent)
        handleShortcutIntent(intent)
        setContent {
            LaunchedEffect(Unit) { container.garageRepository.load() }
            val garage by container.garageRepository.garage.collectAsStateWithLifecycle(null)
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(AppSettings())
            val vm: MileNoteViewModel = viewModel(factory = MileNoteViewModel.factory(container))

            // 桌面长按快捷方式（记一笔 / 记加油 / 扫加油票）：等账本加载完再打开对应入口，
            // 避免冷启动时 load() 与 openEditor() 竞争。
            val pendingShortcut by container.pendingShortcut.collectAsStateWithLifecycle(null)
            LaunchedEffect(pendingShortcut) {
                val shortcut = pendingShortcut ?: return@LaunchedEffect
                container.pendingShortcut.value = null
                vm.state.first { it.garage != null }
                when (shortcut) {
                    AppShortcut.NEW_ENTRY -> vm.openEditor()
                    AppShortcut.FUEL -> vm.openEditor(category = "油费")
                    AppShortcut.OCR -> vm.openEditor(category = "油费", startOcr = true)
                }
            }

            // 界面语言：设置项优先级最高，跟随时读系统语言（英文系统 → 英文界面）。
            val locales = LocalConfiguration.current.locales
            val systemEnglish = locales.size() > 0 && locales[0].language.startsWith("en")
            val english = when (settings.language) {
                AppLanguage.SYSTEM -> systemEnglish
                AppLanguage.ZH -> false
                AppLanguage.EN -> true
            }
            LaunchedEffect(english) { I18n.applyEnglish(english) }

            // 系统分享进来的导入内容：交给 VM，MileNoteApp 会自动跳到导入向导。
            val pendingShare by container.pendingShare.collectAsStateWithLifecycle(null)
            LaunchedEffect(pendingShare) {
                pendingShare?.let {
                    vm.setSharedImport(it)
                    container.pendingShare.value = null
                }
            }

            // WebDAV 自动同步仅在前台运行：打开应用时同步一次，退出后不保留后台任务。
            LaunchedEffect(Unit) {
                vm.maybeAutoSyncOnOpen()
            }
            val appTheme = remember(garage?.theme) { AppTheme.fromId(garage?.theme) }
            val dark = when (settings.darkMode) {
                DarkMode.SYSTEM -> isSystemInDarkTheme()
                DarkMode.LIGHT -> false
                DarkMode.DARK -> true
            }
            MileNoteTheme(
                appTheme = appTheme,
                darkTheme = dark,
            ) {
                MileNoteApp(vm = vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleShortcutIntent(intent)
    }

    /** 桌面长按快捷方式：只记录待处理入口，界面在组合中消费。 */
    private fun handleShortcutIntent(intent: Intent?) {
        val shortcut = AppShortcut.fromAction(intent?.action) ?: return
        (application as MileNoteApplication).container.pendingShortcut.value = shortcut
    }

    /** 接收 ACTION_SEND（钱迹「分享到其他 App」、文件管理器分享等），读取文本或文件字节。 */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val container = (application as MileNoteApplication).container
        lifecycleScope.launch {
            val shared = withContext(Dispatchers.IO) { readSharedContent(intent) }
            if (shared != null) {
                container.pendingShare.value = shared
            } else {
                Toast.makeText(
                    this@MainActivity,
                    tr("没有读取到可导入的内容", "Nothing importable found"),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun readSharedContent(intent: Intent): SharedImport? {
        val mime = intent.type
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrBlank()) {
            return SharedImport(
                bytes = text.toByteArray(Charsets.UTF_8),
                fileName = guessShareFileName(text, mime),
                mimeType = mime ?: "text/plain",
            )
        }
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            val item = clip.getItemAt(0)
            item.text?.takeIf { it.isNotBlank() }?.let { textItem ->
                val textValue = textItem.toString()
                return SharedImport(
                    bytes = textValue.toByteArray(Charsets.UTF_8),
                    fileName = guessShareFileName(textValue, mime),
                    mimeType = mime,
                )
            }
            item.uri?.let { return readSharedUri(it, mime ?: clip.description?.getMimeType(0)) }
        }
        @Suppress("DEPRECATION")
        val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
        stream?.let { return readSharedUri(it, mime) }
        return null
    }

    private fun readSharedUri(uri: Uri, mime: String?): SharedImport? = try {
        val resolvedMime = contentResolver.getType(uri) ?: mime
        val name = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: tr("分享导入", "Shared import")
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        when {
            bytes == null -> null
            bytes.size > 20 * 1024 * 1024 -> {
                Toast.makeText(
                    this,
                    tr(
                        "分享文件超过 20 MB，请先在钱迹中拆分导出",
                        "Shared file is larger than 20 MB — split the export in Qianji first",
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                null
            }
            else -> SharedImport(bytes = bytes, fileName = name, mimeType = resolvedMime)
        }
    } catch (t: Throwable) {
        null
    }

    private fun guessShareFileName(text: String, mime: String?): String {
        val trimmed = text.trimStart()
        return when {
            trimmed.startsWith("[") || trimmed.startsWith("{") -> tr("分享导入.json", "shared-import.json")
            mime?.contains("json", ignoreCase = true) == true -> tr("分享导入.json", "shared-import.json")
            mime?.contains("csv", ignoreCase = true) == true -> tr("分享导入.csv", "shared-import.csv")
            else -> tr("分享导入.txt", "shared-import.txt")
        }
    }
}
