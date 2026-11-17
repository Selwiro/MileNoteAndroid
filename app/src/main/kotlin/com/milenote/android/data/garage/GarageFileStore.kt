package com.milenote.android.data.garage

import android.content.Context
import com.milenote.android.i18n.tr
import java.io.File
import java.io.IOException

/**
 * 账本文件存储接口。
 *
 * 抽成接口是为了在 JVM 单测里替换成内存实现，验证「损坏保护 / 快照 / 隔离」等
 * 数据安全路径——这些路径在真机上很难构造（要求 rename 失败等）。
 */
interface GarageStore {

    fun exists(): Boolean

    /** 读取原始 JSON；文件不存在返回 null。 */
    fun read(): String?

    /**
     * 写入整本账本。
     *
     * **必须用异常表示失败**（不要静默 return）：调用方据此判断「有没有落盘」，
     * 静默失败会被当成保存成功。
     */
    fun write(json: String)

    /**
     * 解析失败时把现场文件挪到 `garage-broken-<ts>.json` 以便用户导出。
     *
     * @return 保留下来的副本；**null 表示无法保住原始数据**，此时调用方绝不能覆写原文件。
     */
    fun quarantine(): File?

    /** 覆盖整本账本（WebDAV 下载 / 还原 / 导入替换）前保存一份本地快照；失败返回 null，不阻塞主流程。 */
    fun snapshot(): File?

    /** 最近一次覆盖前快照；没有则返回 null。 */
    fun latestSnapshot(): File?

    /** 最近一次被隔离（解析失败）的原始文件；没有则返回 null。 */
    fun latestQuarantined(): File?

    /** 当前账本文件（可能不存在）。 */
    fun currentFile(): File
}

/**
 * 账本快照的文件存储：与 Web 版相同的单文件 JSON（version 2），
 * 写入采用「临时文件 + fsync + 原子重命名」，避免半写导致账本损坏。
 * 图片以 base64 dataURL 存在 JSON 内，和 Web 备份完全一致。
 */
class GarageFileStore internal constructor(private val dir: File) : GarageStore {

    /** 应用内使用：`filesDir/garage`（单测可直接传临时目录）。 */
    constructor(context: Context) : this(File(context.filesDir, "garage"))

    private val file: File = File(dir, "garage.json")
    private val lock = Any()

    init {
        dir.mkdirs()
    }

    override fun exists(): Boolean = file.exists()

    override fun read(): String? = synchronized(lock) {
        if (!file.exists()) null else file.readText()
    }

    /**
     * 写入整本账本：临时文件 + fsync + 原子替换。
     *
     * 两条底线（1.0 审查 #1）：
     * - **刷盘失败必须抛**：以前 `fsync` 包在 `runCatching` 里，失败也照样替换，
     *   掉电 / 进程被杀会留下半截账本；
     * - **绝不退化成覆盖原文件**：以前 rename 失败就 `file.writeText(json)`，
     *   等于先把唯一一份旧账本截断再写，磁盘满或写入中断时旧数据直接没了。
     *   现在替换失败只删临时文件并抛错，旧账本保持完好。
     */
    override fun write(json: String) = synchronized(lock) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val tmp = File(dir, "garage.json.tmp")
        // 上一次崩溃可能留下半截临时文件，先清掉，别把它当成本次产物。
        if (tmp.exists()) tmp.delete()
        try {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!atomicReplace(tmp, file)) {
                tmp.delete()
                throw IOException(
                    tr("账本替换失败，已保留原文件", "Could not replace the data file; the previous one was kept"),
                )
            }
        } catch (t: Throwable) {
            // 失败路径不碰 file：宁可本次不落盘，也不能毁掉上一份。
            runCatching { tmp.delete() }
            throw t
        }
    }

    /** 同目录内原子替换；不支持 `ATOMIC_MOVE` 的存储退化成普通 `move`（仍是 rename，不会截断目标）。 */
    private fun atomicReplace(tmp: File, target: File): Boolean {
        val src = tmp.toPath()
        val dst = target.toPath()
        if (runCatching {
                java.nio.file.Files.move(
                    src,
                    dst,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess
        ) {
            return true
        }
        return runCatching {
            java.nio.file.Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
    }

    override fun quarantine(): File? = synchronized(lock) {
        if (!file.exists()) return null
        val broken = File(dir, "garage-broken-${System.currentTimeMillis()}.json")
        if (file.renameTo(broken)) return broken
        // rename 失败（只读目录 / 文件被占用 / 跨卷）：退化为复制，副本落地后再尽力删除原文件。
        // 复制也失败时必须返回 null —— 调用方据此停止写入，宁可本次会话不落盘也不能抹掉坏文件。
        return try {
            file.copyTo(broken, overwrite = true)
            file.delete()
            broken
        } catch (t: Throwable) {
            broken.delete()
            null
        }
    }

    override fun snapshot(): File? = synchronized(lock) {
        if (!file.exists()) return null
        val snap = File(dir, "garage-snapshot-${System.currentTimeMillis()}.json")
        try {
            file.copyTo(snap, overwrite = true)
            pruneSnapshots()
            snap
        } catch (t: Throwable) {
            snap.delete()
            null
        }
    }

    override fun latestSnapshot(): File? = synchronized(lock) {
        files("garage-snapshot-").maxByOrNull { it.lastModified() }
    }

    override fun latestQuarantined(): File? = synchronized(lock) {
        files("garage-broken-").maxByOrNull { it.lastModified() }
    }

    override fun currentFile(): File = file

    /** 只保留最近 [keep] 份快照，避免账本快照无限堆积占用空间。 */
    private fun pruneSnapshots(keep: Int = 3) {
        files("garage-snapshot-")
            .sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { it.delete() }
    }

    private fun files(prefix: String): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".json") }
            ?: emptyList()
}
