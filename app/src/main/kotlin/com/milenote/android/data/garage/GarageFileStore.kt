package com.milenote.android.data.garage

import android.content.Context
import java.io.File

/**
 * 账本快照的文件存储：与 Web 版相同的单文件 JSON（version 2），
 * 写入采用「临时文件 + fsync + 原子重命名」，避免半写导致账本损坏。
 * 图片以 base64 dataURL 存在 JSON 内，和 Web 备份完全一致。
 */
class GarageFileStore(context: Context) {

    private val dir: File = File(context.filesDir, "garage").apply { mkdirs() }
    private val file: File = File(dir, "garage.json")
    private val lock = Any()

    fun exists(): Boolean = file.exists()

    /** 读取原始 JSON；文件不存在返回 null。 */
    fun read(): String? = synchronized(lock) {
        if (!file.exists()) null else file.readText()
    }

    fun write(json: String) = synchronized(lock) {
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

    /** 解析失败时保留现场，便于用户导出原始数据，然后从空账本开始。 */
    fun quarantine(): File? = synchronized(lock) {
        if (!file.exists()) return null
        val broken = File(dir, "garage-broken-${System.currentTimeMillis()}.json")
        if (file.renameTo(broken)) broken else null
    }

    /** 最近一次被隔离（解析失败）的原始文件；没有则返回 null。 */
    fun latestQuarantined(): File? = synchronized(lock) {
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith("garage-broken-") && it.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
    }
}
