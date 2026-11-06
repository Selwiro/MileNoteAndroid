package com.milenote.android.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 文本/二进制产物的导出：优先走系统「保存到文件」（SAF），失败再走分享。
 * 不申请存储权限。
 */
object FileExporter {

    /** 触发系统文件保存器；调用方通过 ActivityResultLauncher 拿 Uri 后调用 [writeTo]。 */
    fun createDocumentIntent(mimeType: String, suggestedName: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }

    fun writeTo(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("无法写入所选文件")
    }

    fun shareText(context: Context, fileName: String, mimeType: String, bytes: ByteArray) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 $fileName"))
    }
}
