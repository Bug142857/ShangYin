package com.shangyin.app.ui.music

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.music.MusicBoard
import com.shangyin.app.data.music.MusicPlatform
import com.shangyin.app.data.music.MusicRepo
import com.shangyin.app.data.music.MusicSong
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch

/** 翻页步长（与 MusicApis 默认 limit 一致） */
private const val BOARD_PAGE_SIZE = 30

/** 翻页上限（接口不支持翻页时靠"本页没有新增"提前结束，这里兜底防死循环） */
private const val BOARD_MAX_PAGE = 20

/**
 * 榜单歌曲页：整榜作为播放队列，点行即从该行起播；滑到底自动加载下一页。
 * 路由参数 platformKey/boardId/boardName 由导航传入，这里自己拼回 [MusicBoard]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicBoardScreen(
    nav: NavHostController,
    platformKey: String,
    boardId: String,
    boardName: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val play = rememberMusicPlay()
    val savedKeys = rememberCollectedKeys()

    val platform = remember(platformKey) { MusicPlatform.of(platformKey) }
    val board = remember(platform, boardId, boardName) {
        platform?.let { MusicBoard(it, boardId, boardName) }
    }

    var songs by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var collectSong by remember { mutableStateOf<MusicSong?>(null) }
    val listState = rememberLazyListState()

    /** 加载第 [target] 页（1 = 首屏 / 重试） */
    fun load(target: Int) {
        val b = board ?: return
        if (target == 1) {
            if (loading) return
            loading = true
            endReached = false
        } else {
            if (loadingMore || endReached || page >= BOARD_MAX_PAGE) return
            loadingMore = true
        }
        error = null
        scope.launch {
            runCatching { MusicRepo.boardSongs(b, target) }
                .onSuccess { list ->
                    val oldSize = songs.size
                    songs = if (target == 1) list
                    else songs + list.filterNot { s -> songs.any { it.key == s.key } }
                    page = target
                    // 返回不满一页 / 本页没有新增（接口不支持翻页）→ 到底了
                    if (list.size < BOARD_PAGE_SIZE || songs.size == oldSize) endReached = true
                    if (target == 1 && songs.isNotEmpty()) listState.scrollToItem(0)
                }
                .onFailure { e ->
                    val msg = e.message ?: "加载失败"
                    if (msg.contains("接口返回空")) {
                        if (target == 1) songs = emptyList() else endReached = true
                    } else {
                        error = msg
                    }
                }
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(board) { if (board != null) load(1) }

    // 滑到底自动加载下一页
    LaunchedEffect(listState, songs) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (songs.isNotEmpty() && last >= songs.size - 3) load(page + 1)
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            boardName.ifBlank { "榜单" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            buildString {
                                append(platform?.label ?: "未知平台")
                                if (songs.isNotEmpty()) append(" · 已加载 ${songs.size} 首")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
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
            when {
                board == null -> MusicErrorBox("榜单地址无效，请返回后重进")

                loading && songs.isEmpty() -> MusicLoadingBox()

                error != null && songs.isEmpty() -> MusicErrorBox(error!!, onRetry = { load(1) })

                songs.isEmpty() -> EmptyView("这个榜单暂时没有歌曲")

                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(songs, key = { _, s -> s.key }) { index, song ->
                        MusicSongRow(
                            song = song,
                            onClick = { play(songs, index) },
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
                    item(key = "board_foot") {
                        MusicListFooter(loadingMore = loadingMore, endReached = endReached)
                    }
                }
            }
        }
    }

    collectSong?.let { song ->
        CollectDialog(
            onDismiss = { collectSong = null },
            collect = { listId -> MusicRepo.collect(song, listId) }
        )
    }
}
