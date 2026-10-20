package com.shangyin.app.ui.xsite

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.Repo
import com.shangyin.app.data.xsite.XSiteClient
import com.shangyin.app.data.xsite.XSiteClient.XCategory
import com.shangyin.app.data.xsite.XSiteClient.XPost
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.data.vod.VodEpisode
import com.shangyin.app.data.vod.VodPlayGroup
import kotlinx.coroutines.launch

/**
 * X站（里世界第九入口）：pektino.com 排行榜，原生渲染无 WebView。
 * 列表 = 榜单 tabs（日/周/月/总）+ 标签筛选 + 排序 + 双列网格 + 页码条；
 * 详情 = 封面即播 + 元信息 + 标签 + 收藏（category="X视频"）+ 原推直达。
 */
/** 收藏条目的分类名（里世界清单里与 番号/本子/吃瓜 同级） */
private const val CATEGORY = "X视频"

private fun collectKey(urlCd: String) = XSiteClient.COLLECT_PREFIX + urlCd

/** X站会话缓存（进程内）：榜单/排序/标签/列表/页码，返回再进原地继续 */
private object XSiteCache {
    var range: String = "daily"
    var sort: String = "favorite"
    var category: String = ""   // "" = 全部
    var page: Int = 1
    var lastPage: Int = 0
    var total: Int = 0
    var items: List<XPost> = emptyList()
    var loadedKey: String? = null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun XSiteHomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var range by remember { mutableStateOf(XSiteCache.range) }
    var sort by remember { mutableStateOf(XSiteCache.sort) }
    var category by remember { mutableStateOf(XSiteCache.category) }
    var items by remember { mutableStateOf(XSiteCache.items) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var loadedKey by remember { mutableStateOf(XSiteCache.loadedKey) }
    var currentPage by remember { mutableIntStateOf(XSiteCache.page) }
    var lastPage by remember { mutableIntStateOf(XSiteCache.lastPage) }
    var total by remember { mutableIntStateOf(XSiteCache.total) }
    var lastRequested by remember { mutableIntStateOf(1) } // 失败重试要重复的请求页码
    var reqSeq by remember { mutableIntStateOf(0) }        // 请求代序号：丢弃被更新的请求所取代的旧响应
    var catList by remember { mutableStateOf<List<XCategory>?>(null) } // null=标签加载中
    val gridState = rememberLazyGridState()

    // 标签分类：进程内缓存，首次进入拉一次（失败回退内置表，UI 恒有值）
    LaunchedEffect(Unit) {
        if (catList == null) catList = XSiteClient.categories()
    }

    /** 整页加载。页面为纯数字（API lastPage），无 melon 的路径换算问题。
     *  请求参数在发起瞬间捕获；响应到达时若已有更新的请求（reqSeq 变了）则整体丢弃，
     *  避免快速切换榜单/标签时旧响应覆盖新现场。 */
    fun loadNav(page: Int) {
        val myReq = ++reqSeq
        val r = range; val s = sort; val c = category
        loading = true
        lastRequested = page
        error = null
        scope.launch {
            try {
                val p = XSiteClient.list(r, page, s, c)
                if (myReq != reqSeq) return@launch
                items = p.items
                lastPage = p.lastPage
                total = p.total
                currentPage = page
                gridState.scrollToItem(0)
                // 成功后同步现场缓存（返回再进不重载）
                XSiteCache.range = r
                XSiteCache.sort = s
                XSiteCache.category = c
                XSiteCache.page = currentPage
                XSiteCache.lastPage = lastPage
                XSiteCache.total = total
                XSiteCache.items = items
                XSiteCache.loadedKey = "$r|$s|$c"
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

    // 榜单/排序/标签变化 → 回第 1 页重载（loadedKey 由 loadNav 成功后写回，失败不写以便再次进入重试）
    LaunchedEffect(range, sort, category) {
        val key = "$range|$sort|$category"
        if (key != loadedKey) {
            loadNav(1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("X站") },
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
            // 榜单 tabs：日/周/月/总（4 个固定 chip，无需滚动）
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                XSiteClient.RANGES.forEach { (label, code) ->
                    FilterChip(
                        selected = range == code,
                        onClick = { range = code },
                        label = { Text(label) },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
            }
            // 标签筛选（横滑一行，可展开）+ 排序下拉（固定右端）
            var catExpanded by remember { mutableStateOf(false) }
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
                            selected = category.isBlank(),
                            onClick = { category = "" },
                            label = { Text("全部") },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        catList?.forEach { c ->
                            FilterChip(
                                selected = category == c.code,
                                onClick = { category = c.code },
                                label = { Text(c.name) },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                    var sortOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { sortOpen = true }) {
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "排序")
                        }
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            XSiteClient.SORTS.forEach { (label, code) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            fontWeight = if (sort == code) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        sort = code
                                        sortOpen = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { catExpanded = true }) {
                        Icon(Icons.Rounded.ArrowDropUp, contentDescription = "展开标签")
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
                            "全部标签",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = { catExpanded = false }) {
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "收起标签")
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
                            selected = category.isBlank(),
                            onClick = { category = ""; catExpanded = false },
                            label = { Text("全部") }
                        )
                        catList?.forEach { c ->
                            FilterChip(
                                selected = category == c.code,
                                onClick = { category = c.code; catExpanded = false },
                                label = { Text(c.name) }
                            )
                        }
                    }
                }
            }

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
                        onClick = { loadedKey = null; loadNav(lastRequested) },
                        modifier = Modifier.padding(top = 16.dp)
                    ) { Text("重试") }
                }

                else -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            error = null
                            runCatching { XSiteClient.list(range, 1, sort, category) }
                                .onSuccess { p ->
                                    items = p.items
                                    lastPage = p.lastPage
                                    total = p.total
                                    currentPage = 1
                                    gridState.scrollToItem(0)
                                    XSiteCache.page = 1
                                    XSiteCache.lastPage = lastPage
                                    XSiteCache.total = total
                                    XSiteCache.items = items
                                }
                                .onFailure { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    // 失败要说出来：有旧数据时错误态分支不显示，用 Toast 明示
                                    Toast.makeText(
                                        context,
                                        "刷新失败：${e.message?.takeIf { it.isNotBlank() } ?: "网络异常"}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            refreshing = false
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
                        itemsIndexed(items, key = { _, p -> p.urlCd }) { index, post ->
                            XPostCard(
                                post = post,
                                rank = (currentPage - 1) * XSiteClient.PER_PAGE + index + 1,
                                onClick = { nav.safeNavigate("xsiteDetail/${android.net.Uri.encode(post.urlCd)}") }
                            )
                        }
                        // 底部：页码条（跨双列）
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth()) {
                                if (items.isEmpty()) {
                                    Text(
                                        if (error != null) "加载失败：$error（点击重试）"
                                        else "暂无内容",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = error != null) {
                                                loadedKey = null; loadNav(lastRequested)
                                            }
                                            .padding(vertical = 12.dp)
                                    )
                                } else {
                                    XPageBar(
                                        currentPage = currentPage,
                                        lastPage = lastPage,
                                        loading = loading,
                                        onGo = { loadNav(it) }
                                    )
                                    if (total > 0) {
                                        Text(
                                            "共 $total 个视频",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp, bottom = 8.dp)
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

/** 页码条：[←] [1] … [cur±w] … [N] [→] + 跳页输入（沿用吃瓜模块的紧凑形态） */
@Composable
private fun XPageBar(currentPage: Int, lastPage: Int, loading: Boolean, onGo: (Int) -> Unit) {
    var showJump by remember { mutableStateOf(false) }
    val half = if (lastPage >= 1000) 1 else 2
    val nums = buildList {
        if (lastPage > 0) {
            val s = (currentPage - half).coerceAtLeast(1)
            val e = (currentPage + half).coerceAtMost(lastPage)
            if (s > 1) {
                add(1)
                if (s > 2) add(-1) // -1 = 省略号占位（点击弹跳页框）
            }
            for (p in s..e) add(p)
            if (e < lastPage) {
                if (e < lastPage - 1) add(-1)
                add(lastPage)
            }
        } else {
            for (d in -half..half) {
                val p = currentPage + d
                if (p >= 1) add(p)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        // 左箭头
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
            nums.forEach { p ->
                if (p == -1) {
                    Text(
                        "…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !loading) { showJump = true }
                            .padding(horizontal = 5.dp, vertical = 8.dp)
                    )
                } else if (p == currentPage) {
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
        }
        // 右箭头
        val fwdEnabled = !loading && (lastPage <= 0 || currentPage < lastPage)
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
                Column {
                    if (lastPage > 0) {
                        Text(
                            "共 $lastPage 页（末页 $lastPage），输入 1 ~ $lastPage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = jumpText,
                        onValueChange = { jumpText = it.filter { ch -> ch.isDigit() } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = { Text("页码") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = jump >= 1 && (lastPage <= 0 || jump <= lastPage),
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

/** 排行卡片：封面（3:4 裁切，兼容横竖屏缩略图）+ 名次角标 + 时长角标 + 推主/点赞 */
@Composable
private fun XPostCard(post: XPost, rank: Int, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (post.thumbnail != null) {
                AsyncImage(
                    model = post.thumbnail,
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
            // 名次角标（左上）
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            ) {
                Text(
                    "#$rank",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            // 时长角标（右下）
            val dur = XSiteClient.formatDuration(post.durationSec)
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
            "@${post.account}",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                "${XSiteClient.formatCount(post.favorite)} · 评论 ${post.comments}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * X站详情：封面即播（点击封面进播放器）+ 元信息 + 标签 + 收藏到里世界清单 + 原推直达。
 * 站点条目无标题：展示名统一「@推主」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun XSiteDetailScreen(nav: NavHostController, urlCd: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<com.shangyin.app.data.xsite.XSiteClient.XDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCollect by remember { mutableStateOf(false) }
    var collected by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            runCatching { XSiteClient.detail(urlCd) }
                .onSuccess {
                    detail = it
                    collected = Repo.isCollected(CATEGORY, collectKey(it.post.urlCd))
                }
                .onFailure { e -> error = e.message?.takeIf { it.isNotBlank() } ?: "加载失败" }
            loading = false
        }
    }
    LaunchedEffect(urlCd) { reload() }

    fun play(d: com.shangyin.app.data.xsite.XSiteClient.XDetail) {
        PlayerSession.clear()
        PlayerSession.itemId = d.post.mediaId
        PlayerSession.title = "X视频 @${d.post.account}"
        PlayerSession.subTitle = ""
        PlayerSession.sourceName = "X站"
        PlayerSession.groups = listOf(
            VodPlayGroup("X站", listOf(VodEpisode("正片", d.post.videoUrl)))
        )
        PlayerSession.groupIndex = 0
        PlayerSession.startIndex = 0
        PlayerSession.startPosMs = 0L
        PlayerSession.isLive = false
        // twimg 直链无需 Referer；UA 对齐客户端标准头
        PlayerSession.streamHeaders = mapOf("User-Agent" to XSiteClient.UA)
        nav.safeNavigate("player")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail?.let { "@${it.post.account}" } ?: "X站",
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
                    // 原推直达
                    if (detail?.post?.tweetUrl != null) {
                        IconButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(detail!!.post.tweetUrl))
                                )
                            }.onFailure {
                                Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "浏览器打开原推"
                            )
                        }
                    }
                    // 收藏（已在清单 = 对号）
                    if (detail != null) {
                        IconButton(onClick = { if (!collected) showCollect = true }) {
                            Icon(
                                if (collected) Icons.Rounded.Check else Icons.Rounded.Add,
                                contentDescription = if (collected) "已收藏" else "收藏"
                            )
                        }
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
                Column(
                    Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // 封面即播
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color(0xFF1B1B1F))
                            .clickable { play(d) }
                    ) {
                        if (d.post.thumbnail != null) {
                            AsyncImage(
                                model = d.post.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "播放",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(60.dp)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(30.dp))
                                .padding(12.dp)
                        )
                        val dur = XSiteClient.formatDuration(d.post.durationSec)
                        if (dur.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Black.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
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
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "X视频 @${d.post.account}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        val date = d.postedAt.takeIf { it.length >= 10 }?.take(10).orEmpty()
                        Text(
                            listOf(
                                date,
                                "赞 ${XSiteClient.formatCount(d.post.favorite)}",
                                "看 ${XSiteClient.formatCount(d.post.pv)}",
                                "评论 ${d.post.comments}"
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (d.tags.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                d.tags.forEach { t ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            t,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showCollect) {
                    CollectDialog(
                        onDismiss = { showCollect = false },
                        collect = { listId ->
                            val itemId = Repo.saveCustomItem(
                                category = CATEGORY,
                                doubanId = collectKey(d.post.urlCd),
                                title = "X视频 @${d.post.account}",
                                coverUrl = d.post.thumbnail,
                                subTitle = d.postedAt.take(10).ifBlank { "" }
                            )
                            if (itemId > 0) {
                                Repo.addItemToList(listId, itemId)
                                collected = true
                                true
                            } else false
                        }
                    )
                }
            }
        }
    }
}
