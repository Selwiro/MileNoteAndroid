package com.milenote.android.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import com.milenote.android.domain.backup.backupsToDelete
import com.milenote.android.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 固定目录备份（F4）的 SAF 读写。
 *
 * 只使用 `DocumentsContract`，不引入 `androidx.documentfile` 依赖；调用方必须先拿到
 * `ACTION_OPEN_DOCUMENT_TREE` 的持久授权（`takePersistableUriPermission`）。
 *
 * 安全底线：[prune] 只删 [com.milenote.android.domain.backup.isBackupFileName] 认得的文件，
 * 用户放在同一目录里的其他文件一律不动。
 */
object BackupFolder {

    /** 目录授权失效、目录被删或 provider 不可用：调用方应清空设置并让用户重选。 */
    class BackupFolderUnavailableException(cause: Throwable? = null) : Exception(cause)

    private const val MIME_JSON = "application/json"

    /** 目录显示名；取不到返回 null（界面显示「已选择」）。 */
    fun displayName(context: Context, treeUri: Uri): String? = runCatching {
        val docUri = treeDocumentUri(treeUri)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /**
     * 写入一份备份：同名文件已存在则覆盖（截断重写），否则新建。
     *
     * @throws SecurityException 授权失效（目录被删 / 用户撤销 / 换机）
     */
    fun write(context: Context, treeUri: Uri, name: String, bytes: ByteArray): Uri = wrapUnavailable {
        val existing = list(context, treeUri).firstOrNull { it.name == name }
        val docUri = existing?.uri ?: run {
            val parent = treeDocumentUri(treeUri)
            DocumentsContract.createDocument(context.contentResolver, parent, MIME_JSON, name)
        } ?: error(tr("无法创建备份文件", "Could not create the backup file"))
        // "wt" = write + truncate：覆盖旧文件时不能留下上一次更长的尾部；个别 provider 不支持 "wt" 时退回 "w"。
        val stream = runCatching { context.contentResolver.openOutputStream(docUri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(docUri, "w")
        stream?.use { it.write(bytes) } ?: error(tr("无法写入备份文件", "Could not write the backup file"))
        docUri
    }

    /**
     * 删除超出保留份数的旧备份，返回实际删除个数。
     *
     * 只处理本应用命名规则（`里程志备份-YYYY-MM-DD.json`）的文件；其他文件不列、不删。
     */
    fun prune(context: Context, treeUri: Uri, keep: Int): Int {
        val docs = wrapUnavailable { list(context, treeUri) }
        val targets = backupsToDelete(docs.map { it.name }, keep).toSet()
        if (targets.isEmpty()) return 0
        var deleted = 0
        docs.filter { it.name in targets }.forEach { doc ->
            val ok = runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, doc.uri)
            }.getOrDefault(false)
            if (ok) deleted++
        }
        return deleted
    }

    /** 目录里的子文件（名称 + Uri）；查询失败时抛异常，由调用方按授权失效处理。 */
    private fun list(context: Context, treeUri: Uri): List<Doc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val docs = mutableListOf<Doc>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                docs += Doc(name = name, uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
            }
        }
        return docs
    }

    /**
     * 一键备份：写当天文件 + 按保留份数清理旧文件。返回（文件名, 删除个数）。
     *
     * @throws SecurityException 授权失效
     */
    suspend fun backupNow(context: Context, treeUri: Uri, keep: Int, text: String): Pair<String, Int> =
        withContext(Dispatchers.IO) {
            val name = LedgerExports.backupFileName()
            write(context, treeUri, name, text.toByteArray(Charsets.UTF_8))
            // 清理失败不影响这次备份本身（最坏是多留几份旧文件）。
            name to runCatching { prune(context, treeUri, keep) }.getOrDefault(0)
        }

    private fun <T> wrapUnavailable(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw BackupFolderUnavailableException(e)
    } catch (e: FileNotFoundException) {
        throw BackupFolderUnavailableException(e)
    } catch (e: IllegalArgumentException) {
        throw BackupFolderUnavailableException(e)
    }

    private fun treeDocumentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private data class Doc(val name: String, val uri: Uri)
}
