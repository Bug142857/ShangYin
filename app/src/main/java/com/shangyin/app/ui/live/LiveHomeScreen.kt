package com.shangyin.app.ui.live

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlaylistPlay
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.shangyin.app.data.live.LiveQuality
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

    /**
     * 页内搜索态（按平台分别存，key = 平台）：
     * - searchInput：搜索框里的字（切平台不串味）
     * - searchKeyword：已提交的关键词（非空 = 处于搜索态）
     * - searchResults / searchPage：搜索结果与已加载页码
     * 都放在这里是为了「进播放页再返回」时原样恢复——搜索也是用户花时间翻出来的，不能白搜。
     */
    val searchInput = mutableMapOf<String, String>()
    val searchKeyword = mutableMapOf<String, String>()
    val searchResults = mutableMapOf<String, List<LiveRoom>>()
    val searchPage = mutableMapOf<String, Int>()

    fun roomsOf(platform: String, catId: String): List<LiveRoom> = rooms["$platform|$catId"].orEmpty()
    fun pageOf(platform: String, catId: String): Int = page["$platform|$catId"] ?: 1
    fun scrollOf(platform: String): Int = scroll[platform] ?: 0
}

/**
 * 里世界「直播」：
 * - 页内搜索窗（照着 H1 / 外网源浏览页的手感）：输入法回车直接搜，结果就显示在本页内容区，
 *   顶栏不单放放大镜、也没有独立搜索页；返回键逐级返回（搜索态先退出搜索回分区浏览）
 * - 平台切换：虎牙 / 斗鱼 / B站 / 抖音 / 电视（自定义 M3U 频道）
 * - 平台页：分区筛选（收起横滑一行 / 展开平铺）+ 房间封面网格，点卡片直接播放，右上角爱心收藏到里世界清单
 * - 电视页：导入的 M3U 频道按分组展示，点频道直接播放
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveHomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    var platform by rememberSaveable { mutableStateOf(LiveCache.platform) }
    var collectTarget by remember { mutableStateOf<LiveRoom?>(null) }
    var biliLoggedIn by remember { mutableStateOf(SettingsStore.isBiliLoggedIn) }

    // 页内搜索态：input = 输入框里的字，keyword = 已提交的关键词（非空就是"处于搜索态"）。
    // 两者都按平台存进 LiveCache：① 切换平台各搜各的、不串味；② 进播放页再返回时原样恢复，不白搜。
    var searchInput by remember { mutableStateOf(LiveCache.searchInput[platform].orEmpty()) }
    var searchKeyword by remember { mutableStateOf(LiveCache.searchKeyword[platform].orEmpty()) }
    var searchResults by remember { mutableStateOf(LiveCache.searchResults[platform].orEmpty()) }
    var searchPage by remember { mutableIntStateOf(LiveCache.searchPage[platform] ?: 1) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchOpeningId by remember { mutableStateOf<String?>(null) }
    val searchActive = searchKeyword.isNotBlank()

    // 已收藏的直播条目（爱心高亮；从清单返回后状态实时更新）
    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val savedIds = remember(allItems) {
        allItems.filter { it.category == LivePlatforms.CATEGORY }.mapNotNull { it.doubanId }.toSet()
    }

    // 各平台能不能搜不一样，占位文字直接说人话（B站/抖音没有可用搜索接口 → 不请求也不假装有结果）
    val searchPlaceholder = when (platform) {
        LivePlatforms.HUYA, LivePlatforms.DOUYU -> "搜索直播间 / 主播"
        LivePlatforms.CUSTOM -> "搜索电视频道名"
        else -> "该平台暂不支持搜索，可搜虎牙 / 斗鱼 / 电视"
    }

    /** 切换平台：把该平台自己的搜索态从缓存换回来（各平台搜索框分别保留，互不干扰） */
    fun switchPlatform(key: String) {
        platform = key
        LiveCache.platform = key
        searchInput = LiveCache.searchInput[key].orEmpty()
        searchKeyword = LiveCache.searchKeyword[key].orEmpty()
        searchResults = LiveCache.searchResults[key].orEmpty()
        searchPage = LiveCache.searchPage[key] ?: 1
        searchError = null
        searchLoading = false
        searchOpeningId = null
    }

    /**
     * 退出搜索态：关键词与结果都清掉，回到分区浏览。
     * 分区选择 / 已加载房间 / 滚动位置本来就在 LiveCache 里（分区浏览侧没被改动过），所以原样还在。
     */
    fun exitSearch() {
        searchInput = ""
        searchKeyword = ""
        searchResults = emptyList()
        searchPage = 1
        searchError = null
        searchLoading = false
        searchOpeningId = null
        LiveCache.searchInput.remove(platform)
        LiveCache.searchKeyword.remove(platform)
        LiveCache.searchResults.remove(platform)
        LiveCache.searchPage.remove(platform)
        focus.clearFocus()
    }

    /**
     * 执行搜索（next = 斗鱼翻页）。
     * 数据来源：虎牙 HuyaClient.search（接口只有一页）/ 斗鱼 DouyuClient.search（支持翻页）/
     * 电视 = 本地在已导入的频道名里过滤 / B站·抖音没有可用搜索接口 → 不请求，内容区给说明。
     */
    fun runSearch(next: Boolean = false) {
        val kw = searchInput.trim()
        if (kw.isEmpty()) {
            exitSearch()
            return
        }
        val atPlatform = platform
        // 只有斗鱼的搜索接口支持翻页；虎牙一页、电视是本地过滤
        if (next && atPlatform != LivePlatforms.DOUYU) return
        val target = if (next) searchPage + 1 else 1
        searchInput = kw
        searchKeyword = kw
        LiveCache.searchInput[atPlatform] = kw
        LiveCache.searchKeyword[atPlatform] = kw
        searchLoading = true
        searchError = null
        if (!next) {
            // 新关键词：先清掉上一次的结果，避免"字换了结果还是旧的"
            searchResults = emptyList()
            LiveCache.searchResults[atPlatform] = emptyList()
        }
        scope.launch {
            val fetched = runCatching {
                when (atPlatform) {
                    LivePlatforms.HUYA -> HuyaClient.search(kw, 1)
                    LivePlatforms.DOUYU -> DouyuClient.search(kw, target)
                    // 电视：M3U 没有远端搜索，在已加载（没有就先拉一次）的频道名里本地过滤
                    LivePlatforms.CUSTOM -> {
                        val sources = SettingsStore.getLiveSources().filter { it.enabled }
                        val loaded = LiveCache.customResults
                            ?: runCatching { M3uClient.loadAll(sources) }
                                .getOrDefault(emptyList())
                                .also { list ->
                                    // 与「电视」页同一口径：全部成功才写缓存，失败别把空列表缓存住
                                    if (list.isNotEmpty() && list.none { it.error != null }) {
                                        LiveCache.customResults = list
                                    }
                                }
                        loaded.flatMap { r ->
                            r.channels.filter { it.name.contains(kw, ignoreCase = true) }
                                .map { ch ->
                                    LiveRoom(
                                        platform = LivePlatforms.CUSTOM,
                                        roomId = ch.url,
                                        title = ch.name,
                                        streamer = r.source.name,
                                        categoryName = ch.group
                                    )
                                }
                        }
                    }
                    // B站 412 风控 / 抖音要签名：没有可用的搜索接口，不请求也不编结果
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            val merged = if (next) searchResults + fetched else fetched
            // 请求期间可能已经切了平台：只写回发起搜索的那个平台的缓存，别把别人的结果盖到当前平台
            LiveCache.searchResults[atPlatform] = merged
            LiveCache.searchPage[atPlatform] = target
            if (platform == atPlatform) {
                searchResults = merged
                searchPage = target
                searchLoading = false
            }
        }
    }

    /** 打开搜索结果：电视源同名频道常有多条地址 → 和「电视」页一致，交给播放器当「线路」菜单 */
    fun openSearchRoom(room: LiveRoom) {
        if (searchOpeningId != null) return
        searchOpeningId = room.collectId
        scope.launch {
            val lines = if (room.platform == LivePlatforms.CUSTOM) {
                val sameName = LiveCache.customResults.orEmpty()
                    .flatMap { r -> r.channels }
                    .filter { it.name == room.title }
                    .map { it.url }
                    .distinct()
                if (sameName.size > 1) {
                    sameName.mapIndexed { i, u ->
                        LiveQuality("线路 ${i + 1}", u, u.substringBefore('?').endsWith(".m3u8", ignoreCase = true))
                    }
                } else emptyList()
            } else emptyList()
            val ok = openLiveAndPlay(nav, context, room, extraQualities = lines)
            if (!ok) searchOpeningId = null
        }
    }

    // 返回逐级返回（用户原话「记得返回要逐级返回以免白搜了」）：
    // 搜索是页内的一层 → 搜索态下返回键只退出搜索回分区浏览，不离开直播页；非搜索态不拦截，返回行为照旧
    BackHandler(enabled = searchActive) { exitSearch() }

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
                    // 顶栏不再放放大镜：搜索框就在内容区顶部（用户原话「直接搜索窗就行，不用单独弄个搜索按钮」）
                    // 电视源配置（添加 / 导入 / 删除）
                    IconButton(onClick = { nav.safeNavigate("liveSources") }) {
                        Icon(Icons.Rounded.PlaylistPlay, contentDescription = "电视源配置")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 页内搜索窗：不开独立搜索页、顶栏也没有放大镜，输入法回车（imeAction = Search）直接搜，
            // 结果就显示在下面这片内容区（和 H1 / 外网源浏览页同一手感）
            OutlinedTextField(
                value = searchInput,
                onValueChange = {
                    searchInput = it
                    LiveCache.searchInput[platform] = it
                },
                placeholder = {
                    Text(searchPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    // 清空 = 退出搜索态回到分区浏览（不是只把字擦掉）
                    if (searchInput.isNotEmpty() || searchActive) {
                        IconButton(onClick = { exitSearch() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
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
                        onClick = { switchPlatform(key) },
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

            when {
                // 搜索态：内容区给搜索结果，分区选择窗与分区浏览网格都不显示（退出搜索态原样回来）
                searchActive -> LiveSearchResultsPane(
                    platform = platform,
                    results = searchResults,
                    loading = searchLoading,
                    error = searchError,
                    savedIds = savedIds,
                    openingId = searchOpeningId,
                    onOpen = { openSearchRoom(it) },
                    onCollect = { collectTarget = it },
                    onLoadMore = { runSearch(next = true) }
                )

                platform == LivePlatforms.CUSTOM -> CustomSourcePane(nav, savedIds, onCollect = { collectTarget = it })
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlatformPane(
    nav: NavHostController,
    platform: String,
    savedIds: Set<String>,
    onCollect: (LiveRoom) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var categories by remember { mutableStateOf(LiveCache.categories[platform].orEmpty()) }
    val cachedCat = LiveCache.selectedCat[platform]
    var selected by remember { mutableStateOf(cachedCat) }
    var rooms by remember {
        mutableStateOf(if (cachedCat != null) LiveCache.roomsOf(platform, cachedCat.id) else emptyList())
    }
    // 网格起点 = 缓存里的滚动位置：进搜索态 / 进播放页都会让本组合被销毁重建，
    // 靠它直接把列表滚回原位（否则"搜一下再退回来"就回到顶部，等于白翻了）
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = if (rooms.isNotEmpty()) {
            LiveCache.scrollOf(platform).coerceIn(0, rooms.size - 1)
        } else 0
    )
    var page by remember {
        mutableStateOf(if (cachedCat != null) LiveCache.pageOf(platform, cachedCat.id) else 1)
    }
    var hasMore by remember { mutableStateOf(rooms.isNotEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var catExpanded by remember { mutableStateOf(LiveCache.catExpanded[platform] ?: false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
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

            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    val cat = selected
                    if (cat != null) {
                        scope.launch {
                            refreshing = true
                            loadRooms(cat, 1, reset = true)
                            refreshing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
            LazyVerticalGrid(
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

/** 房间封面卡：16:9 封面 + 标题 + 主播 / 人气 + 右上角爱心（未开播的显示灰色角标，列表里也排在最后） */
@Composable
internal fun RoomCard(
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
            Text(
                if (room.isLive) "直播中" else "未开播",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (room.isLive) Color(0xFFE53935) else Color(0xFF757575))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
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

/**
 * 搜索结果面板（页内搜索态的内容区）：
 * 复用分区浏览同一张 RoomCard 两列网格（收藏 / 打开是同一套逻辑），"没结果"按平台如实说明：
 * - 虎牙 / 斗鱼：平台搜索接口；斗鱼支持翻页 → 底部给「加载更多」（追加不替换）
 * - 电视：本地在已导入的频道名里过滤（所以要说清"找的是频道名"）
 * - B站 / 抖音：没有可用搜索接口 → 只给说明，绝不假装有结果
 */
@Composable
private fun LiveSearchResultsPane(
    platform: String,
    results: List<LiveRoom>,
    loading: Boolean,
    error: String?,
    savedIds: Set<String>,
    openingId: String?,
    onOpen: (LiveRoom) -> Unit,
    onCollect: (LiveRoom) -> Unit,
    onLoadMore: () -> Unit
) {
    // B站 412 风控 / 抖音要签名：搜不了就直说，别让用户等一个永远不来的结果
    if (platform == LivePlatforms.BILI || platform == LivePlatforms.DOUYIN) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyView(
                "${LivePlatforms.label(platform)} 暂不支持搜索\n" +
                    "该平台没有可用的搜索接口（B站有风控、抖音要签名）\n" +
                    "可切到虎牙、斗鱼或电视搜索"
            )
        }
        return
    }
    when {
        results.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyView(error ?: "没有搜到相关直播间")
                if (platform == LivePlatforms.CUSTOM) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "电视搜索是在已导入的频道名里找；没有就是源里没有这个频道",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(results, key = { it.collectId }) { room ->
                RoomCard(
                    room = room,
                    collected = room.collectId in savedIds,
                    opening = openingId == room.collectId,
                    onClick = { onOpen(room) },
                    onCollect = { onCollect(room) }
                )
            }
            // 只有斗鱼的搜索接口能翻页；虎牙一页、电视本地过滤 → 不给「加载更多」
            if (platform == LivePlatforms.DOUYU) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(enabled = !loading, onClick = onLoadMore) {
                            Text(if (loading) "加载中…" else "加载更多")
                        }
                    }
                }
            }
        }
    }
}

/** 电视：导入的 M3U 频道（按分组筛选，点频道直接播；同名频道多条地址当「线路」） */
@OptIn(ExperimentalMaterial3Api::class)
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
    var refreshing by remember { mutableStateOf(false) }

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
                                        // 电视源里同名频道常有多条地址（不同线路）→ 交给播放器当「线路」菜单
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
}
