package com.shangyin.app.ui.comic

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.Repo
import com.shangyin.app.data.download.ComicDownloadManager
import com.shangyin.app.data.download.DownloadedChapter
import com.shangyin.app.data.download.DownloadedComic
import com.shangyin.app.data.ReadProgressStore
import com.shangyin.app.ui.settings.SettingsStore
import com.shangyin.app.data.komiic.KomiicCategory
import com.shangyin.app.data.komiic.KomiicClient
import com.shangyin.app.data.komiic.KomiicChapter
import com.shangyin.app.data.komiic.KomiicComic
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.LoadingPill
import com.shangyin.app.ui.common.PhotoGridDialog
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val komiicJson = Json { ignoreUnknownKeys = true }

private const val COMIC_PAGE_SIZE = 20

/** 状态筛选本地最多翻的页数（服务端忽略 status 参数，需本地过滤凑满一屏） */
private const val STATUS_FILTER_MAX_ROUNDS = 6

@Serializable
private data class ComicHomeCache(
    val tab: Int,
    val catId: String,
    val status: String,
    val keyword: String,
    val nextOffset: Int,
    val items: List<KomiicComic>
)

/** 状态徽标文案 */
private fun statusLabel(s: String?) = when (s) {
    "ONGOING" -> "连载"
    "END" -> "完结"
    else -> null
}

/**
 * 漫画主页（Komiic）：搜索框 + 最近更新/热门/连载/完结（同一行）+ 可展开分类筛选 + 3 列封面网格。
 * 会话级缓存：返回里世界再进不重新加载（rememberSaveable 存 JSON）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComicHomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var cacheJson by rememberSaveable { mutableStateOf<String?>(null) }

    var tab by rememberSaveable { mutableIntStateOf(0) }          // 0=最近更新 1=热门
    var catId by rememberSaveable { mutableStateOf("0") }         // "0"=全部
    var status by rememberSaveable { mutableStateOf("") }         // ""/ONGOING/END
    var keyword by rememberSaveable { mutableStateOf("") }        // 非空=搜索模式
    var input by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<KomiicComic>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf<List<KomiicCategory>>(emptyList()) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var catExpanded by rememberSaveable { mutableStateOf(false) }  // 分类展开/收起
    var loadJob by remember { mutableStateOf<Job?>(null) }         // 进行中的请求（切换筛选时取消）

    val cacheKey = "$tab|$catId|$status|$keyword"

    suspend fun fetchPage(off: Int): List<KomiicComic> = when {
        keyword.isNotBlank() -> KomiicClient.search(keyword, off)
        catId != "0" -> KomiicClient.comicByCategory(catId, off, status)
        tab == 0 -> KomiicClient.recentUpdate(off, status)
        else -> KomiicClient.hotComics(off, status)
    }

    /** 切换筛选 / 搜索时取消上一次请求，避免"点了分类没反应" */
    fun load(reset: Boolean) {
        loadJob?.cancel()
        if (reset) {
            offset = 0
            items = emptyList()
        }
        error = null
        loading = true
        loadJob = scope.launch {
            var next = if (reset) 0 else offset
            runCatching {
                if (status.isBlank() || keyword.isNotBlank()) {
                    val page = fetchPage(next)
                    next += page.size
                    page
                } else {
                    // 服务端忽略 status → 本地过滤，必要时多翻几页凑满一屏
                    val out = mutableListOf<KomiicComic>()
                    var rounds = 0
                    while (rounds < STATUS_FILTER_MAX_ROUNDS && out.size < COMIC_PAGE_SIZE) {
                        val page = fetchPage(next)
                        if (page.isEmpty()) break
                        next += page.size
                        out += page.filter { it.status == status }
                        rounds++
                        if (page.size < COMIC_PAGE_SIZE) break
                    }
                    out
                }
            }.onSuccess { list ->
                items = if (reset) list else items + list
                offset = next
                cacheJson = komiicJson.encodeToString(
                    ComicHomeCache(tab, catId, status, keyword, offset, items)
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                error = (e.message?.takeIf { m -> m.isNotBlank() } ?: e::class.simpleName) ?: "网络错误"
            }
            loading = false
        }
    }

    // 分类表 + 会话缓存恢复（仅一次）
    LaunchedEffect(Unit) {
        if (categories.isEmpty()) {
            runCatching { KomiicClient.allCategory() }.onSuccess { categories = it }
        }
        if (cacheJson != null) {
            runCatching { komiicJson.decodeFromString<ComicHomeCache>(cacheJson!!) }.onSuccess { c ->
                // 缓存恢复优先：取消首帧发起的那次请求，避免结果覆盖已恢复的列表
                loadJob?.cancel()
                loading = false
                tab = c.tab; catId = c.catId; status = c.status; keyword = c.keyword
                input = c.keyword; offset = c.nextOffset; items = c.items
                loadedKey = "$tab|$catId|$status|$keyword"
            }
        }
    }
    // 筛选条件变化 → 重载（有会话缓存时首帧跳过）
    LaunchedEffect(cacheKey) {
        if (cacheKey != loadedKey) {
            loadedKey = cacheKey
            load(true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("漫画") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("downloads") }) {
                        Icon(Icons.Rounded.Download, contentDescription = "我的下载")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 搜索框（回车触发）
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索漫画", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    keyword = input.trim()
                }),
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = {
                            input = ""
                            keyword = ""
                        }) { Icon(Icons.Rounded.Close, contentDescription = "清空", modifier = Modifier.padding(4.dp)) }
                    }
                }
            )
            if (keyword.isBlank()) {
                // 最近更新 / 热门 / 连载中 / 完结 同一行
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                ) {
                    listOf("最近更新" to 0, "热门" to 1).forEach { (label, t) ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(label) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    listOf("连载" to "ONGOING", "完结" to "END").forEach { (label, s) ->
                        FilterChip(
                            selected = status == s,
                            onClick = { status = if (status == s) "" else s },
                            label = { Text(label) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
                // 分类：收起=横滑一行 + 展开箭头；展开=换行 chips（限高可滚）
                if (!catExpanded) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            FilterChip(
                                selected = catId == "0",
                                onClick = { catId = "0" },
                                label = { Text("全部") },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            categories.forEach { c ->
                                FilterChip(
                                    selected = catId == c.id,
                                    onClick = { catId = c.id },
                                    label = { Text(c.name) },
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                        IconButton(onClick = { catExpanded = true }) {
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "展开分类")
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .heightIn(max = 260.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("全部分类", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { catExpanded = false }) {
                                Icon(Icons.Rounded.ArrowDropUp, contentDescription = "收起分类")
                            }
                        }
                        FlowRow(
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            FilterChip(
                                selected = catId == "0",
                                onClick = { catId = "0"; catExpanded = false },
                                label = { Text("全部") }
                            )
                            categories.forEach { c ->
                                FilterChip(
                                    selected = catId == c.id,
                                    onClick = { catId = c.id; catExpanded = false },
                                    label = { Text(c.name) }
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("「$keyword」的搜索结果", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { input = ""; keyword = "" }) { Text("返回浏览") }
                }
            }

            when {
                loading && items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null && items.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(error ?: "加载失败", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { load(true) }) { Text("重试") }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(items.size, key = { items[it].id }) { i ->
                            val c = items[i]
                            Column(Modifier.clickable { nav.safeNavigate("comicDetail/${c.id}") }) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(1f / 1.35f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    AsyncImage(
                                        model = c.imageUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    statusLabel(c.status)?.let { label ->
                                        Surface(
                                            color = if (c.status == "ONGOING") Color(0xFF2E7D32) else Color(0xFF616161),
                                            contentColor = Color.White,
                                            shape = RoundedCornerShape(bottomEnd = 8.dp),
                                            modifier = Modifier.align(Alignment.TopStart)
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    c.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    c.authors.joinToString("、") { it.name },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    loading -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                                    error != null -> TextButton(onClick = { load(false) }) { Text("重试") }
                                    else -> TextButton(
                                        onClick = { load(false) },
                                        enabled = items.isNotEmpty()
                                    ) {
                                        Text("加载更多")
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

/**
 * 漫画详情（Komiic）：封面信息 + 章节列表（正序/倒序、单章/整套下载）+ 查看全部 + 收藏到里世界清单。
 * 章节图片 URL 由 KomiicClient 追加 fragment，全局拦截器转 Referer，PhotoViewerDialog 零改动。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComicDetailScreen(nav: NavHostController, comicId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<KomiicComic?>(null) }
    var chapters by remember { mutableStateOf<List<KomiicChapter>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var retried by remember { mutableStateOf(false) }
    var viewerUrls by remember { mutableStateOf<List<String>?>(null) }
    var viewerIdx by remember { mutableIntStateOf(-1) }
    var showCollect by remember { mutableStateOf(false) }
    var sortDesc by rememberSaveable { mutableStateOf(SettingsStore.chapterSortDesc) }
    // 查看全部（合并全部章节）
    var allImages by remember { mutableStateOf<List<String>?>(null) }
    var loadingAll by remember { mutableStateOf(false) }
    var allProgress by remember { mutableStateOf("") }
    var allJob by remember { mutableStateOf<Job?>(null) }
    // 单章图片总览（章节行网格按钮）
    var gridIdx by remember { mutableIntStateOf(-1) }
    var gridUrls by remember { mutableStateOf<List<String>?>(null) }

    val ordered = remember(chapters, sortDesc) { if (sortDesc) chapters.reversed() else chapters }
    // 按话数(serial)正序的章节表，与界面正/倒序无关："下一章"永远指向数字上的下一话
    val bySerial = remember(chapters) { chapters.sortedBy { it.serial?.toIntOrNull() ?: Int.MAX_VALUE } }
    // 阅读进度（用于"上次看到"高亮）
    ReadProgressStore.ensure(context)
    val progressMap by ReadProgressStore.all.collectAsStateWithLifecycle(ReadProgressStore.all.value)
    val lastReadKey = progressMap["komiic/$comicId"]

    // 收藏状态（右上角心形高亮）
    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val collected = remember(allItems, comicId) {
        allItems.any { it.category == "漫画" && it.doubanId == comicId }
    }

    // 离线下载状态
    val library by ComicDownloadManager.library.collectAsStateWithLifecycle()
    val activeTasks by ComicDownloadManager.active.collectAsStateWithLifecycle()
    val downloadedKeys = remember(library, comicId) {
        library.firstOrNull { it.source == "komiic" && it.id == comicId }
            ?.chapters?.map { it.key }?.toSet() ?: emptySet()
    }
    LaunchedEffect(comicId) { ComicDownloadManager.refresh(context) }

    LaunchedEffect(retryKey) {
        loading = true
        error = null
        if (comicId.isBlank()) {
            error = "参数异常：漫画 ID 为空，请返回后重新进入"
            loading = false
            return@LaunchedEffect
        }
        // 串行请求：避免并行协程取消连锁导致错误信息丢失（message=null 只能显示"网络错误"）
        runCatching {
            val d = KomiicClient.comicById(comicId)
            detail = d
            chapters = KomiicClient.chapters(comicId)
        }.onFailure {
            error = buildString {
                append(it::class.simpleName ?: "Exception")
                it.message?.takeIf { m -> m.isNotBlank() }?.let { m -> append(": $m") }
            }
            // 网络抖动自动重试一次
            if (!retried) {
                retried = true
                kotlinx.coroutines.delay(1000)
                retryKey++
            }
        }
        loading = false
    }

    fun chapterName(i: Int, ch: KomiicChapter) = ch.serial ?: "第 ${i + 1} 话"

    /** 打开第 idx 章（基于当前排序） */
    fun openChapter(idx: Int) {
        val ch = ordered.getOrNull(idx) ?: return
        scope.launch {
            runCatching { KomiicClient.fetchChapterImages(comicId, ch.id) }
                .onSuccess { urls ->
                    if (urls.isEmpty()) Toast.makeText(context, "该章节暂无图片", Toast.LENGTH_SHORT).show()
                    else {
                        viewerIdx = idx
                        viewerUrls = urls
                        ReadProgressStore.record(context, "komiic", comicId, ch.id)
                    }
                }
                .onFailure {
                    val msg = (it.message?.takeIf { m -> m.isNotBlank() } ?: it::class.simpleName) ?: "网络错误"
                    Toast.makeText(context, "获取图片失败：$msg", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 按 chapterId 打开章节（映射回当前排序下的下标，供"下一章"跨排序推进用） */
    fun openChapterByCh(ch: KomiicChapter) {
        val idx = ordered.indexOfFirst { it.id == ch.id }
        if (idx >= 0) openChapter(idx)
    }

    /** 下载指定章节（1 章或整套） */
    fun download(targets: List<Int>) {
        val list = targets.mapNotNull { ordered.getOrNull(it)?.let { ch -> it to ch } }
        if (list.isEmpty()) return
        val meta = DownloadedComic(
            source = "komiic",
            id = comicId,
            title = detail?.title ?: "漫画",
            cover = detail?.imageUrl
        )
        ComicDownloadManager.enqueue(
            context,
            meta,
            list.map { (i, ch) -> DownloadedChapter(ch.id, chapterName(i, ch), ch.size) }
        ) { key -> KomiicClient.fetchChapterImages(comicId, key) }
        Toast.makeText(context, "已加入下载（${list.size} 章），可在「我的下载」查看", Toast.LENGTH_SHORT).show()
    }

    /** 查看全部：按章节顺序合并全部图片，网格浏览（可中途关闭取消剩余获取） */
    fun fetchAllImages() {
        if (loadingAll) return
        allJob = scope.launch {
            loadingAll = true
            val list = mutableListOf<String>()
            runCatching {
                chapters.forEachIndexed { i, ch ->
                    allProgress = "${i + 1}/${chapters.size}"
                    list.addAll(KomiicClient.fetchChapterImages(comicId, ch.id))
                }
            }.onSuccess {
                if (list.isEmpty()) Toast.makeText(context, "暂无图片", Toast.LENGTH_SHORT).show()
                else allImages = list
            }.onFailure { e ->
                if (e !is CancellationException) {
                    val msg = (e.message?.takeIf { m -> m.isNotBlank() } ?: e::class.simpleName) ?: "网络错误"
                    Toast.makeText(context, "获取图片失败：$msg", Toast.LENGTH_SHORT).show()
                }
            }
            loadingAll = false
        }
    }

    /** 单章图片总览：获取指定章节图片并打开网格（不合并其他章节） */
    fun openChapterGrid(idx: Int) {
        if (gridIdx >= 0) return
        val ch = ordered.getOrNull(idx) ?: return
        gridIdx = idx
        gridUrls = null
        scope.launch {
            runCatching { KomiicClient.fetchChapterImages(comicId, ch.id) }
                .onSuccess { urls ->
                    if (idx != gridIdx) return@launch
                    if (urls.isEmpty()) {
                        Toast.makeText(context, "该章节暂无图片", Toast.LENGTH_SHORT).show()
                        gridIdx = -1
                    } else gridUrls = urls
                }
                .onFailure { e ->
                    if (idx != gridIdx) return@launch
                    val msg = (e.message?.takeIf { m -> m.isNotBlank() } ?: e::class.simpleName) ?: "网络错误"
                    Toast.makeText(context, "获取图片失败：$msg", Toast.LENGTH_SHORT).show()
                    gridIdx = -1
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.title ?: "漫画详情", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("downloads") }) {
                        Icon(Icons.Rounded.Download, contentDescription = "我的下载")
                    }
                    IconButton(onClick = { showCollect = true }) {
                        Icon(
                            if (collected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (collected) Color(0xFFEF5350) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { pad ->
        when {
            loading -> Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { retryKey++ }) { Text("重试") }
            }
            else -> {
                val c = detail
                Box(Modifier.padding(pad).fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (c == null) return@LazyColumn
                    // 头部
                    item {
                        Row(Modifier.fillMaxWidth().padding(16.dp)) {
                            AsyncImage(
                                model = c.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(110.dp)
                                    .aspectRatio(1f / 1.35f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    c.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    c.authors.joinToString("、") { it.name }.ifBlank { "佚名" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    listOfNotNull(
                                        statusLabel(c.status),
                                        c.lastChapterUpdate?.take(10)?.let { "更新于 $it" }
                                    ).joinToString(" · ").ifBlank { null } ?: "—",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (c.categories.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        c.categories.joinToString(" / ") { it.name },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    // 章节区：正序/倒序 + 下载全部 + 查看全部
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (chapters.isEmpty()) "章节" else "章节（${chapters.size}）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(start = 8.dp)
                            )
                            TextButton(
                                onClick = { sortDesc = !sortDesc; SettingsStore.chapterSortDesc = sortDesc },
                                enabled = chapters.size > 1
                            ) {
                                Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(if (sortDesc) "倒序" else "正序", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = { download(ordered.indices.toList()) },
                                enabled = chapters.isNotEmpty()
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("下载全部", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = { fetchAllImages() },
                                enabled = !loadingAll && chapters.isNotEmpty()
                            ) {
                                Text(if (loadingAll) "获取中 $allProgress" else "查看全部", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    items(ordered.size) { i ->
                        val ch = ordered[i]
                        val task = activeTasks[ComicDownloadManager.key("komiic", comicId, ch.id)]
                        val done = ch.id in downloadedKeys
                        val isLast = ch.id == lastReadKey
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (isLast) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                                .clickable { openChapter(i) }
                                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                chapterName(i, ch),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (ch.type == "book") {
                                Text(
                                    "单行本",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            if (isLast) {
                                Text(
                                    "上次看到",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            Text(
                                "${ch.size} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 本章图片总览（网格浏览本章，不合并其他章节）
                            IconButton(
                                onClick = { openChapterGrid(i) },
                                enabled = gridIdx < 0
                            ) {
                                Icon(
                                    Icons.Rounded.GridView,
                                    contentDescription = "本章图片总览",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            when {
                                task != null && task.error == null -> if (task.total > 0) {
                                    // 下载中：进度条 + 已完成张数
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
                                    ) {
                                        Text(
                                            "${task.done}/${task.total} 张",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        LinearProgressIndicator(
                                            progress = { task.done.toFloat() / task.total },
                                            modifier = Modifier.width(72.dp).height(4.dp),
                                            strokeCap = StrokeCap.Round
                                        )
                                    }
                                } else {
                                    // 还在获取图片列表
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp)
                                    ) {
                                        CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "获取中",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                done -> IconButton(onClick = { nav.safeNavigate("downloads") }) {
                                    Icon(
                                        Icons.Rounded.DownloadDone,
                                        contentDescription = "已下载",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                else -> IconButton(
                                    onClick = { download(listOf(i)) },
                                    enabled = ordered.isNotEmpty()
                                ) {
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
                    item { Spacer(Modifier.height(24.dp)) }
                    }
                    // 底部加载提示：让用户知道"查看全部/本章总览"点击已生效
                    when {
                        loadingAll -> LoadingPill(
                            "正在获取全部图片 $allProgress",
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                        )
                        gridIdx >= 0 && gridUrls == null -> LoadingPill(
                            "正在获取本章图片…",
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                        )
                    }
                }
            }
        }
    }

    // 全屏阅读器（双模式 + 缩放 + 长按保存 + 末页询问下一章）
    viewerUrls?.let { urls ->
        val label = ordered.getOrNull(viewerIdx)?.let { chapterName(viewerIdx, it) }
        // 下一章 = 按话数正序的下一话（与界面正/倒序无关，1192 的下一章是 1193）；
        // 话数必须严格递增——防止尾部非数字序号章节（特别篇等排到最后）、重复话数、
        // 或当前章查找失败（indexOfFirst=-1 会取到第一章）导致从"真正的最后一章"误跳
        val nextCh = ordered.getOrNull(viewerIdx)?.let { cur ->
            val i = bySerial.indexOfFirst { it.id == cur.id }
            val cand = bySerial.getOrNull(i + 1) ?: return@let null
            val curNo = cur.serial?.toIntOrNull()
            val candNo = cand.serial?.toIntOrNull()
            if (curNo != null && (candNo == null || candNo <= curNo)) null else cand
        }
        key(viewerIdx) {
            PhotoViewerDialog(
                urls = urls,
                initialIndex = 0,
                onDismiss = { viewerUrls = null; viewerIdx = -1 },
                chapterLabel = label,
                hasNextChapter = nextCh != null,
                onOpenNextChapter = {
                    if (nextCh != null) {
                        Toast.makeText(context, "正在加载下一章…", Toast.LENGTH_SHORT).show()
                        openChapterByCh(nextCh)
                    }
                }
            )
        }
    }

    // 单章图片总览网格（images=null 时内部显示加载占位）
    if (gridIdx >= 0) {
        val gTitle = ordered.getOrNull(gridIdx)?.let { chapterName(gridIdx, it) } ?: "本章"
        PhotoGridDialog(
            title = gridUrls?.let { u -> "$gTitle · 共 ${u.size} 张" } ?: "$gTitle · 获取中…",
            images = gridUrls,
            onDismiss = { gridIdx = -1; gridUrls = null }
        )
    }

    // 查看全部网格（images=null 时内部显示加载占位；关闭即取消剩余获取）
    if (loadingAll || allImages != null) {
        PhotoGridDialog(
            title = if (allImages == null) "查看全部 · 正在获取 $allProgress"
            else "查看全部 · 共 ${allImages?.size ?: 0} 张",
            images = allImages,
            onDismiss = {
                if (loadingAll) allJob?.cancel()
                allImages = null
            }
        )
    }

    // 收藏到里世界清单
    if (showCollect) {
        CollectDialog(
            onDismiss = { showCollect = false },
            collect = { listId ->
                val c = detail
                if (c == null) false
                else {
                    val itemId = Repo.saveCustomItem(
                        category = "漫画",
                        doubanId = comicId,
                        title = c.title,
                        coverUrl = c.imageUrl,
                        subTitle = c.authors.joinToString("、") { it.name }
                    )
                    Repo.addItemToList(listId, itemId)
                    true
                }
            }
        )
    }
}