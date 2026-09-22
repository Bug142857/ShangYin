package com.shangyin.app.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodItem
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 动漫首页（里世界 → 动漫）：动漫专源浏览 + 页内搜索。
 * - 每个启用的动漫源一个分组，横向海报展示该源「动漫」分类的最新一页；点海报进动漫详情页
 * - 搜索时会跨全部动漫源搜（不限分类，提高召回），命中结果同样横向展示
 * - 「查看全部」进该源的资源库页（[com.shangyin.app.ui.search.SourceBrowseScreen] 的动漫模式：分类筛选 + 分页网格）
 */
private data class SrcState(
    val status: Int,          // 0=加载中 1=完成 2=失败
    val total: Int,           // 该源动漫分类的总量（搜索时为命中数）
    val items: List<VodItem>,
    val page: Int
)

/**
 * 动漫页会话缓存：进详情页/播放页再返回时保留搜索词与已加载结果（与 H1Cache 同一套思路）。
 * 与番号页缓存分开，互不影响。
 */
private object AnimeCache {
    var stateKeyword: String? = null
    var keyword: String = ""
    var input: String = ""
    var stateMap: Map<String, SrcState> = emptyMap()
}

/** 各源「动漫」分类 type_id 记忆（避免每次进页都重新拉分类表）；只记成功结果，失败下次重试 */
private val animeTypeIdCache = ConcurrentHashMap<String, Int>()

private suspend fun animeTypeIdOf(src: VodSource): Int? {
    animeTypeIdCache[src.id]?.let { return it }
    val cats = runCatching { VodClient.fetchCategories(src) }.getOrDefault(emptyList())
    val hit = cats.firstOrNull { it.type_name.contains("动漫") }?.type_id
    if (hit != null && hit > 0) animeTypeIdCache[src.id] = hit
    return hit
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeHomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val sources = remember { SettingsStore.getAnimeSources().filter { it.enabled } }

    // 从会话缓存恢复（返回时不再重新加载）
    var keyword by remember { mutableStateOf(AnimeCache.keyword) }
    var input by remember { mutableStateOf(AnimeCache.input) }
    var stateMap by remember { mutableStateOf(AnimeCache.stateMap) }
    val loadingKeys = remember { mutableSetOf<String>() } // "srcId:page" 防重复加载

    LaunchedEffect(keyword, input, stateMap) {
        AnimeCache.keyword = keyword
        AnimeCache.input = input
        AnimeCache.stateMap = stateMap
    }

    /** 加载某源某页（浏览时限定「动漫」分类；搜索时不限分类） */
    fun load(src: VodSource, page: Int, kw: String) {
        val key = "${src.id}:$page"
        if (!loadingKeys.add(key)) return
        if (page == 1) {
            stateMap = stateMap + (src.id to SrcState(0, 0, emptyList(), 0))
        }
        scope.launch {
            val typeId = if (kw.isBlank()) animeTypeIdOf(src) else null
            val resp = VodClient.fetchList(src, kw, page, typeId)
            if (kw != keyword) { loadingKeys.remove(key); return@launch } // 关键词已变，丢弃
            stateMap = stateMap.toMutableMap().apply {
                val old = get(src.id)
                if (resp == null) {
                    put(src.id, SrcState(2, old?.total ?: 0, old?.items ?: emptyList(), old?.page ?: 0))
                } else {
                    val merged = ((old?.items ?: emptyList()) + resp.list).distinctBy { it.vod_id }
                    put(src.id, SrcState(1, resp.total, merged, page))
                }
            }
            loadingKeys.remove(key)
        }
    }

    // 关键词变化：新关键词全部源并行重载；恢复场景只补加载缺失的源
    LaunchedEffect(keyword) {
        if (sources.isEmpty()) return@LaunchedEffect
        if (AnimeCache.stateKeyword == keyword) {
            sources.filter { it.id !in stateMap }.forEach { src -> load(src, 1, keyword) }
        } else {
            AnimeCache.stateKeyword = keyword
            loadingKeys.clear()
            stateMap = emptyMap()
            coroutineScope {
                sources.forEach { src -> launch { load(src, 1, keyword) } }
            }
        }
    }

    // 收藏动漫到里世界清单（category="动漫"，doubanId="srcId|vodId"）
    var collectTarget by remember { mutableStateOf<Pair<VodSource, VodItem>?>(null) }
    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val savedIds = remember(allItems) {
        allItems.filter { it.category == "动漫" }.mapNotNull { it.doubanId }.toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("动漫", fontWeight = FontWeight.Bold)
                        Text(
                            if (keyword.isBlank()) "共 ${sources.size} 个源 · 页内可搜索"
                            else "搜索「$keyword」",
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
        if (sources.isEmpty()) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("还没有可用的动漫源", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "到 设置 → 片源管理 → 动漫源配置 里添加/启用采集源（带「动漫」分类的站点即可）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { nav.safeNavigate("animeSources") }) { Text("去动漫源配置") }
            }
        } else {
            Column(Modifier.padding(pad).fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("搜索动漫，留空浏览最新") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                keyword = input.trim()
                            }
                        ),
                        shape = RoundedCornerShape(24.dp),
                        trailingIcon = {
                            if (input.isNotEmpty()) {
                                IconButton(onClick = { input = ""; keyword = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { keyword = input.trim() },
                        enabled = input.trim() != keyword
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = "搜索")
                    }
                }

                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    sources.forEach { src ->
                        val state = stateMap[src.id] ?: SrcState(0, 0, emptyList(), 0)
                        item(key = "a_${src.id}") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)
                            ) {
                                Text(
                                    src.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when {
                                        state.status == 0 -> "加载中…"
                                        state.status == 2 && state.items.isEmpty() -> "加载失败（可能需外网环境）"
                                        keyword.isBlank() -> "共 ${state.total} 部"
                                        else -> "命中 ${state.total} 部"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                if (state.status == 1) {
                                    TextButton(onClick = { nav.safeNavigate("animeBrowse/" + android.net.Uri.encode(src.id)) }) {
                                        Text("查看全部", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        if (state.items.isNotEmpty()) {
                            item(key = "ar_${src.id}") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(state.items, key = { "${src.id}_${it.vod_id}" }) { item ->
                                        AnimeCard(
                                            item = item,
                                            collected = "${src.id}|${item.vod_id}" in savedIds,
                                            onClick = {
                                                nav.safeNavigate(
                                                    "animeDetail/${android.net.Uri.encode(src.id)}/${item.vod_id}"
                                                )
                                            },
                                            onCollect = { collectTarget = src to item }
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

    // 收藏对话框：category="动漫"（doubanId="srcId|vodId"）挂入里世界清单
    collectTarget?.let { (src, item) ->
        CollectDialog(
            onDismiss = { collectTarget = null },
            collect = { listId ->
                val itemId = Repo.saveCustomItem(
                    category = "动漫",
                    doubanId = "${src.id}|${item.vod_id}",
                    title = item.vod_name,
                    coverUrl = item.vod_pic,
                    subTitle = src.name
                )
                if (itemId > 0) { Repo.addItemToList(listId, itemId); true } else false
            }
        )
    }
}

/** 动漫海报卡：海报 + 片名 + 更新进度（vod_remarks，如「更新至第12集」）；右上角收藏 */
@Composable
private fun AnimeCard(
    item: VodItem,
    collected: Boolean,
    onClick: () -> Unit,
    onCollect: () -> Unit
) {
    Column(
        Modifier
            .width(96.dp)
            .clickable(onClick = onClick)
    ) {
        Box {
            CoverImage(
                url = item.vod_pic,
                modifier = Modifier
                    .width(96.dp)
                    .height(128.dp),
                corner = 8.dp
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(22.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = "收藏动漫",
                    tint = if (collected) Color(0xFFEF5350) else Color.White,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { onCollect() }
                )
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
