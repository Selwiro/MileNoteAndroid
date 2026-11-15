package com.milenote.android.data.webdav

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 上次同步基线存储：三向合并的 base。
 *
 * 每次同步成功（上传 / 下载 / 合并后上传）把当时**两边一致**的明文 JSON 写到
 * `filesDir/garage/last-synced.json`，下次同步时拿它判断「哪一侧改了什么」。
 *
 * 只存本机、不上传；与账本文件同目录、同样的「临时文件 + fsync + 原子重命名」写法：
 * 直接 rename 覆盖旧文件（Linux/Android 上 rename 本身原子），**不先删旧文件**——
 * 先 delete 再 rename 中间有一个无基线的窗口，正好在这时崩溃/断电就会丢基线，
 * 下次同步静默降级为「无基线合并」。
 */
class SyncBaselineStore(
    directory: File,
    /** 原子替换实现；测试注入 `false` 覆盖 rename 失败的回退分支。 */
    private val rename: (File, File) -> Boolean = { tmp, target -> tmp.renameTo(target) },
) {

    constructor(context: Context) : this(File(context.filesDir, "garage"))

    private val dir: File = directory.apply { mkdirs() }
    private val file: File = File(dir, "last-synced.json")
    private val temp: File = File(dir, "last-synced.json.tmp")
    private val lock = Any()

    /** 读取基线；不存在 / 读取失败返回 null（调用方按「无基线」降级合并）。 */
    fun read(): String? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching { file.readText() }.getOrNull()
    }

    /** 覆盖保存基线；失败不阻塞同步（下轮按无基线降级，不会丢数据）。 */
    fun write(json: String) = synchronized(lock) {
        runCatching {
            temp.writeText(json)
            // 尽量把数据刷到磁盘，再原子替换（顺序与 GarageFileStore.write 一致）。
            runCatching { FileOutputStream(temp, true).use { it.fd.sync() } }
            if (!rename(temp, file)) {
                // rename 失败（只读目录 / 文件被占用 / 跨卷）时退化为直接覆盖写，保证不丢基线。
                file.writeText(json)
                temp.delete()
            }
        }
        Unit
    }

    /** 清除基线（切换服务器 / 清除配置时）。 */
    fun clear() = synchronized(lock) {
        runCatching { file.delete() }
        runCatching { temp.delete() }
        Unit
    }
}
