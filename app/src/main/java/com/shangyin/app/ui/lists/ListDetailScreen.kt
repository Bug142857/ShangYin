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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

/**
 * 拖拽排序手势：长按后拖动改变顺序。
 * - 快速抬起 → tap
 * - 长按不动 → long press（删除确认）
 * - 长按 + 拖动 → 拖拽排序
 */
@Composable
private fun dragReorderModifier(
    itemId: Long,
    isListMode: Boolean,
    gridColumns: Int,
    listId: Long,
    currentItemsState: State<List<CollectionItemEntity>>,
    onDragStateChange: (Long?) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit
): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
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
                        val idx = currentItemsState.value.indexOfFirst { it.id == itemId }
                        if (idx >= 0) {
                            val target = idx + dir * cols
                            if (target in currentItemsState.value.indices) {
                                scope.launch { Repo.reorderItem(listId, idx, target) }
                                totalY -= dir * itemHeightPx
                            } else {
                                totalY = 0f
                            }
                        }
                    }
                    // 水平拖动（仅网格模式）→ 同行交换
                    if (!isListMode && abs(totalX) > itemWidthPx * 0.5f) {
                        val dir = if (totalX > 0) 1 else -1
                        val idx = currentItemsState.value.indexOfFirst { it.id == itemId }
                        if (idx >= 0) {
                            val target = idx + dir
                            if (target in currentItemsState.value.indices && idx / cols == target / cols) {
                                scope.launch { Repo.reorderItem(listId, idx, target) }
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
    val currentItems = rememberUpdatedState(items)

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
                        ChildListGridCard(meta) { nav.safeNavigate("list/${meta.list.id}") }
                    }
                    gridItemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                        val isDragging = draggingItemId == item.id
                        GridItemCard(
                            item = item,
                            isEditMode = isEditMode,
                            onRemove = { scope.launch { Repo.removeItemFromList(listId, item.id) } },
                            modifier = if (isEditMode) dragReorderModifier(
                                itemId = item.id,
                                isListMode = false,
                                gridColumns = 3,
                                listId = listId,
                                currentItemsState = currentItems,
                                onDragStateChange = { draggingItemId = it },
                                onTap = {},
                                onLongPress = {}
                            ).graphicsLayer(alpha = if (isDragging) 0.6f else 1f)
                            else Modifier.fillMaxWidth().clickable { nav.safeNavigate("item/${item.id}") }
                        )
                    }
                }
                ListLayoutMode.LIST -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.padding(pad).fillMaxSize()
                ) {
                    items(childLists, key = { "child_${it.list.id}" }) { meta ->
                        ChildListRowCard(meta) { nav.safeNavigate("list/${meta.list.id}") }
                    }
                    itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                        val isDragging = draggingItemId == item.id
                        ItemRowInList(
                            item = item,
                            isEditMode = isEditMode,
                            onRemove = { scope.launch { Repo.removeItemFromList(listId, item.id) } },
                            modifier = if (isEditMode) dragReorderModifier(
                                itemId = item.id,
                                isListMode = true,
                                gridColumns = 1,
                                listId = listId,
                                currentItemsState = currentItems,
                                onDragStateChange = { draggingItemId = it },
                                onTap = {},
                                onLongPress = {}
                            ).graphicsLayer(alpha = if (isDragging) 0.6f else 1f)
                            else Modifier.fillMaxWidth().clickable { nav.safeNavigate("item/${item.id}") }
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

    // 删除清单
    if (showDelete && list != null) {
        val hasChildren = childLists.isNotEmpty()
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除清单") },
            text = {
                Text(
                    buildString {
                        append("删除清单「${list!!.name}」不会删除收藏的条目本身。")
                        if (hasChildren) append("\n⚠️ 该清单下还有 ${childLists.size} 个子清单，将一并删除。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // 先删所有子清单
                        childLists.forEach { Repo.deleteList(it.list) }
                        Repo.deleteList(list!!)
                        showDelete = false
                        nav.safePopBackStack()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
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
private fun ChildListGridCard(meta: ListWithMeta, onClick: () -> Unit) {
    var covers by remember(meta.list.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(meta.list.id) {
        covers = withContext(Dispatchers.IO) { Repo.getListCovers(meta.list.id, 4) }
    }
    val firstChar = meta.list.name.firstOrNull()?.toString() ?: "清"

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onClick)
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
private fun ChildListRowCard(meta: ListWithMeta, onClick: () -> Unit) {
    var covers by remember(meta.list.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(meta.list.id) {
        covers = withContext(Dispatchers.IO) { Repo.getListCovers(meta.list.id, 4) }
    }
    val firstChar = meta.list.name.firstOrNull()?.toString() ?: "清"

    Card(onClick = onClick) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
    onRemove: () -> Unit = {}
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

/** 从本地收藏里挑选条目加入清单 */
@Composable
private fun AddItemToAlertDialog(
    listId: Long,
    existingIds: Set<Long>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showClearOrphan by remember { mutableStateOf(false) }
    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    // 加载所有清单里已添加的条目 ID，过滤掉不显示
    var allListItemIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    LaunchedEffect(Unit) {
        allListItemIds = withContext(Dispatchers.IO) { Repo.getAllListItemIds().toSet() }
    }
    val candidates = remember(allItems, query, allListItemIds) {
        val list = if (query.isBlank()) allItems else allItems.filter {
            it.title.contains(query, ignoreCase = true) || it.subTitle.contains(query, ignoreCase = true)
        }
        // 不显示任何清单里已添加的条目
        list.filter { it.id !in allListItemIds }.take(50)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从收藏中添加") },
        text = {
            Column {
                // 顶部工具栏：搜索 + 清理孤立收藏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索收藏…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { showClearOrphan = true }) {
                        Text("清理", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (allItems.isEmpty()) {
                    Text(
                        "还没有收藏，先去搜索页收藏一些吧",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(candidates, key = { it.id }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CoverImage(
                                url = item.coverUrl,
                                modifier = Modifier.width(36.dp).height(50.dp)
                            )
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            TextButton(onClick = {
                                scope.launch { Repo.addItemToList(listId, item.id) }
                            }) {
                                Text("加入")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )

    // 清理孤立收藏确认
    if (showClearOrphan) {
        AlertDialog(
            onDismissRequest = { showClearOrphan = false },
            title = { Text("清理孤立收藏") },
            text = { Text("删除所有不在任何清单里的收藏？（已在清单里的不会被删）") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val n = Repo.clearOrphanItems()
                        Toast.makeText(context, if (n > 0) "已清理 $n 条孤立收藏" else "没有需要清理的条目", Toast.LENGTH_SHORT).show()
                        showClearOrphan = false
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { showClearOrphan = false }) { Text("取消") } }
        )
    }
}
