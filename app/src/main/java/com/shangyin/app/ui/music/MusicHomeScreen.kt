package com.shangyin.app.ui.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.music.MusicDownloader
import com.shangyin.app.data.music.MusicPlatform
import com.shangyin.app.data.music.MusicRepo
import com.shangyin.app.data.music.MusicSong
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch

/**
 * 音乐模块主页：顶部标题栏 + 搜索页 + 底部迷你播放条。
 *
 * 数据来源：音乐来源（闪闪音乐网 https://www.33ve.com），搜索/歌词/封面都由内置接口提供；
 * 播放直链由 [com.shangyin.app.data.music.Site33] 在播放时现取（直链带时效签名）。
 */

/** 翻页步长：接口每页返回 30 条，用来判断"是否还有下一页" */
private const val PAGE_SIZE = 30

/** 翻页上限：接口不支持翻页时靠"本页没有新增"提前结束，这里再兜一层防死循环 */
private const val MAX_PAGE = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicHomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val searchState = MusicSearchSession.state

    // 进音乐模块：连接播放服务（幂等）
    LaunchedEffect(Unit) {
        MusicPlayback.init(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音乐") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
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
            Box(Modifier.weight(1f)) {
                SearchTab(searchState)
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

    val duration = state.durationMs
    // 拖动进度条时先跟手，松手才 seek（与播放页一致）
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // 换歌时结束拖动，避免残留的拖动位置串到新歌上
    LaunchedEffect(song.key) { dragging = false }

    // 播放进度：时长未知（<=0）时按 0 处理，避免除零
    val playedFraction = if (duration > 0L) {
        (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else 0f
    val fraction = if (dragging) dragFraction else playedFraction
    // 拖动中左侧时间跟手显示拖动位置
    val shownPosition = if (dragging && duration > 0L) (dragFraction * duration).toLong() else state.positionMs

    // 细进度条上挂水平拖动手势：只改本地值，松手才 seek；时长未知时不可拖动
    val dragModifier = if (duration > 0L) {
        Modifier.pointerInput(duration) {
            // 触点 x → 0..1 比例（宽度兜底，避免除零）
            fun fractionAt(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            detectHorizontalDragGestures(
                onDragStart = {
                    dragging = true
                    dragFraction = fractionAt(it.x)
                },
                onDragCancel = { dragging = false },
                onDragEnd = {
                    if (dragging) MusicPlayback.seekTo((dragFraction * duration).toLong())
                    dragging = false
                }
            ) { change, _ ->
                dragFraction = fractionAt(change.position.x)
            }
        }
    } else Modifier

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            // 进度行：左当前时间 / 中间细进度条（可拖动）/ 右总时长
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatMusicTime(shownPosition),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                // 进度条与外层 clickable 行是同级节点，手势不会被抢走；16dp 高只做触摸区，视觉仍是 2dp 细条
                Box(
                    Modifier
                        .weight(1f)
                        .height(16.dp)
                        .then(dragModifier),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatMusicTime(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
}

// ==================== 搜索 ====================

private class SearchTabState {
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

/**
 * 会话级搜索结果缓存：`remember { }` 在导航回本页时会重建（进播放页再返回搜索就没了），
 * 与漫画/游戏模块同一口径——把状态挂在单例上，返回时结果还在。
 */
private object MusicSearchSession {
    val state = SearchTabState()
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
    // 长按搜索结果弹出的小菜单（收藏到清单 / 下载）
    var menuSong by remember { mutableStateOf<MusicSong?>(null) }
    // 下载状态：downloadingKey 为正在下载的歌（非空即视为下载中，天然防重复点击）
    var downloadingKey by remember { mutableStateOf<String?>(null) }
    var downloadPercent by remember { mutableIntStateOf(0) }
    // 下载失败原因（原文展示，不静默）
    var downloadError by remember { mutableStateOf<String?>(null) }

    // 下载一首歌：进度显示在页面底部，成功/失败都给提示
    fun startDownload(song: MusicSong) {
        if (downloadingKey != null) return
        downloadingKey = song.key
        downloadPercent = 0
        scope.launch {
            runCatching { MusicDownloader.download(context, song) { downloadPercent = it } }
                .onSuccess { path ->
                    Toast.makeText(context, "已保存到 $path", Toast.LENGTH_LONG).show()
                }
                .onFailure { e -> downloadError = e.message ?: "下载失败" }
            downloadingKey = null
        }
    }

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
            runCatching { MusicRepo.search(kw, page) }
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

    // 回车 / 点右侧箭头 → 用当前关键词搜首页；关键词为空或没变则不搜
    fun doSearch() {
        keyboard?.hide()
        val kw = st.input.trim()
        if (kw.isNotBlank() && kw != st.keyword) {
            st.keyword = kw
            st.page = 1
            load(1)
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
        // 搜索框：样式与表世界（HomeScreen）保持一致
        OutlinedTextField(
            value = st.input,
            onValueChange = { st.input = it },
            placeholder = { Text("搜索歌曲 / 歌手") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }),
            trailingIcon = {
                IconButton(onClick = { doSearch() }) {
                    Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = "搜索")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // 列表区占满剩余高度，底部留给下载进度条
        Box(Modifier.weight(1f)) {
            when {
                st.loading && st.results.isEmpty() -> MusicLoadingBox()

                st.error != null && st.results.isEmpty() ->
                    MusicErrorBox(st.error!!, onRetry = { load(1) })

                st.results.isEmpty() ->
                    if (st.searched) EmptyView("没有找到相关歌曲，换个关键词试试")
                    else EmptyView("输入关键词搜索，长按结果可收藏或下载")

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    itemsIndexed(st.results, key = { _, s -> s.key }) { index, song ->
                        MusicSongRow(
                            song = song,
                            onClick = { play(st.results, index) },
                            // 长按改为弹菜单（收藏到清单 / 下载）
                            onLongClick = { menuSong = song },
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
        }

        // 下载进度（页面底部，下载中才显示）
        if (downloadingKey != null) MusicDownloadBar(downloadPercent)

        // 点收藏图标 → 收藏到里世界清单
        collectSong?.let { song ->
            CollectDialog(
                onDismiss = { collectSong = null },
                collect = { listId -> MusicRepo.collect(song, listId) }
            )
        }
    }

    // 长按搜索结果弹出的小菜单：收藏到清单 / 下载
    menuSong?.let { song ->
        AlertDialog(
            onDismissRequest = { menuSong = null },
            title = { Text(song.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = {
                        menuSong = null
                        collectSong = song
                    }) { Text("收藏到清单") }
                    TextButton(onClick = {
                        menuSong = null
                        startDownload(song)
                    }) { Text("下载") }
                }
            },
            confirmButton = { TextButton(onClick = { menuSong = null }) { Text("取消") } }
        )
    }

    // 下载失败：展示原因原文，不静默
    downloadError?.let { text ->
        AlertDialog(
            onDismissRequest = { downloadError = null },
            title = { Text("下载失败") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { downloadError = null }) { Text("知道了") } }
        )
    }
}

/** 页面底部下载进度条（拿不到总长度时只显示转圈 + 下载中） */
@Composable
private fun MusicDownloadBar(percent: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (percent > 0) "下载中 $percent%" else "下载中…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

/** 列表页脚：加载下一页中 / 没有更多了 */
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
