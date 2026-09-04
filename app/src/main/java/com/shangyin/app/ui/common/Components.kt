package com.shangyin.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlin.math.abs

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
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(true)
                .build(),
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

/**
 * 全屏图片查看器：统一 awaitEachGesture 手势处理（避免 split pointerInput 冲突）
 * - 双指捏合：缩放 1.0x ~ 5.0x（跟手，以双指中心为锚）
 * - 放大后：单指可拖动图片（带边界限制，不会拖出黑屏）
 * - 双击：在 1x 和 2.5x 之间切换
 * - 单击：关闭图片查看；右上角还有 X 关闭按钮可点
 */
@Composable
fun ZoomableImage(url: String?, onClose: () -> Unit) {
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }
    // 双击检测必须跨手势循环保持
    var lastTapAt by remember(url) { mutableStateOf(0L) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
                .pointerInput(url) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val downMillis = System.currentTimeMillis()
                        var trackZoom = false
                        var totalPanDx = 0f
                        var totalPanDy = 0f

                        do {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.size

                            if (pointers >= 2) {
                                trackZoom = true
                                val zoomDelta = event.calculateZoom()
                                val panDelta = event.calculatePan()
                                totalPanDx += panDelta.x
                                totalPanDy += panDelta.y
                                val newScale = (scale * zoomDelta).coerceIn(1f, 5f)
                                val maxX = size.width * (newScale - 1f) / 2f
                                val maxY = size.height * (newScale - 1f) / 2f
                                scale = newScale
                                if (newScale > 1.01f) {
                                    offsetX = (offsetX + panDelta.x).coerceIn(-maxX, maxX)
                                    offsetY = (offsetY + panDelta.y).coerceIn(-maxY, maxY)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            if (pointers == 1 && !trackZoom) {
                                val pan = event.calculatePan()
                                totalPanDx += pan.x
                                totalPanDy += pan.y
                                if (scale > 1.01f) {
                                    val maxX = size.width * (scale - 1f) / 2f
                                    val maxY = size.height * (scale - 1f) / 2f
                                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                    offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })

                        val total = System.currentTimeMillis() - downMillis
                        val moved = abs(totalPanDx) > 3f || abs(totalPanDy) > 3f || trackZoom
                        if (!moved && total < 300) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapAt in 100L..350L) {
                                // 双击：缩放切换
                                if (scale > 1.01f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                                lastTapAt = 0L
                            } else {
                                // 单击：直接关闭（X按钮也能关，双保险）
                                lastTapAt = now
                                onClose()
                            }
                        }
                        if (scale < 1f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        }
                    }
                }
        )

        // 右上角关闭按钮（防止单击关闭被双指/拖动误触）
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "关闭",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
