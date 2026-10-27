package com.shangyin.app.ui.watch

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.InnerFav
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodItem
import com.shangyin.app.data.vod.VodPlayGroup
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.common.AppSearchField
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.innerfav.FavTopBarIcon
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 搜索结果行：片源 + 影片 + 匹配分 */
private data class WatchRes(val src: VodSource, val item: VodItem, val score: Int)

/** 多线路选择态 */
private data class PickedLines(val src: VodSource, val item: VodItem, val groups: List<VodPlayGroup>)

/** 会话级状态：进播放页再返回时搜索结果还在（与音乐/番号模块同一口径） */
private class WatchSessionState {
    var input by mutableStateOf("")
    var keyword by mutableStateOf("")
    var searched by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var total by mutableStateOf(0)
    var pending by mutableStateOf(0)
    var noSources by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val results = mutableStateListOf<WatchRes>()
}

private object WatchSession {
    val state = WatchSessionState()
}

/**
 * 观影模块（v0.316）：把「清单详情 → 在线观看」的跨片源搜片能力独立成模块。
 * 输入片名 → 并行搜所有已启用片源 → 选结果/线路 → 主播放器播放。
 * 收藏独立存 InnerFavorites（module = watch），与清单里的豆瓣收藏互不影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchHomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val st = WatchSession.state

    var openingKey by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<PickedLines?>(null) }

    fun doSearch(raw: String) {
        val kw = raw.trim()
        if (kw.isEmpty() || st.searching) return
        // 与清单详情的「在线观看」同一口径：只搜国内可访问目录的源（外网源走番号模块）
        val srcs = SettingsStore.getVodSources().filter { it.enabled && it.region != "proxy" }
        st.noSources = srcs.isEmpty()
        if (srcs.isEmpty()) return
        st.keyword = kw
        st.searched = true
        st.error = null
        st.results.clear()
        st.total = srcs.size
        st.pending = srcs.size
        st.searching = true
        scope.launch {
            coroutineScope {
                srcs.forEach { src ->
                    launch {
                        runCatching { VodClient.searchSource(src, kw) }
                            .onSuccess { list ->
                                val scored = list
                                    .map { it to VodClient.matchScore(it.vod_name, kw, it.vod_year, "") }
                                    .sortedByDescending { it.second }
                                st.results.addAll(scored.distinctBy { it.first.vod_id }.map { WatchRes(src, it.first, it.second) })
                            }
                            .onFailure { e ->
                                // 单个源失败不影响其它源；全部失败时在下面统一提示
                                if (st.error == null) st.error = e.message
                            }
                        st.pending--
                    }
                }
            }
            val sorted = st.results.sortedWith(
                compareByDescending<WatchRes> { it.score }.thenBy { it.item.vod_name }
            )
            st.results.clear()
            st.results.addAll(sorted)
            st.searching = false
        }
    }

    fun open(row: WatchRes) {
        if (openingKey != null) return
        val key = row.src.id + "_" + row.item.vod_id
        openingKey = key
        scope.launch {
            val full = if (row.item.vod_play_url.isBlank()) {
                runCatching { VodClient.fetchDetail(row.src, row.item.vod_id) }.getOrDefault(row.item)
            } else row.item
            val groups = VodClient.parsePlayGroups(full.vod_play_from, full.vod_play_url)
            when {
                groups.isEmpty() -> Toast.makeText(
                    context, "「${full.vod_name}」暂无可用播放地址", Toast.LENGTH_SHORT
                ).show()
                groups.size == 1 -> playLines(nav, row.src, full, groups, 0)
                else -> picked = PickedLines(row.src, full, groups)
            }
            openingKey = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("观影")
                        Text(
                            "输入片名，跨片源在线观看",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("innerFavs/watch") }) {
                        Icon(Icons.Rounded.FavoriteBorder, contentDescription = "我的收藏")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            AppSearchField(
                value = st.input,
                onValueChange = { st.input = it },
                onSearch = {
                    keyboard?.hide()
                    doSearch(st.input)
                },
                placeholder = "搜索片名 / 剧名",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            when {
                st.noSources -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("还没有可用的影视片源", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "去「配置管理 → 片源管理」添加影视源后再来搜索",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { nav.safeNavigate("vodSources") }) { Text("去配置片源") }
                }

                st.searching -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${st.total - st.pending}/${st.total} 个源",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                }

                !st.searched -> EmptyView("输入片名搜索，找到就能在线观看\n点爱心可收藏，收藏独立于清单", Modifier.weight(1f))

                st.results.isEmpty() -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("没有找到「${st.keyword}」", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        st.error?.let { "部分片源请求失败：$it" }
                            ?: "换个关键词试试，或到片源管理里测试/更换线路",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 12.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    item(key = "watch_count") {
                        Text(
                            "共 ${st.results.size} 条结果（${st.total} 个片源）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    items(st.results, key = { it.src.id + "_" + it.item.vod_id }) { row ->
                        WatchResultRow(
                            row = row,
                            opening = openingKey == row.src.id + "_" + row.item.vod_id,
                            onClick = { open(row) }
                        )
                    }
                }
            }
        }
    }

    // 多线路选择
    picked?.let { p ->
        ModalBottomSheet(onDismissRequest = { picked = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    "「${p.item.vod_name}」共 ${p.groups.size} 条线路",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 420.dp)) {
                    LazyColumn {
                        itemsIndexed(p.groups) { index, g ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        picked = null
                                        playLines(nav, p.src, p.item, p.groups, index)
                                    }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(g.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${g.episodes.size} 集 · 第1集 ${g.episodes.firstOrNull()?.name.orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 搜索结果行：封面 + 片名 + 年份/分类 + 片源角标 + 爱心收藏 */
@Composable
private fun WatchResultRow(row: WatchRes, opening: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !opening, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = row.item.vod_pic,
            modifier = Modifier.width(56.dp).height(76.dp),
            corner = 6.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.item.vod_name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val meta = listOf(row.item.vod_year, row.item.type_name, row.item.vod_area)
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            row.item.vod_remarks.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            row.src.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
        // 观影收藏（module=watch，独立于清单/其它模块）
        FavTopBarIcon(
            favModule = "watch",
            favId = "${row.src.id}|${row.item.vod_id}",
            build = {
                InnerFav(
                    module = "watch",
                    id = "${row.src.id}|${row.item.vod_id}",
                    title = row.item.vod_name,
                    subtitle = listOf(row.src.name, row.item.vod_year)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    cover = row.item.vod_pic,
                    savedAt = System.currentTimeMillis()
                )
            }
        )
    }
}

/** 按线路起播（续播位置沿用播放地址进度，与详情页在线观看一致） */
private fun playLines(
    nav: NavHostController,
    src: VodSource,
    item: VodItem,
    groups: List<VodPlayGroup>,
    groupIndex: Int
) {
    val eps = groups.getOrNull(groupIndex)?.episodes ?: return
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
    PlayerSession.itemId = 0L
    PlayerSession.title = item.vod_name
    PlayerSession.sourceName = src.name
    PlayerSession.coverUrl = item.vod_pic
    PlayerSession.subTitle = ""
    PlayerSession.isLive = false
    PlayerSession.streamHeaders = emptyMap()
    PlayerSession.liveQualities = emptyList()
    PlayerSession.liveRoom = null
    PlayerSession.groups = groups
    PlayerSession.groupIndex = groupIndex
    PlayerSession.startIndex = idx
    PlayerSession.startPosMs = pos
    nav.safeNavigate("player")
}
