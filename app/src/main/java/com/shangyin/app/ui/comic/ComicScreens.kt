package com.shangyin.app.ui.comic

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.Repo
import com.shangyin.app.data.komiic.KomiicCategory
import com.shangyin.app.data.komiic.KomiicClient
import com.shangyin.app.data.komiic.KomiicChapter
import com.shangyin.app.data.komiic.KomiicComic
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val komiicJson = Json { ignoreUnknownKeys = true }

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
 * 漫画主页（Komiic）：搜索框 + 最近更新/热门 + 分类/状态筛选 + 3 列封面网格。
 * 会话级缓存：返回里世界再进不重新加载（rememberSaveable 存 JSON）。
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    val cacheKey = "$tab|$catId|$status|$keyword"

    fun load(reset: Boolean) {
        if (loading) return
        scope.launch {
            loading = true
            if (reset) offset = 0
            val next = if (reset) 0 else offset
            runCatching {
                when {
                    keyword.isNotBlank() -> KomiicClient.search(keyword, next)
                    catId != "0" -> KomiicClient.comicByCategory(catId, next, status)
                    tab == 0 -> KomiicClient.recentUpdate(next, status)
                    else -> KomiicClient.hotComics(next, status)
                }
            }.onSuccess { list ->
                items = if (reset) list else items + list
                offset = next + list.size
                error = null
                cacheJson = komiicJson.encodeToString(
                    ComicHomeCache(tab, catId, status, keyword, offset, items)
                )
            }.onFailure {
                if (reset) items = emptyList()
                error = (it.message?.takeIf { m -> m.isNotBlank() } ?: it::class.simpleName) ?: "网络错误"
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
                // Tab：最近更新 / 热门
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    listOf("最近更新" to 0, "热门" to 1).forEach { (label, t) ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(label) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                // 分类 chips（全部 + 服务端动态分类）
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
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
                // 状态 chips
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    listOf("全部" to "", "连载中" to "ONGOING", "完结" to "END").forEach { (label, s) ->
                        FilterChip(
                            selected = status == s,
                            onClick = { status = s },
                            label = { Text(label) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
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
 * 漫画详情（Komiic）：封面信息 + 章节列表 + 查看全部（合并全部章节图片网格）+ 收藏到里世界清单。
 * 章节图片 URL 由 KomiicClient 追加 fragment，全局拦截器转 Referer，PhotoViewerDialog 零改动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicDetailScreen(nav: NavHostController, comicId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<KomiicComic?>(null) }
    var chapters by remember { mutableStateOf<List<KomiicChapter>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var viewerUrls by remember { mutableStateOf<List<String>?>(null) }
    var showCollect by remember { mutableStateOf(false) }
    // 查看全部
    var allImages by remember { mutableStateOf<List<String>?>(null) }
    var loadingAll by remember { mutableStateOf(false) }
    var allProgress by remember { mutableStateOf("") }
    var openIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(retryKey) {
        loading = true
        error = null
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
        }
        loading = false
    }

    /** 打开章节阅读器 */
    fun readChapter(ch: KomiicChapter) {
        scope.launch {
            runCatching { KomiicClient.fetchChapterImages(comicId, ch.id) }
                .onSuccess { urls ->
                    if (urls.isEmpty()) Toast.makeText(context, "该章节暂无图片", Toast.LENGTH_SHORT).show()
                    else viewerUrls = urls
                }
                .onFailure {
                    val msg = (it.message?.takeIf { m -> m.isNotBlank() } ?: it::class.simpleName) ?: "网络错误"
                    Toast.makeText(context, "获取图片失败：$msg", Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 查看全部：按章节顺序合并全部图片，网格浏览 */
    fun fetchAllImages() {
        if (loadingAll) return
        scope.launch {
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
            }.onFailure {
                val msg = (it.message?.takeIf { m -> m.isNotBlank() } ?: it::class.simpleName) ?: "网络错误"
                Toast.makeText(context, "获取图片失败：$msg", Toast.LENGTH_SHORT).show()
            }
            loadingAll = false
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
                    IconButton(onClick = { showCollect = true }) {
                        Icon(
                            Icons.Rounded.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = MaterialTheme.colorScheme.primary
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
                LazyColumn(Modifier.padding(pad).fillMaxSize()) {
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
                    // 章节区 + 查看全部
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (chapters.isEmpty()) "章节" else "章节（${chapters.size}）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { fetchAllImages() },
                                enabled = !loadingAll && chapters.isNotEmpty()
                            ) {
                                Text(if (loadingAll) "获取中 $allProgress" else "查看全部")
                            }
                        }
                    }
                    items(chapters.size) { i ->
                        val ch = chapters[i]
                        Row(
                            Modifier.fillMaxWidth().clickable { readChapter(ch) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ch.serial ?: "第 ${i + 1} 话",
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
                            Text(
                                "${ch.size} 张",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    // 全屏阅读器（双模式 + 缩放 + 长按保存）
    viewerUrls?.let { urls ->
        PhotoViewerDialog(urls = urls, initialIndex = 0, onDismiss = { viewerUrls = null })
    }

    // 查看全部网格
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
