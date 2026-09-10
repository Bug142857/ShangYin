package com.shangyin.app.ui.lists

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.CollectionItemEntity
import com.shangyin.app.data.db.ListWithMeta
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.DoubanRating
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 清单内容布局 */
private enum class ListLayoutMode { GRID, LIST }

/** 拖拽排序手势：长按后拖动改变顺序。
 *  - 快速抬起 → tap
 *  - 长按不动 → long press（删除确认）
 *  - 长按 + 拖动 → 拖拽排序
 * 通用版：currentIdsState 为当前可排序区域的有序 ID 列表，onReorder 执行落库 */
@Composable
private fun dragReorderModifier(
    itemId: Long,
    isListMode: Boolean,
    gridColumns: Int,
    currentIdsState: State<List<Long>>,
    onDragStateChange: (Long?) -> Unit,
    onReorder: suspend (fromIdx: Int, toIdx: Int) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val screenW = LocalConfiguration.current.screenWidthDp
    val itemHeightPx = with(density) {
        (if (isListMode) 64.dp else 180.dp).toPx()
    }
    val itemWidthPx = with(density) {
        val w = (screenW - 56) / gridColumns.coerceAtLeast(1)
        w.dp.toPx()
    }

    return Modifier.pointerInput(itemId) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var state = 0 // 0=initial, 1=longPressed, 2=dragging, -1=cancelled
            var totalY = 0f
            var totalX = 0f
            val lpJob = scope.launch {
                delay(viewConfiguration.longPressTimeoutMillis)
                state = 1
                // 长按触发时震一下，提醒用户"可以拖动了"；进入拖动阶段就不再震
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            do {
                val event = awaitPointerEvent()
                val c = event.changes.first()
                val dy = c.positionChange().y
                val dx = c.positionChange().x

                if (state == 0) {
                    if (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop) {
                        lpJob.cancel()
                        state = -1
                    }
                } else if (state == 1) {
                    if (abs(dy) > viewConfiguration.touchSlop / 2 || abs(dx) > viewConfiguration.touchSlop / 2) {
                        state = 2
                        onDragStateChange(itemId)
                        totalY = 0f
                        totalX = 0f
                        c.consume()
                    }
                }

                if (state == 2) {
                    c.consume()
                    totalY += dy
                    totalX += dx
                    val cols = if (isListMode) 1 else gridColumns

                    // 垂直拖动 → 跨行交换
                    if (abs(totalY) > itemHeightPx * 0.5f) {
                        val dir = if (totalY > 0) 1 else -1
                        val idx = currentIdsState.value.indexOf(itemId)
                        if (idx >= 0) {
                            val target = idx + dir * cols
                            if (target in currentIdsState.value.indices) {
                                scope.launch { onReorder(idx, target) }
                                totalY -= dir * itemHeightPx
                            } else {
                                totalY = 0f
                            }
                        }
                    }
                    // 水平拖动（仅网格模式）→ 同行交换
                    if (!isListMode && abs(totalX) > itemWidthPx * 0.5f) {
                        val dir = if (totalX > 0) 1 else -1
                        val idx = currentIdsState.value.indexOf(itemId)
                        if (idx >= 0) {
                            val target = idx + dir
                            if (target in currentIdsState.value.indices && idx / cols == target / cols) {
                                scope.launch { onReorder(idx, target) }
                                totalX -= dir * itemWidthPx
                            } else {
                                totalX = 0f
                            }
                        }
                    }
                }
            } while (event.changes.any { it.pressed })

            lpJob.cancel()
            when (state) {
                0 -> onTap()           // 快速抬起 → 点击
                1 -> onLongPress()     // 长按不动 → 删除确认
                2 -> onDragStateChange(null) // 拖拽结束
                // -1: 移动取消（滚动），不触发任何操作
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(nav: NavHostController, listId: Long) {
    val scope = rememberCoroutineScope()

    val list by Repo.observeList(listId).collectAsStateWithLifecycle(initialValue = null)
    val items by Repo.observeItemsIn(listId).collectAsStateWithLifecycle(initialValue = emptyList())
    val childLists by Repo.observeSubListsWithMeta(listId).collectAsStateWithLifecycle(initialValue = emptyList())

    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showCreateChild by remember { mutableStateOf(false) }
    var layoutMode by rememberSaveable { mutableStateOf(ListLayoutMode.GRID) }
    var isEditMode by remember { mutableStateOf(false) }
    var draggingItemId by remember { mutableStateOf<Long?>(null) }
    var draggingSubListId by remember { mutableStateOf<Long?>(null) }
    var deleteSubTarget by remember { mutableStateOf<ListWithMeta?>(null) }
    val currentItemIds = rememberUpdatedState(items.map { it.id })
    val currentSubIds = rememberUpdatedState(childLists.map { it.list.id })

    // ---- 音乐清单：点击即播（"音乐"根清单默认列表模式） ----
    val context = androidx.compose.ui.platform.LocalContext.current
    var userToggledLayout by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(list?.id, list?.name) {
        if (!userToggledLayout && list?.parentId == null && list?.name == "音乐") {
            layoutMode = ListLayoutMode.LIST
        }
    }
    var mediaPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var currentPlayId by remember { mutableStateOf<Long?>(null) }
    var playTitle by remember { mutableStateOf("") }
    var playPlaying by remember { mutableStateOf(false) }
    var playPreparing by remember { mutableStateOf(false) }
    var playPos by remember { mutableIntStateOf(0) }
    var playDur by remember { mutableIntStateOf(0) }

    fun releasePlayer() {
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        playPlaying = false
    }
    DisposableEffect(Unit) { onDispose { releasePlayer() } }

    fun playMusic(item: com.shangyin.app.data.db.CollectionItemEntity) {
        // 同一首 → 播放/暂停切换
        if (currentPlayId == item.id) {
            val p = mediaPlayer
            if (p != null && playPlaying) { runCatching { p.pause() }; playPlaying = false }
            else if (p != null) { runCatching { p.start() }; playPlaying = true }
            return
        }
        val url = item.doubanUrl
        if (url.isNullOrBlank()) {
            android.widget.Toast.makeText(context, "该歌曲没有播放直链，请在音乐搜索重新收藏", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        releasePlayer()
        playPreparing = true
        playTitle = if (item.subTitle.isNullOrBlank()) item.title else "${item.title} - ${item.subTitle}"
        val m = android.media.MediaPlayer()
        runCatching {
            // 带上泡椒站的 Cookie/Referer/UA，绕过防盗链
            val headers = mutableMapOf(
                "Referer" to "https://flac.music.hi.cn/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            android.webkit.CookieManager.getInstance()
                .getCookie("https://flac.music.hi.cn/")?.let { headers["Cookie"] = it }
            m.setDataSource(context, android.net.Uri.parse(url), headers)
            m.setOnPreparedListener {
                playDur = it.duration
                runCatching { it.start() }
                playPlaying = true
                playPreparing = false
            }
            m.setOnCompletionListener {
                playPlaying = false
                playPos = 0
                // 自动下一首
                val idx = items.indexOfFirst { it.id == item.id }
                val next = items.getOrNull(idx + 1) ?: items.firstOrNull()
                if (next != null && next.id != item.id) playMusic(next)
            }
            m.setOnErrorListener { _, what, extra ->
                playPreparing = false
                playPlaying = false
                android.widget.Toast.makeText(context, "播放失败（$what/$extra），直链可能已失效", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            m.prepareAsync()
            mediaPlayer = m
            currentPlayId = item.id
        }.onFailure {
            playPreparing = false
            android.widget.Toast.makeText(context, "无法播放：${it.message ?: "链接无效"}", android.widget.Toast.LENGTH_SHORT).show()
            runCatching { m.release() }
        }
    }

    fun skipBy(delta: Int) {
        val cur = currentPlayId ?: return
        val idx = items.indexOfFirst { it.id == cur }
        if (idx < 0) return
        val target = items.getOrNull(idx + delta) ?: return
        playMusic(target)
    }

    // 播放中刷新进度
    LaunchedEffect(playPlaying) {
        while (playPlaying) {
            val p = mediaPlayer
            if (p != null) runCatching {
                playPos = p.currentPosition
                playDur = p.duration
            }
            kotlinx.coroutines.delay(500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(list?.name.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditMode) isEditMode = false else nav.safePopBackStack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (isEditMode) {
                        TextButton(onClick = { isEditMode = false }) { Text("完成") }
                    } else {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (layoutMode == ListLayoutMode.GRID) "切换为列表" else "切换为平铺")
                                },
                                onClick = {
                                    menuOpen = false
                                    layoutMode =
                                        if (layoutMode == ListLayoutMode.GRID) ListLayoutMode.LIST else ListLayoutMode.GRID
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; isEditMode = true }
                            )
                            DropdownMenuItem(
                                text = { Text("添加条目") },
                                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                onClick = { menuOpen = false; nav.safeNavigate("search/$listId") }
                            )
                            DropdownMenuItem(
                                text = { Text("创建子清单") },
                                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                onClick = { menuOpen = false; showCreateChild = true }
                            )
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; showRename = true }
                            )
                            DropdownMenuItem(
                                text = { Text("删除清单") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; showDelete = true }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (currentPlayId != null && !isEditMode) {
                // 音乐清单 mini 播放条
                Surface(shadowElevation = 8.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        IconButton(onClick = { skipBy(-1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首")
                        }
                        IconButton(onClick = {
                            val p = mediaPlayer
                            if (p != null && playPlaying) { runCatching { p.pause() }; playPlaying = false }
                            else if (p != null) { runCatching { p.start() }; playPlaying = true }
                        }, modifier = Modifier.size(40.dp)) {
                            if (playPreparing) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    if (playPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = "播放/暂停",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = { skipBy(1) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "下一首")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                playTitle,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            LinearProgressIndicator(
                                progress = { if (playDur > 0) playPos.toFloat() / playDur else 0f },
                                modifier = Modifier.fillMaxWidth().height(3.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { pad ->
        // 子清单与条目平级混排：子清单排最前
        if (items.isEmpty() && childLists.isEmpty()) {
            Column(Modifier.padding(pad)) {
                EmptyView("清单还是空的\n点右上角菜单 → 添加条目")
            }
        } else {
            when (layoutMode) {
                ListLayoutMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(pad).fillMaxSize()
                ) {
                    gridItems(childLists, key = { "child_${it.list.id}" }) { meta ->
                        val isDragging = draggingSubListId == meta.list.id
                        val scale by animateFloatAsState(if (isDragging) 1.08f else 1f, label = "subScale")
                        ChildListGridCard(
                            meta = meta,
                            isEditMode = isEditMode,
                            onRemove = { deleteSubTarget = meta },
                            modifier = (if (isEditMode) dragReorderModifier(
                                itemId = meta.list.id,
                                isListMode = false,
                                gridColumns = 3,
                                currentIdsState = currentSubIds,
                                onDragStateChange = { draggingSubListId = it },
                                onReorder = { from, to -> Repo.reorderSubList(listId, from, to) },
                                onTap = {},
                                onLongPress = {}
                            ) else Modifier)
                                .graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                    shadowElevation = if (isDragging) 24f else 0f
                                },
                            onClick = { nav.safeNavigate("list/${meta.list.id}") }
                        )
                    }
                    gridItemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                        val isDragging = draggingItemId == item.id
                        val scale by animateFloatAsState(if (isDragging) 1.08f else 1f, label = "scale")
                        GridItemCard(
                            item = item,
                            isEditMode = isEditMode,
                            onRemove = { scope.launch { Repo.removeItemFromList(listId, item.id) } },
                            modifier = (if (isEditMode) dragReorderModifier(
                                itemId = item.id,
                                isListMode = false,
                                gridColumns = 3,
                                currentIdsState = currentItemIds,
                                onDragStateChange = { draggingItemId = it },
                                onReorder = { from, to -> Repo.reorderItem(listId, from, to) },
                                onTap = {},
                                onLongPress = {}
                            ) else Modifier.fillMaxWidth().clickable { nav.safeNavigate("item/${item.id}") })
                                .graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                    shadowElevation = if (isDragging) 24f else 0f
                                }
                        )
                    }
                }
                ListLayoutMode.LIST -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.padding(pad).fillMaxSize()
                ) {
                    items(childLists, key = { "child_${it.list.id}" }) { meta ->
                        val isDragging = draggingSubListId == meta.list.id
                        val scale by animateFloatAsState(if (isDragging) 1.03f else 1f, label = "subScale")
                        ChildListRowCard(
                            meta = meta,
                            isEditMode = isEditMode,
                            onRemove = { deleteSubTarget = meta },
                            modifier = (if (isEditMode) dragReorderModifier(
                                itemId = meta.list.id,
                                isListMode = true,
                                gridColumns = 1,
                                currentIdsState = currentSubIds,
                                onDragStateChange = { draggingSubListId = it },
                                onReorder = { from, to -> Repo.reorderSubList(listId, from, to) },
                                onTap = {},
                                onLongPress = {}
                            ) else Modifier)
                                .graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                    shadowElevation = if (isDragging) 24f else 0f
                                },
                            onClick = { nav.safeNavigate("list/${meta.list.id}") }
                        )
                    }
                    itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                        val isDragging = draggingItemId == item.id
                        val scale by animateFloatAsState(if (isDragging) 1.03f else 1f, label = "scale")
                        val isMusic = item.category == "音乐"
                        ItemRowInList(
                            item = item,
                            isEditMode = isEditMode,
                            onRemove = { scope.launch { Repo.removeItemFromList(listId, item.id) } },
                            onOpenDetail = if (isMusic && !isEditMode) {
                                { nav.safeNavigate("item/${item.id}") }
                            } else null,
                            isCurrentPlaying = item.id == currentPlayId && !isEditMode,
                            modifier = (if (isEditMode) dragReorderModifier(
                                itemId = item.id,
                                isListMode = true,
                                gridColumns = 1,
                                currentIdsState = currentItemIds,
                                onDragStateChange = { draggingItemId = it },
                                onReorder = { from, to -> Repo.reorderItem(listId, from, to) },
                                onTap = {},
                                onLongPress = {}
                            ) else Modifier.fillMaxWidth().clickable {
                                if (isMusic) playMusic(item) else nav.safeNavigate("item/${item.id}")
                            })
                                .graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                    shadowElevation = if (isDragging) 24f else 0f
                                }
                        )
                    }
                }
            }
        }
    } // close Scaffold content lambda

    // 重命名
    if (showRename && list != null) {
        NameListDialog(
            title = "重命名清单",
            initialName = list!!.name,
            onConfirm = { name ->
                scope.launch { Repo.renameList(list!!, name) }
                showRename = false
            },
            onDismiss = { showRename = false }
        )
    }

    // 删除清单（递归删除所有层级子清单）
    if (showDelete && list != null) {
        val hasChildren = childLists.isNotEmpty()
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除清单") },
            text = {
                Text(
                    buildString {
                        append("删除清单「${list!!.name}」不会删除收藏的条目本身。")
                        if (hasChildren) append("\n⚠️ 该清单下还有 ${childLists.size} 个子清单（含其下级），将一并删除。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        Repo.deleteListTree(list!!)
                        showDelete = false
                        nav.safePopBackStack()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    // 删除子清单（编辑模式 × 按钮触发）
    deleteSubTarget?.let { meta ->
        var grandchildCount by remember(meta.list.id) { mutableStateOf(0) }
        LaunchedEffect(meta.list.id) {
            grandchildCount = withContext(Dispatchers.IO) { Repo.countSubLists(meta.list.id) }
        }
        AlertDialog(
            onDismissRequest = { deleteSubTarget = null },
            title = { Text("删除子清单") },
            text = {
                Text(
                    buildString {
                        append("删除子清单「${meta.list.name}」不会删除收藏的条目本身。")
                        if (grandchildCount > 0) append("\n⚠️ 其下还有 $grandchildCount 个下级子清单，将一并删除。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        Repo.deleteListTree(meta.list)
                        deleteSubTarget = null
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteSubTarget = null }) { Text("取消") } }
        )
    }

    // 创建子清单
    if (showCreateChild) {
        NameListDialog(
            title = "创建子清单",
            confirmLabel = "创建",
            onConfirm = { name ->
                scope.launch {
                    Repo.createList(name, parentId = listId)
                    showCreateChild = false
                }
            },
            onDismiss = { showCreateChild = false }
        )
    }
}

/** 子清单网格卡片：和条目同尺寸（2:3 封面），角标区分 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChildListGridCard(
    meta: ListWithMeta,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    onRemove: () -> Unit = {},
    onClick: () -> Unit
) {
    var covers by remember(meta.list.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(meta.list.id) {
        covers = withContext(Dispatchers.IO) { Repo.getListCovers(meta.list.id, 4) }
    }
    val firstChar = meta.list.name.firstOrNull()?.toString() ?: "清"

    Column(
        modifier
            .fillMaxWidth()
            .then(
                // 编辑模式下点击/长按不跳转（交给拖拽手势），非编辑模式正常进入子清单
                if (isEditMode) Modifier
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onClick)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (covers.isNotEmpty()) {
                ChildCoverCollage(covers.take(4))
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        firstChar,
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
            // 左上角"清单"角标，和普通条目区分
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Icon(
                    Icons.Rounded.List,
                    contentDescription = "子清单",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
            // 编辑模式下右上角 × 删除按钮
            if (isEditMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCCFF4444))
                        .clickable { onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Clear,
                        contentDescription = "删除子清单",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            meta.list.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${meta.itemCount} 件",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 子清单列表卡片：和条目行同尺寸 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChildListRowCard(
    meta: ListWithMeta,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    onRemove: () -> Unit = {},
    onClick: () -> Unit
) {
    var covers by remember(meta.list.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(meta.list.id) {
        covers = withContext(Dispatchers.IO) { Repo.getListCovers(meta.list.id, 4) }
    }
    val firstChar = meta.list.name.firstOrNull()?.toString() ?: "清"

    Box(modifier.fillMaxWidth()) {
        if (isEditMode) {
            // 编辑模式：不可点击的 Card，点击/长按交给外层拖拽手势
            Card {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChildRowCover(covers, firstChar)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            meta.list.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "子清单 · ${meta.itemCount} 件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Card(onClick = onClick) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChildRowCover(covers, firstChar)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            meta.list.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "子清单 · ${meta.itemCount} 件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // 编辑模式下右上角 × 删除按钮
        if (isEditMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCCFF4444))
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Clear,
                    contentDescription = "删除子清单",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 子清单列表行封面 */
@Composable
private fun ChildRowCover(covers: List<String>, firstChar: String) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(62.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (covers.isNotEmpty()) {
            ChildCoverCollage(covers.take(4))
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    firstChar,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** 子清单封面拼贴（1~4张封面） */
@Composable
private fun ChildCoverCollage(covers: List<String>) {
    when (covers.size) {
        1 -> coil.compose.AsyncImage(
            model = covers[0], contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        2 -> Row(Modifier.fillMaxSize()) {
            coil.compose.AsyncImage(model = covers[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
            coil.compose.AsyncImage(model = covers[1], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
        }
        3 -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                coil.compose.AsyncImage(model = covers[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                coil.compose.AsyncImage(model = covers[1], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
            }
            coil.compose.AsyncImage(model = covers[2], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                coil.compose.AsyncImage(model = covers[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                coil.compose.AsyncImage(model = covers[1], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                coil.compose.AsyncImage(model = covers[2], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                coil.compose.AsyncImage(model = covers[3], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

/** 平铺（海报网格）卡片 */
@Composable
private fun GridItemCard(
    item: CollectionItemEntity,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    onRemove: () -> Unit = {}
) {
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            CoverImage(
                url = item.coverUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            DoubanRating(item.doubanRating)
        }
        // 编辑模式下右上角 × 删除按钮
        if (isEditMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCCFF4444))
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Clear,
                    contentDescription = "移出",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ItemRowInList(
    item: CollectionItemEntity,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    onRemove: () -> Unit = {},
    onOpenDetail: (() -> Unit)? = null,
    isCurrentPlaying: Boolean = false
) {
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            CoverImage(
                url = item.coverUrl,
                modifier = Modifier.width(40.dp).height(56.dp)
            )
            Column(
                Modifier.weight(1f).padding(start = 10.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrentPlaying) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrentPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DoubanRating(item.doubanRating)
                    if (item.status.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            item.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (onOpenDetail != null) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "查看详情",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(start = 4.dp)
                        .size(18.dp)
                        .rotate(180f)
                        .clickable(onClick = onOpenDetail)
                )
            }
        }
        if (isEditMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCCFF4444))
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Clear,
                    contentDescription = "移出",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
