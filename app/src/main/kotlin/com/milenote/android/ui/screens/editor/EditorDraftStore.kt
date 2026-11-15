package com.milenote.android.ui.screens.editor

import android.content.Context
import com.milenote.android.domain.model.Record
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「记一笔」草稿（UX1 / B22）。
 *
 * 之前表单只存在内存里：填到一半被系统回收、或长按返回退出，回来全没了 —— 这是唯一
 * 还会真丢用户输入的坑。这里把**每次改动**（防抖 600ms）落成一份本机草稿，
 * 重进编辑器时提示「继续编辑 / 丢弃」。
 *
 * 只存一条：记录是「这一笔」的上下文，多份草稿反而让人无从选择；保存或明确丢弃后清空。
 * 存的是 [Record]（账本同构，可直接反填表单），不含图片、不上传、不进备份。
 */
@Serializable
internal data class EditorDraft(
    /** 草稿对应的车辆：换了当前车就不该把 A 车的草稿填进 B 车。 */
    val carId: String,
    /** 编辑既有记录时的记录 id；新增为 null。 */
    val editingId: String? = null,
    /** 表单按当前规则构造出的记录（反填时逐字段回写）。 */
    val record: Record,
    val savedAt: Long,
)

/** 草稿的序列化与存储（纯 JVM 可测的编解码 + SharedPreferences 落盘）。 */
internal class EditorDraftStore(private val context: Context?) {

    private val prefs = context?.applicationContext
        ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读出草稿；超过 [MAX_AGE_MILLIS] 或属于别的车就丢弃返回 null。 */
    fun load(carId: String, now: Long = System.currentTimeMillis()): EditorDraft? {
        val raw = prefs?.getString(KEY_DRAFT, null) ?: return null
        val draft = decodeDraft(raw) ?: run {
            clear()
            return null
        }
        if (draft.carId != carId || now - draft.savedAt > MAX_AGE_MILLIS) {
            clear()
            return null
        }
        return draft
    }

    fun save(draft: EditorDraft) {
        // commit 而不是 apply：进程被系统回收（或用户强退）时 apply 的异步写可能还没落盘，
        // 草稿就白存了 —— 这里只写一条几十字节的 JSON，同步写代价可以忽略。
        runCatching { prefs?.edit()?.putString(KEY_DRAFT, encodeDraft(draft))?.commit() }
    }

    fun clear() {
        runCatching { prefs?.edit()?.remove(KEY_DRAFT)?.commit() }
    }

    companion object {
        private const val PREFS_NAME = "milenote-editor-draft"
        private const val KEY_DRAFT = "draft"

        /** 草稿保留 7 天：隔太久再回来，金额/日期多半已经不对，不如重新填。 */
        const val MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        internal fun encodeDraft(draft: EditorDraft): String =
            json.encodeToString(EditorDraft.serializer(), draft)

        internal fun decodeDraft(raw: String): EditorDraft? =
            runCatching { json.decodeFromString(EditorDraft.serializer(), raw) }.getOrNull()
    }
}
