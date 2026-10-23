package com.shangyin.app.ui.search

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.InnerFav
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodCategory
import com.shangyin.app.data.vod.VodItem
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.common.AppPageBar
import com.shangyin.app.ui.common.AppSearchField
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.innerfav.FavHeartButton
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 番号模块（番1~番4，v0.304 重做）：
 * 打开即为「搜索框 + 该片源全部影片网格 + 底部页码条」，不再按源分组横滑。
 * 第 N 个模块对应外网目录里第 N 个启用中的片源；封面显示时长（源提供时）。
 */
private class H1CacheState {
    var keyword: String = ""
    var typeId: Int? = null
    var items: List<VodItem> = emptyList()
    var total: Int = 0
    var lastPage: Int = 0
    var page: Int = 1
    var loaded: Boolean = false
}

private val H1Caches = mutableMapOf<Int, H1CacheState>()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun H1SearchScreen(nav: NavHostController, kwEncoded: String, sourceIndex: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val idx = sourceIndex.coerceAtLeast(1)
    val initialKw = remember {
        runCatching { java.net.URLDecoder.decode(kwEncoded, "UTF-8") }.getOrDefault(kwEncoded)
    }

    // 第 N 个启用中的外网片源
    val src: VodSource? = remember(idx) {
        SettingsStore.getVodSources()
            .filter { it.enabled && it.region == "proxy" }
            .getOrNull(idx - 1)
    }
    val cache = remember(idx) { H1Caches.getOrPut(idx) { H1CacheState() } }

    var input by remember { mutableStateOf(cache.keyword.ifBlank { initialKw.trim() }) }
    var keyword by remember { mutableStateOf(cache.keyword) }
    var items by remember { mutableStateOf(cache.items) }
    var total by remember { mutableIntStateOf(cache.total) }
    var lastPage by remember { mutableIntStateOf(cache.lastPage) }
    var page by remember { mutableIntStateOf(cache.page) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var openingId by remember { mutableStateOf<Long?>(null) }
    // v0.305：分类筛选 + 各分类数量（展开下拉后探测）
    var categories by remember { mutableStateOf<List<com.shangyin.app.data.vod.VodCategory>>(emptyList()) }
    var selectedType by remember { mutableStateOf<Int?>(cache.typeId) }
    var catCounts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var catExpanded by remember { mutableStateOf(false) }
    var reqSeq by remember { mutableIntStateOf(0) } // 竞态防护：只认最后一次请求

    fun load(target: Int) {
        val s = src ?: return
        val myReq = ++reqSeq
        val kw = keyword
        val type = selectedType
        loading = true
        scope.launch {
            val resp = VodClient.fetchList(s, kw, target, type)
            if (myReq != reqSeq) return@launch
            if (resp != null) {
                items = resp.list
                total = resp.total
                lastPage = resp.pagecount
                page = target
                failed = false
                if (resp.categories.isNotEmpty()) categories = resp.categories
                cache.keyword = kw
                cache.typeId = type
                cache.items = resp.list
                cache.total = resp.total
                cache.lastPage = resp.pagecount
                cache.page = target
                cache.loaded = true
            } else {
                failed = true
            }
            loading = false
        }
    }

    // 分类表兜底（很多源 ac=videolist 不带 class）
    LaunchedEffect(src?.id) {
        val s = src ?: return@LaunchedEffect
        if (categories.isEmpty()) {
            runCatching { VodClient.fetchCategories(s) }.getOrNull()?.let {
                if (it.isNotEmpty()) categories = it
            }
        }
    }

    // 展开分类下拉时并发探测各分类数量（限并发 6，失败保持未知）
    LaunchedEffect(catExpanded, categories) {
        val s = src ?: return@LaunchedEffect
        if (!catExpanded || categories.isEmpty()) return@LaunchedEffect
        val missing = categories.filter { it.type_id !in catCounts }
        if (missing.isEmpty()) return@LaunchedEffect
        val sem = kotlinx.coroutines.sync.Semaphore(6)
        kotlinx.coroutines.coroutineScope {
            missing.forEach { cat ->
                launch {
                    sem.withPermit {
                        runCatching { VodClient.fetchList(s, "", 1, cat.type_id)?.total }.getOrNull()
                            ?.let { catCounts = catCounts + (cat.type_id to it) }
                    }
                }
            }
        }
    }

    // 首次进入 / 关键词 / 分类变化 → 拉第一页；返回本页时用缓存恢复，不重复请求
    LaunchedEffect(src?.id, keyword, selectedType) {
        if (src == null) return@LaunchedEffect
        if (cache.loaded && cache.keyword == keyword && cache.typeId == selectedType) return@LaunchedEffect
        items = emptyList()
        total = 0
        lastPage = 0
        page = 1
        load(1)
    }

    fun play(item: VodItem) {
        val s = src ?: return
        if (openingId != null) return
        openingId = item.vod_id
        scope.launch {
            val ok = openVodAndPlay(nav, context, s, item)
            if (!ok) openingId = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("番$idx", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                src == null -> "暂无对应片源"
                                failed && items.isEmpty() -> "加载失败（可能需外网环境）"
                                loading && items.isEmpty() -> "加载中…"
                                else -> "${src.name} · 共 $total 部"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("innerFavs/h1") }) {
                        Icon(Icons.Rounded.FavoriteBorder, contentDescription = "我的收藏")
                    }
                }
            )
        }
    ) { pad ->
        if (src == null) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("番$idx 还没有对应片源", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "番1~番4 依次对应外网目录里第 1~4 个启用中的片源；到片源管理里添加/启用片源后即可使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { nav.safeNavigate("vodSources") }) { Text("去片源管理") }
            }
            return@Scaffold
        }

        Column(Modifier.padding(pad).fillMaxSize()) {
            AppSearchField(
                value = input,
                onValueChange = {
                    input = it
                    if (it.isEmpty()) keyword = ""
                },
                onSearch = {
                    keyboard?.hide()
                    keyword = input.trim()
                },
                placeholder = "在 ${src.name} 里搜索",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // 分类筛选（v0.305 恢复：收起=横滑一行；展开=换行 chips，带各自数量）
            // v0.306：探测到 0 个资源的分类不显示（未探测到的先显示）
            val visibleCats = categories.filter { (catCounts[it.type_id] ?: 1) > 0 }
            if (keyword.isBlank() && visibleCats.isNotEmpty()) {
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
                                selected = selectedType == null,
                                onClick = { selectedType = null },
                                label = { Text("全部") },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            visibleCats.forEach { c ->
                                FilterChip(
                                    selected = selectedType == c.type_id,
                                    onClick = { selectedType = c.type_id },
                                    label = { Text(h1CatLabel(c, catCounts)) },
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
                            .heightIn(max = 280.dp)
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
                                selected = selectedType == null,
                                onClick = { selectedType = null; catExpanded = false },
                                label = { Text("全部") }
                            )
                            visibleCats.forEach { c ->
                                FilterChip(
                                    selected = selectedType == c.type_id,
                                    onClick = { selectedType = c.type_id; catExpanded = false },
                                    label = { Text(h1CatLabel(c, catCounts)) }
                                )
                            }
                        }
                    }
                }
            }

            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                failed && items.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("加载失败", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "该源可能需要外网环境才能访问，或接口暂时不可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { load(page.coerceAtLeast(1)) }) { Text("重试") }
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (keyword.isBlank()) "该源暂无内容" else "没有找到「$keyword」",
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(items, key = { it.vod_id }) { item ->
                            H1GridCard(
                                item = item,
                                opening = openingId == item.vod_id,
                                favId = "${src.id}|${item.vod_id}",
                                favSubtitle = src.name,
                                onClick = { play(item) }
                            )
                        }
                        // 底部页码条（v0.305：样式与 X 站一致，跟着列表滚到最后才出现）
                        item(key = "footer", span = { GridItemSpan(3) }) {
                            AppPageBar(
                                currentPage = page,
                                lastPage = lastPage,
                                loading = loading,
                                onGo = { p -> if (!loading) load(p) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .navigationBarsPadding()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 番号网格卡：海报 + 时长角标 + 片名 + 备注，右上角爱心快藏 */
@Composable
private fun H1GridCard(
    item: VodItem,
    opening: Boolean,
    favId: String,
    favSubtitle: String,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !opening, onClick = onClick)
    ) {
        Box {
            CoverImage(
                url = item.vod_pic,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
                corner = 8.dp
            )
            // 时长角标（右下角黑底白字；源没给时长就不显示）
            vodDurationLabel(item)?.let { d ->
                Text(
                    d,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            FavHeartButton(
                favModule = "h1",
                favId = favId,
                build = {
                    InnerFav(
                        module = "h1",
                        id = favId,
                        title = item.vod_name,
                        subtitle = favSubtitle,
                        cover = item.vod_pic,
                        savedAt = System.currentTimeMillis()
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
            )
            if (opening) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "打开中",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.vod_name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            when {
                item.vod_remarks.isNotBlank() -> item.vod_remarks
                item.type_name.isNotBlank() -> item.type_name
                item.vod_year.isNotBlank() -> item.vod_year
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 分类 chip 文案：`名称 (数量)`；数量未探测到就只显示名称 */
private fun h1CatLabel(cat: VodCategory, counts: Map<Int, Int>): String =
    cat.type_name + (counts[cat.type_id]?.let { " ($it)" } ?: "")

/**
 * 时长展示：优先用源给的 vod_duration；没有时从备注里找「mm:ss / hh:mm:ss / N分钟」。
 * 都找不到返回 null（不显示角标，绝不编造）。
 */
private fun vodDurationLabel(item: VodItem): String? {
    val raw = item.vod_duration.trim()
    if (raw.isNotBlank()) {
        return if (raw.all { it.isDigit() }) "${raw}分钟" else raw
    }
    val m = Regex("""(\d{1,2}:\d{2}(?::\d{2})?|\d{1,3}\s*分钟)""").find(item.vod_remarks)
    return m?.value
}
