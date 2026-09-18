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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.positionChange
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

/** 长按 + 点击 组合手势：
 *  - 按下后立即启动 450ms 协程触发长按
 *  - 移动超 touchSlop → 取消长按
 *  - 手指抬起时如果长按协程还活着 → 判定为 tap */
@Composable
private fun Modifier.longPressAndTapModifier(
    key: String?,
    onTap: () -> Unit,
    onLongPress: () -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    return this.composed {
        pointerInput(key) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var lpJob: Job? = scope.launch { delay(450); onLongPress() }
                do {
                    val ev = awaitPointerEvent()
                    if (lpJob != null && ev.changes.any { c: PointerInputChange ->
                            abs(c.positionChange().x) > viewConfiguration.touchSlop ||
                            abs(c.positionChange().y) > viewConfiguration.touchSlop
                        }) { lpJob?.cancel(); lpJob = null }
                } while (ev.changes.any { it.pressed })
                if (lpJob?.isActive == true) { lpJob?.cancel(); onTap() }
            }
        }
    }
}

/** 仅长按手势（不会吞 tap，长按触发后才 consume） */
@Composable
private fun Modifier.longPressOnlyModifier(
    key: String?,
    onLongPress: () -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    return this.composed {
        pointerInput(key) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var lpJob: Job? = scope.launch { delay(450); onLongPress() }
                do {
                    val ev = awaitPointerEvent()
                    if (lpJob != null && ev.changes.any { c: PointerInputChange ->
                            abs(c.positionChange().x) > viewConfiguration.touchSlop ||
                            abs(c.positionChange().y) > viewConfiguration.touchSlop
                        }) { lpJob?.cancel(); lpJob = null }
                } while (ev.changes.any { it.pressed })
                lpJob?.cancel()
            }
        }
    }
}

/** 封面图：点击 + 长按保存；**默认 downloadable=false**。
 *  保存功能已收敛：小图（列表/卡片/缩略）不挂长按手势，长按保存仅在放大查看（PhotoViewerDialog）中生效。
 *  downloadable 参数保留，仅特殊独立卡片场景使用。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    /** 是否允许长按下载（默认 false，避免与外层 Box.clickable 手势冲突） */
    downloadable: Boolean = false,
    /** 点击回调（导航/查看大图）；null 则不处理点击，事件穿透给外层 */
    onClick: (() -> Unit)? = null
) {
    val saveRequester = rememberImageSaveRequester()

    val finalModifier = when {
        // 有 downloadable 长按保存需求 → 自定义 pointerInput 协程调度
        downloadable && !url.isNullOrBlank() && onClick != null -> modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .longPressAndTapModifier(url, { onClick() }, { saveRequester(url!!) })
        downloadable && !url.isNullOrBlank() -> modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .longPressOnlyModifier(url) { saveRequester(url!!) }
        onClick != null -> modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
        else -> modifier
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
 * - 单章阅读：末尾有占位提示——还有下一章时到末页松手询问是否继续，已是最后一页则提示"已经是最后一页了"
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerDialog(
    urls: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    /** 当前章节名（末页提示用，如"第 3 话"）；配合 hasNextChapter 使用 */
    chapterLabel: String? = null,
    hasNextChapter: Boolean = false,
    onOpenNextChapter: (() -> Unit)? = null
) {
    if (urls.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        // 阅读方向：false=左右翻页（默认，支持双击/双指缩放） / true=上下连续滑动
        var vertical by rememberSaveable { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        // 垂直模式：最后一张图是否已加载完（加载完才允许触发"下一章"，防止图片未加载就被误判到末页）
        var lastImgReady by remember { mutableStateOf(false) }
        // 末尾永远保留一页虚拟占位：有下一章=翻到即询问（复用 Pager 原生翻页手势，必定触发）；
        // 没有下一章=停留显示"已经是最后一页了"（纯告知，无任何跳转）
        val hasNext = hasNextChapter && onOpenNextChapter != null
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, urls.size - 1),
            initialPageOffsetFraction = 0f,
            pageCount = { urls.size + 1 }
        )
        val listState = rememberLazyListState()
        // 切换方向时跳回当前页（LaunchedEffect 首次运行时 jumpIndex 为 null 不动作）
        var jumpIndex by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(vertical) {
            jumpIndex?.let { idx ->
                val i = idx.coerceIn(0, urls.size - 1)
                if (vertical) listState.scrollToItem(i) else pagerState.scrollToPage(i)
                jumpIndex = null
            }
        }
        val saveRequester = rememberImageSaveRequester()

        // 末页询问下一章：末尾追加占位项（列表/Pager），滚动到占位项即触发——
        // 结构化方案，任意手指位置、慢拖快甩都行，不依赖增量累计。
        var showNextPrompt by remember { mutableStateOf(false) }
        var pageZoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.currentPage) {
            pageZoomed = false
            // 翻到"虚拟下一章"页 → 弹窗询问并弹回最后一页（左右翻页模式的末页手势）
            if (hasNext && pagerState.currentPage >= urls.size) {
                showNextPrompt = true
                pagerState.scrollToPage(urls.size - 1)
            }
        }
        // 上下滑动：末页占位项滚入超过一半 → 有下一章弹窗询问并弹回最后一页；
        // 没有下一章则停留，占位项本身就显示"已经是最后一页了"（纯告知，无任何跳转）。
        // 结构化触发：任意手指位置、慢拖或惯性抛掷都成立，与左右模式虚拟页同思路
        LaunchedEffect(vertical, listState, lastImgReady, hasNext) {
            snapshotFlow {
                val info = listState.layoutInfo
                val footer = info.visibleItemsInfo.firstOrNull { it.index >= urls.size }
                if (footer == null) 0f
                else ((info.viewportEndOffset - footer.offset).toFloat() / footer.size)
                    .coerceIn(0f, 1f)
            }.collect { frac ->
                if (frac >= 0.5f && lastImgReady && hasNext && !showNextPrompt) {
                    showNextPrompt = true
                    listState.scrollToItem((urls.size - 1).coerceAtLeast(0))
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (vertical) {
                // 上下连续滑动模式：顶栏占位排版（不悬浮在图片上，信息永远清晰，首图也不会被遮挡）；
                // 列表末尾追加"末页占位"（触发见上方 LaunchedEffect）：有下一章=滚入过半弹窗询问，
                // 没有下一章=停留显示"已经是最后一页了"（纯告知，无任何跳转）。
                // 图片最小高度 240dp 占位——未加载的尾部也有真实高度
                Column(modifier = Modifier.fillMaxSize()) {
                    PhotoViewerTopBar(
                        vertical = true,
                        chapterLabel = chapterLabel,
                        pageCount = urls.size,
                        currentPage = listState.firstVisibleItemIndex,
                        onToggleMode = {
                            jumpIndex = listState.firstVisibleItemIndex
                            vertical = !vertical
                        },
                        onDismiss = onDismiss,
                        onJumpTo = { target -> scope.launch { listState.scrollToItem(target) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 48.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        items(urls.size) { pageIdx ->
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(urls[pageIdx])
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                onSuccess = { if (pageIdx == urls.size - 1) lastImgReady = true },
                                onError = { if (pageIdx == urls.size - 1) lastImgReady = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 240.dp)
                                    .pointerInput(pageIdx) {
                                        detectTapGestures(onLongPress = { saveRequester(urls[pageIdx]) })
                                    }
                            )
                        }
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (hasNext) "松开进入下一章" else "已经是最后一页了",
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { pageIdx ->
                    if (pageIdx >= urls.size) {
                        // 末尾虚拟占位页：有下一章=到达即弹窗并弹回；没有=停留显示"已经是最后一页了"
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (hasNext) "松开进入下一章" else "已经是最后一页了",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        ZoomableImage(
                            urls[pageIdx],
                            onClose = onDismiss,
                            onLongPress = { imgUrl -> saveRequester(imgUrl) },
                            onZoom = { pageZoomed = it }
                        )
                    }
                }
            }
            // 顶部信息栏：上下滑动模式已在 Column 内占位排版；左右翻页模式悬浮在 Pager 上（渐变遮罩）
            if (!vertical) {
                PhotoViewerTopBar(
                    vertical = false,
                    chapterLabel = chapterLabel,
                    pageCount = urls.size,
                    currentPage = pagerState.currentPage,
                    onToggleMode = {
                        jumpIndex = pagerState.currentPage
                        vertical = !vertical
                    },
                    onDismiss = onDismiss,
                    onJumpTo = { target -> scope.launch { pagerState.scrollToPage(target) } },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                )
            }
        }

        // 本章最后一页：询问是否继续查看下一章
        if (showNextPrompt) {
            AlertDialog(
                onDismissRequest = { showNextPrompt = false },
                title = { Text("本章已看完") },
                text = {
                    Text(
                        buildString {
                            append(chapterLabel?.takeIf { it.isNotBlank() } ?: "本章")
                            append(" 已到最后一页，是否继续查看下一章？")
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showNextPrompt = false
                        onOpenNextChapter?.invoke()
                    }) { Text("下一章") }
                },
                dismissButton = {
                    TextButton(onClick = { showNextPrompt = false }) { Text("留在本章") }
                }
            )
        }
    }
}

/** 阅读器顶部信息栏：模式切换 / 章节名 / 页码进度 / 关闭 + 快速跳转滑动条。
 *  vertical=true 时在布局内占位排版（图片从其下方开始，永不遮挡信息与首图）；
 *  vertical=false 时由调用处叠加渐变背景悬浮在 Pager 上。 */
@Composable
private fun PhotoViewerTopBar(
    vertical: Boolean,
    chapterLabel: String?,
    pageCount: Int,
    currentPage: Int,
    onToggleMode: () -> Unit,
    onDismiss: () -> Unit,
    onJumpTo: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 阅读方向切换
            IconButton(onClick = onToggleMode) {
                Icon(
                    if (vertical) Icons.Rounded.SwapHoriz else Icons.Rounded.SwapVert,
                    contentDescription = if (vertical) "切换为左右翻页" else "切换为上下滑动",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            // 章节名
            Text(
                chapterLabel?.takeIf { it.isNotBlank() } ?: "阅读",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 页码进度（左右模式可能停在虚拟占位页，页码收敛到最后一页）
            if (pageCount > 1) {
                Text(
                    "${currentPage.coerceIn(0, pageCount - 1) + 1} / $pageCount",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            // 关闭
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        // 快速跳转滑动条（拖动显示目标页码，松手跳转）
        if (pageCount > 1) {
            var dragging by remember { mutableStateOf(false) }
            var dragPos by remember { mutableStateOf(0) }
            if (dragging) {
                Text(
                    "跳转到第 ${dragPos.coerceIn(0, pageCount - 1) + 1} 页",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            Slider(
                value = (if (dragging) dragPos else currentPage).coerceIn(0, pageCount - 1).toFloat(),
                onValueChange = {
                    dragging = true
                    dragPos = it.toInt()
                },
                onValueChangeFinished = {
                    onJumpTo(dragPos.coerceIn(0, pageCount - 1))
                    dragging = false
                },
                valueRange = 0f..(pageCount - 1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp)
            )
        }
    }
}

/**
 * 全屏图片网格总览（「查看全部」/ 单章图片总览共用）：3 列缩略图，点击进入阅读器放大。
 * images 为 null 时居中显示加载占位（页面底部还有 LoadingPill 进度提示）。
 */
@Composable
fun PhotoGridDialog(
    title: String,
    images: List<String>?,
    onDismiss: () -> Unit
) {
    var openIndex by remember { mutableStateOf(-1) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (images == null) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text("正在获取图片…", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(images.size) { i ->
                        AsyncImage(
                            model = images[i],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(0.75f)
                                .clickable { openIndex = i }
                        )
                    }
                }
                Text(
                    "共 ${images.size} 张 · 点击放大",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
                )
            }
            // 左上角标题（如"查看全部 · 获取中 3/12"或"第 3 话 · 共 45 张"）
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 60.dp, top = 20.dp)
            )
            // 右上角关闭
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
    // 点击缩略图 → 阅读器（后组合的 Dialog 显示在上层）
    if (openIndex >= 0) {
        images?.let {
            PhotoViewerDialog(urls = it, initialIndex = openIndex, onDismiss = { openIndex = -1 })
        }
    }
}

/** 底部居中加载提示胶囊（如"正在获取图片 3/12"），让用户知道点击已生效 */
@Composable
fun LoadingPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xCC1C1B1F), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White)
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
fun ZoomableImage(
    url: String?,
    onClose: () -> Unit,
    onLongPress: ((String) -> Unit)? = null,
    /** 缩放状态变化回调（true=已放大）——阅读器末页手势用它排除放大后拖图误触 */
    onZoom: ((Boolean) -> Unit)? = null
) {
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
                        // 长按已触发标记（触发后吞掉事件，防 Pager 翻页/误判单击）
                        var longPressHandled = false
                        // 长按移动容差：系统 touchSlop，轻微抖动不算移动
                        val slopPx = viewConfiguration.touchSlop
                        // 按下时启动的长按触发协程（450ms 后触发保存，移动/抬起时取消）
                        var longPressJob: Job? = null
                        if (onLongPress != null && url != null) {
                            longPressJob = scope.launch {
                                delay(450)
                                if (!longPressHandled) {
                                    longPressHandled = true
                                    onLongPress(url)
                                }
                            }
                        }

                        do {
                            val event = awaitPointerEvent()

                            // 长按已触发：持续消费所有事件直到松手
                            if (longPressHandled) {
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            val pointers = event.changes.size

                            if (pointers >= 2) {
                                // 双指缩放/拖动：取消长按
                                longPressJob?.cancel()
                                trackZoom = true
                                val zoomDelta = event.calculateZoom()
                                val panDelta = event.calculatePan()
                                totalPanDx += panDelta.x
                                totalPanDy += panDelta.y
                                val newScale = (scale * zoomDelta).coerceIn(1f, 5f)
                                val maxX = size.width * (newScale - 1f) / 2f
                                val maxY = size.height * (newScale - 1f) / 2f
                                scale = newScale
                                onZoom?.invoke(newScale > 1.01f)
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
                                // 移动超容差 → 取消长按
                                if (abs(totalPanDx) > slopPx || abs(totalPanDy) > slopPx) {
                                    longPressJob?.cancel()
                                }
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

                        // 手势结束：长按协程如果还没触发，取消它（手指抬起了）
                        longPressJob?.cancel()

                        val total = System.currentTimeMillis() - downMillis
                        val moved = abs(totalPanDx) > 3f || abs(totalPanDy) > 3f || trackZoom
                        if (!longPressHandled && !moved && total < 300) {
                            if (closeJob?.isActive == true) {
                                // 双击：缩放切换
                                closeJob?.cancel()
                                closeJob = null
                                if (scale > 1.01f) {
                                    scale = 1f; offsetX = 0f; offsetY = 0f
                                    onZoom?.invoke(false)
                                } else {
                                    scale = 2.5f
                                    onZoom?.invoke(true)
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
