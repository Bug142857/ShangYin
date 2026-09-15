package com.shangyin.app.ui.search

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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
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
 * 分类分开展示，点击影片直接播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(nav: NavHostController, srcId: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val src = remember { SettingsStore.getVodSources().firstOrNull { it.id == srcId } }

    var categories by remember { mutableStateOf<List<VodCategory>>(emptyList()) }
    var selectedType by remember { mutableStateOf<Int?>(null) } // null=全部
    var items by remember { mutableStateOf<List<VodItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) } // 已加载页码
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var openingId by remember { mutableStateOf<Long?>(null) }
    val gridState = rememberLazyGridState()

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
                items = if (target == 1) resp.list
                else (items + resp.list).distinctBy { it.vod_id }
                total = resp.total
                page = target
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

    // 首次进入 / 切换分类 → 重置加载第一页
    LaunchedEffect(selectedType) {
        items = emptyList()
        total = 0
        page = 0
        loadPage(1)
    }

    // 分类下拉框状态 + 各分类资源数探测（total=0 的分类不显示）
    var catExpanded by remember { mutableStateOf(false) }
    var catCounts by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    // 并发探测每个分类的资源总数（限并发 8），空分类自动从下拉中剔除
    LaunchedEffect(categories) {
        if (categories.isEmpty()) return@LaunchedEffect
        val sem = Semaphore(8)
        categories.map { cat ->
            launch {
                sem.withPermit {
                    val t = runCatching {
                        VodClient.fetchList(src, "", 1, cat.type_id)?.total ?: 0
                    }.getOrDefault(0)
                    catCounts = catCounts + (cat.type_id to t)
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
            // 分类下拉框（"全部" + 有资源的分类，右侧显示资源数）
            ExposedDropdownMenuBox(
                expanded = catExpanded,
                onExpandedChange = { catExpanded = it },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(selName, style = MaterialTheme.typography.bodyMedium)
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                ExposedDropdownMenu(
                    expanded = catExpanded,
                    onDismissRequest = { catExpanded = false },
                    modifier = Modifier.width(220.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("全部") },
                        trailingIcon = if (selectedType == null) {
                            { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            selectedType = null
                            catExpanded = false
                        }
                    )
                    visibleCats.forEach { cat ->
                        val n = catCounts[cat.type_id]
                        DropdownMenuItem(
                            text = {
                                Text(if (n != null) "${cat.type_name}（$n）" else cat.type_name)
                            },
                            trailingIcon = if (selectedType == cat.type_id) {
                                { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            onClick = {
                                selectedType = cat.type_id
                                catExpanded = false
                            }
                        )
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
                            onClick = {
                                if (openingId == null) {
                                    openingId = item.vod_id
                                    scope.launch {
                                        val ok = openVodAndPlay(nav, context, src, item)
                                        if (!ok) openingId = null
                                    }
                                }
                            }
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
}

/** 网格海报卡：海报 3:4 + 片名 + 备注，点击播放 */
@Composable
private fun GridCard(
    item: VodItem,
    opening: Boolean,
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
