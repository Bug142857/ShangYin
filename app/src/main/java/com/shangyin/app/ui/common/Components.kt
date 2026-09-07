package com.shangyin.app.ui.common

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 封面图，加载失败/为空时显示占位；长按弹确认框下载到相册；点击透传 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    /** 是否允许长按下载（默认 true） */
    downloadable: Boolean = true,
    /** 点击回调（导航/查看大图）；null 则不处理点击 */
    onClick: (() -> Unit)? = null,
    /** 自定义长按回调，默认弹下载确认框 */
    onLongPress: ((String) -> Unit)? = null
) {
    val saveRequester = rememberImageSaveRequester()

    // 统一手势处理：combinedClickable 同时处理点击和长按
    val finalModifier = if (downloadable && !url.isNullOrBlank()) {
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = {
                    if (onLongPress != null) onLongPress(url) else saveRequester(url)
                }
            )
    } else if (onClick != null) {
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    } else {
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    }

    if (url.isNullOrBlank()) {
        Box(
            modifier = finalModifier,
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
            modifier = finalModifier
        )
    }
}

/**
 * 图片保存请求器：返回一个 (url) -> Unit 函数，调用后弹"保存图片"确认框，
 * 确认才下载（API 26-28 先申请存储权限）。CoverImage 和大图浏览器共用，行为一致。
 */
@Composable
fun rememberImageSaveRequester(): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadUrl by remember { mutableStateOf<String?>(null) }

    val needRuntimePermission = android.os.Build.VERSION.SDK_INT in 26..28
    val permissionLauncher = if (needRuntimePermission) {
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            val u = downloadUrl
            if (granted && u != null) {
                scope.launch { performDownload(context, u) }
            } else if (!granted) {
                Toast.makeText(context, "存储权限被拒绝，无法保存图片", Toast.LENGTH_LONG).show()
            }
        }
    } else null

    fun start(url: String) {
        if (needRuntimePermission) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) scope.launch { performDownload(context, url) }
            else permissionLauncher?.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            scope.launch { performDownload(context, url) }
        }
    }

    // 保存确认对话框
    downloadUrl?.let { pendingUrl ->
        AlertDialog(
            onDismissRequest = { downloadUrl = null },
            title = { Text("保存图片") },
            text = { Text("是否将这张图片保存到相册？") },
            confirmButton = {
                TextButton(onClick = { downloadUrl = null; start(pendingUrl) }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { downloadUrl = null }) { Text("取消") }
            }
        )
    }

    return { url -> downloadUrl = url }
}

/** 执行下载 + toast 反馈 */
private suspend fun performDownload(context: android.content.Context, url: String) {
    runCatching { com.shangyin.app.ImageDownloader.download(context, url) }
        .onSuccess { name -> Toast.makeText(context, "已保存到相册：$name", Toast.LENGTH_SHORT).show() }
        .onFailure { e -> Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_LONG).show() }
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
 * 全屏图片浏览器（统一入口）：HorizontalPager 左右滑动翻页 + 每页双指缩放/双击放大。
 * - 未放大：大幅度左右滑动切换上一张/下一张（手势交给 Pager），单击关闭
 * - 放大后：单指拖动看图（带边界限制），双击/双指可缩放
 * - 顶部页码指示，右上角 X 关闭
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerDialog(urls: List<String>, initialIndex: Int = 0, onDismiss: () -> Unit) {
    if (urls.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, urls.size - 1),
            initialPageOffsetFraction = 0f,
            pageCount = { urls.size }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val saveRequester = rememberImageSaveRequester()
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIdx ->
                ZoomableImage(
                    urls[pageIdx],
                    onClose = onDismiss,
                    onLongPress = { imgUrl -> saveRequester(imgUrl) }
                )
            }
            // 右上角 X 关闭按钮
            IconButton(
                onClick = onDismiss,
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
            // 顶部页码
            if (urls.size > 1) {
                Text(
                    "${pagerState.currentPage + 1} / ${urls.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp)
                )
            }
        }
    }
}

/**
 * 单张可缩放图片（放在 PhotoViewerDialog 的 Pager 里使用）：
 * - 双指捏合：缩放 1.0x ~ 5.0x（跟手，以双指中心为锚）
 * - 放大后：单指拖动图片（带边界限制，不会拖出黑屏）
 * - 未放大：不消费水平滑动手势 → 外层 HorizontalPager 翻页
 * - 双击：在 1x 和 2.5x 之间切换（单击延迟 280ms 关闭，给双击留窗口）
 */
@Composable
fun ZoomableImage(url: String?, onClose: () -> Unit, onLongPress: ((String) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }
    // 单击延迟关闭任务（双击时取消，修复"首次单击立即关闭导致双击放大永远不触发"）
    var closeJob by remember(url) { mutableStateOf<Job?>(null) }

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
                                } else {
                                    offsetX = 0f; offsetY = 0f
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            if (pointers == 1 && !trackZoom) {
                                val pan = event.calculatePan()
                                totalPanDx += pan.x
                                totalPanDy += pan.y
                                if (scale > 1.01f) {
                                    // 已放大：单指拖动图片（边界限制），消费手势
                                    val maxX = size.width * (scale - 1f) / 2f
                                    val maxY = size.height * (scale - 1f) / 2f
                                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                    offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                    event.changes.forEach { it.consume() }
                                }
                                // 未放大：不消费任何移动事件，水平大幅度滑动交给外层 Pager 翻页；
                                // 若 Pager 赢得手势，本协程会被取消，不会误判成单击关闭。
                                continue
                            }

                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })

                        val total = System.currentTimeMillis() - downMillis
                        val moved = abs(totalPanDx) > 3f || abs(totalPanDy) > 3f || trackZoom
                        // 长按：按住不动超过 450ms 后松手 → 触发保存回调
                        if (!moved && total >= 450 && onLongPress != null && url != null) {
                            onLongPress(url)
                        }
                        if (!moved && total < 300) {
                            if (closeJob?.isActive == true) {
                                // 双击：缩放切换
                                closeJob?.cancel()
                                closeJob = null
                                if (scale > 1.01f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            } else {
                                // 单击：延迟关闭，留出双击识别窗口
                                closeJob = scope.launch {
                                    delay(280)
                                    onClose()
                                }
                            }
                        }
                        if (scale < 1f) {
                            scale = 1f; offsetX = 0f; offsetY = 0f
                        }
                    }
                }
        )
    }
}
