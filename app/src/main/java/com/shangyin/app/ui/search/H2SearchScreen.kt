package com.shangyin.app.ui.search

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.bika.BikaChapter
import com.shangyin.app.data.bika.BikaClient
import com.shangyin.app.data.bika.BikaComic
import com.shangyin.app.data.bika.BikaCategory
import com.shangyin.app.data.bika.BikaComicsPage
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.launch

/**
 * 本子页会话内存缓存：会话内复用接口结果，避免每次进页/返回都重新请求（哔咔接口延迟高）。
 * - 分类列表只拉一次
 * - 列表按 分类|关键词|排序 维度缓存（切排序再切回、返回列表都即时呈现）
 * - 详情/章节缓存，从阅读页返回详情即时
 */
object BikaUiCache {
    var categories by mutableStateOf<List<BikaCategory>?>(null)

    data class ComicsCache(val items: List<BikaComic>, val page: Int, val pages: Int)

    val comics = mutableMapOf<String, ComicsCache>() // key = cat|kw|sort
    val details = mutableMapOf<String, BikaComic>() // comicId -> 详情
    val chapters = mutableMapOf<String, List<BikaChapter>>() // comicId -> 章节

    fun comicsKey(category: String?, keyword: String?, sort: String): String =
        "${category ?: ""}|${keyword ?: ""}|$sort"
}

/**
 * 本子（哔咔漫画）页，界面参考 haka_comic：
 * - 一级：搜索栏 + 最近更新入口 + 分类封面网格
 * - 二级：某分类/最近更新/搜索的漫画列表（行式卡片：封面 + [N P]标题 + 作者 + 标签 + 喜欢/观看）
 * - 排序在列表页顶栏
 * 需登录哔咔账号（设置 → 账号管理 → 哔咔登录）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H2SearchScreen(nav: NavHostController) {
    val loggedIn = SettingsStore.bikaToken.isNotBlank()

    // 未登录：引导去设置登录
    if (!loggedIn) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("本子") },
                    navigationIcon = {
                        IconButton(onClick = { nav.safePopBackStack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(pad).fillMaxSize().padding(32.dp)
            ) {
                EmptyView("未登录哔咔账号")
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = { nav.safeNavigate("account") }) {
                    Text("去 设置 → 账号管理 → 哔咔登录")
                }
            }
        }
        return
    }

    // inList=false=一级分类网格；true=二级列表（viewCategory：null=最近更新，其余=分类名）。
    // ⚠️ 不能用 viewCategory==null 判断是否在一级页：点「最近更新」就是 null，会被误判回一级页没反应
    var inList by rememberSaveable { mutableStateOf(false) }
    var viewCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var searchKeyword by rememberSaveable { mutableStateOf<String?>(null) } // 搜索模式列表

    // 系统返回键：在二级列表（分类/最近更新/搜索）时先回到一级分类页，而非直接退回里世界
    fun backToGrid() {
        searchKeyword = null
        inList = false
    }
    BackHandler(enabled = inList) { backToGrid() }

    if (!inList) {
        CategoryGridPage(
            nav = nav,
            onOpenCategory = { cat ->
                viewCategory = cat
                searchKeyword = null
                inList = true
            },
            onSearch = { kw ->
                searchKeyword = kw
                viewCategory = null
                inList = true
            }
        )
    } else {
        ComicListPage(
            nav = nav,
            category = viewCategory?.takeIf { it.isNotBlank() },
            keyword = searchKeyword,
            onBack = { backToGrid() }
        )
    }
}

// ---------------- 一级：分类封面网格 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryGridPage(
    nav: NavHostController,
    onOpenCategory: (String?) -> Unit,
    onSearch: (String) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    // 优先用缓存：进过一次后秒开
    var categories by remember { mutableStateOf(BikaUiCache.categories ?: emptyList()) }
    var loading by remember { mutableStateOf(BikaUiCache.categories == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        if (BikaUiCache.categories != null) return@LaunchedEffect // 已有缓存，不重复拉
        runCatching { BikaClient.withAuth { t -> BikaClient.fetchCategories(t) } }
            .onSuccess {
                BikaUiCache.categories = it
                categories = it
                error = null
            }
            .onFailure { error = it.message }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本子") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 搜索栏：输入回车进入搜索结果列表
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索本子…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = {
                        val kw = query.trim()
                        if (kw.isNotEmpty()) {
                            keyboard?.hide()
                            onSearch(kw)
                        }
                    }
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    EmptyView("加载失败：$error")
                    Row {
                        TextButton(onClick = { retryKey++; loading = true }) { Text("重试") }
                        TextButton(onClick = { nav.safePopBackStack() }) { Text("返回") }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 最近更新入口（全部漫画，最新在前）
                    gridItems(listOf("最近更新")) {
                        CategoryCard(
                            title = "最近更新",
                            thumbUrl = null,
                            onClick = { onOpenCategory(null) }
                        )
                    }
                    gridItems(categories, key = { it.id ?: it.title }) { cat ->
                        CategoryCard(
                            title = cat.title,
                            thumbUrl = cat.thumbUrl.takeIf { it.isNotBlank() },
                            onClick = { onOpenCategory(cat.title) }
                        )
                    }
                }
            }
        }
    }
}

// ---------------- 二级：漫画行式列表 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicListPage(
    nav: NavHostController,
    category: String?,
    keyword: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf("dd") } // dd新到旧/da旧到新/ld最多喜欢/vd最多观看
    var sortMenu by remember { mutableStateOf(false) }
    // 列表缓存：按 分类|关键词|排序 维度，命中直接呈现不重新请求（切排序/返回列表都即时）
    val cacheKey = BikaUiCache.comicsKey(category, keyword, sort)
    val cached = BikaUiCache.comics[cacheKey]
    var items by remember(cacheKey) { mutableStateOf(cached?.items ?: emptyList()) }
    var page by remember(cacheKey) { mutableIntStateOf(cached?.page ?: 1) }
    var pages by remember(cacheKey) { mutableIntStateOf(cached?.pages ?: 1) }
    var loading by remember(cacheKey) { mutableStateOf(cached == null) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember(cacheKey) { mutableStateOf<String?>(null) }
    val reqId = remember { mutableIntStateOf(0) }

    /** 加载一页 */
    fun load(pg: Int) {
        // ⚠️ 首页加载（pg==1）永远放行：loading 初值就是 true（无缓存时），
        // 若在此被拦，LaunchedEffect 的首次 load(1) 会被静默吞掉 → 永远转圈且无错误提示。
        // 旧请求由 reqId 失效机制丢弃，重复 load(1) 无副作用。guard 只用于拦截加载更多的连点。
        if (pg != 1 && (loading || loadingMore)) return
        val my = ++reqId.intValue
        scope.launch {
            if (pg == 1) loading = true else loadingMore = true
            runCatching {
                BikaClient.withAuth { t ->
                    val kw = keyword
                    if (!kw.isNullOrBlank()) BikaClient.searchComics(t, kw, pg, sort)
                    else BikaClient.fetchComics(t, pg, category, sort)
                }
            }.onSuccess { resp: BikaComicsPage ->
                if (my == reqId.intValue) {
                    val merged = if (pg == 1) resp.docs else (items + resp.docs).distinctBy { it.id }
                    items = merged
                    page = resp.page
                    pages = resp.pages
                    error = null
                    // 会话缓存上限 12 组，超出丢最旧的
                    if (BikaUiCache.comics.size > 12) BikaUiCache.comics.remove(BikaUiCache.comics.keys.first())
                    BikaUiCache.comics[cacheKey] = BikaUiCache.ComicsCache(merged, resp.page, resp.pages)
                }
            }.onFailure {
                if (my == reqId.intValue) error = it.message
            }
            if (pg == 1) loading = false else loadingMore = false
        }
    }

    // 缓存命中直接用，否则加载第一页
    LaunchedEffect(category, keyword, sort) {
        if (BikaUiCache.comics[cacheKey] == null) load(1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            !keyword.isNullOrBlank() -> "搜索「$keyword」"
                            category != null -> category
                            else -> "最近更新"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.Rounded.Sort, contentDescription = "排序")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf(
                            "dd" to "新到旧", "da" to "旧到新",
                            "ld" to "最多喜欢", "vd" to "最多观看"
                        ).forEach { (k, label) ->
                            DropdownMenuItem(
                                text = { Text(if (sort == k) "● $label" else label) },
                                onClick = { sort = k; sortMenu = false }
                            )
                        }
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
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(pad).fillMaxSize()
            ) {
                val msg = if (error == "未登录哔咔账号" || error == "哔咔登录已失效") {
                    "哔咔登录已失效，请到 设置 → 账号管理 重新登录"
                } else "加载失败：$error"
                EmptyView(msg)
                Row {
                    TextButton(onClick = { load(1) }) { Text("重试") }
                    TextButton(onClick = onBack) { Text("返回") }
                }
            }
            items.isEmpty() -> Box(Modifier.padding(pad).fillMaxSize()) {
                EmptyView("没有找到漫画")
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 6.dp),
                modifier = Modifier.padding(pad).fillMaxSize()
            ) {
                items(items, key = { it.id }) { comic ->
                    ComicRow(comic) { nav.safeNavigate("bikaComic/${android.net.Uri.encode(comic.id)}") }
                }
                if (page < pages) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            if (loadingMore) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            else TextButton(onClick = { load(page + 1) }) { Text("加载更多（${page}/${pages} 页）") }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 组件 ----------------

/** 分类卡片：封面（1:1.35）+ 名称；无封面时主题色块（最近更新入口） */
@Composable
private fun CategoryCard(
    title: String,
    thumbUrl: String?,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f / 1.35f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            if (thumbUrl != null) {
                CoverImage(url = thumbUrl, modifier = Modifier.fillMaxSize())
            } else {
                // 入口卡：主题色 + 大字
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                                )
                            ),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 漫画行卡片（haka_comic ListItem 风格）：90:130 封面 + [N P]标题 + 作者 + 分类标签 + 喜欢/观看；完结角标 */
@Composable
private fun ComicRow(comic: BikaComic, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 10.dp, vertical = 5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.width(92.dp).aspectRatio(90f / 130f).clip(RoundedCornerShape(10.dp))) {
                CoverImage(url = comic.thumbUrl, modifier = Modifier.fillMaxSize())
                // 完结角标（右上角绿底）
                if (comic.finished) {
                    Text(
                        "完结",
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(
                                androidx.compose.ui.graphics.Color(0xFF43A047).copy(alpha = 0.85f),
                                RoundedCornerShape(bottomStart = 8.dp, topEnd = 10.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    if (comic.pagesCount > 0) "[${comic.pagesCount}P]${comic.title}" else comic.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    comic.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                // 分类标签（横滑，防挤爆）
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    comic.categories.take(4).forEach { tag ->
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Favorite, contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFFEF5350),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(formatCount(comic.likes), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        Icons.Rounded.Visibility, contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFFFFB300),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(formatCount(comic.views), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** 数字格式化：>=1万 显示 x.x万 */
private fun formatCount(n: Int): String = when {
    n >= 10000 -> {
        val w = n / 10000.0
        if (w >= 10) "${w.toInt()}万" else String.format("%.1f万", w)
    }
    else -> "$n"
}
