package com.milenote.android.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.security.MessageDigest
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
     * 写入一份备份：同名文件已存在则**在旧文件可恢复的前提下**替换。
     *
     * 1.0 审查 #2：以前三条路径都会先动旧文件（`wt` 先截断 / 删旧再建 / 退 `w` 留旧尾部），
     * 写到一半失败就留下坏文件，界面还报成功并刷新备份时间。现在改为：
     *
     * 1. 新内容先写进独立临时文件 `name.part-<ts>`，**整个过程不碰旧文件**；
     * 2. 读回临时文件算 SHA-256 与预期比对（provider 写短了 / 静默失败在这里发现）；
     * 3. 校验通过才换名：旧文件先退成 `name.old-<ts>`，新文件改成正名，成功后再删旧文件；
     * 4. provider 不支持改名（少数第三方网盘）时，退化为「临时文件已备好 → 原地覆盖 → 再校验」，
     *    覆盖失败则保留那份已验证的临时文件并在错误里写明文件名，旧备份仍可从它恢复。
     *
     * 任何一步失败都会抛异常，调用方因此不会刷新「上次备份时间」。
     *
     * @throws SecurityException 授权失效（目录被删 / 用户撤销 / 换机）
     */
    fun write(context: Context, treeUri: Uri, subdir: String?, name: String, bytes: ByteArray): Uri =
        wrapUnavailable {
            val resolver = context.contentResolver
            val rootId = rootDocumentId(context, treeUri, subdir)
            val dirUri = documentUri(treeUri, rootId)
            val stamp = System.currentTimeMillis()
            val existing = list(context, treeUri, rootId).firstOrNull { !it.isDir && it.name == name }
            val partName = "$name.part-$stamp"
            val part = DocumentsContract.createDocument(resolver, dirUri, MIME_JSON, partName)
                ?: error(tr("无法创建备份文件", "Could not create the backup file"))

            // 临时文件通过校验后，原地覆盖期间它就是那份「可恢复副本」，不能删。
            var keepPartOnFailure = false
            try {
                writeBytes(context, part, bytes)
                verifyBytes(context, part, bytes)

                if (existing == null) {
                    val renamed = renameOrNull(context, part, name)
                    if (renamed != null) {
                        cleanupSiblings(context, treeUri, rootId, name, keep = emptySet())
                        return@wrapUnavailable renamed
                    }
                    // 不支持改名：直接建正式文件；两个文件都写一遍很便宜，账本最多几十 MB 且备份是低频操作。
                    val created = DocumentsContract.createDocument(resolver, dirUri, MIME_JSON, name)
                        ?: error(tr("无法创建备份文件", "Could not create the backup file"))
                    writeBytes(context, created, bytes)
                    verifyBytes(context, created, bytes)
                    cleanupSiblings(context, treeUri, rootId, name, keep = setOf(name))
                    return@wrapUnavailable created
                }

                // 旧文件先退到一边：改名成功才继续，失败说明 provider 不支持这套动作。
                val aside = renameOrNull(context, existing.uri, "$name.old-$stamp")
                if (aside != null) {
                    val renamed = renameOrNull(context, part, name)
                    if (renamed == null) {
                        // 至少把旧文件名字改回来，别让用户在自己的目录里找不到上一份备份。
                        runCatching { DocumentsContract.renameDocument(resolver, aside, name) }
                        error(
                            tr(
                                "无法替换备份文件，已保留上一份",
                                "Could not replace the backup file; the previous one was kept",
                            ),
                        )
                    }
                    runCatching { DocumentsContract.deleteDocument(resolver, aside) }
                    cleanupSiblings(context, treeUri, rootId, name, keep = setOf(name))
                    return@wrapUnavailable renamed
                }

                // provider 不支持改名：临时文件已验证可用，原地覆盖，再校验一次。
                keepPartOnFailure = true
                val inPlace = runCatching {
                    // 能截断就截断（避免旧文件更长的尾部）；不支持 "wt" 只能 "w" + 读回校验兜底。
                    val truncated = runCatching { writeBytes(context, existing.uri, bytes, mode = "wt") }.isSuccess
                    if (!truncated) writeBytes(context, existing.uri, bytes, mode = "w")
                    verifyBytes(context, existing.uri, bytes)
                }
                if (inPlace.isFailure) {
                    error(
                        tr(
                            "无法替换备份文件，新备份暂存为「%s」，可从它恢复",
                            "Could not replace the backup file; the new backup was kept as %s and can be recovered from it",
                            partName,
                        ),
                    )
                }
                keepPartOnFailure = false
                cleanupSiblings(context, treeUri, rootId, name, keep = setOf(name))
                existing.uri
            } catch (t: Throwable) {
                if (!keepPartOnFailure) runCatching { DocumentsContract.deleteDocument(resolver, part) }
                throw t
            }
        }

    /** 改名；provider 不支持时返回 null（不同错误类型都按「不支持」处理，由调用方选降级路径）。 */
    private fun renameOrNull(context: Context, uri: Uri, name: String): Uri? =
        runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, name) }.getOrNull()

    /**
     * 读回校验：写入的字节必须一字不差，否则宁可报错也不能留一份坏备份。
     *
     * 用流式 SHA-256 比对，不把整份备份再读进内存。
     */
    private fun verifyBytes(context: Context, uri: Uri, expected: ByteArray) {
        val expectedDigest = MessageDigest.getInstance("SHA-256").apply { update(expected) }.digest()
        val actualDigest = MessageDigest.getInstance("SHA-256")
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: error(
                tr(
                    "备份写入后无法读回，已保留上一份",
                    "The backup could not be read back; the previous one was kept",
                ),
            )
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                actualDigest.update(buffer, 0, read)
            }
        }
        if (!expectedDigest.contentEquals(actualDigest.digest())) {
            error(
                tr(
                    "备份写入不完整，已保留上一份",
                    "The backup was written incompletely; the previous one was kept",
                ),
            )
        }
    }

    /** 清掉同一份备份留下的临时 / 旧文件（只在这次写入已经成功后调用）。 */
    private fun cleanupSiblings(context: Context, treeUri: Uri, rootId: String, name: String, keep: Set<String>) {
        val prefixes = listOf("$name.part-", "$name.old-")
        runCatching {
            list(context, treeUri, rootId)
                .filter { doc -> !doc.isDir && doc.name !in keep && prefixes.any { doc.name.startsWith(it) } }
                .forEach { doc -> DocumentsContract.deleteDocument(context.contentResolver, doc.uri) }
        }
    }

    /** 写入字节；打不开流就报成可展示的「无法写入」。`wt` 要求截断（覆盖旧文件时必须）。 */
    private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray, mode: String = "w") {
        val stream = runCatching { context.contentResolver.openOutputStream(uri, mode) }.getOrNull()
            ?: error(tr("无法写入备份文件", "Could not write the backup file"))
        stream.use {
            it.write(bytes)
            it.flush()
        }
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
