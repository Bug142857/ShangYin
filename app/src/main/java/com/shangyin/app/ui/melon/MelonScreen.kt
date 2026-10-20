package com.shangyin.app.ui.melon

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.melon.MelonBlock
import com.shangyin.app.data.melon.MelonCache
import com.shangyin.app.data.melon.MelonCategory
import com.shangyin.app.data.melon.MelonClient
import com.shangyin.app.data.melon.MelonPost
import com.shangyin.app.data.vod.VodEpisode
import com.shangyin.app.data.vod.VodPlayGroup
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 吃瓜（里世界第八入口）：51爆料，原生列表/详情（v0.219 起，替代旧 WebView 方案）。
 *
 * 原生解析（MelonClient）天然把站点广告挡在门外：列表广告条（article.ad-item）、
 * 详情页按钮墙（.txt-apps）、横幅、播放器贴片全部不进 App。
 * 线路自动发现 + 探活 + 失效自动换线（着陆页解码出镜像列表）。
 */
/** 吃瓜列表现场缓存（进程内）：保存分类/关键词/列表/分页锚点，返回再进原地继续 */
private object MelonHomeCache {
    var catPath: String = ""
    var keyword: String = ""
    var items: List<MelonPost> = emptyList()
    var nextPath: String? = null
    var page: Int = 1      // v0.255 当前页码（页码条）
    var lastPage: Int = 0  // v0.255 末页页码（分页器解析，0=未知）
    var loadedKey: String? = null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MelonHomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    // 列表现场缓存（v0.230）：Navigation 返回时普通 remember 全部重置 → 表现为
    // 「从文章返回后回到最新」。模块级单例保存 (分类/关键词/列表/分页/已加载键)，
    // 重进时先恢复现场，LaunchedEffect 的 loadedKey 比对命中即不重新加载，
    // LazyListState（rememberSaveable 实现）恢复滚动位置 → 原地继续。
    var catPath by remember { mutableStateOf(MelonHomeCache.catPath) }      // ""=最新
    var keyword by remember { mutableStateOf(MelonHomeCache.keyword) }      // 非空=搜索模式
    var input by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(MelonHomeCache.items) }
    var nextPath by remember { mutableStateOf(MelonHomeCache.nextPath) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadedKey by remember { mutableStateOf(MelonHomeCache.loadedKey) }
    // v0.255：页码条导航——用户要求「中间可以选页，知道最后一页」。currentPage 显式计数，
    // 跳页/上/下页统一走 loadNav(target, page)；lastPage 来自分页器解析（null=未知）
    var currentPage by remember { mutableIntStateOf(MelonHomeCache.page) }
    var lastPage by remember { mutableIntStateOf(MelonHomeCache.lastPage) }
    var lastTarget by remember { mutableStateOf<String?>(null) } // 失败重试要重复上次导航
    var lastRequested by remember { mutableIntStateOf(1) }       // 重试要重复的请求页码（失败时 currentPage 未变）
    val lastSeen = remember { MelonCache.last }
    // 列表状态须在 fetch 之前定义：reset 加载成功后要 scrollToItem(0) 回第一条
    val listState = rememberLazyListState()

    /** 目标页的请求路径：P=1 → null（走初始加载：首页/分类/搜索）；P≥2 → 由已知样例路径换尾段数字 */
    fun pathFor(p: Int): String? {
        if (p <= 1) return null
        val sample = nextPath ?: MelonHomeCache.nextPath
        if (sample != null) {
            val replaced = sample.replace(Regex("""/(\d+)/$"""), "/$p/")
            if (replaced != sample) return replaced
        }
        // 样例缺失/尾段未命中（处于末页 next=null 时跳页）：按当前版块形态构造，防错跳首页 404
        // （v0.256：catPath 是相对形态 category/xsxy/，必须带头斜杠，否则与 base 拼接粘域名）
        return when {
            keyword.isNotBlank() -> "/search/$keyword/$p/"
            catPath.isNotBlank() -> "/" + catPath.trimStart('/') + "$p/"
            else -> "/page/$p/"
        }
    }

    /** 整页加载（target=null=首页/分类/搜索第一页；非空=指定页路径）。每次整页替换 */
    suspend fun fetchPage(target: String?) {
        runCatching {
            if (target != null) MelonClient.nextPage(target)
            else when {
                keyword.isNotBlank() -> MelonClient.search(keyword)
                catPath.isNotBlank() -> MelonClient.category(catPath)
                else -> MelonClient.home()
            }
        }.onFailure { e ->
            // 协程取消不是加载失败，必须原样重抛，否则离屏取消被记成"网络错误"噪音
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (target == null) items = emptyList()
            error = e.message?.takeIf { it.isNotBlank() } ?: "网络错误"
            if (target != null) {
                com.shangyin.app.data.melon.MelonFlightLog.log(
                    "LIST", "翻页失败 $target ${e.javaClass.simpleName}: ${e.message?.take(60)}"
                )
            }
        }.onSuccess { page ->
            items = page.items.distinctBy { it.id }
            nextPath = page.nextPath
            page.lastPage?.let { lastPage = it }
            // 翻页/重置后回顶部：页码条模式下每页都是新列表
            listState.scrollToItem(0)
            // 成功后同步现场缓存（返回再进不重载、不丢位置）
            // v0.255 审查修复：页码不在 fetchPage 写——此刻 currentPage 还是导航前的旧值
            // （loadNav 在 fetchPage 之后才更新），写进去会滞后一页；改由 loadNav 成功后补写
            MelonHomeCache.catPath = catPath
            MelonHomeCache.keyword = keyword
            MelonHomeCache.items = items
            MelonHomeCache.nextPath = nextPath
            MelonHomeCache.lastPage = lastPage
            MelonHomeCache.loadedKey = "$catPath|$keyword"
        }.getOrThrow() // 回调内异常抛给 loadNav 的 catch（loading 复位由 finally 保证）
    }

    /** 页码条导航：target=null=初始加载（第1页）；非空=指定页路径。页码变化只在成功后生效 */
    fun loadNav(target: String?, page: Int) {
        if (loading) return
        loading = true
        lastTarget = target
        lastRequested = page
        error = null
        scope.launch {
            try {
                fetchPage(target)
                currentPage = page
                MelonHomeCache.page = currentPage
                com.shangyin.app.data.melon.MelonFlightLog.log(
                    "LIST", "页码=$currentPage/${lastPage.takeIf { it > 0 } ?: "?"} ${items.size}条 next=${nextPath ?: "null"}"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 协程取消不是错误，原样重抛（finally 照常复位 loading）
            } catch (e: Exception) {
                // fetch 回调（scrollToItem/缓存写入）抛异常会杀掉协程 → loading 永久 true
                // → 之后所有翻页被 if(loading) return 吞掉（v0.251 教训）。finally 保证复位
                com.shangyin.app.data.melon.MelonFlightLog.log(
                    "LIST", "加载协程异常 ${e.javaClass.simpleName}: ${e.message?.take(60)}"
                )
                if (target == null) items = emptyList()
                error = e.message?.takeIf { it.isNotBlank() } ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    /** 初始/刷新/切分类：回到第 1 页 */
    fun load() {
        loadNav(null, 1)
    }

    // 分类/关键词变化 → 重置加载
    LaunchedEffect(catPath, keyword) {
        val key = "$catPath|$keyword"
        if (key != loadedKey) {
            loadedKey = key
            load()
        }
    }

    // v0.254：触底自动加载已移除（用户明确要求「需要分页 不要无限翻」）——
    // 翻页改由底部页码条 [上一页 / 第N页 / 下一页] 显式导航

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("吃瓜") },
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
            // 搜索框
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索吃瓜爆料", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = {
                            input = ""
                            if (keyword.isNotEmpty()) keyword = ""
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    keyword = input.trim()
                })
            )
            // 分类：与游戏模块同款——收起=横滑一行 + 下拉箭头；展开=换行 chips（限高可滚）
            var catExpanded by rememberSaveable { mutableStateOf(false) }
            if (keyword.isNotBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "「$keyword」的搜索结果",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { input = ""; keyword = "" }) { Text("返回浏览") }
                }
            } else if (!catExpanded) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        MelonClient.CATEGORIES.forEach { c ->
                            FilterChip(
                                selected = catPath == c.path,
                                onClick = { catPath = c.path },
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
                        MelonClient.CATEGORIES.forEach { c ->
                            FilterChip(
                                selected = catPath == c.path,
                                onClick = { catPath = c.path; catExpanded = false },
                                label = { Text(c.name) }
                            )
                        }
                    }
                }
            }

            when {
                // 首屏加载中
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
                        onClick = { loadedKey = null; load() },
                        modifier = Modifier.padding(top = 16.dp)
                    ) { Text("重试") }
                }

                else -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            error = null
                            runCatching { fetchPage(null) }
                                .onFailure { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                }
                            // v0.254：刷新=回到第 1 页（栈已由 currentPage 取代）
                            currentPage = 1
                            MelonHomeCache.page = 1
                            refreshing = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 会话内续看：最新页顶部给「上次看到」入口
                    if (catPath.isBlank() && keyword.isBlank() && lastSeen != null) {
                        item(key = "lastSeen") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { nav.safeNavigate("melonDetail/${lastSeen.id}") }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "上次看到：《${lastSeen.title}》",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(items, key = { _, p -> p.id }) { _, post ->
                        MelonPostCard(post) { nav.safeNavigate("melonDetail/${post.id}") }
                    }
                    // 底部状态：页码条导航（v0.254，用户要求「需要分页 不要无限翻」）
                    item(key = "footer") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                items.isEmpty() -> Text(
                                    if (keyword.isNotBlank()) "没有找到相关内容" else "暂无内容",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                error != null -> Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // v0.252：失败原因直接亮出来——"加载失败"四个字没法定位
                                    Text(
                                        "加载失败：${error}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        "点击重试",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            // 重复上次导航（目标路径+页码均已存档，失败时页面状态未变动）
                                            loadNav(lastTarget, lastRequested)
                                        }
                                    )
                                }
                                else -> {
                                    if (lastPage > 0) {
                                        // v0.255 数字页码条（用户要求「中间可以选页，知道最后一页」）：
                                        // 窗口 = 1 … cur-1 cur cur+1 … N，当前页高亮，点击跳页
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            modifier = Modifier.horizontalScroll(rememberScrollState())
                                        ) {
                                            TextButton(
                                                enabled = currentPage > 1 && !loading,
                                                onClick = { loadNav(pathFor(currentPage - 1), currentPage - 1) },
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                                            ) { Text("上一页") }
                                            // v0.256：窗口 cur±3（用户要求显示更多页码，方便连续选页）
                                            val window = buildList {
                                                add(1); add(lastPage)
                                                for (d in -3..3) {
                                                    val p = currentPage + d
                                                    if (p in 1..lastPage) add(p)
                                                }
                                            }.distinct().sorted()
                                            var prevNum = 0
                                            window.forEach { p ->
                                                if (p - prevNum > 1) {
                                                    Text(
                                                        "…",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.outline,
                                                        modifier = Modifier.padding(horizontal = 2.dp)
                                                    )
                                                }
                                                prevNum = p
                                                if (p == currentPage) {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = MaterialTheme.colorScheme.primary
                                                    ) {
                                                        Text(
                                                            "$p",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                        )
                                                    }
                                                } else {
                                                    TextButton(
                                                        enabled = !loading,
                                                        onClick = { loadNav(pathFor(p), p) },
                                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                                                    ) { Text("$p") }
                                                }
                                            }
                                            TextButton(
                                                enabled = nextPath != null && !loading,
                                                onClick = { nextPath?.let { loadNav(it, currentPage + 1) } },
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                                            ) { Text("下一页") }
                                        }
                                    } else {
                                        // 末页未知（分页器没解析到）→ 退回上一页/下一页形态
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(
                                                enabled = currentPage > 1 && !loading,
                                                onClick = { loadNav(pathFor(currentPage - 1), currentPage - 1) }
                                            ) { Text("上一页") }
                                            Text(
                                                "第 ${currentPage} 页",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.padding(horizontal = 10.dp)
                                            )
                                            TextButton(
                                                enabled = nextPath != null && !loading,
                                                onClick = { nextPath?.let { loadNav(it, currentPage + 1) } }
                                            ) { Text("下一页") }
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
    }
}

/** 列表卡片：封面背景 + 渐变压暗 + 标题/元信息浮层（还原站点卡片观感） */
@Composable
private fun MelonPostCard(post: MelonPost, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        if (post.cover != null) {
            AsyncImage(
                model = post.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.78f))
                        )
                    )
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                post.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (post.cover != null) Color.White else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            val meta = listOf(post.author, post.date).filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank() || post.categories.isNotEmpty()) {
                Text(
                    listOf(meta, post.categories.joinToString(", "))
                        .filter { it.isNotBlank() }
                        .joinToString("  "),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (post.cover != null) Color.White.copy(alpha = 0.75f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 吃瓜详情：标题 + 元信息 + 正文块（文字/小标题/图片/视频）+ 相关推荐。
 * 图片点击进全屏查看器（长按可保存）；视频点击进内置播放器（HLS，支持断点续播）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelonDetailScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<com.shangyin.app.data.melon.MelonDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewerIndex by remember { mutableIntStateOf(-1) } // -1=关闭

    fun reload() {
        loading = true
        error = null
        scope.launch {
            runCatching { MelonClient.detail(id) }
                .onSuccess {
                    detail = it
                    // 会话内记住最后浏览的帖子（首页「上次看到」入口用）
                    MelonCache.last = MelonPost(
                        it.id, it.title, it.cover, it.author, it.date, it.categories
                    )
                }
                .onFailure { e -> error = e.message?.takeIf { it.isNotBlank() } ?: "加载失败" }
            loading = false
        }
    }
    LaunchedEffect(id) { reload() }

    fun openInBrowser(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
    }

    fun play(video: MelonBlock.Video) {
        val d = detail ?: return
        val vids = d.blocks.filterIsInstance<MelonBlock.Video>()
        PlayerSession.clear()
        PlayerSession.itemId = id.toLongOrNull() ?: 0L
        PlayerSession.title = d.title
        PlayerSession.subTitle = ""
        PlayerSession.sourceName = "51爆料"
        PlayerSession.groups = listOf(
            VodPlayGroup("51爆料", vids.map { VodEpisode(it.title, it.url) })
        )
        PlayerSession.groupIndex = 0
        PlayerSession.startIndex = vids.indexOfFirst { it.url == video.url }.coerceAtLeast(0)
        PlayerSession.startPosMs = 0L
        PlayerSession.isLive = false
        PlayerSession.streamHeaders = buildMap {
            put("User-Agent", MelonClient.UA)
            if (MelonClient.lastBase.isNotBlank()) put("Referer", MelonClient.lastBase + "/")
        }
        nav.safeNavigate("player")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail?.title ?: "吃瓜",
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
                    IconButton(onClick = { openInBrowser("${MelonClient.lastBase}/archives/$id/") }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "浏览器打开"
                        )
                    }
                }
            )
        }
    ) { pad ->
        when {
            loading -> Box(
                Modifier
                    .padding(pad)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(pad)
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                Text(
                    error ?: "加载失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
                Button(onClick = { reload() }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("重试")
                }
            }

            else -> {
                val d = detail ?: return@Scaffold
                val images = remember(d) {
                    d.blocks.filterIsInstance<MelonBlock.Image>().map { it.url }
                }
                LazyColumn(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)
                ) {
                    if (d.cover != null) {
                        item {
                            AsyncImage(
                                model = d.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                            )
                        }
                    }
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                d.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            val meta = listOf(d.author, d.date).filter { it.isNotBlank() }
                                .joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (d.categories.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    d.categories.forEach { c ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                c,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(
                                                    horizontal = 8.dp, vertical = 3.dp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(d.blocks) { _, block ->
                        when (block) {
                            is MelonBlock.Heading -> Text(
                                block.text,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            is MelonBlock.Text -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            is MelonBlock.Image -> AsyncImage(
                                model = block.url,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        viewerIndex = images.indexOf(block.url)
                                    }
                            )
                            is MelonBlock.Video -> Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1B1B1F))
                                    .clickable { play(block) }
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = "播放",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(56.dp)
                                        .background(
                                            Color.Black.copy(alpha = 0.45f),
                                            RoundedCornerShape(28.dp)
                                        )
                                        .padding(10.dp)
                                )
                                Text(
                                    block.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.9f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.35f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    // v0.230：相关推荐已按用户要求移除（推荐位与正片无关且含导流内容）
                }

                // 全屏图片查看（含长按保存）
                if (viewerIndex >= 0 && images.isNotEmpty()) {
                    PhotoViewerDialog(
                        urls = images,
                        initialIndex = viewerIndex.coerceIn(0, images.size - 1),
                        onDismiss = { viewerIndex = -1 }
                    )
                }
            }
        }
    }
}
