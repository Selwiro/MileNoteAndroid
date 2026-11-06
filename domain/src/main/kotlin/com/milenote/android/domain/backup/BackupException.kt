package com.milenote.android.domain.backup

/** 备份 / 账本业务校验失败；message 为可直接展示的中文文案。 */
class BackupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
