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
 * 默认目录是「所选目录 / milenote」：授权仍是父目录（这样重启后授权身份不变），
 * 读写时按 [DEFAULT_SUBDIR] 找到或新建子目录再操作；用户明确选了别的目录时 [subdir] 传 null。
 *
 * 安全底线：[prune] 只删 [com.milenote.android.domain.backup.isBackupFileName] 认得的文件，
 * 用户放在同一目录里的其他文件一律不动。
 */
object BackupFolder {

    /** 默认备份子目录名（Documents/milenote）。 */
    const val DEFAULT_SUBDIR = "milenote"

    /** 目录授权失效、目录被删或 provider 不可用：调用方应清空设置并让用户重选。 */
    class BackupFolderUnavailableException(cause: Throwable? = null) : Exception(cause)

    private const val MIME_JSON = "application/json"
    private const val MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR

    /**
     * 目录显示名；取不到返回 null（界面显示「已选择」）。
     *
     * [subdir] 非空时返回子目录名（必要时新建），反映备份实际写入的位置。
     */
    fun displayName(context: Context, treeUri: Uri, subdir: String? = null): String? = runCatching {
        val docId = rootDocumentId(context, treeUri, subdir)
        context.contentResolver.query(
            documentUri(treeUri, docId),
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
    fun write(context: Context, treeUri: Uri, subdir: String?, name: String, bytes: ByteArray): Uri =
        wrapUnavailable {
            val rootId = rootDocumentId(context, treeUri, subdir)
            val existing = list(context, treeUri, rootId).firstOrNull { !it.isDir && it.name == name }
            if (existing != null) {
                // 优先 "wt"（写入 + 截断）：覆盖旧文件时不能留下上一次更长的尾部。
                val truncating = runCatching {
                    context.contentResolver.openOutputStream(existing.uri, "wt")
                }.getOrNull()
                if (truncating != null) {
                    truncating.use { it.write(bytes) }
                    return@wrapUnavailable existing.uri
                }
                // 个别 provider 不支持 "wt"，用 "w" 覆盖可能不截断 → 旧文件更长的尾部会让 JSON 变成
                // 「有效前缀 + 垃圾」。这里删掉旧文档再新建同名文件，从 0 开始写。
                val deleted = runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, existing.uri)
                }.getOrDefault(false)
                if (deleted) {
                    val recreated = DocumentsContract.createDocument(
                        context.contentResolver,
                        documentUri(treeUri, rootId),
                        MIME_JSON,
                        name,
                    ) ?: error(tr("无法创建备份文件", "Could not create the backup file"))
                    writeBytes(context, recreated, bytes)
                    return@wrapUnavailable recreated
                }
                // 删除也失败（只读 / 被占用）：最后退化为 "w"，最坏情况是多留几份旧文件。
                val fallback = runCatching {
                    context.contentResolver.openOutputStream(existing.uri, "w")
                }.getOrNull() ?: error(tr("无法写入备份文件", "Could not write the backup file"))
                fallback.use { it.write(bytes) }
                return@wrapUnavailable existing.uri
            }
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                documentUri(treeUri, rootId),
                MIME_JSON,
                name,
            ) ?: error(tr("无法创建备份文件", "Could not create the backup file"))
            writeBytes(context, created, bytes)
            created
        }

    /** 写入字节；流打不开就报成可展示的「无法写入」。 */
    private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
        val stream = runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()
            ?: error(tr("无法写入备份文件", "Could not write the backup file"))
        stream.use { it.write(bytes) }
    }

    /**
     * 删除超出保留份数的旧备份，返回实际删除个数。
     *
     * 只处理本应用命名规则（`里程志备份-YYYY-MM-DD.json`）的文件；其他文件不列、不删。
     */
    fun prune(context: Context, treeUri: Uri, subdir: String?, keep: Int): Int {
        val docs = wrapUnavailable { list(context, treeUri, rootDocumentId(context, treeUri, subdir)) }
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

    /**
     * 一键备份：写当天文件 + 按保留份数清理旧文件。返回（文件名, 删除个数）。
     *
     * @throws SecurityException 授权失效
     */
    suspend fun backupNow(
        context: Context,
        treeUri: Uri,
        subdir: String?,
        keep: Int,
        text: String,
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        val name = LedgerExports.backupFileName()
        write(context, treeUri, subdir, name, text.toByteArray(Charsets.UTF_8))
        // 清理失败不影响这次备份本身（最坏是多留几份旧文件）。
        name to runCatching { prune(context, treeUri, subdir, keep) }.getOrDefault(0)
    }

    /**
     * 备份根目录的 document id：授权目录本身，或其中名为 [subdir] 的子目录（不存在就新建）。
     *
     * 子目录用父授权目录的 tree Uri 寻址，不额外申请子目录授权——重启后父目录授权仍然有效。
     */
    private fun rootDocumentId(context: Context, treeUri: Uri, subdir: String?): String =
        wrapUnavailable {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val name = subdir?.trim().orEmpty()
            if (name.isEmpty()) rootId else ensureChildDir(context, treeUri, rootId, name)
        }

    private fun ensureChildDir(context: Context, treeUri: Uri, parentId: String, name: String): String {
        childDocs(context, treeUri, parentId)
            .firstOrNull { it.isDir && it.name.equals(name, ignoreCase = true) }
            ?.let { return it.id }
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            documentUri(treeUri, parentId),
            MIME_DIR,
            name,
        ) ?: error(tr("无法创建备份文件夹", "Could not create the backup folder"))
        return DocumentsContract.getDocumentId(created)
    }

    /** 目录里的子文件 / 子目录；查询失败时抛异常，由调用方按授权失效处理。 */
    private fun list(context: Context, treeUri: Uri, parentId: String): List<Doc> =
        childDocs(context, treeUri, parentId)

    private fun childDocs(context: Context, treeUri: Uri, parentId: String): List<Doc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val docs = mutableListOf<Doc>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val isDir = cursor.getString(2) == MIME_DIR
                docs += Doc(name = name, id = id, uri = documentUri(treeUri, id), isDir = isDir)
            }
        }
        return docs
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

    private fun documentUri(treeUri: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private data class Doc(val name: String, val id: String, val uri: Uri, val isDir: Boolean)
}
