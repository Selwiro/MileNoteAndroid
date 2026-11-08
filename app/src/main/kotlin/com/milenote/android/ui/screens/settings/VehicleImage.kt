package com.milenote.android.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.milenote.android.R
import com.milenote.android.i18n.tr
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.util.DataUrlImages

/** 车辆图片是否为远程 URL（http/https）。 */
internal fun isRemoteVehicleImage(value: String?): Boolean =
    value != null && (value.startsWith("http://") || value.startsWith("https://"))

/**
 * 统一的车辆图片展示：[Vehicle.image] 三态回退。
 *
 * 1. `http(s)` URL → Coil 异步加载（失败时 Coil 会显示空位，外层再兜底插画）；
 * 2. `data:image/...;base64,` → [DataUrlImages] 内存缓存解码；
 * 3. 空 / 解析失败 → `res` 里的线描车辆插画（drawable-night 自动切深色描边）。
 *
 * @param image 车辆图片（dataURL 或 URL）。
 * @param contentScale `contain` 用 [ContentScale.Fit]、`cover` 用 [ContentScale.Crop]。
 */
@Composable
internal fun VehicleImage(
    image: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    when {
        isRemoteVehicleImage(image) -> {
            AsyncImage(
                model = image,
                contentDescription = tr("车辆图片", "Vehicle image"),
                contentScale = contentScale,
                modifier = modifier,
            )
        }

        DataUrlImages.isDataUrl(image) -> {
            val bitmap = remember(image) { DataUrlImages.decode(image) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = tr("车辆图片", "Vehicle image"),
                    contentScale = contentScale,
                    modifier = modifier,
                )
            } else {
                VehiclePlaceholder(modifier = modifier)
            }
        }

        else -> VehiclePlaceholder(modifier = modifier)
    }
}

/** 车辆缩略图：固定方形圆角容器 + 居中裁切/适配的图片。 */
@Composable
internal fun VehicleThumb(
    image: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MileNoteShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        VehicleImage(
            image = image,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/** 默认车辆插画（与空态插画同一套线描风格，不含文字）。 */
@Composable
private fun VehiclePlaceholder(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.ill_vehicle_placeholder),
        contentDescription = tr("默认车辆插画", "Default vehicle illustration"),
        contentScale = ContentScale.Fit,
        modifier = modifier.padding(6.dp),
    )
}
