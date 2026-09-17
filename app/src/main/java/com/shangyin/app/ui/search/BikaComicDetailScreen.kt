package com.shangyin.app.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.shangyin.app.data.Repo
import com.shangyin.app.data.bika.BikaChapter
import com.shangyin.app.data.bika.BikaClient
import com.shangyin.app.data.bika.BikaComic
import com.shangyin.app.data.download.ComicDownloadManager
import com.shangyin.app.data.download.DownloadedChapter
import com.shangyin.app.data.download.DownloadedComic
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch

/**
 * 哔咔漫画详情页：封面/简介/标签/章节列表，点章节取全部图片后全屏翻页阅读。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BikaComicDetailScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 优先用会话缓存：从阅读页返回详情秒开
    var comic by remember { mutableStateOf(BikaUiCache.details[id]) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var chapters by remember { mutableStateOf(BikaUiCache.chapters[id] ?: emptyList()) }
    var chaptersLoading by remember { mutableStateOf(!BikaUiCache.chapters.containsKey(id)) }

    var loadingEp by remember { mutableStateOf<Int?>(null) }   // 正在取图的章节 order
    var viewerUrls by remember { mutableStateOf<List<String>?>(null) }
    var viewerIdx by remember { mutableIntStateOf(-1) }        // viewerUrls 对应 ordered 下标（-1=单本无章节）
    var sortDesc by rememberSaveable { mutableStateOf(false) }

    // 查看全部：按章节顺序取全部图片，网格浏览 + 点击进入阅读器放大
    var allImages by remember { mutableStateOf<List<String>?>(null) }
    var loadingAll by remember { mutableStateOf(false) }
    var allProgress by remember { mutableStateOf("") }
    var openIndex by remember { mutableStateOf(-1) }

    // 收藏到里世界清单（category="本子"）
    var showCollect by remember { mutableStateOf(false) }
    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val collected = remember(allItems, id) { allItems.any { it.category == "本子" && it.doubanId == id } }

    // 离线下载状态（本子：source="bika"，章节 key = order）
    val library by ComicDownloadManager.library.collectAsStateWithLifecycle()
    val activeTasks by ComicDownloadManager.active.collectAsStateWithLifecycle()
    val downloadedKeys = remember(library, id) {
        library.firstOrNull { it.source == "bika" && it.id == id }
            ?.chapters?.map { it.key }?.toSet() ?: emptySet()
    }
    LaunchedEffect(id) { ComicDownloadManager.refresh(context) }

    val ordered = remember(chapters, sortDesc) { if (sortDesc) chapters.reversed() else chapters }

    /** 章节显示名（行内用） */
    fun epLabel(ch: BikaChapter) = "第 ${ch.order} 话"

    /** 下载用章节名（带标题） */
    fun epName(ch: BikaChapter) = buildString {
        append(epLabel(ch))
        ch.title?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
    }

    /** 登录失效已由 BikaClient.withAuth 自动重新注册，二次失败提示返回 */
    fun handleAuthError() {
        Toast.makeText(context, "哔咔账号异常，请重新进入", Toast.LENGTH_LONG).show()
        nav.safePopBackStack()
    }

    // 详情 + 章节并行加载，互不影响
    LaunchedEffect(id) {
        scope.launch {
            runCatching { BikaClient.withAuth { t -> BikaClient.fetchComicDetail(t, id) } }
                .onSuccess {
                    comic = it
                    BikaUiCache.details[id] = it
                }
                .onFailure {
                    if (it is BikaClient.BikaAuthException) handleAuthError()
                    else detailError = it.message ?: "加载失败"
                }
        }
        scope.launch {
            runCatching { BikaClient.withAuth { t -> BikaClient.fetchChapters(t, id) } }
                .onSuccess {
                    chapters = it
                    BikaUiCache.chapters[id] = it
                }
                .onFailure { if (it is BikaClient.BikaAuthException) handleAuthError() }
            chaptersLoading = false
        }
    }

    /** 取某章节全部图片并打开阅读器 */
    fun readChapter(order: Int, idx: Int = -1) {
        if (loadingEp != null) return
        scope.launch {
            loadingEp = order
            runCatching { BikaClient.withAuth { t -> BikaClient.fetchChapterImages(t, id, order) } }
                .onSuccess { urls ->
                    if (urls.isEmpty()) Toast.makeText(context, "该章节暂无图片", Toast.LENGTH_SHORT).show()
                    else {
                        viewerIdx = idx
                        viewerUrls = urls
                    }
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        if (it is BikaClient.BikaAuthException) "哔咔账号异常，请重新进入" else "获取图片失败：${it.message ?: "网络错误"}",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (it is BikaClient.BikaAuthException) handleAuthError()
                }
            loadingEp = null
        }
    }

    /** 下载指定章节（1 章或整套）；无章节的单本按 order=1 下载 */
    fun download(targets: List<BikaChapter>) {
        val c = comic ?: return
        val single = targets.isEmpty()
        val list = if (single) listOf(BikaChapter("1", null, 1)) else targets
        val meta = DownloadedComic(source = "bika", id = id, title = c.title, cover = c.thumbUrl)
        ComicDownloadManager.enqueue(
            context,
            meta,
            list.map { DownloadedChapter(it.order.toString(), if (single) "本篇" else epName(it), 0) }
        ) { key -> BikaClient.withAuth { t -> BikaClient.fetchChapterImages(t, id, key.toInt()) } }
        Toast.makeText(context, "已加入下载（${list.size} 章），可在「我的下载」查看", Toast.LENGTH_SHORT).show()
    }

    /** 查看全部：按章节顺序拉取全部图片（无章节=单本），成功后打开网格浏览 */
    fun fetchAllImages() {
        if (loadingAll) return
        scope.launch {
            loadingAll = true
            val list = mutableListOf<String>()
            val orders = if (chapters.isEmpty()) listOf(1) else chapters.map { it.order }.sorted()
            runCatching {
                orders.forEachIndexed { i, order ->
                    allProgress = "${i + 1}/${orders.size}"
                    list.addAll(BikaClient.withAuth { t -> BikaClient.fetchChapterImages(t, id, order) })
                }
            }.onSuccess {
                if (list.isEmpty()) Toast.makeText(context, "暂无图片", Toast.LENGTH_SHORT).show()
                else allImages = list
            }.onFailure {
                Toast.makeText(context, "获取图片失败：${it.message ?: "网络错误"}", Toast.LENGTH_SHORT).show()
            }
            loadingAll = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        comic?.title ?: "漫画详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("downloads") }) {
                        Icon(Icons.Rounded.DownloadDone, contentDescription = "我的下载")
                    }
                    IconButton(onClick = { showCollect = true }) {
                        Icon(
                            if (collected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (collected) androidx.compose.ui.graphics.Color(0xFFEF5350)
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { pad ->
        when {
            // 详情加载失败
            detailError != null && comic == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(pad).fillMaxSize().padding(32.dp)
            ) {
                Text(detailError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            // 加载中
            comic == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(pad).fillMaxSize()
            ) {
                CircularProgressIndicator()
            }
            else -> {
                val c = comic!!
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(pad).fillMaxSize()
                ) {
                    // 头部：封面 + 信息
                    item {
                        Row {
                            CoverImage(
                                url = c.thumbUrl,
                                modifier = Modifier.width(110.dp).height(147.dp),
                                corner = 10.dp
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (c.author.isNotBlank()) {
                                    Text(
                                        "作者：${c.author}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                if (c.chineseTeam.isNotBlank()) {
                                    Text(
                                        "汉化：${c.chineseTeam}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    buildString {
                                        val parts = mutableListOf<String>()
                                        parts.add(if (c.finished) "已完结" else "连载中")
                                        if (c.epsCount > 0) parts.add("${c.epsCount} 话")
                                        if (c.pagesCount > 0) parts.add("${c.pagesCount} 页")
                                        if (c.likes > 0) parts.add("${c.likes} 赞")
                                        if (c.views > 0) parts.add("${c.views} 看")
                                        append(parts.joinToString(" · "))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                    // 分类 + 标签
                    if (c.categories.isNotEmpty() || c.tags.isNotEmpty()) {
                        item {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                (c.categories + c.tags).forEach { t ->
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            t,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 简介
                    if (c.description.isNotBlank()) {
                        item {
                            Text(
                                c.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 章节区：正序/倒序 + 下载全部 + 查看全部
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    chaptersLoading -> "章节加载中…"
                                    chapters.isEmpty() -> "本篇"
                                    else -> "章节（${chapters.size}）"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { sortDesc = !sortDesc },
                                enabled = chapters.size > 1
                            ) {
                                Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (sortDesc) "倒序" else "正序", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = { download(chapters) },
                                enabled = !chaptersLoading
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (chapters.isEmpty()) "下载本篇" else "下载全部", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = { fetchAllImages() },
                                enabled = !loadingAll
                            ) {
                                Text(if (loadingAll) "获取中 $allProgress" else "查看全部", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (!chaptersLoading && chapters.isEmpty()) {
                        // 无章节：单本漫画，直接开始阅读
                        item {
                            Button(
                                onClick = { if (loadingEp == null) readChapter(1) },
                                enabled = loadingEp == null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (loadingEp == null) "开始阅读" else "获取图片中…")
                            }
                        }
                    } else {
                        items(ordered.size) { i ->
                            val ch = ordered[i]
                            val reading = loadingEp == ch.order
                            val task = activeTasks[ComicDownloadManager.key("bika", id, ch.order.toString())]
                            val done = ch.order.toString() in downloadedKeys
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                onClick = { if (!reading) readChapter(ch.order, i) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                                ) {
                                    Text(
                                        epLabel(ch),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (!ch.title.isNullOrBlank()) {
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            ch.title!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    when {
                                        reading -> CircularProgressIndicator(
                                            modifier = Modifier.padding(10.dp).size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        task != null && task.error == null -> CircularProgressIndicator(
                                            modifier = Modifier.padding(10.dp).size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        done -> IconButton(onClick = { nav.safeNavigate("downloads") }) {
                                            Icon(
                                                Icons.Rounded.DownloadDone,
                                                contentDescription = "已下载",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        else -> IconButton(onClick = { download(listOf(ch)) }) {
                                            Icon(
                                                Icons.Rounded.Download,
                                                contentDescription = "下载本章",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 全屏阅读器（可缩放/翻页/长按保存当前页/切换上下连续滑动 + 末页询问下一章）
    viewerUrls?.let { urls ->
        val label = ordered.getOrNull(viewerIdx)?.let { epLabel(it) }
        key(viewerIdx) {
            PhotoViewerDialog(
                urls = urls,
                initialIndex = 0,
                onDismiss = { viewerUrls = null; viewerIdx = -1 },
                chapterLabel = label,
                hasNextChapter = viewerIdx >= 0 && viewerIdx < ordered.size - 1,
                onOpenNextChapter = {
                    Toast.makeText(context, "正在加载下一章…", Toast.LENGTH_SHORT).show()
                    ordered.getOrNull(viewerIdx + 1)?.let { next -> readChapter(next.order, viewerIdx + 1) }
                }
            )
        }
    }

    // 查看全部：全屏网格浏览，点击单张进入阅读器（支持缩放/长按保存/阅读方向切换）
    allImages?.let { urls ->
        Dialog(
            onDismissRequest = { allImages = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(urls.size) { i ->
                        AsyncImage(
                            model = urls[i],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(0.75f)
                                .clickable { openIndex = i }
                        )
                    }
                }
                IconButton(
                    onClick = { allImages = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
                }
                Text(
                    "共 ${urls.size} 张 · 点击放大",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
                )
            }
        }
        if (openIndex >= 0) {
            PhotoViewerDialog(urls = urls, initialIndex = openIndex, onDismiss = { openIndex = -1 })
        }
    }

    // 收藏对话框：存为 category="本子" 条目并挂入所选里世界清单
    if (showCollect) {
        val c = comic
        CollectDialog(
            onDismiss = { showCollect = false },
            collect = { listId ->
                if (c == null) false
                else {
                    val itemId = Repo.saveCustomItem(
                        category = "本子", doubanId = id, title = c.title,
                        coverUrl = c.thumbUrl, subTitle = c.author
                    )
                    if (itemId > 0) { Repo.addItemToList(listId, itemId); true } else false
                }
            }
        )
    }
}
