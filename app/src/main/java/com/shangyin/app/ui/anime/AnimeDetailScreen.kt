package com.shangyin.app.ui.anime

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodItem
import com.shangyin.app.data.vod.VodPlayGroup
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 动漫详情页（类 animeko 的番剧页）：
 * 封面 / 基本信息 / 简介 + 线路切换 + 选集网格 + 收藏 + 换源（同名换到其它动漫源）。
 * 进播放页会让本组合被销毁重建，故用 [AnimeDetailCache] 保留已加载详情与选中线路。
 * ⚠️ [AnimeDetailCache.entryKey] 记的是**进入本页时的路由参数**：换源只改页内状态、不改路由，
 * 所以"换源 → 播放 → 返回"必须按 entryKey 命中缓存，否则会退回路由里那个原源重新加载（选集白选）。
 */
private object AnimeDetailCache {
    var entryKey: String? = null
    var srcId: String? = null
    var vodId: Long = -1L
    var detail: VodItem? = null
    var groups: List<VodPlayGroup> = emptyList()
    var selGroup: Int = 0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(nav: NavHostController, srcId: String, vodId: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sources = remember { SettingsStore.getAnimeSources() }
    val entryKey = "$srcId|$vodId"
    // 返回（从播放页回来）时用缓存恢复：只要这次进入的路由参数与缓存一致，就恢复换源后的状态
    val restored = remember { AnimeDetailCache.entryKey == entryKey && AnimeDetailCache.detail != null }
    var src by remember {
        mutableStateOf(
            sources.firstOrNull { it.id == (if (restored) AnimeDetailCache.srcId else srcId) }
        )
    }
    var currentVodId by remember { mutableStateOf(if (restored) AnimeDetailCache.vodId else vodId) }
    var detail by remember { mutableStateOf(if (restored) AnimeDetailCache.detail else null) }
    var groups by remember { mutableStateOf(if (restored) AnimeDetailCache.groups else emptyList()) }
    var selGroup by remember { mutableIntStateOf(if (restored) AnimeDetailCache.selGroup else 0) }
    var loading by remember { mutableStateOf(!restored) }
    var failed by remember { mutableStateOf(false) }
    var loadedKey by remember {
        mutableStateOf(if (restored) "${AnimeDetailCache.srcId}|${AnimeDetailCache.vodId}" else "")
    }
    var introExpanded by remember { mutableStateOf(false) }

    // 换源
    var showSwitch by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var switchResults by remember { mutableStateOf<List<Pair<VodSource, VodItem>>>(emptyList()) }

    // 收藏（category="动漫"，doubanId="srcId|vodId"）
    var showCollect by remember { mutableStateOf(false) }
    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val collected = remember(allItems, src, currentVodId) {
        allItems.any { it.category == "动漫" && it.doubanId == "${src?.id}|$currentVodId" }
    }

    // 状态写回缓存（进播放页返回后完整恢复，含换源后的源与选集）
    LaunchedEffect(entryKey, src?.id, currentVodId, detail, groups, selGroup) {
        AnimeDetailCache.entryKey = entryKey
        AnimeDetailCache.srcId = src?.id ?: srcId
        AnimeDetailCache.vodId = currentVodId
        AnimeDetailCache.detail = detail
        AnimeDetailCache.groups = groups
        AnimeDetailCache.selGroup = selGroup
    }

    fun load(s: VodSource, id: Long) {
        loading = true
        failed = false
        scope.launch {
            val d = runCatching { VodClient.fetchDetail(s, id) }.getOrNull()
            if (d == null || d.vod_name.isBlank()) {
                detail = null
                groups = emptyList()
                failed = true
            } else {
                detail = d
                groups = VodClient.parsePlayGroups(d.vod_play_from, d.vod_play_url)
            }
            selGroup = 0
            introExpanded = false
            loading = false
        }
    }

    // 首次进入 / 换源后加载（换源会同时改 src 与 vodId）
    LaunchedEffect(src?.id, currentVodId) {
        val s = src ?: return@LaunchedEffect
        val key = "${s.id}|$currentVodId"
        if (loadedKey == key) return@LaunchedEffect
        loadedKey = key
        load(s, currentVodId)
    }

    // 源被删除：提示并返回
    if (src == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "片源已被删除，无法打开", Toast.LENGTH_LONG).show()
            nav.safePopBackStack()
        }
        return
    }
    val currentSrc = src!!

    val episodes = groups.getOrNull(selGroup)?.episodes.orEmpty()

    // 断点续播：选中线路里进度最新的那一集（与 openVodAndPlay 同一口径，itemId 固定 0）
    fun resumeOf(groupIdx: Int): Pair<Int, Long> {
        val eps = groups.getOrNull(groupIdx)?.episodes.orEmpty()
        var idx = 0
        var pos = 0L
        var bestTs = -1L
        eps.forEachIndexed { i, ep ->
            val p = SettingsStore.getVodProgress(SettingsStore.vodProgressKey(0L, ep.url))
                ?: return@forEachIndexed
            if (p.third > bestTs) {
                bestTs = p.third
                idx = i
                pos = p.first
            }
        }
        return idx to pos
    }

    /** 播放选中线路的第 epIndex 集（自动续播该集进度） */
    fun play(groupIdx: Int, epIndex: Int) {
        val eps = groups.getOrNull(groupIdx)?.episodes.orEmpty()
        if (eps.isEmpty()) {
            Toast.makeText(context, "暂无可用播放地址", Toast.LENGTH_SHORT).show()
            return
        }
        val ep = eps[epIndex.coerceIn(0, eps.size - 1)]
        PlayerSession.itemId = 0L
        PlayerSession.title = detail?.vod_name.orEmpty()
        PlayerSession.groups = groups
        PlayerSession.groupIndex = groupIdx
        PlayerSession.startIndex = epIndex.coerceIn(0, eps.size - 1)
        PlayerSession.startPosMs = SettingsStore.getVodProgress(SettingsStore.vodProgressKey(0L, ep.url))?.first ?: 0L
        nav.safeNavigate("player")
    }

    /** 换源：用当前片名在其它动漫源里搜同名条目 */
    fun searchOtherSources() {
        val name = detail?.vod_name.orEmpty()
        if (name.isBlank()) return
        showSwitch = true
        if (switchResults.isNotEmpty()) return
        switching = true
        scope.launch {
            val list = runCatching {
                coroutineScope {
                    sources.filter { it.enabled }.map { s ->
                        async { s to VodClient.searchSource(s, name) }
                    }.awaitAll()
                }.flatMap { (s, items) -> items.map { s to it } }
            }.getOrDefault(emptyList())
            switchResults = list
            switching = false
        }
    }

    val resume = remember(groups, selGroup) { if (episodes.isEmpty()) 0 to 0L else resumeOf(selGroup) }
    val intro = remember(detail) { stripHtml(detail?.vod_content.orEmpty()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.vod_name ?: "动漫详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { searchOtherSources() }, enabled = detail != null && !switching) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = "换源")
                    }
                    IconButton(onClick = { if (!collected) showCollect = true }) {
                        Icon(
                            if (collected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (collected) "已收藏" else "收藏",
                            tint = if (collected) Color(0xFFEF5350) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { pad ->
        when {
            loading -> Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            failed || detail == null -> Column(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("加载失败", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "该源可能需要外网环境，或接口暂时不可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { load(currentSrc, currentVodId) }) { Text("重试") }
            }

            else -> {
                val d = detail!!
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(pad).fillMaxSize()
                ) {
                    // ---------- 头部：封面 + 基本信息 ----------
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            Row {
                                CoverImage(
                                    url = d.vod_pic,
                                    modifier = Modifier.width(104.dp).height(140.dp),
                                    corner = 10.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        d.vod_name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    val meta = listOf(
                                        d.vod_score.takeIf { it.isNotBlank() && it != "0.0" }?.let { "评分 $it" },
                                        d.vod_year.takeIf { it.isNotBlank() },
                                        d.vod_area.takeIf { it.isNotBlank() },
                                        d.type_name.takeIf { it.isNotBlank() },
                                        currentSrc.name
                                    ).filterNotNull()
                                    if (meta.isNotEmpty()) {
                                        Text(
                                            meta.joinToString(" · "),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (d.vod_remarks.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            d.vod_remarks,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (d.vod_director.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "导演：" + stripHtml(d.vod_director),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (d.vod_actor.isNotBlank()) {
                                        Text(
                                            "声优：" + stripHtml(d.vod_actor),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = { if (episodes.isNotEmpty()) play(selGroup, resume.first) },
                                        enabled = episodes.isNotEmpty()
                                    ) {
                                        Text(
                                            when {
                                                episodes.isEmpty() -> "暂无剧集"
                                                resume.second > 0L -> "继续观看 第${resume.first + 1}集"
                                                else -> "播放第1集"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---------- 简介 ----------
                    if (intro.isNotBlank()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "intro") {
                            Column(Modifier.padding(top = 4.dp)) {
                                Text(
                                    "简介",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    intro,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (introExpanded) Int.MAX_VALUE else 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { introExpanded = !introExpanded }
                                )
                            }
                        }
                    }

                    // ---------- 线路 + 选集标题 ----------
                    item(span = { GridItemSpan(maxLineSpan) }, key = "eps_header") {
                        Column(Modifier.padding(top = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "选集 · 共${episodes.size}集",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.weight(1f))
                                if (switching) {
                                    Text(
                                        "正在换源…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (groups.size > 1) {
                                Spacer(Modifier.height(4.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(groups.indices.toList(), key = { "g_$it" }) { gi ->
                                        FilterChip(
                                            selected = gi == selGroup,
                                            onClick = { selGroup = gi },
                                            label = { Text(groups[gi].name) }
                                        )
                                    }
                                }
                            }
                            if (episodes.isEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "该源暂无可用播放地址，可点右上角「换源」换一个源试试",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // ---------- 选集网格 ----------
                    itemsIndexed(episodes, key = { i, _ -> "ep_$i" }) { i, ep ->
                        EpisodeButton(
                            name = ep.name,
                            highlight = i == resume.first && resume.second > 0L,
                            onClick = { play(selGroup, i) }
                        )
                    }
                }
            }
        }
    }

    // 收藏对话框
    if (showCollect) {
        CollectDialog(
            onDismiss = { showCollect = false },
            collect = { listId ->
                val d = detail
                if (d == null) false else {
                    val itemId = Repo.saveCustomItem(
                        category = "动漫",
                        doubanId = "${currentSrc.id}|$currentVodId",
                        title = d.vod_name,
                        coverUrl = d.vod_pic,
                        subTitle = currentSrc.name
                    )
                    if (itemId > 0) { Repo.addItemToList(listId, itemId); true } else false
                }
            }
        )
    }

    // 换源对话框：同名条目按源列出，点选即切换
    if (showSwitch) {
        val others = switchResults.filter { !(it.first.id == currentSrc.id && it.second.vod_id == currentVodId) }
        AlertDialog(
            onDismissRequest = { showSwitch = false },
            title = { Text("换源") },
            text = {
                Column {
                    Text(
                        "用「${detail?.vod_name.orEmpty()}」在其它动漫源里找到的条目：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        switching -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("正在搜索其它源…", style = MaterialTheme.typography.bodySmall)
                        }

                        others.isEmpty() -> Text(
                            "其它源里没有找到同名条目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        else -> androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.height((others.size * 60).coerceAtMost(320).dp)
                        ) {
                            items(others, key = { "${it.first.id}_${it.second.vod_id}" }) { (s, it) ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            src = s
                                            currentVodId = it.vod_id
                                            showSwitch = false
                                            switchResults = emptyList()
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        listOfNotNull(
                                            it.vod_remarks.takeIf { v -> v.isNotBlank() },
                                            it.vod_year.takeIf { v -> v.isNotBlank() },
                                            it.type_name.takeIf { v -> v.isNotBlank() }
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSwitch = false }) { Text("关闭") } }
        )
    }
}

/** 选集按钮（网格单元）：居中一行剧集名，续播中的那一集高亮 */
@Composable
private fun EpisodeButton(name: String, highlight: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlight) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 去掉简介/演职员里的 HTML 标签与实体（采集源常带 <p>、&nbsp;） */
private fun stripHtml(raw: String): String =
    raw.replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()
