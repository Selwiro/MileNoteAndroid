package com.milenote.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 里程志配色系统（Material 3 Expressive）。
 *
 * 数据来源：`outputs/Android/research/03-android-design-spec.md` §2。
 *
 * - 5 套皮肤：里程蓝（品牌默认，seed `#55758b`）/ 森林绿 / 丁香紫 / 暖日橙 / 石墨灰。
 * - 每套皮肤各一套完整的浅色 + 深色 [ColorScheme]（48 个 M3 角色全部显式赋值，
 *   包括 `surfaceContainer*`、`surfaceDim/Bright`、`outline*`、`inverse*`、`scrim`、`surfaceTint`，
 *   避免 Material 默认紫色泄漏）。
 * - **语义色与分类色不随皮肤变化**（见 [MileNoteSemanticColors]、[categoryColors]），
 *   保证账本/图表含义在所有皮肤下保持同义。
 * - hero 渐变沿用 Web 版 `--hero → --hero2`，见 [heroGradient]。
 */

// ---------------------------------------------------------------------------------------------
// 内部建模：每套皮肤 = primary/secondary/tertiary 三个色族（浅/深各 4 角色）+ 两套中性色。
// ---------------------------------------------------------------------------------------------

/** 一个色族（primary/secondary/tertiary）的浅色与深色 4 角色。 */
private data class Family(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val primaryDark: Long,
    val onPrimaryDark: Long,
    val primaryContainerDark: Long,
    val onPrimaryContainerDark: Long,
)

/** 中性色族（surface 系列与描边）。浅/深各一套。 */
private data class Neutral(
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val outline: Long,
    val outlineVariant: Long,
    val surfaceDim: Long,
    val surfaceBright: Long,
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val inverseSurface: Long,
    val inverseOnSurface: Long,
)

/** 一套皮肤的全部原料：三个色族 + 浅/深中性色 + hero 渐变。 */
private data class Skin(
    val primary: Family,
    val secondary: Family,
    val tertiary: Family,
    val light: Neutral,
    val dark: Neutral,
    val heroLight: Pair<Long, Long>,
    val heroDark: Pair<Long, Long>,
)

// 语义色（全皮肤一致，见设计规范 §2.5）。
private val ErrorLight = 0xFFB3261EL
private val OnErrorLight = 0xFFFFFFFFL
private val ErrorContainerLight = 0xFFF9DEDCL
private val OnErrorContainerLight = 0xFF410E0BL
private val ErrorDark = 0xFFF2B8B5L
private val OnErrorDark = 0xFF601410L
private val ErrorContainerDark = 0xFF8C1D18L
private val OnErrorContainerDark = 0xFFF9DEDCL

private fun color(value: Long): Color = Color(value)

// ---------------------------------------------------------------------------------------------
// 五套皮肤原料。
// ---------------------------------------------------------------------------------------------

private val BlueSkin = Skin(
    primary = Family(
        primary = 0xFF446378, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFD4E5F2, onPrimaryContainer = 0xFF0C1E29,
        primaryDark = 0xFFB3CADB, onPrimaryDark = 0xFF1C3343,
        primaryContainerDark = 0xFF2E4A5D, onPrimaryContainerDark = 0xFFD4E5F2,
    ),
    secondary = Family(
        primary = 0xFF456373, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFD5E6EF, onPrimaryContainer = 0xFF0D1E26,
        primaryDark = 0xFFB4CBD7, onPrimaryDark = 0xFF1D343F,
        primaryContainerDark = 0xFF304B59, onPrimaryContainerDark = 0xFFD5E6EF,
    ),
    tertiary = Family(
        primary = 0xFF59661E, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFDFE8C4, onPrimaryContainer = 0xFF191F00,
        primaryDark = 0xFFC1CD9D, onPrimaryDark = 0xFF000000,
        primaryContainerDark = 0xFF424D05, onPrimaryContainerDark = 0xFFDFE8C4,
    ),
    light = Neutral(
        surface = 0xFFF6FAFC, onSurface = 0xFF191C1E,
        surfaceVariant = 0xFFDDE3E8, onSurfaceVariant = 0xFF42484B,
        outline = 0xFF72787C, outlineVariant = 0xFFC1C7CC,
        surfaceDim = 0xFFD7DADD, surfaceBright = 0xFFF6FAFC,
        surfaceContainerLowest = 0xFFFFFFFF, surfaceContainerLow = 0xFFF1F4F7,
        surfaceContainer = 0xFFEBEEF1, surfaceContainerHigh = 0xFFE5E9EB,
        surfaceContainerHighest = 0xFFE0E3E5,
        inverseSurface = 0xFF2E3133, inverseOnSurface = 0xFFEEF1F4,
    ),
    dark = Neutral(
        surface = 0xFF121416, onSurface = 0xFFE0E3E5,
        surfaceVariant = 0xFF42484B, onSurfaceVariant = 0xFFC1C7CC,
        outline = 0xFF8C9296, outlineVariant = 0xFF42484B,
        surfaceDim = 0xFF121416, surfaceBright = 0xFF373A3C,
        surfaceContainerLowest = 0xFF0E1012, surfaceContainerLow = 0xFF191C1E,
        surfaceContainer = 0xFF1D2022, surfaceContainerHigh = 0xFF282A2C,
        surfaceContainerHighest = 0xFF323537,
        inverseSurface = 0xFFE0E3E5, inverseOnSurface = 0xFF2E3133,
    ),
    heroLight = 0xFF354F61L to 0xFF69899EL,
    heroDark = 0xFF22394AL to 0xFF4A6A80L,
)

private val ForestSkin = Skin(
    primary = Family(
        primary = 0xFF376A54, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFCFEADC, onPrimaryContainer = 0xFF082018,
        primaryDark = 0xFF9DD2B8, onPrimaryDark = 0xFF003824,
        primaryContainerDark = 0xFF1E523C, onPrimaryContainerDark = 0xFFB9EFD4,
    ),
    secondary = Family(
        primary = 0xFF4D692C, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFE2EBC8, onPrimaryContainer = 0xFF131F00,
        primaryDark = 0xFFB6CF8E, onPrimaryDark = 0xFF233600,
        primaryContainerDark = 0xFF364E16, onPrimaryContainerDark = 0xFFD2E9A9,
    ),
    tertiary = Family(
        primary = 0xFF7A571E, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFFFDDB8, onPrimaryContainer = 0xFF2A1700,
        primaryDark = 0xFFE5C086, onPrimaryDark = 0xFF422C00,
        primaryContainerDark = 0xFF5E4210, onPrimaryContainerDark = 0xFFFFDDB8,
    ),
    light = Neutral(
        surface = 0xFFF7FBF6, onSurface = 0xFF191D1A,
        surfaceVariant = 0xFFDFE5DA, onSurfaceVariant = 0xFF424940,
        outline = 0xFF72796F, outlineVariant = 0xFFC2C9BD,
        surfaceDim = 0xFFD7DBD3, surfaceBright = 0xFFF7FBF6,
        surfaceContainerLowest = 0xFFFFFFFF, surfaceContainerLow = 0xFFF1F5EE,
        surfaceContainer = 0xFFEBEFE9, surfaceContainerHigh = 0xFFE5E9E3,
        surfaceContainerHighest = 0xFFE0E4DE,
        inverseSurface = 0xFF2E322C, inverseOnSurface = 0xFFEFF1EA,
    ),
    dark = Neutral(
        surface = 0xFF0F1512, onSurface = 0xFFDFE4DD,
        surfaceVariant = 0xFF424940, onSurfaceVariant = 0xFFC2C9BD,
        outline = 0xFF8C9388, outlineVariant = 0xFF424940,
        surfaceDim = 0xFF0F1512, surfaceBright = 0xFF353B36,
        surfaceContainerLowest = 0xFF0A0F0C, surfaceContainerLow = 0xFF171D19,
        surfaceContainer = 0xFF1B211C, surfaceContainerHigh = 0xFF252B26,
        surfaceContainerHighest = 0xFF303631,
        inverseSurface = 0xFFDFE4DD, inverseOnSurface = 0xFF2C322C,
    ),
    heroLight = 0xFF1F3D32L to 0xFF3D6550L,
    heroDark = 0xFF142B23L to 0xFF2C4B3BL,
)

private val PurpleSkin = Skin(
    primary = Family(
        primary = 0xFF68508C, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFE8DCFF, onPrimaryContainer = 0xFF250B45,
        primaryDark = 0xFFD4BBFA, onPrimaryDark = 0xFF3A2460,
        primaryContainerDark = 0xFF513A75, onPrimaryContainerDark = 0xFFE8DCFF,
    ),
    secondary = Family(
        primary = 0xFF64567D, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFEADDF6, onPrimaryContainer = 0xFF1F1734,
        primaryDark = 0xFFCFC0E8, onPrimaryDark = 0xFF352C47,
        primaryContainerDark = 0xFF4C4261, onPrimaryContainerDark = 0xFFEADDF6,
    ),
    tertiary = Family(
        primary = 0xFF2B6A67, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFCCE8E4, onPrimaryContainer = 0xFF00201E,
        primaryDark = 0xFF96D0CB, onPrimaryDark = 0xFF003734,
        primaryContainerDark = 0xFF0F4F4C, onPrimaryContainerDark = 0xFFCCE8E4,
    ),
    light = Neutral(
        surface = 0xFFFAF8FE, onSurface = 0xFF1C1B20,
        surfaceVariant = 0xFFE6E1EC, onSurfaceVariant = 0xFF48454E,
        outline = 0xFF79757F, outlineVariant = 0xFFC9C4CF,
        surfaceDim = 0xFFDBD9DF, surfaceBright = 0xFFFAF8FE,
        surfaceContainerLowest = 0xFFFFFFFF, surfaceContainerLow = 0xFFF4F2F8,
        surfaceContainer = 0xFFEEECF2, surfaceContainerHigh = 0xFFE8E6EC,
        surfaceContainerHighest = 0xFFE3E1E7,
        inverseSurface = 0xFF303036, inverseOnSurface = 0xFFF3EFF7,
    ),
    dark = Neutral(
        surface = 0xFF131218, onSurface = 0xFFE6E1E9,
        surfaceVariant = 0xFF48454E, onSurfaceVariant = 0xFFC9C4CF,
        outline = 0xFF938F99, outlineVariant = 0xFF48454E,
        surfaceDim = 0xFF131218, surfaceBright = 0xFF39383F,
        surfaceContainerLowest = 0xFF0E0D13, surfaceContainerLow = 0xFF1B1A20,
        surfaceContainer = 0xFF201F25, surfaceContainerHigh = 0xFF2A2930,
        surfaceContainerHighest = 0xFF35343A,
        inverseSurface = 0xFFE6E1E9, inverseOnSurface = 0xFF303036,
    ),
    heroLight = 0xFF3A2D55L to 0xFF6E598AL,
    heroDark = 0xFF2A2040L to 0xFF523F6BL,
)

private val OrangeSkin = Skin(
    primary = Family(
        primary = 0xFF864F1A, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFFCDCC2, onPrimaryContainer = 0xFF2D1600,
        primaryDark = 0xFFFAB98A, onPrimaryDark = 0xFF4C2600,
        primaryContainerDark = 0xFF6A3A0A, onPrimaryContainerDark = 0xFFFCDCC2,
    ),
    secondary = Family(
        primary = 0xFF7B552F, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFF3DFC8, onPrimaryContainer = 0xFF2C1704,
        primaryDark = 0xFFE0C3A5, onPrimaryDark = 0xFF422B16,
        primaryContainerDark = 0xFF5A4228, onPrimaryContainerDark = 0xFFF3DFC8,
    ),
    tertiary = Family(
        primary = 0xFF4A6845, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFCCE8C4, onPrimaryContainer = 0xFF072107,
        primaryDark = 0xFFAFCEA7, onPrimaryDark = 0xFF1B3818,
        primaryContainerDark = 0xFF334F2F, onPrimaryContainerDark = 0xFFCCE8C4,
    ),
    light = Neutral(
        surface = 0xFFFDF8F3, onSurface = 0xFF201B17,
        surfaceVariant = 0xFFE9E1D8, onSurfaceVariant = 0xFF4C453D,
        outline = 0xFF7E766C, outlineVariant = 0xFFCEC5B9,
        surfaceDim = 0xFFDDD7D0, surfaceBright = 0xFFFDF8F3,
        surfaceContainerLowest = 0xFFFFFFFF, surfaceContainerLow = 0xFFF7F2EC,
        surfaceContainer = 0xFFF1ECE5, surfaceContainerHigh = 0xFFEBE6DF,
        surfaceContainerHighest = 0xFFE5E0D9,
        inverseSurface = 0xFF35302B, inverseOnSurface = 0xFFF8EFE6,
    ),
    dark = Neutral(
        surface = 0xFF17130F, onSurface = 0xFFEAE1D9,
        surfaceVariant = 0xFF4C453D, onSurfaceVariant = 0xFFCEC5B9,
        outline = 0xFF978F84, outlineVariant = 0xFF4C453D,
        surfaceDim = 0xFF17130F, surfaceBright = 0xFF3C3731,
        surfaceContainerLowest = 0xFF120E0A, surfaceContainerLow = 0xFF1F1B17,
        surfaceContainer = 0xFF24201B, surfaceContainerHigh = 0xFF2E2A25,
        surfaceContainerHighest = 0xFF39342F,
        inverseSurface = 0xFFEAE1D9, inverseOnSurface = 0xFF35302B,
    ),
    heroLight = 0xFF533A2CL to 0xFF926B4BL,
    heroDark = 0xFF3B291FL to 0xFF6E4E36L,
)

private val GraphiteSkin = Skin(
    primary = Family(
        primary = 0xFF4D6270, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFD8E4ED, onPrimaryContainer = 0xFF091E29,
        primaryDark = 0xFFB4CBD7, onPrimaryDark = 0xFF1F3340,
        primaryContainerDark = 0xFF354A57, onPrimaryContainerDark = 0xFFD8E4ED,
    ),
    secondary = Family(
        primary = 0xFF53606A, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFDAE4EA, onPrimaryContainer = 0xFF101D25,
        primaryDark = 0xFFBFCCD5, onPrimaryDark = 0xFF27333C,
        primaryContainerDark = 0xFF3D4A54, onPrimaryContainerDark = 0xFFDAE4EA,
    ),
    tertiary = Family(
        primary = 0xFF4A655F, onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFCFE9E2, onPrimaryContainer = 0xFF00201B,
        primaryDark = 0xFFB3D0C8, onPrimaryDark = 0xFF1D3530,
        primaryContainerDark = 0xFF324B45, onPrimaryContainerDark = 0xFFCFE9E2,
    ),
    light = Neutral(
        surface = 0xFFF7F9FB, onSurface = 0xFF191C1E,
        surfaceVariant = 0xFFDFE3E6, onSurfaceVariant = 0xFF43474A,
        outline = 0xFF73787B, outlineVariant = 0xFFC3C7CA,
        surfaceDim = 0xFFD8DBDD, surfaceBright = 0xFFF7F9FB,
        surfaceContainerLowest = 0xFFFFFFFF, surfaceContainerLow = 0xFFF1F4F6,
        surfaceContainer = 0xFFEBEEF0, surfaceContainerHigh = 0xFFE5E8EA,
        surfaceContainerHighest = 0xFFE0E3E5,
        inverseSurface = 0xFF2E3133, inverseOnSurface = 0xFFEFF1F3,
    ),
    dark = Neutral(
        surface = 0xFF111416, onSurface = 0xFFE0E3E5,
        surfaceVariant = 0xFF43474A, onSurfaceVariant = 0xFFC3C7CA,
        outline = 0xFF8D9194, outlineVariant = 0xFF43474A,
        surfaceDim = 0xFF111416, surfaceBright = 0xFF373A3C,
        surfaceContainerLowest = 0xFF0C0F10, surfaceContainerLow = 0xFF191C1E,
        surfaceContainer = 0xFF1D2022, surfaceContainerHigh = 0xFF272A2B,
        surfaceContainerHighest = 0xFF323536,
        inverseSurface = 0xFFE0E3E5, inverseOnSurface = 0xFF2E3133,
    ),
    heroLight = 0xFF273C49L to 0xFF516976L,
    heroDark = 0xFF1B2A35L to 0xFF3C515DL,
)

private fun Skin.lightScheme(): ColorScheme = lightColorScheme(
    primary = color(primary.primary),
    onPrimary = color(primary.onPrimary),
    primaryContainer = color(primary.primaryContainer),
    onPrimaryContainer = color(primary.onPrimaryContainer),
    inversePrimary = color(primary.primaryDark),
    secondary = color(secondary.primary),
    onSecondary = color(secondary.onPrimary),
    secondaryContainer = color(secondary.primaryContainer),
    onSecondaryContainer = color(secondary.onPrimaryContainer),
    tertiary = color(tertiary.primary),
    onTertiary = color(tertiary.onPrimary),
    tertiaryContainer = color(tertiary.primaryContainer),
    onTertiaryContainer = color(tertiary.onPrimaryContainer),
    error = color(ErrorLight),
    onError = color(OnErrorLight),
    errorContainer = color(ErrorContainerLight),
    onErrorContainer = color(OnErrorContainerLight),
    background = color(light.surface),
    onBackground = color(light.onSurface),
    surface = color(light.surface),
    onSurface = color(light.onSurface),
    surfaceVariant = color(light.surfaceVariant),
    onSurfaceVariant = color(light.onSurfaceVariant),
    surfaceTint = color(primary.primary),
    surfaceDim = color(light.surfaceDim),
    surfaceBright = color(light.surfaceBright),
    surfaceContainerLowest = color(light.surfaceContainerLowest),
    surfaceContainerLow = color(light.surfaceContainerLow),
    surfaceContainer = color(light.surfaceContainer),
    surfaceContainerHigh = color(light.surfaceContainerHigh),
    surfaceContainerHighest = color(light.surfaceContainerHighest),
    inverseSurface = color(light.inverseSurface),
    inverseOnSurface = color(light.inverseOnSurface),
    outline = color(light.outline),
    outlineVariant = color(light.outlineVariant),
    scrim = Color.Black,
    primaryFixed = color(primary.primaryContainer),
    primaryFixedDim = color(primary.primaryDark),
    onPrimaryFixed = color(primary.onPrimaryContainer),
    onPrimaryFixedVariant = color(primary.primaryContainerDark),
    secondaryFixed = color(secondary.primaryContainer),
    secondaryFixedDim = color(secondary.primaryDark),
    onSecondaryFixed = color(secondary.onPrimaryContainer),
    onSecondaryFixedVariant = color(secondary.primaryContainerDark),
    tertiaryFixed = color(tertiary.primaryContainer),
    tertiaryFixedDim = color(tertiary.primaryDark),
    onTertiaryFixed = color(tertiary.onPrimaryContainer),
    onTertiaryFixedVariant = color(tertiary.primaryContainerDark),
)

private fun Skin.darkScheme(): ColorScheme = darkColorScheme(
    primary = color(primary.primaryDark),
    onPrimary = color(primary.onPrimaryDark),
    primaryContainer = color(primary.primaryContainerDark),
    onPrimaryContainer = color(primary.onPrimaryContainerDark),
    inversePrimary = color(primary.primary),
    secondary = color(secondary.primaryDark),
    onSecondary = color(secondary.onPrimaryDark),
    secondaryContainer = color(secondary.primaryContainerDark),
    onSecondaryContainer = color(secondary.onPrimaryContainerDark),
    tertiary = color(tertiary.primaryDark),
    onTertiary = color(tertiary.onPrimaryDark),
    tertiaryContainer = color(tertiary.primaryContainerDark),
    onTertiaryContainer = color(tertiary.onPrimaryContainerDark),
    error = color(ErrorDark),
    onError = color(OnErrorDark),
    errorContainer = color(ErrorContainerDark),
    onErrorContainer = color(OnErrorContainerDark),
    background = color(dark.surface),
    onBackground = color(dark.onSurface),
    surface = color(dark.surface),
    onSurface = color(dark.onSurface),
    surfaceVariant = color(dark.surfaceVariant),
    onSurfaceVariant = color(dark.onSurfaceVariant),
    surfaceTint = color(primary.primaryDark),
    surfaceDim = color(dark.surfaceDim),
    surfaceBright = color(dark.surfaceBright),
    surfaceContainerLowest = color(dark.surfaceContainerLowest),
    surfaceContainerLow = color(dark.surfaceContainerLow),
    surfaceContainer = color(dark.surfaceContainer),
    surfaceContainerHigh = color(dark.surfaceContainerHigh),
    surfaceContainerHighest = color(dark.surfaceContainerHighest),
    inverseSurface = color(dark.inverseSurface),
    inverseOnSurface = color(dark.inverseOnSurface),
    outline = color(dark.outline),
    outlineVariant = color(dark.outlineVariant),
    scrim = Color.Black,
    // Fixed 角色在浅/深色下保持相同值（Material 3 规范）。
    primaryFixed = color(primary.primaryContainer),
    primaryFixedDim = color(primary.primaryDark),
    onPrimaryFixed = color(primary.onPrimaryContainer),
    onPrimaryFixedVariant = color(primary.primaryContainerDark),
    secondaryFixed = color(secondary.primaryContainer),
    secondaryFixedDim = color(secondary.primaryDark),
    onSecondaryFixed = color(secondary.onPrimaryContainer),
    onSecondaryFixedVariant = color(secondary.primaryContainerDark),
    tertiaryFixed = color(tertiary.primaryContainer),
    tertiaryFixedDim = color(tertiary.primaryDark),
    onTertiaryFixed = color(tertiary.onPrimaryContainer),
    onTertiaryFixedVariant = color(tertiary.primaryContainerDark),
)

private fun AppTheme.skin(): Skin = when (this) {
    AppTheme.BLUE -> BlueSkin
    AppTheme.FOREST -> ForestSkin
    AppTheme.PURPLE -> PurpleSkin
    AppTheme.ORANGE -> OrangeSkin
    AppTheme.GRAPHITE -> GraphiteSkin
}

/** 皮肤对应的浅色 [ColorScheme]。 */
val AppTheme.lightColorScheme: ColorScheme
    get() = skin().lightScheme()

/** 皮肤对应的深色 [ColorScheme]。 */
val AppTheme.darkColorScheme: ColorScheme
    get() = skin().darkScheme()

/** 按深浅色返回皮肤对应的 [ColorScheme]。 */
fun AppTheme.colorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) darkColorScheme else lightColorScheme

// ---------------------------------------------------------------------------------------------
// 语义色（全皮肤一致）。
// ---------------------------------------------------------------------------------------------

/** 一对语义色：前景（文字/图标）与容器底色。 */
data class SemanticColorPair(val content: Color, val container: Color)

/** 全皮肤统一的语义色。通过 [LocalMileNoteSemanticColors] 获取当前深浅色版本。 */
data class MileNoteSemanticColors(
    /** 支出（默认金额用墨色，不用红色）。 */
    val expense: SemanticColorPair,
    /** 收入 / 退款。 */
    val income: SemanticColorPair,
    /** 警告 / 即将到期（30 天内 / 1000km 内）。 */
    val warning: SemanticColorPair,
    /** 危险 / 过期 / 删除。 */
    val danger: SemanticColorPair,
    /** 成功 / 已加满 / 已保存。 */
    val success: SemanticColorPair,
    /** 信息 / 演示说明。 */
    val info: SemanticColorPair,
    /** 演示模式专属（唯一紫色语义）。 */
    val demo: SemanticColorPair,
)

/** 浅色语义色（设计规范 §2.5）。 */
val LightMileNoteSemanticColors = MileNoteSemanticColors(
    expense = SemanticColorPair(color(0xFF191C1E), color(0xFFFFFFFF)),
    income = SemanticColorPair(color(0xFF1F6E43), color(0xFFD3EFDD)),
    warning = SemanticColorPair(color(0xFF8A5A00), color(0xFFFFE7B8)),
    danger = SemanticColorPair(color(0xFFB3261E), color(0xFFF9DEDC)),
    success = SemanticColorPair(color(0xFF1F6E43), color(0xFFD3EFDD)),
    info = SemanticColorPair(color(0xFF35607F), color(0xFFD6E7F3)),
    demo = SemanticColorPair(color(0xFF6A4E9C), color(0xFFE9DFFA)),
)

/** 深色语义色（设计规范 §2.5）。 */
val DarkMileNoteSemanticColors = MileNoteSemanticColors(
    expense = SemanticColorPair(color(0xFFE0E3E5), color(0xFF191C1E)),
    income = SemanticColorPair(color(0xFF8FD9AC), color(0xFF0B3A22)),
    warning = SemanticColorPair(color(0xFFF5C46B), color(0xFF3F2A00)),
    danger = SemanticColorPair(color(0xFFF2B8B5), color(0xFF8C1D18)),
    success = SemanticColorPair(color(0xFF8FD9AC), color(0xFF0B3A22)),
    info = SemanticColorPair(color(0xFF9CC7E4), color(0xFF16344A)),
    demo = SemanticColorPair(color(0xFFC9B4EC), color(0xFF3B2A5C)),
)

// ---------------------------------------------------------------------------------------------
// 九类费用分类色（固定顺序：油费/保险/保养/过路费/停车费/维修/罚单/汽车用品/汽车美容）。
// 图表实色使用色觉缺陷（CVD）安全的明暗双版本（设计规范 §2.6），不随皮肤变化。
// ---------------------------------------------------------------------------------------------

/** 分类固定顺序（与 Web 版 `CATEGORIES` 一致，不可重排）。 */
val MileNoteCategoryNames: List<String> = listOf(
    "油费", "保险", "保养", "过路费", "停车费", "维修", "罚单", "汽车用品", "汽车美容",
)

/** 浅色图表分类色（≥3:1）。 */
val LightCategoryColors: List<Color> = listOf(
    color(0xFF55758B), // 油费
    color(0xFF3E7F8F), // 保险
    color(0xFF5C8A6A), // 保养
    color(0xFF38566C), // 过路费
    color(0xFF8A7B63), // 停车费
    color(0xFFA0674F), // 维修
    color(0xFFB07A3C), // 罚单
    color(0xFF7A8CA6), // 汽车用品
    color(0xFFA97BB0), // 汽车美容
)

/** 深色图表分类色（≥4.5:1）。 */
val DarkCategoryColors: List<Color> = listOf(
    color(0xFF8FB6CC),
    color(0xFF79C2C8),
    color(0xFF8BC79A),
    color(0xFF6E8FB0),
    color(0xFFC7AE8A),
    color(0xFFD99275),
    color(0xFFE0A45C),
    color(0xFFA9B6C9),
    color(0xFFC79BD0),
)

/**
 * 各皮肤 Web 版的分类色（仅用于图例小色块、分类图标底、导入预览标签等低风险场景）。
 * 图表实色请始终使用 [LightCategoryColors] / [DarkCategoryColors]。
 */
val AppTheme.legacyCategoryColors: List<Color>
    get() = when (this) {
        AppTheme.BLUE -> listOf(
            0xFF55758B, 0xFF7E9EAF, 0xFFAEC5D0, 0xFF38566C, 0xFF94AAB7,
            0xFFC1D3DC, 0xFF6D899D, 0xFFDBE5EA, 0xFFB58AC4,
        )
        AppTheme.FOREST -> listOf(
            0xFF789657, 0xFFB1C78E, 0xFFD1DDA9, 0xFF485F4B, 0xFFD9BC91,
            0xFF9CAE9B, 0xFFC79779, 0xFFE1DACA, 0xFFB58AC4,
        )
        AppTheme.PURPLE -> listOf(
            0xFF8A76BD, 0xFFB7A8D4, 0xFFDBD2EC, 0xFF665780, 0xFFB7A1C5,
            0xFFC6B9D7, 0xFF9175A8, 0xFFE2DCEB, 0xFFB58AC4,
        )
        AppTheme.ORANGE -> listOf(
            0xFFCA8A54, 0xFFDDB289, 0xFFEFCFAD, 0xFF8E6647, 0xFFD7B59B,
            0xFFE4CEBA, 0xFFBB8B71, 0xFFEEE2D4, 0xFFB58AC4,
        )
        AppTheme.GRAPHITE -> listOf(
            0xFF718592, 0xFF9DACB7, 0xFFC4CED5, 0xFF465C6A, 0xFF98A5AA,
            0xFFB5C0C5, 0xFF899AA5, 0xFFDCE2E5, 0xFFB58AC4,
        )
    }.map { color(it) }

// ---------------------------------------------------------------------------------------------
// Hero 渐变（Web `--hero → --hero2`，见设计规范 §2.3）。
// ---------------------------------------------------------------------------------------------

/**
 * Hero 渐变。
 *
 * @property start 渐变起点（左上），@property end 渐变终点（右下）。
 * @property contentColor 压在渐变上的文字/图标默认颜色。
 */
data class HeroGradient(
    val start: Color,
    val end: Color,
    val contentColor: Color = Color(0xFFF4F8FA),
) {
    /** 用于 `Brush.linearGradient` 的颜色列表。 */
    val colors: List<Color> get() = listOf(start, end)
}

/** 皮肤对应的 hero 渐变。 */
fun AppTheme.heroGradient(darkTheme: Boolean): HeroGradient {
    val (start, end) = when (this) {
        AppTheme.BLUE -> if (darkTheme) BlueSkin.heroDark else BlueSkin.heroLight
        AppTheme.FOREST -> if (darkTheme) ForestSkin.heroDark else ForestSkin.heroLight
        AppTheme.PURPLE -> if (darkTheme) PurpleSkin.heroDark else PurpleSkin.heroLight
        AppTheme.ORANGE -> if (darkTheme) OrangeSkin.heroDark else OrangeSkin.heroLight
        AppTheme.GRAPHITE -> if (darkTheme) GraphiteSkin.heroDark else GraphiteSkin.heroLight
    }
    return HeroGradient(color(start), color(end))
}
