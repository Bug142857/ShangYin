package com.shangyin.app.ui.live

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlaylistPlay
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.live.LiveChannelResult
import com.shangyin.app.data.live.LivePlatforms
import com.shangyin.app.data.live.LiveQuality
import com.shangyin.app.data.live.LiveRoom
import com.shangyin.app.data.live.M3uClient
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.launch

/**
 * 电视页的会话缓存：从播放页返回、退出搜索都不重头解析。
 *
 * ⚠️ 只在「所有启用的源都成功」时才写 [customResults]：否则首次失败会把空列表缓存成"结果"，
 * 之后 `if (results == null)` 永不重试（v0.192 踩过，表现是"电视里一直是空的"）。
 */
object LiveCache {
    var customResults: List<LiveChannelResult>? = null
    var customGroup: String? = null
    var customScroll: Int = 0
    var searchInput: String = ""
    var searchKeyword: String = ""
}

/**
 * 电视（自定义 M3U / M3U8 源）：按分组浏览 + 页内搜索频道名 + 下拉刷新 + 点频道直接播放。
 *
 * 曾经这里是「直播」模块（虎牙/斗鱼/B站/抖音四个平台 + 电视），
 * 因平台侧接口/流地址问题太多（手机 UA 差异、签名、短时效地址、"发一段就断"…），
 * 用户决定**只保留电视这一块**，模块与收藏分类统一改名「电视」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LiveHomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val savedIds = remember(allItems) {
        allItems.filter { it.category == LivePlatforms.CATEGORY }.mapNotNull { it.doubanId }.toSet()
    }

    // 源配置可能在「电视源配置」里改过：每次进入本页重读一次
    val sources = remember { SettingsStore.getLiveSources() }

    var results by remember { mutableStateOf(LiveCache.customResults) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var selGroup by remember { mutableStateOf(LiveCache.customGroup) }
    var groupExpanded by rememberSaveable { mutableStateOf(false) }
    var openingUrl by remember { mutableStateOf<String?>(null) }
    var collectTarget by remember { mutableStateOf<LiveRoom?>(null) }
    var query by remember { mutableStateOf(LiveCache.searchInput) }
    var keyword by remember { mutableStateOf(LiveCache.searchKeyword) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = LiveCache.customScroll)

    /** 重新拉取并解析所有电视源（失败不写缓存） */
    suspend fun reload() {
        loading = true
        val list = runCatching { M3uClient.loadAll(sources) }.getOrDefault(emptyList())
        results = list
        LiveCache.customResults = if (list.isNotEmpty() && list.none { it.error != null }) list else null
        loading = false
    }

    LaunchedEffect(sources) {
        if (results == null) reload()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { LiveCache.customScroll = it }
    }

    val allChannels = remember(results) {
        results.orEmpty().flatMap { r -> r.channels.map { r.source.name to it } }
    }
    val failed = remember(results) { results.orEmpty().filter { it.error != null } }
    val groups = remember(allChannels) { allChannels.map { it.second.group }.distinct() }
    val counts = remember(allChannels) { allChannels.groupingBy { it.second.group }.eachCount() }
    val searchActive = keyword.isNotBlank()
    val shown = remember(allChannels, selGroup, searchActive, keyword) {
        allChannels.filter { (_, ch) ->
            val byGroup = searchActive || selGroup == null || ch.group == selGroup
            val byKw = !searchActive || ch.name.contains(keyword, ignoreCase = true)
            byGroup && byKw
        }
    }

    // 逐级返回：搜索态下返回键先退出搜索（回到分组浏览），再按返回才离开电视页
    BackHandler(enabled = searchActive) {
        keyword = ""
        LiveCache.searchKeyword = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电视", fontWeight = FontWeight.Bold) },
                actions = {
                    // 电视源配置（添加 / 导入 / 测试 / 导出）
                    IconButton(onClick = { nav.safeNavigate("liveSources") }) {
                        Icon(Icons.Rounded.PlaylistPlay, contentDescription = "电视源配置")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 页内搜索窗：没有单独的搜索按钮，输入法回车即搜（与其它模块一致）
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    LiveCache.searchInput = it
                    if (it.isBlank()) {
                        keyword = ""
                        LiveCache.searchKeyword = ""
                    }
                },
                placeholder = { Text("搜索电视频道名") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            keyword = ""
                            LiveCache.searchInput = ""
                            LiveCache.searchKeyword = ""
                        }) { Icon(Icons.Rounded.Close, contentDescription = "清空") }
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                    keyword = query.trim()
                    LiveCache.searchKeyword = keyword
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 分组筛选：与漫画 / 游戏 / 源资源库同一套形态 —— 收起=横滑一行 chips + 下拉箭头，
            // 展开=换行 chips（限高可滚）；电视能精确统计每组频道数，所以 chip 带数量（搜索态隐藏，避免与关键词混淆）
            if (!searchActive && groups.isNotEmpty()) {
                val pick: (String?) -> Unit = { g ->
                    selGroup = g
                    LiveCache.customGroup = g
                    scope.launch { listState.scrollToItem(0) }
                }
                if (!groupExpanded) {
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
                                selected = selGroup == null,
                                onClick = { pick(null) },
                                label = { Text("全部（${allChannels.size}）") },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            groups.forEach { g ->
                                FilterChip(
                                    selected = selGroup == g,
                                    onClick = { pick(g) },
                                    label = { Text("$g（${counts[g] ?: 0}）") },
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                        IconButton(onClick = { groupExpanded = true }) {
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "展开分组")
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
                            Text("全部分组", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { groupExpanded = false }) {
                                Icon(Icons.Rounded.ArrowDropUp, contentDescription = "收起分组")
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
                                selected = selGroup == null,
                                onClick = { pick(null); groupExpanded = false },
                                label = { Text("全部（${allChannels.size}）") }
                            )
                            groups.forEach { g ->
                                FilterChip(
                                    selected = selGroup == g,
                                    onClick = { pick(g); groupExpanded = false },
                                    label = { Text("$g（${counts[g] ?: 0}）") }
                                )
                            }
                        }
                    }
                }
            }

            if (searchActive) {
                Text(
                    "搜索「$keyword」：命中 ${shown.size} 个频道（只在已导入的源里找）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            when {
                loading && shown.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyView(
                            when {
                                failed.isNotEmpty() ->
                                    failed.joinToString("\n") { "「${it.source.name}」${it.error}" }
                                allChannels.isNotEmpty() -> "该分组下没有频道"
                                searchActive -> "没有搜到「$keyword」\n（电视搜索只找已导入源里的频道名）"
                                sources.none { it.enabled } -> "还没有启用的电视源\n去「电视源配置」添加 M3U 地址或导入本地文件"
                                else -> "该源没有解析到频道，可在「电视源配置」里更换地址或导入本地文件"
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    results = null
                                    LiveCache.customResults = null
                                    reload()
                                }
                            }) { Text(if (loading) "加载中…" else "重试") }
                            Button(onClick = { nav.safeNavigate("liveSources") }) {
                                Text("去电视源配置")
                            }
                        }
                    }
                }

                else -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            results = null
                            LiveCache.customResults = null
                            reload()
                            refreshing = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        lazyItems(shown, key = { it.second.url }) { (sourceName, ch) ->
                            val room = LiveRoom(
                                platform = LivePlatforms.CUSTOM,
                                roomId = ch.url,
                                title = ch.name,
                                streamer = sourceName,
                                categoryName = ch.group
                            )
                            val collected = room.collectId in savedIds
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (openingUrl == null) {
                                            openingUrl = ch.url
                                            scope.launch {
                                                // M3U 里同名频道常有多条地址（不同线路）→ 交给播放器当「线路」菜单
                                                val sameName = allChannels
                                                    .filter { it.second.name == ch.name }
                                                    .map { it.second.url }
                                                    .distinct()
                                                val lines = if (sameName.size > 1) {
                                                    sameName.mapIndexed { i, u ->
                                                        LiveQuality(
                                                            "线路 ${i + 1}",
                                                            u,
                                                            u.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                                                        )
                                                    }
                                                } else emptyList()
                                                val ok = openLiveAndPlay(nav, context, room, extraQualities = lines)
                                                if (!ok) openingUrl = null
                                            }
                                        }
                                    }
                                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ch.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            append(ch.group)
                                            if (sourceName.isNotBlank()) append(" · ").append(sourceName)
                                            if (linesHint(allChannels, ch.name)) append(" · 多线路")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (openingUrl == ch.url) {
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                                IconButton(onClick = { collectTarget = room }) {
                                    Icon(
                                        if (collected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = if (collected) "已收藏" else "收藏",
                                        tint = if (collected) Color(0xFFE53935)
                                        else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                        if (failed.isNotEmpty()) {
                            item {
                                Text(
                                    failed.joinToString("\n") { "「${it.source.name}」${it.error}" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    collectTarget?.let { room ->
        CollectDialog(
            onDismiss = { collectTarget = null },
            collect = { listId ->
                val itemId = Repo.saveCustomItem(
                    category = LivePlatforms.CATEGORY,
                    doubanId = room.collectId,
                    title = room.title,
                    coverUrl = room.cover,
                    subTitle = buildString {
                        append("电视")
                        if (room.categoryName.isNotBlank()) append(" · ").append(room.categoryName)
                        if (room.streamer.isNotBlank()) append(" · ").append(room.streamer)
                    }
                )
                if (itemId > 0) {
                    Repo.addItemToList(listId, itemId)
                    true
                } else false
            }
        )
    }
}

/** 同名频道是否有多条地址（有多条就提示"多线路"，与播放页的线路菜单对应） */
private fun linesHint(all: List<Pair<String, com.shangyin.app.data.live.LiveChannel>>, name: String): Boolean =
    all.count { it.second.name == name } > 1
