package com.milenote.android.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable

/**
 * 里程志动效 token（设计规范 §7.1）。
 *
 * 主题默认挂载 [MotionScheme.expressive]（M3 Expressive spring 物理动效），
 * 组件内可直接用 `MaterialTheme.motionScheme.fastSpatialSpec()` 等；
 * 本对象提供同族参数，供需要在 `spring()` 中显式指定、或需要“减少动画”降级时使用。
 */
object MileNoteMotion {
    /** 列表项增删、chip 选中、FAB 收起（~250ms）。 */
    val fastSpatial: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 800f)

    /** 页面/尺寸/位置过渡、BottomSheet 展开（~450ms）。 */
    val defaultSpatial: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** 共享元素、Hero 折叠、容器变形（~650ms）。 */
    val slowSpatial: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 200f)

    /** 颜色/透明度即时反馈（~120ms）。 */
    val fastEffects: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 3800f)

    /** 内容 Crossfade、状态切换（~200ms）。 */
    val defaultEffects: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f)

    /** 图表入场、骨架淡出（~300ms）。 */
    val slowEffects: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 800f)

    /** 系统“移除动画”或应用内减少动画开关开启时的统一降级时长。 */
    const val ReducedMotionMillis: Int = 100

    /** 减少动画时的过渡规格（100ms fade）。 */
    val ReducedMotionSpec: FiniteAnimationSpec<Float> = tween(durationMillis = ReducedMotionMillis)

    /** tab 切换入场/退场（设计规范 §7.2：出 90ms / 入 210ms）。 */
    const val TabExitMillis: Int = 90
    const val TabEnterMillis: Int = 210

    /** 期间切换 Crossfade（不做数字滚动，避免误读金额）。 */
    const val PeriodCrossfadeMillis: Int = 150

    /** Snackbar 停留时长：普通 4s，带撤销 5s。 */
    const val SnackbarMillis: Long = 4_000L
    const val SnackbarWithUndoMillis: Long = 5_000L
}

/** 当前主题的 M3 Expressive MotionScheme（= `MotionScheme.expressive()`）。 */
@Composable
fun mileNoteMotionScheme(): MotionScheme = MotionScheme.expressive()
