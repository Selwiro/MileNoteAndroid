package com.milenote.android.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 里程志尺寸 token（设计规范 §4.2）。
 *
 * 8dp 基准栅格；所有触控目标 ≥ 48dp；列表行 ≥ 64dp；输入控件 56dp。
 */
object MileNoteDimens {
    // ---- 页面 ----
    /** 页面左右 padding（compact）。medium 24dp / expanded 32dp 由页面按 WindowSizeClass 覆盖。 */
    val PageHorizontal = 16.dp

    /** 区块间距。 */
    val SectionGap = 24.dp

    /** 同组卡片间距。 */
    val CardGap = 12.dp

    /** 卡片内 padding。 */
    val CardPadding = 16.dp

    /** 卡片描边宽度（浅色 1dp `outlineVariant`；深色不描边）。 */
    val CardBorder = 1.dp

    // ---- 触控 ----
    /** 最小触控目标。 */
    val TouchTarget = 48.dp

    /** 列表行最小高（单行 / 双行）。 */
    val RowMinHeight = 64.dp
    val RowMinHeightTwoLine = 72.dp

    /** Outlined 输入控件高度。 */
    val InputHeight = 56.dp

    /** 主按钮最小高 / 小按钮。 */
    val ButtonMinHeight = 48.dp
    val ButtonSmallHeight = 40.dp

    // ---- 结构 ----
    /** 底栏高度（含手势 inset 之外）。 */
    val BottomBarHeight = 80.dp

    /** 页面滚动内容底部留白（底栏高度已由外层 Scaffold 预留，不再重复加大段空白）。 */
    val PageBottom = 16.dp

    /** 顶栏高度（collapsed）。 */
    val TopBarHeight = 64.dp

    /** LargeFlexibleTopAppBar 展开高度。 */
    val LargeTopBarExpandedHeight = 152.dp

    // ---- 组件 ----
    /** 空态插画。 */
    val EmptyIllustration = 160.dp

    /** 分类图标底座。 */
    val CategoryIconSize = 40.dp

    /** BottomSheet / Dialog 内容最大宽（medium/expanded）。 */
    val SheetMaxWidth = 640.dp
}
