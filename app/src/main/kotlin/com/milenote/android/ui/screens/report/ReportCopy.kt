package com.milenote.android.ui.screens.report

import com.milenote.android.domain.calc.ReportKind
import com.milenote.android.i18n.tr

/**
 * 用车报告的共享文案（UX10）。
 *
 * 报告页（`ReportScreen`）和分享海报（`ReportShare`）各写了一份 Compose，标题与指标名
 * 以前是两套：同一个「日均」在页面是 `Daily`、海报是 `Daily avg`，改一处另一处就漂。
 * 这里收口成唯一来源：两边都从 [ReportCopy] 取，改文案只改这一处。
 *
 * 层级写法约定（同时写进 `work/docs/i18n-en-guide.md`）：
 * 页面标题 =「用车报告」，页内周期 tab =「月报 / 年报 / 累计」，
 * 对外表述统一写成「用车报告 · 月报」这种层级（不写成「月报报告」或把 tab 当页面名）。
 */
internal object ReportCopy {

    /** 应用名（海报落款 / 品牌行）。 */
    fun appName(): String = tr("里程志", "MileNote")

    /** 页面 / 海报标题。 */
    fun title(): String = tr("用车报告", "Vehicle report")

    /** 周期 tab（页面顶部三个 tab 与海报的周期标签共用）。 */
    fun tab(kind: ReportKind): String = when (kind) {
        ReportKind.MONTH -> tr("月报", "Month")
        ReportKind.YEAR -> tr("年报", "Year")
        ReportKind.ALL -> tr("累计", "All time")
    }

    /** 支出主数字的标签（随周期变化）。 */
    fun spendLabel(kind: ReportKind): String = when (kind) {
        ReportKind.MONTH -> tr("本月支出", "This month")
        ReportKind.YEAR -> tr("本年支出", "This year")
        ReportKind.ALL -> tr("累计支出", "All time")
    }

    /** 封面三个数：里程 / 每公里 / 日均（或月均）。 */
    fun distanceLabel(): String = tr("里程", "Distance")

    fun perKmLabel(): String = tr("每公里", "Per km")

    fun dailyLabel(): String = tr("日均", "Daily avg")

    fun monthlyLabel(): String = tr("月均", "Monthly")

    /**
     * 累计月均的口径说明（T007）。
     *
     * 月均 = 累计支出 ÷ 首笔至今的自然月数（含没有记录的月份），记录稀疏时看着会偏低；
     * 这行把「摊了几个月、其中几个月有记录」写出来，用户不用猜口径。月报 / 年报不显示。
     */
    fun monthlyAverageNote(spanMonths: Int?, monthsWithData: Int): String? {
        val span = spanMonths?.takeIf { it > 0 } ?: return null
        return tr(
            "月均按 %d 个自然月均摊（其中有记录 %d 个月）",
            "Monthly average spreads spending over %d calendar months (%d with records)",
            span,
            monthsWithData,
        )
    }
}
