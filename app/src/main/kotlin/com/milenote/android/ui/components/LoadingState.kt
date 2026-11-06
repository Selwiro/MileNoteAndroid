package com.milenote.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.MileNoteTheme

/**
 * 加载态：M3 Expressive [LoadingIndicator]（形变加载）+ 可选说明文字。
 *
 * 用于首帧加载、解析文件、OCR 识别等场景；局部加载（按钮内）用 18dp 尺寸自行调用
 * `LoadingIndicator(Modifier.size(18.dp))`。
 *
 * @param label 加载说明（如「正在解析文件…」）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoadingIndicator(
            modifier = Modifier.size(48.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 骨架块：`surfaceContainerHigh` 呼吸闪烁（1400ms / 700ms 单程），减少动画时由页面降级为静态块。
 *
 * @param shape 骨架形状，默认 8dp 圆角。
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = MileNoteShapes.small,
    baseAlpha: Float = 0.5f,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = baseAlpha,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier.background(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
            shape = shape,
        ),
    )
}

/** 列表行骨架（记录/加油明细加载用，高 72dp）。 */
@Composable
fun ListRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBlock(modifier = Modifier.size(40.dp), shape = CircleShape)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SkeletonBlock(modifier = Modifier.size(width = 140.dp, height = 14.dp))
            SkeletonBlock(modifier = Modifier.size(width = 100.dp, height = 10.dp))
        }
        SkeletonBlock(modifier = Modifier.size(width = 64.dp, height = 18.dp))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun LoadingStatePreview() {
    MileNoteTheme {
        Column {
            LoadingState(label = "正在解析文件…")
            ListRowSkeleton()
            ListRowSkeleton()
        }
    }
}
