package com.milenote.android.ui.screens.report

import com.milenote.android.domain.calc.ReportKind
import com.milenote.android.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * T007 / T008 报告页的纯函数：卡头两行切换、周期胶囊宽度、空态「回到本月」、累计月均口径说明。
 *
 * 这些边界（1.3× / 2.0× 字号、异常字号、期是否当期）只有单测能稳定覆盖，
 * 截图只验最终视觉。
 */
class ReportHeaderLayoutTest {

    private val today = LocalDate.of(2026, 11, 16)

    @Before
    fun setUp() {
        I18n.applyEnglish(false)
    }

    @After
    fun tearDown() {
        I18n.applyEnglish(false)
    }

    // ---------------------------------------------------------------- 卡头布局

    @Test
    fun normal_scale_keeps_single_row() {
        assertFalse(periodHeaderStacks(1f, 411f))
        assertFalse(periodHeaderStacks(1.15f, 411f))
    }

    @Test
    fun large_font_switches_to_two_rows() {
        assertTrue(periodHeaderStacks(1.3f, 411f))
        assertTrue(periodHeaderStacks(1.5f, 411f))
        assertTrue(periodHeaderStacks(2f, 411f))
    }

    @Test
    fun narrow_screen_switches_even_at_normal_font() {
        assertTrue(periodHeaderStacks(1f, 320f))
        assertFalse(periodHeaderStacks(1f, 340f))
    }

    @Test
    fun broken_scales_do_not_crash_or_flip_the_wrong_way() {
        assertFalse(periodHeaderStacks(Float.NaN, 411f))
        assertTrue(periodHeaderStacks(Float.POSITIVE_INFINITY, 411f))
        // 宽度非法（0 / NaN）时保持单行，避免测量阶段来回切换布局。
        assertFalse(periodHeaderStacks(1f, 0f))
        assertFalse(periodHeaderStacks(1f, Float.NaN))
    }

    @Test
    fun period_label_width_is_fixed_and_grows_only_with_font_scale() {
        // 固定宽度：9 月 / 11 月只差数字位数，胶囊与两侧箭头的位置不能跟着变。
        assertEquals(96f, periodLabelWidthDp(1f), 0.01f)
        assertEquals(124.8f, periodLabelWidthDp(1.3f), 0.01f)
        assertEquals(192f, periodLabelWidthDp(2f), 0.01f)
        assertEquals(192f, periodLabelWidthDp(3f), 0.01f)
        assertEquals(96f, periodLabelWidthDp(Float.NaN), 0.01f)
    }

    @Test
    fun period_pill_height_stays_tab_height_and_grows_with_font_capped() {
        // 1.0× 视觉高度 36dp（和三个 tab 齐平），大字号最多长到 54dp；触控行仍是 48dp。
        assertEquals(36f, periodStepperPillHeightDp(1f), 0.01f)
        assertEquals(46.8f, periodStepperPillHeightDp(1.3f), 0.01f)
        assertEquals(54f, periodStepperPillHeightDp(2f), 0.01f)
        assertEquals(54f, periodStepperPillHeightDp(3f), 0.01f)
        assertEquals(36f, periodStepperPillHeightDp(Float.NaN), 0.01f)
        assertEquals(36f, periodStepperPillHeightDp(0f), 0.01f)
    }

    @Test
    fun breakdown_stacks_on_large_font_or_narrow_screen() {
        // 并排：1.0× 且宽度够（411dp）才成立；1.3× 与 320/390dp 都改成竖排。
        assertFalse(breakdownStacks(1f, 411f))
        assertTrue(breakdownStacks(1f, 390f))
        assertTrue(breakdownStacks(1f, 320f))
        assertTrue(breakdownStacks(1.3f, 411f))
        // 异常值按正常字号 / 保持并排处理，避免测量阶段反复切换。
        assertFalse(breakdownStacks(Float.NaN, 411f))
        assertFalse(breakdownStacks(1f, 0f))
        assertFalse(breakdownStacks(1f, Float.NaN))
    }

    // ---------------------------------------------------------------- 空态出口

    @Test
    fun current_period_has_no_back_to_now_action() {
        assertNull(reportNowActionLabel(ReportKind.MONTH, 2026, 11, today))
        assertNull(reportNowActionLabel(ReportKind.YEAR, 2026, 1, today))
        assertNull(reportNowActionLabel(ReportKind.ALL, 2025, 1, today))
    }

    @Test
    fun old_period_offers_back_to_this_month_or_year() {
        assertEquals("回到本月", reportNowActionLabel(ReportKind.MONTH, 2025, 3, today))
        assertEquals("回到今年", reportNowActionLabel(ReportKind.YEAR, 2025, 1, today))
        // 未来月份没有「回到本月」以外的歧义，同样允许快速回来。
        assertEquals("回到本月", reportNowActionLabel(ReportKind.MONTH, 2027, 1, today))
    }

    @Test
    fun back_to_now_labels_are_translated() {
        I18n.applyEnglish(true)
        assertEquals("Back to this month", reportNowActionLabel(ReportKind.MONTH, 2025, 3, today))
        assertEquals("Back to this year", reportNowActionLabel(ReportKind.YEAR, 2025, 1, today))
    }

    // ---------------------------------------------------------------- 月均口径说明

    @Test
    fun monthly_average_note_is_null_without_a_span() {
        assertNull(ReportCopy.monthlyAverageNote(null, 0))
        assertNull(ReportCopy.monthlyAverageNote(0, 0))
    }

    @Test
    fun monthly_average_note_mentions_both_month_counts() {
        val note = ReportCopy.monthlyAverageNote(12, 8)!!
        assertTrue(note.contains("12"))
        assertTrue(note.contains("8"))
        assertTrue(note.contains("自然月"))

        I18n.applyEnglish(true)
        val en = ReportCopy.monthlyAverageNote(12, 8)!!
        assertTrue(en.contains("12"))
        assertTrue(en.contains("8"))
        assertTrue(en.contains("calendar months"))
    }
}
