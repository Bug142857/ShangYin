package com.shangyin.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.vod.VodCategory
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodItem
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 外网源资源库页（H1 浏览页点"查看全部"进入）：
 * 顶部分类 chips（接口 class 字段，按分类 ?t= 过滤）+ 3 列海报网格分页浏览，
 * 分类分开展示，点击影片直接播放，右上角收藏番号到里世界清单。
 */
/**
 * 浏览页会话缓存：进播放页会让本组合被销毁重建（Compose 只保留 rememberSaveable），
 * 用普通 remember 的话**返回后分类选择、已加载列表全丢**（表现："返回到选分类之前"）。
 * 这里按 srcId 记一份，返回时原样恢复（与 H1SearchScreen 的 H1Cache 同一套思路）。
 */
private object BrowseCache {
    var srcId: String? = null
    var categories: List<VodCategory> = emptyList()
    var catCounts: Map<Int, Int> = emptyMap()
    var catExpanded = false
    var selectedType: Int? = null

    /** 已加载列表对应的分类（null 表示"全部"）——用它判断返回时要不要重新拉第一页 */
    var loadedType: Int? = null
    var items: List<VodItem> = emptyList()
    var total = 0
    var page = 0
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SourceBrowseScreen(nav: NavHostController, srcId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val src = remember { SettingsStore.getVodSources().firstOrNull { it.id == srcId } }

    // 同一次浏览会话（srcId 相同）→ 用缓存恢复；换了源则重新开始
    val restored = remember(srcId) { BrowseCache.srcId == srcId }
    var categories by remember { mutableStateOf(if (restored) BrowseCache.categories else emptyList()) }
    var selectedType by remember { mutableStateOf(if (restored) BrowseCache.selectedType else null) }
    var items by remember { mutableStateOf(if (restored) BrowseCache.items else emptyList()) }
    var total by remember { mutableIntStateOf(if (restored) BrowseCache.total else 0) }
    var page by remember { mutableIntStateOf(if (restored) BrowseCache.page else 0) } // 已加载页码
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var openingId by remember { mutableStateOf<Long?>(null) }
    val gridState = rememberLazyGridState()

    // 分类下拉框状态 + 各分类资源数探测（total=0 的分类不显示）
    var catExpanded by remember { mutableStateOf(if (restored) BrowseCache.catExpanded else false) }
    var catCounts by remember { mutableStateOf(if (restored) BrowseCache.catCounts else emptyMap<Int, Int>()) }

    // 收藏番号到里世界清单（category="番号"，doubanId="srcId|vodId"，与 H1 浏览页同一口径）
    var collectTarget by remember { mutableStateOf<VodItem?>(null) }
    val allItems by com.shangyin.app.data.Repo.observeItems(null)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val savedIds = remember(allItems) {
        allItems.filter { it.category == "番号" }.mapNotNull { it.doubanId }.toSet()
    }

    // 状态变化实时写回缓存（进播放页返回后完整恢复：分类选择、已加载列表、展开状态）
    LaunchedEffect(srcId, categories, selectedType, items, total, page, catExpanded, catCounts) {
        BrowseCache.srcId = srcId
        BrowseCache.categories = categories
        BrowseCache.selectedType = selectedType
        BrowseCache.items = items
        BrowseCache.total = total
        BrowseCache.page = page
        BrowseCache.catExpanded = catExpanded
        BrowseCache.catCounts = catCounts
    }

    if (src == null) {
        // 源不存在（被删除）：直接返回
        androidx.compose.runtime.LaunchedEffect(Unit) { nav.safePopBackStack() }
        return
    }

    fun loadPage(target: Int) {
        if (loading) return
        loading = true
        scope.launch {
            // 分类表兜底：很多源 ac=videolist 不带 class 字段，首次加载并行补拉 ?ac=list
            val needCats = categories.isEmpty()
            val catsJob = if (needCats) scope.async { VodClient.fetchCategories(src) } else null
            val resp = VodClient.fetchList(src, "", target, selectedType)
            if (resp != null) {
                if (categories.isEmpty()) categories = resp.categories
                // 换分类后的第一页直接替换，否则追加（去重）
                // 列表 key = vod_id：翻页可能回带同一部 → 两种分支都去重，否则重复 key 崩列表
                items = (if (target == 1) resp.list else items + resp.list).distinctBy { it.vod_id }
                total = resp.total
                page = target
                // 记录"这份列表属于哪个分类"，返回本页时据此判断是否需要重新拉第一页
                BrowseCache.loadedType = selectedType
                failed = false
            } else {
                failed = true
            }
            val cats = catsJob?.await().orEmpty()
            if (cats.isNotEmpty()) {
                // 合并去重（顶级分类 type_pid=0 排前面，二级分类随后）
                categories = (categories + cats)
                    .distinctBy { it.type_id }
                    .sortedWith(compareBy({ it.type_pid }, { it.type_id }))
            }
            loading = false
        }
    }

    // 首次进入 / 切换分类 → 重置加载第一页。
    // ⚠️ 进播放页再返回时本组合会重建（remember 丢失），所以先看会话缓存：
    //    缓存里的列表就是这个分类的数据 → 原样保留，不要重置（否则"返回后回到选分类之前"）
    LaunchedEffect(selectedType) {
        if (BrowseCache.srcId == srcId &&
            BrowseCache.loadedType == selectedType &&
            items.isNotEmpty()
        ) {
            return@LaunchedEffect
        }
        items = emptyList()
        total = 0
        page = 0
        loadPage(1)
    }

    // 并发探测每个分类的资源总数（限并发 8），空分类自动从下拉中剔除
    LaunchedEffect(categories) {
        if (categories.isEmpty()) return@LaunchedEffect
        // 已全部探测过（返回本页的场景）→ 不重复探测
        if (categories.all { catCounts.containsKey(it.type_id) }) return@LaunchedEffect
        val sem = Semaphore(8)
        categories.map { cat ->
            launch {
                sem.withPermit {
                    // 探测失败（网络/被墙）返回 null：**不能**当成 0，否则分类会被静默从下拉里抹掉
                    val t = runCatching { VodClient.fetchList(src, "", 1, cat.type_id)?.total }
                        .getOrNull()
                    if (t != null) catCounts = catCounts + (cat.type_id to t)
                }
            }
        }.joinAll()
    }

    // 只展示"有资源"的分类（未探测完的先全部显示，探测到空再剔除）
    val visibleCats = categories.filter { (catCounts[it.type_id] ?: 1) > 0 }
    val selName = selectedType
        ?.let { tid -> categories.firstOrNull { it.type_id == tid }?.type_name }
        ?: "全部分类"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(src.name, fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                failed && items.isEmpty() -> "加载失败（可能需外网环境）"
                                loading && items.isEmpty() -> "加载中…"
                                else -> "共 $total 部 · 已加载 ${items.size} 部"
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
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            // 分类区：收起=横滑一行 + 三角展开；展开=标题行 + 换行 chips（限高可滚），
            // 两种状态互斥显示（避免首行重复）
            if (!catExpanded) {
                Row(
                    Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedType == null,
                                onClick = { selectedType = null },
                                label = { Text("全部") }
                            )
                        }
                        items(visibleCats, key = { it.type_id }) { cat ->
                            FilterChip(
                                selected = selectedType == cat.type_id,
                                onClick = { selectedType = cat.type_id },
                                label = { Text(cat.type_name) }
                            )
                        }
                    }
                    IconButton(onClick = { catExpanded = true }) {
                        Icon(
                            Icons.Rounded.ArrowDropDown,
                            contentDescription = "展开分类"
                        )
                    }
                }
            } else {
                Column(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .heightIn(max = 320.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        FilterChip(
                            selected = selectedType == null,
                            onClick = {
                                selectedType = null
                                catExpanded = false
                            },
                            label = { Text("全部") }
                        )
                        visibleCats.forEach { cat ->
                            val n = catCounts[cat.type_id]
                            FilterChip(
                                selected = selectedType == cat.type_id,
                                onClick = {
                                    selectedType = cat.type_id
                                    catExpanded = false
                                },
                                label = {
                                    Text(if (n != null) "${cat.type_name} ${n}" else cat.type_name)
                                }
                            )
                        }
                    }
                }
            }

            if (failed && items.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
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
                    Button(onClick = { loadPage(1) }) { Text("重试") }
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.vod_id }) { item ->
                        GridCard(
                            item = item,
                            opening = openingId == item.vod_id,
                            collected = "${src.id}|${item.vod_id}" in savedIds,
                            onClick = {
                                if (openingId == null) {
                                    openingId = item.vod_id
                                    scope.launch {
                                        val ok = openVodAndPlay(nav, context, src, item)
                                        if (!ok) openingId = null
                                    }
                                }
                            },
                            onCollect = { collectTarget = item }
                        )
                    }
                    // 底部加载更多（占满整行）
                    if (items.isNotEmpty() && (total <= 0 || items.size < total)) {
                        item(span = { GridItemSpan(3) }, key = "more") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = { if (!loading) loadPage(page + 1) },
                                    enabled = !loading
                                ) {
                                    Text(if (loading) "加载中…" else "加载更多 (已 ${items.size}/$total)")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 收藏番号对话框：存为 category="番号" 条目（doubanId="srcId|vodId"）挂入里世界清单
    // ——「查看全部」页也要能收藏，否则用户从 H1 进来就找不到收藏入口
    collectTarget?.let { item ->
        com.shangyin.app.ui.common.CollectDialog(
            onDismiss = { collectTarget = null },
            collect = { listId ->
                val itemId = com.shangyin.app.data.Repo.saveCustomItem(
                    category = "番号",
                    doubanId = "${src.id}|${item.vod_id}",
                    title = item.vod_name,
                    coverUrl = item.vod_pic,
                    subTitle = src.name
                )
                if (itemId > 0) {
                    com.shangyin.app.data.Repo.addItemToList(listId, itemId)
                    true
                } else false
            }
        )
    }
}

/** 网格海报卡：海报 3:4 + 片名 + 备注，点击播放，右上角收藏番号 */
@Composable
private fun GridCard(
    item: VodItem,
    opening: Boolean,
    collected: Boolean,
    onClick: () -> Unit,
    onCollect: () -> Unit
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
            // 收藏角标（右上角小爱心，带半透明底，不挡海报点击）
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(24.dp)
                    .background(
                        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f),
                        RoundedCornerShape(50)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = "收藏番号",
                    tint = if (collected) androidx.compose.ui.graphics.Color(0xFFEF5350)
                    else androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable { onCollect() }
                )
            }
            if (opening) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .background(
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "打开中",
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White
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
                item.vod_year.isNotBlank() -> item.vod_year
                else -> " "
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
