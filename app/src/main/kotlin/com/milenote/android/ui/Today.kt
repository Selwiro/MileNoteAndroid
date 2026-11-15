package com.milenote.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.milenote.android.domain.calc.Dates
import java.time.LocalDate

/**
 * 全局「今天」。
 *
 * 页面如果各自 `remember { Dates.today() }`，隔夜或跨月不重启就会一直用昨天：
 * 首页「本月」、报告当期、提醒到期都会错（审计 B10）。这里把日期收成一份 Compose 状态，
 * 由 `MainActivity` 在回前台和本地零点刷新；所有页面读 [current] 即可自动重组。
 */
object Today {

    var current: LocalDate by mutableStateOf(Dates.today())
        private set

    /** 重新读取系统日期；由前台恢复与零点定时器调用。 */
    fun refresh() {
        val today = Dates.today()
        if (today != current) current = today
    }
}
