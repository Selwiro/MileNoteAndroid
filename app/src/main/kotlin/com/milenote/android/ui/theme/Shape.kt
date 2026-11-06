package com.milenote.android.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 里程志形状系统（设计规范 §4.1）。
 *
 * | Token | dp | 用途 |
 * |---|---|---|
 * | extraSmall | 4 | 内嵌小标签 |
 * | small | 8 | Chip、分段、图例色块 |
 * | medium | 12 | 输入框、小卡片、缩略图 |
 * | large | 16 | 标准卡片、指标卡、列表图片 |
 * | extraLarge | 28 | Hero 卡、BottomSheet、Dialog |
 * | full | 50% | 按钮、SegmentedButton、导航指示器、Chip |
 *
 * MaterialShapes（形变）只用于状态/强调场景，不用于正文卡片。
 */
val MileNoteShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 补充形状 token：M3 [Shapes] 之外需要的胶囊/全圆角。 */
object MileNoteShapeTokens {
    /** 按钮、Chip、SegmentedButton、头像与导航指示器（50%）。 */
    val full: Shape = CircleShape

    /** 28dp Hero / BottomSheet 顶角（与 `shapes.extraLarge` 同值，语义化别名）。 */
    val hero: Shape = RoundedCornerShape(28.dp)

    /** 16dp 标准卡片（与 `shapes.large` 同值，语义化别名）。 */
    val card: Shape = RoundedCornerShape(16.dp)
}
