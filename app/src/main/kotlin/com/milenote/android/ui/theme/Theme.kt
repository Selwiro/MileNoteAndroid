package com.milenote.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 五套皮肤。默认里程蓝，品牌基色取自 Logo 的车身 `#55758b` / 道路 `#7e9eaf`。
 *
 * 皮肤**只替换 ColorScheme 与 hero 渐变**，字阶/形状/动效/分类色/语义色保持不变，
 * 避免图表与状态含义在不同皮肤下漂移（设计规范 §2.3）。
 */
enum class AppTheme(
    /** 设置页展示用的中文名。 */
    val label: String,
    /** 色板预览用主色。 */
    val swatch: Color,
) {
    BLUE("里程蓝", Color(0xFF55758B)),
    FOREST("森林绿", Color(0xFF50846C)),
    PURPLE("丁香紫", Color(0xFF8B72B2)),
    ORANGE("暖日橙", Color(0xFFBA7F4C)),
    GRAPHITE("石墨灰", Color(0xFF697F8E)),
    ;

    companion object {
        /** 由持久化 id 安全还原（大小写不敏感，账本里存 `blue/forest/purple/orange/graphite`）；未知值回退 [BLUE]。 */
        fun fromId(id: String?): AppTheme =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: BLUE
    }
}

/** 当前语义色（支出/收入/警告/危险/成功/信息/演示）。 */
val LocalMileNoteSemanticColors = staticCompositionLocalOf { LightMileNoteSemanticColors }

/** 当前深浅色下的 9 类费用图表色（固定顺序：油费…汽车美容）。 */
val LocalMileNoteCategoryColors = staticCompositionLocalOf { LightCategoryColors }

/** 当前皮肤的 hero 渐变。 */
val LocalMileNoteHeroGradient = staticCompositionLocalOf {
    AppTheme.BLUE.heroGradient(darkTheme = false)
}

/** 当前皮肤（用于设置页回显）。 */
val LocalMileNoteAppTheme = staticCompositionLocalOf { AppTheme.BLUE }

/** 当前是否深色主题（组件用于描边/遮罩等结构差异，避免直接用系统判断）。 */
val LocalMileNoteDarkTheme = staticCompositionLocalOf { false }

/**
 * 里程志主题入口。
 *
 * 用法：
 * ```kotlin
 * MileNoteTheme(appTheme = settings.theme, darkTheme = dark) {
 *     MileNoteApp()
 * }
 * ```
 *
 * @param appTheme 五套皮肤之一，默认 [AppTheme.BLUE]（品牌色，与 Web 版默认一致）。
 * @param darkTheme 是否深色；默认跟随系统。
 * @param content 页面内容。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MileNoteTheme(
    appTheme: AppTheme = AppTheme.BLUE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = remember(appTheme, darkTheme) { appTheme.colorScheme(darkTheme) }
    val semantic = if (darkTheme) DarkMileNoteSemanticColors else LightMileNoteSemanticColors
    val categories = if (darkTheme) DarkCategoryColors else LightCategoryColors
    val hero = remember(scheme, appTheme, darkTheme) { appTheme.heroGradient(darkTheme) }

    CompositionLocalProvider(
        LocalMileNoteSemanticColors provides semantic,
        LocalMileNoteCategoryColors provides categories,
        LocalMileNoteHeroGradient provides hero,
        LocalMileNoteAppTheme provides appTheme,
        LocalMileNoteDarkTheme provides darkTheme,
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            typography = MileNoteTypography,
            shapes = MileNoteShapes,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

/**
 * 便捷访问器：`MileNoteThemeTokens.semantic`、`MileNoteThemeTokens.categoryColors` 等。
 *
 * 页面代码不需要直接 replace CompositionLocal：
 * ```kotlin
 * val warning = MileNoteThemeTokens.semantic.warning
 * val colors = MileNoteThemeTokens.categoryColors
 * ```
 */
object MileNoteThemeTokens {
    /** M3 角色色。 */
    val colors: ColorScheme
        @Composable get() = MaterialTheme.colorScheme

    /** 语义色（状态徽章/提示条/金额正负）。 */
    val semantic: MileNoteSemanticColors
        @Composable get() = LocalMileNoteSemanticColors.current

    /** 9 类费用图表色（顺序固定）。 */
    val categoryColors: List<Color>
        @Composable get() = LocalMileNoteCategoryColors.current

    /** 当前皮肤 hero 渐变。 */
    val heroGradient: HeroGradient
        @Composable get() = LocalMileNoteHeroGradient.current

    /** 当前皮肤。 */
    val appTheme: AppTheme
        @Composable get() = LocalMileNoteAppTheme.current

}

/**
 * 按分类名取图表色；顺序与 [MileNoteCategoryNames] 一致。
 *
 * `维修保养` 归一为 `保养`，未知分类回退到 `onSurfaceVariant`（Web `categoryOf` 规则的精简版）。
 */
@Composable
fun categoryColorOf(category: String): Color {
    val name = if (category == "维修保养") "保养" else category
    val index = MileNoteCategoryNames.indexOf(name)
    return if (index >= 0) {
        MileNoteThemeTokens.categoryColors[index % MileNoteThemeTokens.categoryColors.size]
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
}
