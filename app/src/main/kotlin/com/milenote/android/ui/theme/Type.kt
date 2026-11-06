package com.milenote.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 里程志中文字阶（M3 Expressive 15 级 + 15 级 emphasized 变体）。
 *
 * 设计来源：`outputs/Android/research/03-android-design-spec.md` §3.2 / §3.3。
 *
 * 中文落地规则：
 * - 不打包字体，使用系统默认字体（Roboto + Noto Sans CJK SC），`sp` 随系统字号缩放。
 * - `body*` / `label*` 的 letterSpacing 归零（英文 tracking 对中文偏松）；`title*` 保留 0.1–0.15sp。
 * - 正文行高 ≥ 1.5×（bodyLarge 16/24 = 1.5）。
 * - 金额/里程/日期使用 tabular figures（`fontFeatureSettings = "tnum"`），见 [tabular] 与 [moneyLarge] 等扩展。
 */

private val Sans = FontFamily.Default

private fun text(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Float = 0f,
): TextStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

/** 品牌字阶。金额/里程碑建议配合 [tabular] 使用。 */
val MileNoteTypography: Typography = Typography(
    // ---- 15 级基础字阶 ----
    displayLarge = text(57, 64, FontWeight.Normal, -0.25f),
    displayMedium = text(45, 52, FontWeight.Normal),
    displaySmall = text(36, 44, FontWeight.Normal),
    headlineLarge = text(32, 40, FontWeight.Normal),
    headlineMedium = text(28, 36, FontWeight.Normal),
    headlineSmall = text(24, 32, FontWeight.Normal),
    titleLarge = text(22, 28, FontWeight.Normal),
    titleMedium = text(16, 24, FontWeight.Medium, 0.15f),
    titleSmall = text(14, 20, FontWeight.Medium, 0.1f),
    bodyLarge = text(16, 24, FontWeight.Normal),
    bodyMedium = text(14, 20, FontWeight.Normal),
    bodySmall = text(12, 16, FontWeight.Normal),
    labelLarge = text(14, 20, FontWeight.Medium),
    labelMedium = text(12, 16, FontWeight.Medium),
    labelSmall = text(11, 16, FontWeight.Medium),
    // ---- 15 级 emphasized 变体（同字号/行高，字重 +100～+200，用于“表达性”强调） ----
    displayLargeEmphasized = text(57, 64, FontWeight.Medium, -0.25f),
    displayMediumEmphasized = text(45, 52, FontWeight.Medium),
    displaySmallEmphasized = text(36, 44, FontWeight.Medium),
    headlineLargeEmphasized = text(32, 40, FontWeight.Medium),
    headlineMediumEmphasized = text(28, 36, FontWeight.Medium),
    headlineSmallEmphasized = text(24, 32, FontWeight.Medium),
    titleLargeEmphasized = text(22, 28, FontWeight.Medium),
    titleMediumEmphasized = text(16, 24, FontWeight.SemiBold, 0.15f),
    titleSmallEmphasized = text(14, 20, FontWeight.SemiBold, 0.1f),
    bodyLargeEmphasized = text(16, 24, FontWeight.Medium),
    bodyMediumEmphasized = text(14, 20, FontWeight.Medium),
    bodySmallEmphasized = text(12, 16, FontWeight.Medium),
    labelLargeEmphasized = text(14, 20, FontWeight.SemiBold),
    labelMediumEmphasized = text(12, 16, FontWeight.SemiBold),
    labelSmallEmphasized = text(11, 16, FontWeight.SemiBold),
)

/** OpenType `tnum` 特性：等宽数字，列表右对齐金额时不跳动。 */
const val TabularFigures: String = "tnum"

/** 给任意 [TextStyle] 打开 tabular figures。 */
val TextStyle.tabular: TextStyle
    get() = copy(fontFeatureSettings = TabularFigures)

/**
 * 金额/里程排版 token（均带 tabular figures）。
 *
 * 用法：`Text(text = "1,234.56", style = MaterialTheme.typography.moneyLarge)`。
 * 结构约定（设计规范 §3.4）：`¥` 用 labelLarge + `onSurfaceVariant`，数字用下列 token，单位用 labelMedium。
 */
val Typography.moneyLarge: TextStyle
    get() = headlineMedium.tabular // 36sp/44sp 级英雄数字（总览主卡）
val Typography.moneyMedium: TextStyle
    get() = titleLarge.tabular // 22sp/28sp 指标/列表金额
val Typography.moneySmall: TextStyle
    get() = titleMedium.tabular // 16sp/24sp 紧凑金额
val Typography.metricValue: TextStyle
    get() = headlineSmall.tabular // 24sp/32sp 指标卡数值
val Typography.metricValueLarge: TextStyle
    get() = displaySmall.tabular // 36sp/44sp 指标卡强调数值
