package com.milenote.android.data.garage

import android.content.Context
import java.io.File

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
class GarageFileStore(context: Context) : GarageStore {

    private val dir: File = File(context.filesDir, "garage").apply { mkdirs() }
    private val file: File = File(dir, "garage.json")
    private val lock = Any()

    override fun exists(): Boolean = file.exists()

    override fun read(): String? = synchronized(lock) {
        if (!file.exists()) null else file.readText()
    }

    override fun write(json: String) = synchronized(lock) {
        val tmp = File(dir, "garage.json.tmp")
        tmp.writeText(json)
        // 尽量把数据刷到磁盘，再原子替换。
        runCatching {
            java.io.FileOutputStream(tmp, true).use { it.fd.sync() }
        }
        if (!tmp.renameTo(file)) {
            // rename 失败时退化为复制覆盖，保证不丢数据。
            file.writeText(json)
            tmp.delete()
        }
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
