package com.milenote.android.ui.icons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.theme.MileNoteTheme

/**
 * 图标集预览（全部 24dp / 1.65dp 描边）。用于 Android Studio Compose Preview 快速核对图标外形，
 * 不参与业务逻辑。
 */
@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "MileNoteIcons", widthDp = 420, heightDp = 640, showBackground = true)
@Composable
private fun MileNoteIconsPreview() {
    MileNoteTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                IconSection(
                    title = "底栏（4）",
                    icons = MileNoteIcons.BottomNav,
                )
                IconSection(
                    title = "九类费用（固定顺序）",
                    icons = MileNoteIcons.Category,
                )
                IconSection(
                    title = "操作",
                    icons = listOf(
                        MileNoteIcons.Add, MileNoteIcons.Camera, MileNoteIcons.Import,
                        MileNoteIcons.Export, MileNoteIcons.Delete, MileNoteIcons.Edit,
                        MileNoteIcons.Search, MileNoteIcons.Settings, MileNoteIcons.Check,
                        MileNoteIcons.CheckCircle, MileNoteIcons.ChevronRight, MileNoteIcons.ChevronDown,
                        MileNoteIcons.Back, MileNoteIcons.Forward, MileNoteIcons.Share,
                        MileNoteIcons.Calendar, MileNoteIcons.Bell, MileNoteIcons.Car,
                        MileNoteIcons.Road, MileNoteIcons.Wallet, MileNoteIcons.Chart,
                        MileNoteIcons.FilterList, MileNoteIcons.Lock, MileNoteIcons.Visibility,
                        MileNoteIcons.Location, MileNoteIcons.Sparkle, MileNoteIcons.Info,
                        MileNoteIcons.Warning, MileNoteIcons.Error, MileNoteIcons.Close,
                        MileNoteIcons.MoreVert, MileNoteIcons.Minus, MileNoteIcons.Plus,
                        MileNoteIcons.Person, MileNoteIcons.PersonFilled,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconSection(title: String, icons: List<ImageVector>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icons.forEach { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = icon.name.substringAfterLast('.'),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
