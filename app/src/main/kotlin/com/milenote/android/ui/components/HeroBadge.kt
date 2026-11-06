package com.milenote.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.theme.LocalMileNoteHeroGradient
import com.milenote.android.ui.theme.MileNoteShapeTokens

/**
 * Hero 卡上的半透明徽章（如「爱车已陪伴你 128 天」）。
 *
 * @param text 文案；数字部分建议在调用方用 [androidx.compose.ui.text.buildAnnotatedString] 强调。
 * @param icon 可选前置图标。
 */
@Composable
fun HeroBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val contentColor = LocalMileNoteHeroGradient.current.contentColor
    Surface(
        modifier = modifier,
        shape = MileNoteShapeTokens.full,
        color = Color.White.copy(alpha = 0.14f),
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}
