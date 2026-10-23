package com.shangyin.app.ui.download

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.ReadProgressStore
import com.shangyin.app.data.download.ComicDownloadManager
import com.shangyin.app.data.download.ComicDownloadStore
import com.shangyin.app.data.download.DownloadedComic
import com.shangyin.app.data.download.DownloadedVideo
import com.shangyin.app.data.download.MediaFileStore
import com.shangyin.app.data.download.VideoDownloadManager
import com.shangyin.app.data.download.VideoTask
import com.shangyin.app.data.vod.VodEpisode
import com.shangyin.app.data.vod.VodPlayGroup
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.music.MusicPlayback
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 我的下载（主页）：视频 / 漫画 / 本子 / 音乐 / 书籍 五个文件夹卡，
 * 点卡片进入对应分区的下载列表页（downloadsList/{section}），
 * 卡片行尾「打开系统目录」按钮用系统文件管理器打开该分区目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) {
        ComicDownloadManager.refresh(context)
        VideoDownloadManager.refresh(context)
    }
    // v0.304：每次回到本页（含从系统目录/其他页面返回）重新统计，不再需要退出去再进来
    var refreshTick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        refreshTick++
        scope.launch {
            ComicDownloadManager.refresh(context)
            VideoDownloadManager.refresh(context)
        }
    }

    // 漫画 / 本子按来源拆分：bika = 本子（独立目录），其余 = 漫画
    val library by ComicDownloadManager.library.collectAsStateWithLifecycle()
    val comics = remember(library) { library.filterNot { it.source == ComicDownloadStore.BIKA } }
    val bikas = remember(library) { library.filter { it.source == ComicDownloadStore.BIKA } }
    val comicsSize = remember(comics, refreshTick) {
        comics.sumOf { ComicDownloadStore.size(context, it.source, it.id) }
    }
    val bikasSize = remember(bikas, refreshTick) {
        bikas.sumOf { ComicDownloadStore.size(context, it.source, it.id) }
    }

    // 视频 / 音乐 / 书籍：文件系统枚举（Android 10+ 走 MediaStore，8/9 走私有目录），IO 放后台线程
    val videos by produceState(MediaFileStore.EMPTY, context, refreshTick) {
        value = withContext(Dispatchers.IO) { MediaFileStore.videoSummary(context) }
    }
    val music by produceState(MediaFileStore.EMPTY, context, refreshTick) {
        value = withContext(Dispatchers.IO) { MediaFileStore.musicSummary(context) }
    }
    val books by produceState(MediaFileStore.EMPTY, context, refreshTick) {
        value = withContext(Dispatchers.IO) { MediaFileStore.bookSummary(context) }
    }

    // 各分区目录信息（打开系统目录用）
    val comicsDir = remember(context) { ComicDownloadStore.comicDirInfo(context) }
    val bikasDir = remember(context) { ComicDownloadStore.bikaDirInfo(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载管理") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad).fillMaxSize()
        ) {
            item(key = "folder-video") {
                FolderCard(
                    icon = Icons.Rounded.PlayArrow,
                    title = "视频",
                    meta = folderMeta(videos.count, "个", videos.totalBytes),
                    onClick = { nav.safeNavigate("downloadsList/video") },
                    onOpenDir = {
                        if (videos.isPublic) openPublicDir(context, videos.path)
                        else openPrivateDir(context, File(videos.path))
                    }
                )
            }
            item(key = "folder-comic") {
                FolderCard(
                    icon = Icons.Rounded.Folder,
                    title = "漫画",
                    meta = folderMeta(comics.size, "部", comicsSize),
                    onClick = { nav.safeNavigate("downloadsList/comic") },
                    onOpenDir = {
                        if (comicsDir.isPublic) openPublicDir(context, comicsDir.relative.orEmpty())
                        else openPrivateDir(context, File(comicsDir.path))
                    }
                )
            }
            item(key = "folder-bika") {
                FolderCard(
                    icon = Icons.Rounded.Folder,
                    title = "本子",
                    meta = folderMeta(bikas.size, "本", bikasSize),
                    onClick = { nav.safeNavigate("downloadsList/bika") },
                    onOpenDir = {
                        if (bikasDir.isPublic) openPublicDir(context, bikasDir.relative.orEmpty())
                        else openPrivateDir(context, File(bikasDir.path))
                    }
                )
            }
            item(key = "folder-music") {
                FolderCard(
                    icon = Icons.Rounded.Folder,
                    title = "音乐",
                    meta = folderMeta(music.count, "个", music.totalBytes),
                    onClick = { nav.safeNavigate("downloadsList/music") },
                    onOpenDir = {
                        if (music.isPublic) openPublicDir(context, music.path)
                        else openPrivateDir(context, MediaFileStore.musicAppDir(context))
                    }
                )
            }
            item(key = "folder-book") {
                FolderCard(
                    icon = Icons.Rounded.Folder,
                    title = "书籍",
                    meta = folderMeta(books.count, "个", books.totalBytes),
                    onClick = { nav.safeNavigate("downloadsList/book") },
                    onOpenDir = {
                        if (books.isPublic) openPublicDir(context, books.path)
                        else openPrivateDir(context, MediaFileStore.bookAppDir(context))
                    }
                )
            }
        }
    }
}

/** "N 部 · 12 MB"；没有内容时给个引导语 */
private fun folderMeta(count: Int, unit: String, bytes: Long): String =
    if (count > 0) "$count $unit · ${ComicDownloadStore.formatSize(bytes)}" else "暂无下载"

/** 文件夹卡：图标 + 标题 + 数量/大小，点卡片进列表页；行尾「打开系统目录」 */
@Composable
private fun FolderCard(
    icon: ImageVector,
    title: String,
    meta: String,
    onClick: () -> Unit,
    onOpenDir: () -> Unit
) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpenDir) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("打开系统目录", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * 分区下载列表页：某类资源的已下载内容 + 进行中任务。
 * section = video / comic / bika / music / book，由主页文件夹卡或各详情页「我的下载」进入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadListScreen(nav: NavHostController, section: String) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 漫画 / 本子共用 ComicDownloadManager：任务与库按 source 过滤
    val library by ComicDownloadManager.library.collectAsStateWithLifecycle()
    val active by ComicDownloadManager.active.collectAsStateWithLifecycle()
    val videoLibrary by VideoDownloadManager.library.collectAsStateWithLifecycle()
    val videoTasks by VideoDownloadManager.active.collectAsStateWithLifecycle()

    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 离线阅读：漫画 + 章节 key + 页面（支持"下一章"继续阅读已下载章节）
    var viewer by remember { mutableStateOf<Triple<DownloadedComic, String, List<String>>?>(null) }
    // v0.301：删除统一走「编辑 → 勾选 → 删除」，不再有逐条删除按钮
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 音乐/书籍文件增删后刷新枚举
    var mediaRefresh by remember { mutableStateOf(0) }

    fun toggleSelect(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    LaunchedEffect(Unit) {
        ComicDownloadManager.refresh(context)
        VideoDownloadManager.refresh(context)
    }
    // v0.304：回到本页自动刷新文件枚举/下载库（删除或新增后不用退出去再进来）
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        mediaRefresh++
        scope.launch {
            ComicDownloadManager.refresh(context)
            VideoDownloadManager.refresh(context)
        }
    }

    // 音乐 / 书籍：文件系统枚举（IO 放后台线程）；mediaRefresh 变化（编辑删除后）重查
    val music by produceState(MediaFileStore.EMPTY, mediaRefresh, context) {
        value = withContext(Dispatchers.IO) { MediaFileStore.musicSummary(context) }
    }
    val books by produceState(MediaFileStore.EMPTY, mediaRefresh, context) {
        value = withContext(Dispatchers.IO) { MediaFileStore.bookSummary(context) }
    }

    // 音乐元信息（歌手/封面/时长）：进音乐分区时后台批量读取一次（与音乐收藏页对齐用）
    val musicMetas = if (section == "music") {
        produceState<Map<String, MediaFileStore.MusicMeta>>(emptyMap(), music, context) {
            value = withContext(Dispatchers.IO) {
                music.entries.associate { it.name to MediaFileStore.loadMusicMeta(context, it) }
            }
        }
    } else {
        remember { androidx.compose.runtime.mutableStateOf(emptyMap<String, MediaFileStore.MusicMeta>()) }
    }
    // 音乐排序（与音乐收藏同款）：更新（文件时间）/ 歌手
    var musicSort by rememberSaveable { mutableStateOf("added") }

    val comics = remember(library) { library.filterNot { it.source == ComicDownloadStore.BIKA } }
    val bikas = remember(library) { library.filter { it.source == ComicDownloadStore.BIKA } }

    // 本节已下载条目数（决定「编辑」按钮是否出现）
    val downloadedCount = when (section) {
        "video" -> videoLibrary.size
        "comic" -> comics.size
        "bika" -> bikas.size
        "music" -> music.entries.size
        "book" -> books.entries.size
        else -> 0
    }

    /** 先按当前活跃任务做一次快照（Map 顺序稳定，避免下标越界） */
    val allTasks = active.values.toList()
    // 本子（bika）任务归本子页，其余漫画任务归漫画页
    val comicTasks = remember(allTasks) { allTasks.filter { it.source != ComicDownloadStore.BIKA } }
    val bikaTasks = remember(allTasks) { allTasks.filter { it.source == ComicDownloadStore.BIKA } }

    val title = when (section) {
        "video" -> "视频下载"
        "comic" -> "漫画下载"
        "bika" -> "本子下载"
        "music" -> "音乐下载"
        "book" -> "书籍下载"
        else -> "下载"
    }
    // 漫画 / 本子页顶栏「全部取消」（任务非空时出现）
    val sectionTasks = when (section) {
        "comic" -> comicTasks
        "bika" -> bikaTasks
        else -> emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (editing) { editing = false; selected = emptySet() }
                        else nav.safePopBackStack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (sectionTasks.isNotEmpty()) {
                        TextButton(onClick = { ComicDownloadManager.cancelAll() }) { Text("全部取消") }
                    }
                    // v0.301：编辑模式统一删除入口
                    if (downloadedCount > 0) {
                        if (editing) {
                            TextButton(
                                enabled = selected.isNotEmpty(),
                                onClick = { confirmDelete = true }
                            ) {
                                Text(
                                    if (selected.isEmpty()) "删除" else "删除(${selected.size})",
                                    color = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                            TextButton(onClick = { editing = false; selected = emptySet() }) { Text("完成") }
                        } else {
                            TextButton(onClick = { editing = true; selected = emptySet() }) { Text("编辑") }
                        }
                    }
                }
            )
        },
        // v0.303：迷你播放条只在音乐分区显示（其他分区不显示）
        bottomBar = {
            if (section == "music") {
                com.shangyin.app.ui.music.MusicMiniPlayer(nav, Modifier.navigationBarsPadding())
            }
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad).fillMaxSize()
        ) {
            when (section) {
                "video" -> videoSection(
                    context, videoTasks, videoLibrary,
                    editing = editing,
                    selected = selected,
                    onToggle = { toggleSelect("video/$it") },
                    onPlay = { playLocalVideo(nav, it) }
                )
                "comic" -> comicSectionContent(
                    keyPrefix = "comic", comics = comics,
                    tasks = comicTasks, emptyHint = "还没有下载漫画，去漫画详情页点「下载全部」或章节右侧的下载按钮",
                    expanded = expanded, editing = editing, selected = selected,
                    onToggleExpand = { c -> expanded = toggleKey(expanded, c) },
                    onToggleSelect = { c -> toggleSelect("comic/${c.source}/${c.id}") },
                    onReadChapter = { c, chapterKey ->
                        openChapter(context, c, chapterKey) { pages -> viewer = Triple(c, chapterKey, pages) }
                    }
                )
                "bika" -> comicSectionContent(
                    keyPrefix = "bika", comics = bikas,
                    tasks = bikaTasks, emptyHint = "还没有下载本子，去哔咔详情页点「下载全部」或章节右侧的下载按钮",
                    expanded = expanded, editing = editing, selected = selected,
                    onToggleExpand = { c -> expanded = toggleKey(expanded, c) },
                    onToggleSelect = { c -> toggleSelect("comic/${c.source}/${c.id}") },
                    onReadChapter = { c, chapterKey ->
                        openChapter(context, c, chapterKey) { pages -> viewer = Triple(c, chapterKey, pages) }
                    }
                )
                "music" -> musicSectionContent(
                    context, music,
                    metas = musicMetas.value,
                    sortMode = musicSort,
                    onSortChange = { musicSort = it },
                    editing = editing,
                    selected = selected,
                    onToggleSelect = { toggleSelect("music/$it") },
                    onPlay = { songs, idx -> MusicPlayback.play(context, songs, idx) }
                )
                "book" -> bookSectionContent(
                    context, books,
                    editing = editing,
                    selected = selected,
                    onToggleSelect = { toggleSelect("book/$it") },
                    onOpen = { openBook(context, it) }
                )
            }
        }
    }

    // 离线阅读器：末页"下一章"跳到下一个已下载章节（未下载则提示）
    viewer?.let { (c, chapterKey, urls) ->
        // 按章节名中的数字正序（"第 1192 话"排在"第 999 话"之后），与下载先后无关
        val chapters = remember(c.id) { c.chapters.sortedBy { chapterNo(it.name) } }
        val idx = chapters.indexOfFirst { it.key == chapterKey }
        key(idx) {
            PhotoViewerDialog(
                urls = urls,
                initialIndex = 0,
                onDismiss = { viewer = null },
                chapterLabel = chapters.getOrNull(idx)?.name,
                // 下一章话数必须严格递增（chapterNo 解析不到数字的章节视为排在最后），
                // 防止重复/特殊章节导致从"真正的最后一章"误跳
                hasNextChapter = idx in 0 until chapters.lastIndex &&
                    chapterNo(chapters[idx + 1].name) > chapterNo(chapters[idx].name),
                onOpenNextChapter = {
                    val next = chapters.getOrNull(idx + 1)
                    val pages = next?.let { ComicDownloadStore.chapterPages(context, c.source, c.id, it.key) }
                    if (next == null || pages.isNullOrEmpty()) {
                        Toast.makeText(context, "下一章尚未下载：${next?.name ?: "没有更多章节"}", Toast.LENGTH_SHORT).show()
                    } else {
                        viewer = Triple(c, next.key, pages)
                        ReadProgressStore.record(context, c.source, c.id, next.key)
                    }
                }
            )
        }
    }

    // 编辑模式统一删除：先按 key 删真实文件，再清选择、刷新音乐/书籍枚举
    if (confirmDelete) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除所选") },
            text = { Text("确定删除选中的 $n 项下载内容吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        var ok = 0
                        selected.forEach { key ->
                            val done = when {
                                key.startsWith("video/") -> {
                                    videoLibrary.firstOrNull { "video/${it.id}" == key }
                                        ?.let { VideoDownloadManager.delete(context, it) }
                                    true
                                }
                                key.startsWith("comic/") -> {
                                    val parts = key.removePrefix("comic/").split("/")
                                    val comic = library.firstOrNull { it.source == parts.getOrNull(0) && it.id == parts.getOrNull(1) }
                                    if (comic != null) {
                                        ComicDownloadManager.deleteComic(context, comic.source, comic.id); true
                                    } else false
                                }
                                key.startsWith("music/") -> {
                                    // v0.302：直接按枚举出的 Entry 的 URI 删，个别机型按名称匹配不到导致删不掉
                                    val name = key.removePrefix("music/")
                                    music.entries.firstOrNull { it.name == name }
                                        ?.let { MediaFileStore.deleteEntry(context, it) } ?: false
                                }
                                key.startsWith("book/") -> {
                                    val name = key.removePrefix("book/")
                                    books.entries.firstOrNull { it.name == name }
                                        ?.let { MediaFileStore.deleteEntry(context, it) } ?: false
                                }
                                else -> false
                            }
                            if (done) ok++
                        }
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            Toast.makeText(context, "已删除 $ok 项", Toast.LENGTH_SHORT).show()
                            selected = emptySet()
                            editing = false
                            mediaRefresh++
                        }
                    }
                    confirmDelete = false
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

/** 展开态切换：点同一本再点一次收起 */
private fun toggleKey(expanded: Set<String>, c: DownloadedComic): Set<String> {
    val k = "${c.source}/${c.id}"
    return if (k in expanded) expanded - k else expanded + k
}

/** 打开某章离线阅读（页面为空 = 本地文件缺失） */
private fun openChapter(
    context: Context,
    c: DownloadedComic,
    chapterKey: String,
    onOpen: (List<String>) -> Unit
) {
    val pages = ComicDownloadStore.chapterPages(context, c.source, c.id, chapterKey)
    if (pages.isEmpty()) Toast.makeText(context, "该章节本地文件缺失", Toast.LENGTH_SHORT).show()
    else {
        onOpen(pages)
        ReadProgressStore.record(context, c.source, c.id, chapterKey)
    }
}

/** 播放本地已下载视频：content://（Q+ 公共目录）或 file://（8/9 私有目录）塞进 PlayerSession 复用播放页 */
private fun playLocalVideo(nav: NavHostController, v: DownloadedVideo) {
    val playUri = if (v.location.startsWith("content:")) v.location else "file://" + v.location
    PlayerSession.itemId = -1L
    PlayerSession.title = v.title
    PlayerSession.sourceName = "本地下载"
    PlayerSession.subTitle = ""
    PlayerSession.isLive = false
    PlayerSession.streamHeaders = emptyMap()
    PlayerSession.liveQualities = emptyList()
    PlayerSession.liveRoom = null
    PlayerSession.startPosMs = 0L
    PlayerSession.groupIndex = 0
    PlayerSession.startIndex = 0
    PlayerSession.groups = listOf(
        VodPlayGroup("本地", listOf(VodEpisode(v.title, playUri)))
    )
    nav.navigate("player")
}

/** 视频下载进行中任务卡：进度条 / 失败信息 + 取消、移除 */
@Composable
private fun VideoTaskCard(task: VideoTask, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            if (task.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "失败：${task.error}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = onDismiss) { Text("移除") }
                }
            } else if (task.progress < 0) {
                // v0.230：服务器未告知总大小（chunked）→ 不确定进度（转圈）
                // v0.249：转圈附带"已下载 x MB"——纯转圈看起来像没动（用户反馈 0.248）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onCancel) { Text("取消") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "下载中… 已下载 ${"%.1f".format(task.bytesCopied / 1048576f)} MB",
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // v0.252：确定进度也显示实时字节——「进度条不动」时立刻分辨是
                    // UI 没刷新（%不动但MB动）还是流水线卡死（两者都不动）
                    Text(
                        "${(task.progress * 100).toInt()}% · 已下载 ${"%.1f".format(task.bytesCopied / 1048576f)} MB",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        }
    }
}

/** 毫秒 → "x:xx / xx:xx"；拿不到时长返回 null */
private fun formatDuration(ms: Long): String? {
    if (ms <= 0) return null
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 已下载视频卡（v0.301：双列网格，封面 + 时长角标 + 大小 + 编辑勾选） */
@Composable
private fun VideoGridCard(
    video: DownloadedVideo,
    editing: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit
) {
    val context = LocalContext.current
    val meta by produceState(MediaFileStore.VideoMeta(), video.location) {
        value = withContext(Dispatchers.IO) { MediaFileStore.loadVideoMeta(context, video.location) }
    }
    Card(Modifier.fillMaxWidth().clickable(enabled = !editing) { onPlay() }) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // v0.302：优先收藏时的封面图（下载时记录），缺失再退回本地抽帧
                if (video.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = video.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else meta.thumbnail?.let { bmp ->
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: run {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.Center).size(32.dp)
                    )
                }
                // 右下角时长角标（黑底白字，封面缺失时不显示）
                formatDuration(meta.durationMs)?.let { d ->
                    Text(
                        d,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                // 编辑模式：整卡左上角勾选圈
                if (editing) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .clickable { onToggle() }
                    )
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (selected) "已选中" else "未选中",
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(24.dp)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    // v0.304：原来的「x1」没意义，改成来源（吃瓜 / P站 / X站 / 片源名）
                    "${video.sourceName.ifBlank { "视频" }} · ${ComicDownloadStore.formatSize(video.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 每节标题：左右分别是「标题」与「数量 · 大小」 */
@Composable
private fun SectionHeader(title: String, meta: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

/** 视频分区：下载中任务 + 已下载封面网格（v0.301 双列，点击播放，编辑模式勾选删除） */
private fun LazyListScope.videoSection(
    context: Context,
    tasks: Map<String, VideoTask>,
    library: List<DownloadedVideo>,
    editing: Boolean,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onPlay: (DownloadedVideo) -> Unit
) {
    if (tasks.isNotEmpty()) {
        items(tasks.values.sortedBy { it.title }, key = { "video-task-${it.url}" }) { t ->
            VideoTaskCard(
                task = t,
                onCancel = { VideoDownloadManager.cancel(context, t.url) },
                onDismiss = { VideoDownloadManager.dismiss(t.url) }
            )
        }
    }
    if (library.isEmpty() && tasks.isEmpty()) {
        item(key = "video-empty") {
            EmptyHint("还没有下载视频，播放器右上角点下载按钮试试（吃瓜/在线观影都支持）")
        }
    } else {
        val sorted = library.sortedByDescending { it.createdAt }
        // LazyColumn 里模拟双列网格：两两一组成行
        items(
            sorted.chunked(2),
            key = { row -> "video-row-${row.joinToString("_") { it.id.toString() }}" }
        ) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { v ->
                    Box(Modifier.weight(1f)) {
                        VideoGridCard(
                            video = v,
                            editing = editing,
                            selected = "video/${v.id}" in selected,
                            onToggle = { onToggle(v.id) },
                            onPlay = { onPlay(v) }
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 音乐分区（v0.302）：与音乐收藏页一模一样——
 * 排序行（更新/歌手）+ 歌曲行（52dp 封面 + 歌名 + 歌手，整行可点），底部迷你播放条；
 * 编辑模式仍走勾选删除。
 */
private fun LazyListScope.musicSectionContent(
    context: Context,
    summary: MediaFileStore.Summary,
    metas: Map<String, MediaFileStore.MusicMeta>,
    sortMode: String,
    onSortChange: (String) -> Unit,
    editing: Boolean,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onPlay: (List<com.shangyin.app.data.music.MusicSong>, Int) -> Unit
) {
    if (summary.entries.isEmpty()) {
        item(key = "music-empty") { EmptyHint("还没有下载音乐，去音乐页点下载按钮吧") }
        return
    }
    // 歌手排序：按内嵌歌手标签（读不到的排后面）；更新排序：保持枚举的修改时间倒序
    val entries = if (sortMode == "artist") {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
        summary.entries.sortedWith(
            compareBy(collator) { metas[it.name]?.artist?.ifBlank { "￣" } ?: "￣" }
        )
    } else {
        summary.entries
    }
    val songs = entries.map { e ->
        com.shangyin.app.data.music.MusicSong(
            platform = com.shangyin.app.data.music.MusicPlatform.LOCAL,
            id = e.name,
            name = e.name.substringBeforeLast('.'),
            artists = metas[e.name]?.artist.orEmpty().ifBlank { "本地下载" },
            raw = mapOf("path" to e.uri.orEmpty())
        )
    }
    // 排序行（与音乐收藏同款）
    item(key = "sort_row") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "排序",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = sortMode == "added",
                onClick = { onSortChange("added") },
                label = { Text("更新") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = sortMode == "artist",
                onClick = { onSortChange("artist") },
                label = { Text("歌手") }
            )
        }
    }
    items(entries, key = { "music-${it.name}" }) { e ->
        val idx = entries.indexOf(e)
        MusicFileRow(
            context = context,
            entry = e,
            song = songs[idx],
            meta = metas[e.name] ?: MediaFileStore.MusicMeta(),
            editing = editing,
            selected = "music/${e.name}" in selected,
            onToggleSelect = { onToggleSelect(e.name) },
            onPlay = { onPlay(songs, idx) }
        )
    }
}

/** 书籍分区（v0.302）：封面行（PDF/EPUB 提取封面，点击直接打开阅读），编辑模式勾选删除 */
private fun LazyListScope.bookSectionContent(
    context: Context,
    summary: MediaFileStore.Summary,
    editing: Boolean,
    selected: Set<String>,
    onToggleSelect: (String) -> Unit,
    onOpen: (MediaFileStore.Entry) -> Unit
) {
    if (summary.entries.isEmpty()) {
        item(key = "book-empty") { EmptyHint("还没有下载书籍，去图书详情页点「下载」吧") }
    } else {
        items(summary.entries, key = { "book-${it.name}" }) { e ->
            BookFileRow(
                context = context,
                entry = e,
                editing = editing,
                selected = "book/${e.name}" in selected,
                onToggleSelect = { onToggleSelect(e.name) },
                onOpen = { onOpen(e) }
            )
        }
    }
}

/** 勾选圈（编辑模式）：选中 = primary 实心，未选中 = 灰圈 */
@Composable
private fun SelectDot(selected: Boolean, onClick: () -> Unit) {
    Icon(
        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
        contentDescription = if (selected) "已选中" else "未选中",
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .size(26.dp)
            .clickable { onClick() }
    )
}

/** 音乐文件行（v0.302 对齐音乐收藏行样式）：52dp 封面 + 歌名 + 歌手，整行可点播放 */
@Composable
private fun MusicFileRow(
    context: Context,
    entry: MediaFileStore.Entry,
    song: com.shangyin.app.data.music.MusicSong,
    meta: MediaFileStore.MusicMeta,
    editing: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !editing) { onPlay() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editing) {
            SelectDot(selected) { onToggleSelect() }
            Spacer(Modifier.width(10.dp))
        }
        // 52dp 封面：内嵌图 → 音符占位
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (meta.cover != null) {
                androidx.compose.foundation.Image(
                    bitmap = meta.cover.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.name.substringBeforeLast('.'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                meta.artist.ifBlank { "本地下载" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 书籍文件行：封面（PDF 首页 / EPUB cover）+ 文件名 + 打开阅读 */
@Composable
private fun BookFileRow(
    context: Context,
    entry: MediaFileStore.Entry,
    editing: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit
) {
    // 封面提取放后台（PDF 渲染 / EPUB 解压较慢），只取一次
    var cover by remember(entry.name) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var tried by remember(entry.name) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(entry.name) {
        if (!tried) {
            tried = true
            cover = withContext(Dispatchers.IO) { MediaFileStore.loadBookCover(context, entry) }
        }
    }
    val ext = entry.name.substringAfterLast('.', "").uppercase()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !editing) { onOpen() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (editing) {
            SelectDot(selected) { onToggleSelect() }
            Spacer(Modifier.width(10.dp))
        }
        Box(
            Modifier
                .size(width = 42.dp, height = 56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) {
                androidx.compose.foundation.Image(
                    bitmap = cover!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    ext.ifBlank { "书" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${ext.ifBlank { "文件" }} · ${ComicDownloadStore.formatSize(entry.size)} · 点击阅读",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 打开本地书籍：PDF 用系统查看器，EPUB/TXT/MOBI/AZW3 尝试系统应用，失败给提示 */
private fun openBook(context: Context, entry: MediaFileStore.Entry) {
    val uriStr = entry.uri ?: run {
        Toast.makeText(context, "文件不可访问", Toast.LENGTH_SHORT).show(); return
    }
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    runCatching {
        val uri = if (uriStr.startsWith("content:")) {
            android.net.Uri.parse(uriStr)
        } else {
            // 8/9 私有目录 file:// → FileProvider（外部应用读不了私有路径）
            FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(uriStr.removePrefix("file://"))
            )
        }
        val mime = when (ext) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "mobi" -> "application/x-mobipocket-ebook"
            "azw3" -> "application/vnd.amazon.ebook"
            "txt" -> "text/plain"
            else -> "*/*"
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "没有找到能打开 $ext 的应用", Toast.LENGTH_SHORT).show()
    }
}

/** 漫画 / 本子的一节：下载中任务 + 已下载条目卡片（展开逐章；v0.301 编辑勾选删除；v0.302 去掉数量·大小标题） */
private fun LazyListScope.comicSectionContent(
    keyPrefix: String,
    comics: List<DownloadedComic>,
    tasks: List<ComicDownloadManager.Task>,
    emptyHint: String,
    expanded: Set<String>,
    editing: Boolean,
    selected: Set<String>,
    onToggleExpand: (DownloadedComic) -> Unit,
    onToggleSelect: (DownloadedComic) -> Unit,
    onReadChapter: (DownloadedComic, String) -> Unit
) {
    if (tasks.isNotEmpty()) {
        item(key = "$keyPrefix-tasks-header") { SectionHeader("下载中（${tasks.size}）", "") }
        items(tasks, key = { "task-${it.source}/${it.id}/${it.chapterKey}" }) { t ->
            DownloadTaskCard(t)
        }
    }
    if (comics.isEmpty()) {
        item(key = "$keyPrefix-empty") { EmptyHint(emptyHint) }
    } else {
        items(comics, key = { "$keyPrefix-${it.source}/${it.id}" }) { comic ->
            ComicDownloadRow(
                comic = comic,
                expanded = "${comic.source}/${comic.id}" in expanded,
                editing = editing,
                selected = "comic/${comic.source}/${comic.id}" in selected,
                onToggleExpand = { onToggleExpand(comic) },
                onToggleSelect = { onToggleSelect(comic) },
                onReadChapter = { key -> onReadChapter(comic, key) }
            )
        }
    }
}

/** 进行中的下载任务卡片（保持原功能） */
@Composable
private fun DownloadTaskCard(t: ComicDownloadManager.Task) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "${t.title} · ${t.chapterName}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            if (t.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "失败：${t.error}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = {
                        ComicDownloadManager.dismiss(t.source, t.id, t.chapterKey)
                    }) { Text("移除") }
                }
            } else if (t.total <= 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("获取图片列表…", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                LinearProgressIndicator(
                    progress = { t.done.toFloat() / t.total },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(
                        "${t.done}/${t.total} 张",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        ComicDownloadManager.cancel(t.source, t.id, t.chapterKey)
                    }) { Text("取消") }
                }
            }
        }
    }
}

/** 单本已下载漫画：封面 + 标题 + 大小，展开后逐章离线阅读（v0.301：编辑模式用勾选圈替代删除按钮） */
@Composable
private fun ComicDownloadRow(
    comic: DownloadedComic,
    expanded: Boolean,
    editing: Boolean,
    selected: Boolean,
    onToggleExpand: () -> Unit,
    onToggleSelect: () -> Unit,
    onReadChapter: (String) -> Unit
) {
    val context = LocalContext.current
    val size = remember(comic) { ComicDownloadStore.size(context, comic.source, comic.id) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable {
                    if (editing) onToggleSelect() else onToggleExpand()
                }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(width = 54.dp, height = 72.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                ) {
                    if (!comic.cover.isNullOrBlank()) {
                        AsyncImage(
                            model = comic.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        comic.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${if (comic.source == "bika") "本子" else "漫画"} · 已下载 ${comic.chapters.size} 章 · ${ComicDownloadStore.formatSize(size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (editing) {
                    SelectDot(selected) { onToggleSelect() }
                } else {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val progressMap by ReadProgressStore.all.collectAsStateWithLifecycle(ReadProgressStore.all.value)
            if (expanded && !editing) {
                comic.chapters.sortedBy { chapterNo(it.name) }.forEach { ch ->
                    val isLast = ch.key == progressMap["${comic.source}/${comic.id}"]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isLast) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onReadChapter(ch.key) }
                            .padding(start = 78.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            ch.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isLast) {
                            Text(
                                "上次看到",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        Text(
                            "${ch.count} 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 章节名排序键：取名字里第一串数字（"第 1192 话"排在"第 999 话"之后），无数字排最后 */
private fun chapterNo(name: String): Long =
    Regex("\\d+").find(name)?.value?.toLongOrNull() ?: Long.MAX_VALUE

/** 外部存储提供者（系统文件管理器）的 authority */
private const val EXTERNAL_STORAGE_AUTH = "com.android.externalstorage.documents"

/**
 * 打开公共目录（如 Download/老郑分享/音乐）。
 * `primary:` 后面是**相对外部存储根**的路径，不能有前导斜杠。
 */
private fun openPublicDir(context: Context, relative: String) {
    val docId = "primary:" + relative.trim('/')
    launchDir(context, docId) {
        Toast.makeText(context, "系统文件管理器打不开该目录：$relative", Toast.LENGTH_LONG).show()
    }
}

/**
 * 打开应用私有目录。Android 11+ 基本打不开，先尝试同样的 ACTION_VIEW，
 * 失败就 Toast 说明（含绝对路径），绝不崩。
 */
private fun openPrivateDir(context: Context, dir: File) {
    val abs = dir.absolutePath
    val extRoot = Environment.getExternalStorageDirectory().absolutePath
    val docId = if (abs.startsWith("$extRoot/")) {
        "primary:" + abs.removePrefix("$extRoot/").trim('/')
    } else null
    val fail: () -> Unit = {
        Toast.makeText(context, "该目录在应用私有空间，系统文件管理器无法直接打开：$abs", Toast.LENGTH_LONG).show()
    }
    if (docId == null) fail() else launchDir(context, docId, fail)
}

/**
 * 用系统文件管理器打开一个 documents 目录，**多级兜底**（部分国产 ROM 不认通用 ACTION_VIEW）：
 * 1. 通用 `ACTION_VIEW`（原生系统文件管理器认它）；
 * 2. 显式指定 DocumentsUI 包名再试（部分系统把文件管理器从隐式匹配里排除了）；
 * 3. `ACTION_OPEN_DOCUMENT_TREE` + `EXTRA_INITIAL_URI`（系统文件选择器直接定位到该目录，全部 ROM 都有）；
 * 全失败才走 onFail（Toast 提示路径）。任何一级成功即返回。
 */
private fun launchDir(context: Context, docId: String, onFail: () -> Unit) {
    val uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTH, docId)
    // 1. 通用 ACTION_VIEW
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (tryStartActivity(context, view)) return
    // 2. 显式指定系统文件管理器（DocumentsUI 的两个常见包名）
    for (pkg in listOf("com.android.documentsui", "com.google.android.documentsui")) {
        if (tryStartActivity(context, Intent(view).apply { setPackage(pkg) })) return
    }
    // 3. 文件选择器定位到该目录（R+ 支持 INITIAL_URI，低版本会打开选择器根目录）
    val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }
    }
    if (tryStartActivity(context, picker)) {
        Toast.makeText(context, "已在文件选择器中定位该目录", Toast.LENGTH_SHORT).show()
        return
    }
    onFail()
}

/** 起 Activity，任何异常（没有可处理的应用等）都算失败 */
private fun tryStartActivity(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent) }.isSuccess
