package com.shangyin.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** 封面图，加载失败/为空时显示占位 */
@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp
) {
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

/** 豆瓣评分 */
@Composable
fun DoubanRating(rating: Float?, modifier: Modifier = Modifier) {
    if (rating == null || rating <= 0f) return
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.Star,
            contentDescription = null,
            tint = Color(0xFFF5A623),
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = String.format("%.1f", rating),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFB45309)
        )
    }
}

/** 空状态 */
@Composable
fun EmptyView(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = text,
            color = MaterialTheme.colorScheme.outline,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/** 无水波纹点击 */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}
