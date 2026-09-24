package com.shangyin.app.ui.lists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.shangyin.app.data.comic.ComicStatus
import com.shangyin.app.data.comic.ComicStatusStore
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.CollectionItemEntity
import com.shangyin.app.data.db.ListWithMeta
import com.shangyin.app.data.music.MusicRepo
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.DoubanRating
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.common.dragReorderModifier
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 清单内容布局 */
private enum class ListLayoutMode { GRID, LIST }

/** 音乐条目的展示顺序：按添加时间（默认）/ 按歌手名称 */
private enum class MusicSortMode { ADDED, ARTIST }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // 布局模式按清单 ID 持久化（key: list_layout_<id>）；从未记过时先按平铺，下面按"是否含音乐条目"定默认值
    val savedLayout = remember(listId) { SettingsStore.listLayout(listId) }
    var layoutMode by remember(listId) {
        mutableStateOf(
            if (savedLayout == SettingsStore.LAYOUT_LIST) ListLayoutMode.LIST else ListLayoutMode.GRID
        )
    }
    var layoutDecided by remember(listId) { mutableStateOf(savedLayout.isNotBlank()) }
    // 音乐排序按清单 ID 持久化（key: list_sort_<id>），默认按添加时间
    var sortMode by remember(listId) {
        mutableStateOf(
            if (SettingsStore.listSort(listId) == SettingsStore.SORT_ARTIST) MusicSortMode.ARTIST
            else MusicSortMode.ADDED
        )
    }
    var isEditMode by remember { mutableStateOf(false) }
    var draggingItemId by remember { mutableStateOf<Long?>(null) }
    var draggingSubListId by remember { mutableStateOf<Long?>(null) }
    var deleteSubTarget by remember { mutableStateOf<ListWithMeta?>(null) }
    // 长按移除：搜索结果行（跨清单，有时就是搜出来删的）
    var deleteSearchTarget by remember { mutableStateOf<com.shangyin.app.data.db.ItemWithOwnerList?>(null) }
    val currentItemIds = rememberUpdatedState(items.map { it.id })
    val currentSubIds = rememberUpdatedState(childLists.map { it.list.id })

    // 清单里是否含音乐条目：决定默认布局 + 排序菜单是否出现
    val hasMusicItems = remember(items) { items.any { it.category == MusicRepo.CATEGORY } }
    /**
     * 是否「音乐清单」：新建时显式选了音乐清单类型，或清单内容里含音乐条目（老清单兼容，不用手改）。
     * 音乐清单与普通清单不是一回事：固定列表布局、没有子清单、不参与拖拽排序。
     */
    val isMusicList = remember(list?.musicList, hasMusicItems) { list?.musicList == true || hasMusicItems }
    // 音乐清单固定列表布局（忽略持久化的布局记忆，因为音乐清单没有切换布局的入口）；
    // 其它清单：首次拿到条目时按内容判定一次，不回写持久化
    LaunchedEffect(listId, items, isMusicList) {
        if (isMusicList) {
            layoutMode = ListLayoutMode.LIST
            layoutDecided = true
        } else if (!layoutDecided && items.isNotEmpty()) {
            layoutMode = ListLayoutMode.GRID
            layoutDecided = true
        }
    }
    // 展示顺序：「更新」= 音乐条目倒序（最新添加在最前），非音乐条目顺序与位置不变；「歌手」= 按歌手名排序
    val displayItems = remember(items, sortMode) {
        if (sortMode == MusicSortMode.ADDED) sortItemsByAddedDesc(items) else sortItemsByArtist(items)
    }
    // 切换排序：写回持久化（key: list_sort_<id>）
    fun selectSort(mode: MusicSortMode) {
        sortMode = mode
        SettingsStore.setListSort(
            listId,
            if (mode == MusicSortMode.ARTIST) SettingsStore.SORT_ARTIST else SettingsStore.SORT_ADDED
        )
    }

    // ---- 清单内搜索：本清单 + 所有层级子清单 ----
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<com.shangyin.app.data.db.ItemWithOwnerList>>(emptyList()) }

    // 系统返回键：编辑/搜索状态先取消当前状态，而不是退出页面
    BackHandler(enabled = isSearching || isEditMode) {
        if (isSearching) {
            isSearching = false
            searchQuery = ""
        } else {
            isEditMode = false
        }
    }

    // 全部清单名映射（搜索结果显示"来自哪个子清单"）
    val allLists by Repo.observeAllLists().collectAsStateWithLifecycle(initialValue = emptyList())
    val listNameById = remember(allLists) { allLists.associate { it.id to it.name } }

    // ---- 音乐条目：点歌直接播（整份清单当播放队列），不进条目详情页 ----
    val context = LocalContext.current
    // Android 13+ 通知需运行时授权：从清单里直接起播也要申请，否则通知栏没有播放控制
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    fun ensureNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val musicSongs = remember(items) {
        items.mapNotNull { com.shangyin.app.data.music.MusicRepo.songOf(it) }
    }
    fun openEntity(entity: CollectionItemEntity) {
        val song = com.shangyin.app.data.music.MusicRepo.songOf(entity)
        if (song != null && musicSongs.isNotEmpty()) {
            val idx = musicSongs.indexOfFirst { it.key == song.key }.coerceAtLeast(0)
            ensureNotifPermission()
            // 点完直接播：底部有迷你播放条（点它才进完整播放页），不自动跳大播放器
            com.shangyin.app.ui.music.MusicPlayback.play(context, musicSongs, idx)
        } else {
            nav.safeNavigate("item/${entity.id}")
        }
    }
    // 数据变化时若正在搜索则重查（避免结果过期）
    LaunchedEffect(isSearching, searchQuery, items.size, childLists.size) {
        if (!isSearching || searchQuery.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(250)
        val q = searchQuery.trim().lowercase()
        searchResults = withContext(Dispatchers.IO) {
            Repo.searchItemsInTree(listId).filter {
                it.item.title.lowercase().contains(q) ||
                    (it.item.subTitle ?: "").lowercase().contains(q)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text("搜索本清单及子清单", style = MaterialTheme.typography.bodyMedium)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                list?.name.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            // 条目数量跟在清单名称右侧（与清单列表页一致）
                            if (items.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${items.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isMusicList) {
                                Spacer(Modifier.width(6.dp))
                                com.shangyin.app.ui.common.MusicListTag()
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearching) {
                            isSearching = false
                            searchQuery = ""
                        } else if (isEditMode) isEditMode = false else nav.safePopBackStack()
                    }) {
                        Icon(
                            if (isSearching) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = if (isSearching) "退出搜索" else "返回"
                        )
                    }
                },
                actions = {
                    if (isEditMode) {
                        TextButton(onClick = { isEditMode = false }) { Text("完成") }
                    } else {
                        IconButton(onClick = { isSearching = !isSearching; if (isSearching) searchQuery = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // 音乐清单固定列表布局、也没有子清单，这两项对它没意义，直接不给
                            if (!isMusicList) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (layoutMode == ListLayoutMode.GRID) "切换为列表" else "切换为平铺")
                                    },
                                    onClick = {
                                        menuOpen = false
                                        val next = if (layoutMode == ListLayoutMode.GRID) ListLayoutMode.LIST
                                        else ListLayoutMode.GRID
                                        layoutMode = next
                                        layoutDecided = true
                                        SettingsStore.setListLayout(
                                            listId,
                                            if (next == ListLayoutMode.LIST) SettingsStore.LAYOUT_LIST
                                            else SettingsStore.LAYOUT_GRID
                                        )
                                    }
                                )
                            }
                            // 编辑：进去后可以逐条移出（音乐清单不参与拖拽排序）
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    isEditMode = true
                                }
                            )
                            if (!isMusicList) {
                                DropdownMenuItem(
                                    text = { Text("创建子清单") },
                                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                    onClick = { menuOpen = false; showCreateChild = true }
                                )
                            }
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
        // 音乐清单里点歌后，页面底部常驻迷你播放条（没在播时不渲染）
        // 本页补上系统导航栏内边距，避免被三键导航遮住（组件本身不动，其它页面另处理）
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
            ) {
                com.shangyin.app.ui.music.MusicMiniPlayer(nav)
            }
        }
    ) { pad ->
        // 搜索模式：显示本清单 + 所有子清单的匹配条目
        if (isSearching) {
            if (searchQuery.isBlank()) {
                Column(Modifier.padding(pad).fillMaxSize()) {
                    EmptyView("输入关键字搜索\n范围包含所有层级的子清单")
                }
            } else if (searchResults.isEmpty()) {
                Column(Modifier.padding(pad).fillMaxSize()) {
                    EmptyView("没有找到「$searchQuery」")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.padding(pad).fillMaxSize()
                ) {
                    item {
                        Text(
                            "找到 ${searchResults.size} 个结果（含子清单）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    items(searchResults, key = { "${it.item.id}_${it.ownerListId}" }) { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = { openEntity(r.item) },
                                    // 长按 → 从所属清单移除（有时就是搜出来删的）
                                    onLongClick = { deleteSearchTarget = r }
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val sub = r.item.subTitle
                                    if (!sub.isNullOrBlank()) {
                                        Text(
                                            sub,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    val ownerName = listNameById[r.ownerListId] ?: ""
                                    Text(
                                        if (ownerName == list?.name) "本清单" else "来自「$ownerName」",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (items.isEmpty() && childLists.isEmpty()) {
            Column(Modifier.padding(pad)) {
                EmptyView("清单还是空的")
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
                    // 排序控件放在最上方，占满整行；grid 的 contentPadding 已提供左右 16dp 边距
                    if (hasMusicItems) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SortModeRow(sortMode = sortMode) { selectSort(it) }
                        }
                    }
                    gridItems(childLists, key = { "child_${it.list.id}" }) { meta ->
                        val isDragging = draggingSubListId == meta.list.id
                        val scale by animateFloatAsState(if (isDragging) 1.08f else 1f, label = "subScale")
                        ChildListGridCard(
                            meta = meta,
                            isEditMode = isEditMode,
                            onRemove = { deleteSubTarget = meta },
                            modifier = (if (isEditMode && !isMusicList) dragReorderModifier(
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
                    gridItemsIndexed(displayItems, key = { _, it -> it.id }) { idx, item ->
                        val isDragging = draggingItemId == item.id
                        val scale by animateFloatAsState(if (isDragging) 1.08f else 1f, label = "scale")
                        GridItemCard(
                            item = item,
                            isEditMode = isEditMode,
                            onRemove = { scope.launch { Repo.removeItemFromList(listId, item.id) } },
                            // 音乐清单不参与拖拽排序（展示顺序完全由「更新 / 歌手」排序决定）
                            modifier = (if (isEditMode && !isMusicList && sortMode == MusicSortMode.ADDED) dragReorderModifier(
                                itemId = item.id,
                                isListMode = false,
                                gridColumns = 3,
                                currentIdsState = currentItemIds,
                                onDragStateChange = { draggingItemId = it },
                                onReorder = { from, to -> Repo.reorderItem(listId, from, to) },
                                onTap = {},
                                onLongPress = {}
                            ) else Modifier.fillMaxWidth().clickable { openEntity(item) })
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
                    // 排序控件放在最上方；list 的 contentPadding 只有纵向，这里补左右 16dp 与 grid 对齐
                    if (hasMusicItems) {
                        item(key = "sort_row") {
                            SortModeRow(
                                sortMode = sortMode,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) { selectSort(it) }
                        }
                    }
                    items(childLists, key = { "child_${it.list.id}" }) { meta ->
                        val isDragging = draggingSubListId == meta.list.id
                        val scale by animateFloatAsState(if (isDragging) 1.03f else 1f, label = "subScale")
                        ChildListRowCard(
                            meta = meta,
                            isEditMode = isEditMode,
                            onRemove = { deleteSubTarget = meta },
                            modifier = (if (isEditMode && !isMusicList) dragReorderModifier(
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
                    itemsIndexed(displayItems, key = { _, it -> it.id }) { idx, item ->
                        val isDragging = draggingItemId == item.id
                        val scale by animateFloatAsState(if (isDragging) 1.03f else 1f, label = "scale")
                        // 音乐条目用专用行（封面 + 歌名 + 歌手），其余沿用通用行
                        // 音乐清单不参与拖拽排序（展示顺序完全由「更新 / 歌手」排序决定）
                        val rowModifier = (if (isEditMode && !isMusicList && sortMode == MusicSortMode.ADDED) dragReorderModifier(
                            itemId = item.id,
                            isListMode = true,
                            gridColumns = 1,
                            currentIdsState = currentItemIds,
                            onDragStateChange = { draggingItemId = it },
                            onReorder = { from, to -> Repo.reorderItem(listId, from, to) },
                            onTap = {},
                            onLongPress = {}
                        ) else Modifier.fillMaxWidth().clickable { openEntity(item) })
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                shadowElevation = if (isDragging) 24f else 0f
                            }
                        if (item.category == MusicRepo.CATEGORY) {
                            MusicItemRowInList(
                                item = item,
                                modifier = rowModifier,
                                isEditMode = isEditMode,
                                onRemove = { scope.launch { Repo.removeItemFromList(listId, item.id) } }
                            )
                        } else {
                            ItemRowInList(
                                item = item,
                                index = idx + 1,
                                isEditMode = isEditMode,
                                onRemove = { scope.launch { Repo.removeItemFromList(listId, item.id) } },
                                modifier = rowModifier
                            )
                        }
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
                        append("确定删除清单「${list!!.name}」吗？")
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
                        append("确定删除子清单「${meta.list.name}」吗？")
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

    // 搜索结果移除（长按触发）：从条目所属清单移出
    deleteSearchTarget?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteSearchTarget = null },
            title = { Text("移出条目") },
            text = {
                Text(
                    buildString {
                        val ownerName = listNameById[r.ownerListId] ?: "清单"
                        append("将「${r.item.title}」从「$ownerName」中移除？")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { Repo.removeItemFromList(r.ownerListId, r.item.id) }
                    searchResults = searchResults.filterNot {
                        it.item.id == r.item.id && it.ownerListId == r.ownerListId
                    }
                    deleteSearchTarget = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteSearchTarget = null }) { Text("取消") } }
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
    val context = LocalContext.current
    // 漫画/本子：封面右上角显示「更新 N 话 / 已完结」（来自各自站点，带内存+磁盘缓存）
    var comicStatus by remember(item.doubanId) { mutableStateOf<ComicStatus?>(null) }
    if (item.category == "漫画" || item.category == "本子") {
        LaunchedEffect(item.doubanId) {
            comicStatus = ComicStatusStore.status(context, item.category, item.doubanId)
        }
    }
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            CoverImage(
                url = item.coverUrl,
                placeholderText = item.title,
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
        // 编辑模式下右上角 × 删除按钮（优先于更新角标）
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
        } else {
            // 角标文案为 null 表示「连载中且已读到最新」→ 不显示
            comicStatus?.let { st ->
                st.label?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(
                                if (st.finished) Color(0xFF43A047).copy(alpha = 0.9f)
                                else Color(0xFFE53935).copy(alpha = 0.9f),
                                RoundedCornerShape(bottomStart = 8.dp, topEnd = 8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRowInList(
    item: CollectionItemEntity,
    modifier: Modifier = Modifier,
    index: Int = 0,
    isEditMode: Boolean = false,
    onRemove: () -> Unit = {}
) {
    val context = LocalContext.current
    // 漫画/本子：行内显示「更新 N 话 / 已完结」（封面只有 40dp，放角标看不清）
    var comicStatus by remember(item.doubanId) { mutableStateOf<ComicStatus?>(null) }
    if (item.category == "漫画" || item.category == "本子") {
        LaunchedEffect(item.doubanId) {
            comicStatus = ComicStatusStore.status(context, item.category, item.doubanId)
        }
    }
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
                CoverImage(
                    url = item.coverUrl,
                    placeholderText = item.title,
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
                        val statusLabel = item.status.ifBlank { comicStatus?.label.orEmpty() }
                        if (statusLabel.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (comicStatus?.finished == true) Color(0xFF43A047)
                                else MaterialTheme.colorScheme.onSurfaceVariant
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

/** 音乐条目专用行：52dp 圆角封面 + 歌名 + 歌手（间距/字号沿用通用行风格，整行高度同为 64dp） */
@Composable
private fun MusicItemRowInList(
    item: CollectionItemEntity,
    modifier: Modifier = Modifier,
    isEditMode: Boolean = false,
    onRemove: () -> Unit = {}
) {
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverImage(
                url = item.coverUrl,
                placeholderText = item.title,
                modifier = Modifier.size(52.dp)
            )
            Column(
                Modifier.weight(1f).padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // 歌手未知时退化为分类名，避免第二行空着
                    item.subTitle.ifBlank { MusicRepo.CATEGORY },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

/**
 * 音乐条目按歌手（subTitle）排序：**英文歌手在前**（按字母 a-z，不区分大小写），**中文歌手在后**（按拼音 a-z，
 * 用 Collator 的中文排序规则，免新增依赖）；sortedWith 为稳定排序，歌手相同时保持添加顺序；
 * 音乐条目填回原来属于音乐条目的位置，非音乐条目的顺序与位置都不变。
 */
private fun sortItemsByArtist(items: List<CollectionItemEntity>): List<CollectionItemEntity> {
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    val music = items.filter { it.category == MusicRepo.CATEGORY }.sortedWith(
        compareBy(
            // 首字符是 ASCII（字母/数字，即"英文歌手"）的排前面；空歌手跟中文一组排后面
            { (it.subTitle.firstOrNull()?.code ?: Int.MAX_VALUE) >= 128 },
            // 统一用 CollationKey（Collator 对纯字母也是 a-z 序；中文是拼音序）；小写化避免大小写干扰
            { collator.getCollationKey(it.subTitle.trim().lowercase()) }
        )
    )
    if (music.isEmpty()) return items
    var i = 0
    return items.map { if (it.category == MusicRepo.CATEGORY) music[i++] else it }
}

/**
 * 音乐条目按添加时间倒序（最新添加的在最前）：倒序后的音乐条目填回原来属于音乐条目的位置，
 * 非音乐条目的顺序与位置都不变；纯音乐清单即整体倒序。
 */
private fun sortItemsByAddedDesc(items: List<CollectionItemEntity>): List<CollectionItemEntity> {
    val music = items.filter { it.category == MusicRepo.CATEGORY }.reversed()
    if (music.isEmpty()) return items
    var i = 0
    return items.map { if (it.category == MusicRepo.CATEGORY) music[i++] else it }
}

/** 排序控件：仅含音乐条目的清单显示，常驻在内容区最上方一行 */
@Composable
private fun SortModeRow(
    sortMode: MusicSortMode,
    modifier: Modifier = Modifier,
    onSelect: (MusicSortMode) -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "排序",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        FilterChip(
            selected = sortMode == MusicSortMode.ADDED,
            onClick = { onSelect(MusicSortMode.ADDED) },
            label = { Text("更新") }
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = sortMode == MusicSortMode.ARTIST,
            onClick = { onSelect(MusicSortMode.ARTIST) },
            label = { Text("歌手") }
        )
    }
}
