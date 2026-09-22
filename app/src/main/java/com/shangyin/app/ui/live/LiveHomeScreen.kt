package com.shangyin.app.ui.live

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlaylistPlay
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.live.BiliLiveClient
import com.shangyin.app.data.live.DouyuClient
import com.shangyin.app.data.live.DouyinClient
import com.shangyin.app.data.live.HuyaClient
import com.shangyin.app.data.live.LiveCategory
import com.shangyin.app.data.live.LiveChannelResult
import com.shangyin.app.data.live.LivePlatforms
import com.shangyin.app.data.live.LiveRoom
import com.shangyin.app.data.live.M3uClient
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 直播会话缓存：Compose 导航到播放页会销毁前一个组合，
 * 用进程内缓存保住「平台 / 分区选择 / 已加载房间 / 展开状态 / 滚动位置」，返回时不重头加载。
 */
internal object LiveCache {
    var platform: String = LivePlatforms.HUYA
    val categories = mutableMapOf<String, List<LiveCategory>>()
    val selectedCat = mutableMapOf<String, LiveCategory>()
    val rooms = mutableMapOf<String, List<LiveRoom>>()
    val page = mutableMapOf<String, Int>()
    val catExpanded = mutableMapOf<String, Boolean>()
    val scroll = mutableMapOf<String, Int>()

    /** 自定义源解析结果（null = 还没加载过） */
    var customResults: List<LiveChannelResult>? = null
    var customGroup: String? = null

    fun roomsOf(platform: String, catId: String): List<LiveRoom> = rooms["$platform|$catId"].orEmpty()
    fun pageOf(platform: String, catId: String): Int = page["$platform|$catId"] ?: 1
    fun scrollOf(platform: String): Int = scroll[platform] ?: 0
}

/**
 * 里世界「直播」：
 * - 平台切换：虎牙 / 斗鱼 / B站 / 我的源（自定义 M3U 频道）
 * - 平台页：分区筛选（收起横滑一行 / 展开平铺）+ 房间封面网格，点卡片直接播放，右上角爱心收藏到里世界清单
 * - 我的源页：导入的 M3U 频道按分组展示，点频道直接播放
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveHomeScreen(nav: NavHostController) {
    var platform by rememberSaveable { mutableStateOf(LiveCache.platform) }
    var collectTarget by remember { mutableStateOf<LiveRoom?>(null) }
    var biliLoggedIn by remember { mutableStateOf(SettingsStore.isBiliLoggedIn) }

    // 已收藏的直播条目（爱心高亮；从清单返回后状态实时更新）
    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val savedIds = remember(allItems) {
        allItems.filter { it.category == LivePlatforms.CATEGORY }.mapNotNull { it.doubanId }.toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("直播", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 自定义源管理（添加 / 导入 / 删除）
                    IconButton(onClick = { nav.safeNavigate("liveSources") }) {
                        Icon(Icons.Rounded.PlaylistPlay, contentDescription = "直播源管理")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 平台切换
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LivePlatforms.ALL.forEach { key ->
                    FilterChip(
                        selected = platform == key,
                        onClick = {
                            platform = key
                            LiveCache.platform = key
                        },
                        label = { Text(LivePlatforms.label(key)) }
                    )
                }
            }
            // B站未登录提示（匿名只能到超清，登录后可看原画）
            if (platform == LivePlatforms.BILI && !biliLoggedIn) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "未登录 B 站，只能看超清；到「设置 → 账号管理 → B站登录」可看原画",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { nav.safeNavigate("account") }) { Text("去登录") }
                }
            }

            when (platform) {
                LivePlatforms.CUSTOM -> CustomSourcePane(nav, savedIds, onCollect = { collectTarget = it })
                // ⚠️ key(platform) 必须有：四个平台共用同一个 composable 调用点，
                // 不加 key 时 remember 会把上一个平台的分区/房间状态带过来 → 切平台「没反应」
                else -> key(platform) {
                    PlatformPane(nav, platform, savedIds, onCollect = { collectTarget = it })
                }
            }
        }
    }

    // B站登录态可能在账号管理里变过，回到本页时重读一次
    LaunchedEffect(Unit) { biliLoggedIn = SettingsStore.isBiliLoggedIn }

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
                        append(LivePlatforms.label(room.platform))
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

/** 平台分区 + 房间网格 */
@Composable
private fun PlatformPane(
    nav: NavHostController,
    platform: String,
    savedIds: Set<String>,
    onCollect: (LiveRoom) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    var categories by remember { mutableStateOf(LiveCache.categories[platform].orEmpty()) }
    val cachedCat = LiveCache.selectedCat[platform]
    var selected by remember { mutableStateOf(cachedCat) }
    var rooms by remember {
        mutableStateOf(if (cachedCat != null) LiveCache.roomsOf(platform, cachedCat.id) else emptyList())
    }
    var page by remember {
        mutableStateOf(if (cachedCat != null) LiveCache.pageOf(platform, cachedCat.id) else 1)
    }
    var hasMore by remember { mutableStateOf(rooms.isNotEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var catExpanded by remember { mutableStateOf(LiveCache.catExpanded[platform] ?: false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var openingId by remember { mutableStateOf<String?>(null) }

    /** 拉某个分区的房间（reset = 回到第一页） */
    suspend fun loadRooms(cat: LiveCategory, targetPage: Int, reset: Boolean) {
        loading = true
        error = null
        val fetched = runCatching {
            when (platform) {
                LivePlatforms.HUYA -> HuyaClient.rooms(cat.id, targetPage)
                LivePlatforms.DOUYU -> DouyuClient.rooms(cat.id, targetPage)
                LivePlatforms.DOUYIN -> DouyinClient.rooms(cat.id, targetPage)
                else -> BiliLiveClient.rooms(cat.id, targetPage)
            }
        }.getOrDefault(emptyList())
        rooms = if (reset) fetched else rooms + fetched
        page = targetPage
        hasMore = fetched.isNotEmpty()
        loading = false
        if (reset && fetched.isEmpty()) {
            error = "没有加载到房间（可能是网络问题，也可能是该分区当前无人直播），可点重试"
        }
        LiveCache.rooms["$platform|${cat.id}"] = rooms
        LiveCache.page["$platform|${cat.id}"] = page
    }

    // 首次进入（或缓存为空）：拉分区 → 选中第一个/缓存里的 → 拉第一页
    LaunchedEffect(platform) {
        var cat = selected
        if (categories.isEmpty()) {
            loading = true
            error = null
            // 首次打开偶发失败（网络抖动/站点限速）时自动重试一次，避免"要点几下才出来"
            var fetched = emptyList<LiveCategory>()
            var attempt = 0
            while (attempt < 2 && fetched.isEmpty()) {
                if (attempt > 0) delay(800)
                fetched = runCatching {
                    when (platform) {
                        LivePlatforms.HUYA -> HuyaClient.categories()
                        LivePlatforms.DOUYU -> DouyuClient.categories()
                        LivePlatforms.DOUYIN -> DouyinClient.categories()
                        else -> BiliLiveClient.categories()
                    }
                }.getOrDefault(emptyList())
                attempt++
            }
            categories = fetched
            LiveCache.categories[platform] = fetched
            loading = false
            if (fetched.isEmpty()) error = "没有加载到分区（可能是网络问题），可点重试"
            if (cat == null) cat = fetched.firstOrNull()
        }
        if (cat != null) {
            selected = cat
            LiveCache.selectedCat[platform] = cat
            if (rooms.isEmpty()) {
                loadRooms(cat, 1, reset = true)
                // 恢复上次滚动位置（同一分区）
                val cachedScroll = LiveCache.scrollOf(platform)
                if (cachedScroll > 0) runCatching { gridState.scrollToItem(cachedScroll) }
            }
        }
    }

    // 滚动位置实时写回缓存
    LaunchedEffect(gridState, platform) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { i -> LiveCache.scroll[platform] = i }
    }

    Column(Modifier.fillMaxSize()) {
        if (categories.isNotEmpty()) {
            // 分区选择窗：点开弹窗选（斗鱼分区上百个 → 弹窗里带搜索；平台接口拿不到每区房间数，只列名称）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { pickerOpen = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    selected?.name ?: "选择分区",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "选择分区")
            }
        }

        if (pickerOpen) {
            LivePickerDialog(
                title = "选择分区",
                items = categories.map { it.name },
                selectedIndex = categories.indexOfFirst { it.id == selected?.id },
                onPick = { idx ->
                    pickerOpen = false
                    val cat = categories.getOrNull(idx)
                    if (cat != null && selected?.id != cat.id) {
                        selected = cat
                        LiveCache.selectedCat[platform] = cat
                        rooms = LiveCache.roomsOf(platform, cat.id)
                        page = LiveCache.pageOf(platform, cat.id)
                        hasMore = rooms.isNotEmpty()
                        error = null
                        scope.launch {
                            if (rooms.isEmpty()) {
                                loadRooms(cat, 1, reset = true)
                            } else {
                                LiveCache.rooms["$platform|${cat.id}"] = rooms
                            }
                        }
                    }
                },
                onDismiss = { pickerOpen = false }
            )
        }

        // 房间网格
        when {
            rooms.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            rooms.isEmpty() -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyView(error ?: "该分区暂时没有在播房间")
                if (error != null && selected != null) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { scope.launch { loadRooms(selected!!, 1, reset = true) } }) {
                        Text("重试")
                    }
                }
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(rooms, key = { it.collectId }) { room ->
                    RoomCard(
                        room = room,
                        collected = room.collectId in savedIds,
                        opening = openingId == room.collectId,
                        onClick = {
                            if (openingId == null) {
                                openingId = room.collectId
                                scope.launch {
                                    val ok = openLiveAndPlay(nav, context, room)
                                    if (!ok) openingId = null
                                }
                            }
                        },
                        onCollect = { onCollect(room) }
                    )
                }
                if (hasMore) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(
                                enabled = !loading && selected != null,
                                onClick = { scope.launch { loadRooms(selected!!, page + 1, reset = false) } }
                            ) {
                                Text(if (loading) "加载中…" else "加载更多")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分类/分组选择窗：弹窗里列全部分类（可搜索），限高可滚。
 *
 * ⚠️ 为什么用 AlertDialog 而不是 DropdownMenu：斗鱼分区上百个，DropdownMenu 里塞 LazyColumn
 * 会踩「滚动组件被无限高度约束测量」的崩溃（用户实测「直播点下拉闪退」），
 * 弹窗自带确定的高度约束，安全且更适合长列表。
 */
@Composable
internal fun LivePickerDialog(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, items) {
        items.mapIndexed { i, n -> i to n }
            .filter { query.isBlank() || it.second.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 条目多时给搜索框（斗鱼分区上百个，翻着找太累）
                if (items.size > 12) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索分类") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (shown.isEmpty()) {
                    Text(
                        "没有匹配的分类",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        lazyItems(shown, key = { it.first }) { (idx, name) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(idx) }
                                    .padding(vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (idx == selectedIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (idx == selectedIndex) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 房间封面卡：16:9 封面 + 标题 + 主播 / 人气 + 右上角爱心 */
@Composable
private fun RoomCard(
    room: LiveRoom,
    collected: Boolean,
    opening: Boolean,
    onClick: () -> Unit,
    onCollect: () -> Unit
) {
    Column(Modifier.fillMaxWidth().clickable { onClick() }) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            CoverImage(
                url = room.cover,
                corner = 10.dp,
                modifier = Modifier.fillMaxSize()
            )
            if (room.isLive) {
                Text(
                    "直播中",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE53935))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            if (room.hot.isNotBlank()) {
                Text(
                    room.hot,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            if (opening) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            IconButton(
                onClick = onCollect,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    if (collected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (collected) Color(0xFFE53935) else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            room.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            listOf(room.streamer, room.categoryName).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 自定义源：导入的 M3U 频道（按分组筛选，点频道直接播） */
@Composable
private fun CustomSourcePane(
    nav: NavHostController,
    savedIds: Set<String>,
    onCollect: (LiveRoom) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf(LiveCache.customResults) }
    var loading by remember { mutableStateOf(false) }
    var selGroup by remember { mutableStateOf(LiveCache.customGroup) }
    var openingUrl by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    // 源配置可能在「源管理」里改过：每次进入本页都重读一次
    val sources = remember { SettingsStore.getLiveSources() }

    /** 重新拉取并解析所有电视源的频道（失败不写缓存，见下） */
    suspend fun reload() {
        loading = true
        val list = runCatching { M3uClient.loadAll(sources) }.getOrDefault(emptyList())
        results = list
        // ⚠️ 只有全部成功才写会话缓存：否则首次失败会被缓存成"空列表"，
        // 之后即使网络恢复也不会再自动重试（用户实测「电视里是空的」就是这个成因）
        LiveCache.customResults = if (list.isNotEmpty() && list.none { it.error != null }) list else null
        loading = false
    }

    LaunchedEffect(sources) {
        if (results == null) reload()
    }

    // 频道 + 分组
    val allChannels = remember(results) {
        results.orEmpty().flatMap { r -> r.channels.map { r.source.name to it } }
    }
    val groups = remember(allChannels) { allChannels.map { it.second.group }.distinct() }
    val shown = remember(allChannels, selGroup) {
        allChannels.filter { selGroup == null || it.second.group == selGroup }
    }
    val failed = remember(results) { results.orEmpty().filter { it.error != null } }

    Column(Modifier.fillMaxSize()) {
        if (groups.isNotEmpty()) {
            // 分组选择窗：电视源能精确统计每组频道数，所以带数量显示
            val counts = allChannels.groupingBy { it.second.group }.eachCount()
            val names = listOf("全部频道（${allChannels.size}）") +
                groups.map { "$it（${counts[it] ?: 0}）" }
            val selIdx = if (selGroup == null) 0 else groups.indexOf(selGroup) + 1
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { pickerOpen = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    names.getOrElse(selIdx) { names.first() },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "选择分组")
            }
            if (pickerOpen) {
                LivePickerDialog(
                    title = "选择分组",
                    items = names,
                    selectedIndex = selIdx,
                    onPick = { idx ->
                        pickerOpen = false
                        selGroup = if (idx == 0) null else groups.getOrNull(idx - 1)
                        LiveCache.customGroup = selGroup
                    },
                    onDismiss = { pickerOpen = false }
                )
            }
        }

        when {
            sources.none { it.enabled } -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyView("还没有电视源\n点右上角「源管理」添加 M3U / M3U8 地址，或从本地文件导入")
            }

            loading && shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyView(
                        if (failed.isNotEmpty()) failed.joinToString("\n") { "「${it.source.name}」${it.error}" }
                        else "该源没有解析到频道，可在「源管理」里更换地址或导入本地文件"
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
                        Button(onClick = { nav.safeNavigate("liveSources") }) { Text("去电视源配置") }
                    }
                }
            }

            else -> LazyColumn(
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (openingUrl == null) {
                                    openingUrl = ch.url
                                    scope.launch {
                                        val ok = openLiveAndPlay(nav, context, room)
                                        if (!ok) openingUrl = null
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                                "$sourceName · ${ch.group}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (openingUrl == ch.url) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        IconButton(onClick = { onCollect(room) }) {
                            Icon(
                                if (room.collectId in savedIds) Icons.Rounded.Favorite
                                else Icons.Rounded.FavoriteBorder,
                                contentDescription = "收藏",
                                tint = if (room.collectId in savedIds) Color(0xFFE53935)
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
