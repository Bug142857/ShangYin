package com.shangyin.app.ui.psite

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.InnerFav
import com.shangyin.app.data.psite.PsiteClient
import com.shangyin.app.data.psite.PsiteClient.PVideo
import com.shangyin.app.data.vod.VodEpisode
import com.shangyin.app.data.vod.VodPlayGroup
import com.shangyin.app.ui.innerfav.FavHeartButton
import com.shangyin.app.ui.common.AppSearchField
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * P站（里世界第十入口）：pornhub.com 原生解析，无 WebView。
 * 搜索框 + 排序 tabs（推荐/最新/最热/人气/高分，默认推荐）+ 分类筛选（可展开）+ 双列网格 + 页码条；
 * 卡片点击先解析 flashvars 直链（HLS）再进播放器；0.291 起卡片支持爱心收藏（无下载）。
 */
/** 播放：解析直链后进播放器（PlayerSession）；resolving 期间 UI 显示转圈。
 *  公开：收藏页重放复用 */
fun playVideo(
    scope: kotlinx.coroutines.CoroutineScope,
    nav: NavHostController,
    context: android.content.Context,
    video: PVideo,
    onResolvingChange: (Boolean) -> Unit
) {
    onResolvingChange(true)
    scope.launch {
        try {
            val media = PsiteClient.resolve(video.vkey)
            PlayerSession.clear()
            PlayerSession.itemId = video.vkey.hashCode().toLong()
            PlayerSession.title = video.title
            PlayerSession.subTitle = ""
            PlayerSession.sourceName = PsiteClient.SOURCE_NAME
            PlayerSession.groups = listOf(
                VodPlayGroup("P站", listOf(VodEpisode("正片", media.url)))
            )
            PlayerSession.groupIndex = 0
            PlayerSession.startIndex = 0
            PlayerSession.startPosMs = 0L
            PlayerSession.isLive = false
            // v0.303：带上列表封面，下载管理里的视频封面与页面一致
            PlayerSession.coverUrl = video.thumbnail.orEmpty()
            // phncdn 实测仅 UA 即可（无 Referer 校验）；带 Referer 更接近浏览器形态
            PlayerSession.streamHeaders = mapOf(
                "User-Agent" to PsiteClient.UA,
                "Referer" to PsiteClient.BASE + "/"
            )
            onResolvingChange(false)
            nav.safeNavigate("player")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onResolvingChange(false)
            Toast.makeText(
                context,
                "解析失败：${e.message?.takeIf { it.isNotBlank() } ?: "网络异常"}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/** P站会话缓存（进程内）：筛选/整页数据/滚动位——播放后返回原地继续 */
private object PsiteCache {
    var sort: String = PsiteClient.DEFAULT_SORT
    var category: Int = 0     // 0 = 全部
    var query: String = ""
    var page: Int = 1
    var lastPage: Int = 0
    var items: List<PVideo> = emptyList()
    var scrollIndex: Int = 0
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PsiteHomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var sort by remember {
        mutableStateOf(PsiteCache.sort.takeIf { s -> PsiteClient.SORTS.any { it.second == s } }
            ?: PsiteClient.DEFAULT_SORT)
    }
    var category by remember { mutableStateOf(PsiteCache.category) }
    var query by remember { mutableStateOf(PsiteCache.query) }
    var input by remember { mutableStateOf(PsiteCache.query) } // 搜索框草稿
    var restoredRef by remember { mutableStateOf(PsiteCache.items.isNotEmpty()) }
    var items by remember { mutableStateOf(PsiteCache.items) }
    var lastPage by remember { mutableIntStateOf(PsiteCache.lastPage) }
    var loading by remember { mutableStateOf(PsiteCache.items.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(PsiteCache.page) }
    var lastRequested by remember { mutableIntStateOf(1) }
    var reqSeq by remember { mutableIntStateOf(0) } // 竞态防护：丢弃被更新请求取代的旧响应
    var resolving by remember { mutableStateOf(false) } // 点卡片后的直链解析中
    val gridState = rememberLazyGridState()

    /** 翻页/筛选变化加载（末页未知，允许任意前翻；next 无限制由页码条控制） */
    fun loadNav(page: Int) {
        val myReq = ++reqSeq
        val s = sort; val c = category; val q = query
        items = emptyList()
        loading = true
        lastRequested = page
        error = null
        scope.launch {
            try {
                val resp = PsiteClient.list(s, c, q, page)
                if (myReq != reqSeq) return@launch
                items = resp.items
                lastPage = resp.lastPage
                currentPage = page
                gridState.scrollToItem(0)
                PsiteCache.sort = s
                PsiteCache.category = c
                PsiteCache.query = q
                PsiteCache.page = page
                PsiteCache.lastPage = resp.lastPage
                PsiteCache.items = resp.items
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (myReq != reqSeq) return@launch
                error = e.message?.takeIf { it.isNotBlank() } ?: "加载失败"
            } finally {
                if (myReq == reqSeq) loading = false
            }
        }
    }

    // 筛选变化 → 回第 1 页；首次进入有缓存现场则恢复滚动位
    LaunchedEffect(sort, category, query) {
        if (restoredRef) {
            restoredRef = false
            val maxIdx = (PsiteCache.items.size - 1).coerceAtLeast(0)
            val target = PsiteCache.scrollIndex.coerceIn(0, maxIdx)
            if (target > 0) gridState.scrollToItem(target)
        } else {
            loadNav(1)
        }
    }

    // 滚动位实时写缓存，离开页面（进播放器）后可恢复
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { PsiteCache.scrollIndex = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("P站") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("innerFavs/psite") }) {
                        Icon(Icons.Rounded.FavoriteBorder, contentDescription = "我的收藏")
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
            // 搜索栏：输入法确认键触发（v0.301 全局统一样式）
            AppSearchField(
                value = input,
                onValueChange = {
                    input = it
                    if (it.isEmpty()) query = ""
                },
                onSearch = { query = input.trim() },
                placeholder = "搜索 P站 视频…",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            // 排序 tabs（点已选 = 刷新；5 个 chip 超出屏宽，横向滚动）
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PsiteClient.SORTS.forEach { (label, code) ->
                    FilterChip(
                        selected = sort == code,
                        onClick = {
                            if (sort == code) {
                                if (!loading) loadNav(currentPage)
                            } else {
                                sort = code
                            }
                        },
                        label = { Text(label) },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            // 分类筛选（横滑一行，可展开）
            var catExpanded by remember { mutableStateOf(false) }
            // v0.304：各分类视频数（下拉展开后并发探测；拿不到的保持无后缀）
            var catCounts by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
            LaunchedEffect(catExpanded) {
                if (!catExpanded) return@LaunchedEffect
                val missing = PsiteClient.CATEGORIES.filter { it.id !in catCounts }
                if (missing.isEmpty()) return@LaunchedEffect
                val sem = Semaphore(3)
                kotlinx.coroutines.coroutineScope {
                    missing.map { c ->
                        launch {
                            sem.withPermit {
                                val n = runCatching { PsiteClient.categoryCount(c.id) }.getOrNull()
                                if (n != null) catCounts = catCounts + (c.id to n)
                            }
                        }
                    }
                }
            }
            fun catLabel(c: PsiteClient.PCategory): String =
                "${c.label}${catCounts[c.id]?.let { " ($it)" } ?: ""}"
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
                            selected = category == 0,
                            onClick = {
                                if (category != 0) category = 0
                                else if (!loading) loadNav(1) // 已是全部再点 = 刷新
                            },
                            label = { Text("全部分类") },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        PsiteClient.CATEGORIES.forEach { c ->
                            FilterChip(
                                selected = category == c.id,
                                onClick = {
                                    if (category == c.id) {
                                        if (!loading) loadNav(1)
                                    } else {
                                        category = c.id
                                    }
                                },
                                label = { Text(catLabel(c)) },
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
                        Text(
                            "全部分类",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
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
                            selected = category == 0,
                            onClick = { category = 0; catExpanded = false },
                            label = { Text("全部分类") }
                        )
                        PsiteClient.CATEGORIES.forEach { c ->
                            FilterChip(
                                selected = category == c.id,
                                onClick = { category = c.id; catExpanded = false },
                                label = { Text(catLabel(c)) }
                            )
                        }
                    }
                }
            }

            // 内容区 + 解析直链中的轻遮罩（列表保持可见，解析完直接进播放器）
            Box(Modifier.fillMaxSize()) {
                when {
                    loading && items.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                error != null && items.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                ) {
                    Text(
                        error ?: "加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { loadNav(lastRequested) },
                        modifier = Modifier.padding(top = 16.dp)
                    ) { Text("重试") }
                }

                items.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                ) {
                    Text(
                        "没有找到视频，换个关键词或分类试试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }

                else -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            val myReq = ++reqSeq
                            refreshing = true
                            error = null
                            val s = sort; val c = category; val q = query
                            runCatching { PsiteClient.list(s, c, q, 1) }
                                .onSuccess { p ->
                                    if (myReq != reqSeq) return@onSuccess
                                    items = p.items
                                    lastPage = p.lastPage
                                    currentPage = 1
                                    gridState.scrollToItem(0)
                                    PsiteCache.page = 1
                                    PsiteCache.lastPage = p.lastPage
                                    PsiteCache.items = p.items
                                }
                                .onFailure { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    if (myReq != reqSeq) return@onFailure
                                    Toast.makeText(
                                        context,
                                        "刷新失败：${e.message?.takeIf { it.isNotBlank() } ?: "网络异常"}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            if (myReq == reqSeq) refreshing = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(items, key = { _, v -> v.vkey }) { _, video ->
                            PVideoCard(video = video, onClick = {
                                if (!resolving) playVideo(scope, nav, context, video) { resolving = it }
                            })
                        }
                        // 底部页码条（跨双列；v0.305 解析出尾页，与 X 站/吃瓜一致）
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            com.shangyin.app.ui.common.AppPageBar(
                                currentPage = currentPage,
                                lastPage = lastPage,
                                loading = loading,
                                onGo = { loadNav(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
                }

                // 解析直链中：轻遮罩浮在列表上（列表保持可见，解析完直接进播放器）
                if (resolving) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.30f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    "正在解析播放地址…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 视频卡片：16:9 封面 + 时长角标 + 标题 + 频道 · 观看数；点击 = 解析直链后播放；右上角爱心收藏 */
@Composable
private fun PVideoCard(video: PVideo, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (video.thumbnail != null) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.Center).size(40.dp)
                )
            }
            // 爱心收藏（右上角）
            FavHeartButton(
                favModule = "psite",
                favId = video.vkey,
                build = {
                    InnerFav(
                        module = "psite",
                        id = video.vkey,
                        title = video.title,
                        subtitle = listOf(video.uploader, video.views)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        cover = video.thumbnail,
                        savedAt = System.currentTimeMillis(),
                        durationSec = video.durationSec
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            )
            // 时长角标（右下）
            val dur = PsiteClient.formatDuration(video.durationSec)
            if (dur.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                ) {
                    Text(
                        dur,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Text(
            video.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            listOf(video.uploader, video.views).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** 页码条：[←] [相邻页码] … [跳页] [→]（站点不暴露总页数，末页未知模式） */
@Composable
private fun PPageBar(currentPage: Int, loading: Boolean, onGo: (Int) -> Unit, modifier: Modifier = Modifier) {
    var showJump by remember { mutableStateOf(false) }
    val nums = (-2..2).map { currentPage + it }.filter { it >= 1 }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        val backEnabled = currentPage > 1 && !loading
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = backEnabled) { onGo(currentPage - 1) }
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "上一页",
                tint = if (backEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            if (nums.first() > 1) {
                Text(
                    "1 …",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !loading) { showJump = true }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }
            nums.forEach { p ->
                if (p == currentPage) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            "$p",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                } else {
                    Text(
                        "$p",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !loading) { onGo(p) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }
            Text(
                "… 跳页",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !loading) { showJump = true }
                    .padding(horizontal = 6.dp, vertical = 8.dp)
            )
        }
        val fwdEnabled = !loading
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = fwdEnabled) { onGo(currentPage + 1) }
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "下一页",
                tint = if (fwdEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
        }
    }
    if (showJump) {
        var jumpText by remember { mutableStateOf("") }
        val jump = jumpText.toIntOrNull() ?: 0
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("跳转到指定页") },
            text = {
                OutlinedTextField(
                    value = jumpText,
                    onValueChange = { jumpText = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    placeholder = { Text("页码") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = jump >= 1,
                    onClick = {
                        showJump = false
                        onGo(jump)
                    }
                ) { Text("跳转") }
            },
            dismissButton = {
                TextButton(onClick = { showJump = false }) { Text("取消") }
            }
        )
    }
}
