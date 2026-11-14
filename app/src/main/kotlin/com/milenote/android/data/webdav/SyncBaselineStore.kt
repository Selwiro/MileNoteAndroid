package com.milenote.android.data.webdav

import android.content.Context
import java.io.File

/**
 * 上次同步基线存储：三向合并的 base。
 *
 * 每次同步成功（上传 / 下载 / 合并后上传）把当时**两边一致**的明文 JSON 写到
 * `filesDir/garage/last-synced.json`，下次同步时拿它判断「哪一侧改了什么」。
 *
 * 只存本机、不上传；与账本文件同目录、同样的「临时文件 + 原子重命名」写法。
 * 体积与账本同量级（图片以 base64 内嵌），因此不放 DataStore（Preferences 不适合大字符串）。
 */
class SyncBaselineStore(context: Context) {

    private val dir: File = File(context.filesDir, "garage").apply { mkdirs() }
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
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
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
