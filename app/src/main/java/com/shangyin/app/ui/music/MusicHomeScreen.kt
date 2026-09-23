package com.shangyin.app.ui.music

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.music.MusicBoard
import com.shangyin.app.data.music.MusicPlatform
import com.shangyin.app.data.music.MusicRepo
import com.shangyin.app.data.music.MusicSong
import com.shangyin.app.data.music.MusicSourceStore
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch

/**
 * 音乐模块主页：顶部标题栏 + 底部三标签（搜索 / 排行榜 / 我的）+ 底部迷你播放条。
 *
 * 数据来源：搜索/榜单/歌词走 App 内置接口（[MusicRepo]），播放直链由 LX 音源脚本解析
 * （音源在「音源管理」页导入，右上角入口）。
 */

/** 翻页步长（与 MusicApis 默认 limit 一致，用来判断"是否还有下一页"） */
private const val PAGE_SIZE = 30

/** 翻页上限：接口不支持翻页时靠"本页没有新增"提前结束，这里再兜一层防死循环 */
private const val MAX_PAGE = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicHomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    // 三个标签的状态提到这里：切标签回来时搜索结果/榜单不丢
    val searchState = remember { SearchTabState() }
    val boardState = remember { BoardTabState() }

    // 进音乐模块：连接播放服务 + 初始化音源（幂等）
    LaunchedEffect(Unit) {
        MusicPlayback.init(context)
        MusicSourceStore.ensureInitialized()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音乐") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("musicSources") }) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = "音源")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            // 没有可用音源时的引导：搜索/榜单走内置接口能出数据，但播放要靠音源脚本换直链
            val scripts by MusicSourceStore.scripts.collectAsStateWithLifecycle()
            if (scripts.none { it.enabled && it.support.isNotEmpty() }) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { nav.safeNavigate("musicSources") }
                ) {
                    Text(
                        "还没有可用音源，点这里导入（推荐六音 / LX 音源）——搜索能出歌，播放需要音源解析直链",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> SearchTab(searchState)
                    1 -> BoardTab(nav, boardState)
                    else -> MineTab(nav)
                }
            }
            TabRow(selectedTabIndex = tab) {
                listOf("搜索", "排行榜", "我的").forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label) }
                    )
                }
            }
            MusicMiniPlayer(nav)
        }
    }
}

/**
 * 全局迷你播放条：任何页面都可以挂（没在播时不渲染任何东西）。
 * 点击进完整播放页，右侧可播放/暂停、切下一首。
 */
@Composable
fun MusicMiniPlayer(nav: NavHostController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by MusicPlayback.state.collectAsStateWithLifecycle()
    val song = state.song ?: return

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { nav.safeNavigate("musicPlayer") }
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            CoverImage(
                url = song.cover,
                modifier = Modifier.size(40.dp),
                corner = 6.dp,
                placeholderText = song.name
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.subtitle.ifBlank { song.platform.label },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.buffering) {
                CircularProgressIndicator(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp
                )
            }
            IconButton(onClick = { MusicPlayback.toggle(context) }) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放"
                )
            }
            IconButton(onClick = { MusicPlayback.next() }) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "下一首")
            }
        }
    }
}

// ==================== 搜索 ====================

private class SearchTabState {
    var platform by mutableStateOf(MusicPlatform.WY)
    var input by mutableStateOf("")
    var keyword by mutableStateOf("")
    var results by mutableStateOf<List<MusicSong>>(emptyList())
    var page by mutableStateOf(1)
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var endReached by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun SearchTab(st: SearchTabState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val play = rememberMusicPlay()
    val savedKeys = rememberCollectedKeys()
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    var collectSong by remember { mutableStateOf<MusicSong?>(null) }

    /** 加载第 [page] 页（page=1 为新搜索） */
    fun load(page: Int) {
        val kw = st.keyword
        if (kw.isBlank()) return
        if (page == 1) {
            if (st.loading) return
            st.loading = true
            st.searched = true
            st.endReached = false
        } else {
            if (st.loadingMore || st.endReached || st.page >= MAX_PAGE) return
            st.loadingMore = true
        }
        st.error = null
        scope.launch {
            runCatching { MusicRepo.search(st.platform, kw, page) }
                .onSuccess { list ->
                    if (kw != st.keyword) return@onSuccess // 关键词已变，丢弃旧响应
                    val oldSize = st.results.size
                    st.results = if (page == 1) list
                    else st.results + list.filterNot { s -> st.results.any { it.key == s.key } }
                    st.page = page
                    // 不满一页 / 本页没有新增（接口不支持翻页）→ 到底了
                    if (list.size < PAGE_SIZE || (page > 1 && st.results.size == oldSize)) {
                        st.endReached = true
                    }
                    if (page == 1 && st.results.isNotEmpty()) listState.scrollToItem(0)
                }
                .onFailure { e ->
                    if (kw != st.keyword) return@onFailure
                    val msg = e.message ?: "搜索失败"
                    // 接口把"没有数据"当成异常抛（"接口返回空"）：首页 = 没结果，翻页 = 没有更多
                    if (msg.contains("接口返回空")) {
                        if (page == 1) st.results = emptyList() else st.endReached = true
                    } else {
                        st.error = msg
                    }
                }
            st.loading = false
            st.loadingMore = false
        }
    }

    // 滑到底自动加载下一页
    LaunchedEffect(listState, st.results) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (st.results.isNotEmpty() && last >= st.results.size - 3) load(st.page + 1)
            }
    }

    Column(Modifier.fillMaxSize()) {
        PlatformChips(st.platform) { p ->
            st.platform = p
            st.results = emptyList()
            st.searched = false
            st.endReached = false
            st.page = 1
            st.error = null
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = st.input,
                onValueChange = { st.input = it },
                placeholder = { Text("搜索歌曲 / 歌手") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    val kw = st.input.trim()
                    if (kw.isNotBlank() && kw != st.keyword) {
                        st.keyword = kw
                        st.page = 1
                        load(1)
                    }
                }),
                trailingIcon = {
                    if (st.input.isNotEmpty()) {
                        IconButton(onClick = { st.input = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    keyboard?.hide()
                    val kw = st.input.trim()
                    if (kw.isNotBlank() && kw != st.keyword) {
                        st.keyword = kw
                        st.page = 1
                        load(1)
                    }
                },
                enabled = st.input.isNotBlank() && st.input.trim() != st.keyword
            ) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索")
            }
        }

        when {
            st.loading && st.results.isEmpty() -> MusicLoadingBox()

            st.error != null && st.results.isEmpty() ->
                MusicErrorBox(st.error!!, onRetry = { load(1) })

            st.results.isEmpty() ->
                if (st.searched) EmptyView("没有找到相关歌曲，换个关键词或平台试试")
                else EmptyView("输入关键词搜索，或长按结果收藏到清单")

            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                itemsIndexed(st.results, key = { _, s -> s.key }) { index, song ->
                    MusicSongRow(
                        song = song,
                        onClick = { play(st.results, index) },
                        onLongClick = { collectSong = song },
                        trailing = {
                            IconButton(onClick = {
                                if (song.key in savedKeys) {
                                    Toast.makeText(context, "已在清单中", Toast.LENGTH_SHORT).show()
                                } else {
                                    collectSong = song
                                }
                            }) {
                                val saved = song.key in savedKeys
                                Icon(
                                    if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "收藏",
                                    tint = if (saved) Color(0xFFEF5350)
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
                item(key = "search_foot") {
                    MusicListFooter(loadingMore = st.loadingMore, endReached = st.endReached)
                }
            }
        }

        // 长按行 / 点收藏图标 → 收藏到里世界清单
        collectSong?.let { song ->
            CollectDialog(
                onDismiss = { collectSong = null },
                collect = { listId -> MusicRepo.collect(song, listId) }
            )
        }
    }
}

// ==================== 排行榜 ====================

private class BoardTabState {
    var platform by mutableStateOf(MusicPlatform.WY)
    var boards by mutableStateOf<List<MusicBoard>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun BoardTab(nav: NavHostController, st: BoardTabState) {
    val scope = rememberCoroutineScope()

    fun load() {
        if (st.loading) return
        st.loading = true
        st.error = null
        val platform = st.platform
        scope.launch {
            runCatching { MusicRepo.boards(platform) }
                .onSuccess { if (platform == st.platform) st.boards = it }
                .onFailure { if (platform == st.platform) st.error = it.message ?: "榜单加载失败" }
            st.loading = false
        }
    }

    LaunchedEffect(st.platform) { load() }

    Column(Modifier.fillMaxSize()) {
        PlatformChips(st.platform) { p ->
            st.platform = p
            st.boards = emptyList()
            st.error = null
        }
        when {
            st.loading && st.boards.isEmpty() -> MusicLoadingBox()
            st.error != null && st.boards.isEmpty() -> MusicErrorBox(st.error!!, onRetry = { load() })
            st.boards.isEmpty() -> EmptyView("这个平台暂时没有榜单")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    count = st.boards.size,
                    key = { i -> st.boards[i].platform.key + st.boards[i].id }
                ) { i ->
                    val board = st.boards[i]
                    Card(onClick = {
                        nav.safeNavigate(
                            "musicBoard/${board.platform.key}/${board.id}/${Uri.encode(board.name)}"
                        )
                    }) {
                        Column {
                            CoverImage(
                                url = board.cover,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                                corner = 8.dp,
                                placeholderText = board.name
                            )
                            Text(
                                board.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp)
                            )
                            Text(
                                board.updateTime.ifBlank { board.platform.label },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 我的 ====================

@Composable
private fun MineTab(nav: NavHostController) {
    val play = rememberMusicPlay()
    val songsFlow = remember { MusicRepo.observeAllSongs() }
    val listsFlow = remember { Repo.observeRootListsWithMeta(1) }
    val songs by songsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val lists by listsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "h_songs") { MusicSectionTitle("我的收藏 · ${songs.size}") }
        if (songs.isEmpty()) {
            item(key = "e_songs") { MusicHint("还没有收藏的歌曲，去搜索页点收藏") }
        } else {
            itemsIndexed(songs, key = { _, s -> "s_" + s.key }) { index, song ->
                // 点即播（不做移除，保持简单）
                MusicSongRow(song = song, onClick = { play(songs, index) })
            }
        }

        item(key = "h_lists") { MusicSectionTitle("里世界清单") }
        if (lists.isEmpty()) {
            item(key = "e_lists") { MusicHint("还没有里世界清单") }
        } else {
            items(lists, key = { "l_" + it.list.id }) { meta ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { nav.safeNavigate("list/${meta.list.id}") }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            meta.list.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${meta.itemCount} 件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

// ==================== 音乐模块公共 UI ====================

/** 起播前申请通知权限（Android 13+）：拒绝也照常播放，只是没有通知栏控制 */
@Composable
internal fun rememberMusicPlay(): (List<MusicSong>, Int) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    return { songs, index ->
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        MusicPlayback.play(context, songs, index)
    }
}

/** 已收藏歌曲的 key 集合（列表里的收藏图标状态用） */
@Composable
internal fun rememberCollectedKeys(): Set<String> {
    val flow = remember { MusicRepo.observeAllSongs() }
    val songs by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(songs) { songs.map { it.key }.toSet() }
}

/** 平台选择 chips（搜索页与排行榜页共用） */
@Composable
internal fun PlatformChips(selected: MusicPlatform, onSelect: (MusicPlatform) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(MusicPlatform.entries, key = { it.key }) { platform ->
            FilterChip(
                selected = platform == selected,
                onClick = { onSelect(platform) },
                label = { Text(platform.label) }
            )
        }
    }
}

/**
 * 歌曲行：封面 + 歌名 + 歌手·专辑；点即播，长按收藏。
 * [trailing] 放行尾按钮（收藏图标等）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MusicSongRow(
    song: MusicSong,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        CoverImage(
            url = song.cover,
            modifier = Modifier.size(48.dp),
            corner = 6.dp,
            placeholderText = song.name
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.subtitle.ifBlank { song.platform.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}

/** 分组小标题 */
@Composable
internal fun MusicSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
    )
}

/** 分组里的提示文案（空态/说明） */
@Composable
internal fun MusicHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
    )
}

/** 列表页脚：加载下一页中 / 没有更多了（搜索页与榜单页共用） */
@Composable
internal fun MusicListFooter(loadingMore: Boolean, endReached: Boolean) {
    if (loadingMore) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    } else if (endReached) {
        Text(
            "没有更多了",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
    }
}

/** 居中加载中 */
@Composable
internal fun MusicLoadingBox() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** 居中错误提示（带可选重试） */
@Composable
internal fun MusicErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 毫秒 → mm:ss */
internal fun formatMusicTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}
