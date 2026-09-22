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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.shangyin.app.data.animeko.AnimekoClient
import com.shangyin.app.data.vod.VodEpisode
import com.shangyin.app.data.vod.VodPlayGroup
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * animeko 网页源待打开的条目。
 * 网页源的条目没有数字 id（只有站点页面地址），所以不走路由参数传递，用这个对象接力
 * （与 PlayerSession / DetailCache 同一套先例）。
 */
data class AnimeWebSubject(val srcId: String, val name: String, val pageUrl: String)

object AnimeWebNav {
    var pending: AnimeWebSubject? = null
}

/**
 * animeko 网页源详情页：线路（频道）+ 选集 + 预解析播放地址 + 播放 + 收藏。
 *
 * 网页源只有"剧集页地址"，真实 m3u8/mp4 要逐集解析：
 * 进入页面就**后台预解析当前线路**（并发 4，边解析边点亮），点集时若还没解析好则等待
 * （显示进度、可取消）。这样播放器拿到的是完整可连播的播放列表，也不需要播放器里做懒解析。
 * 线路切换在**本页**完成（播放页只带当前线路，避免把未解析的地址交给播放器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeWebDetailScreen(nav: NavHostController, srcId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subject = remember { AnimeWebNav.pending?.takeIf { it.srcId == srcId } }
    if (subject == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "条目信息已失效，请重新搜索", Toast.LENGTH_SHORT).show()
            nav.safePopBackStack()
        }
        return
    }
    val sub = subject!!
    val src = remember { SettingsStore.getAnimeSources().firstOrNull { it.id == srcId } }
    if (src == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "片源已被删除，无法打开", Toast.LENGTH_LONG).show()
            nav.safePopBackStack()
        }
        return
    }
    val source = src!!

    var channels by remember { mutableStateOf<List<AnimekoClient.WebChannel>?>(null) } // null=加载中
    var failed by remember { mutableStateOf(false) }
    var selChannel by remember { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // pageUrl -> 已解析出的播放地址（snapshot map：主线程写；解析回调经 scope.launch 回主线程）
    val resolved = remember { mutableStateMapOf<String, String>() }
    var resolveDone by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var pendingIndex by remember { mutableStateOf<Int?>(null) }
    var showCollect by remember { mutableStateOf(false) }

    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val collected = remember(sub, allItems) {
        allItems.any { it.category == "动漫" && it.doubanId == "$srcId|ak|${sub.pageUrl}" }
    }

    // 加载详情页 → 线路/剧集
    LaunchedEffect(srcId, sub.pageUrl, reloadKey) {
        channels = null
        failed = false
        val r = runCatching { AnimekoClient.channels(source, sub.pageUrl) }.getOrNull()
        if (r == null) {
            failed = true
        } else {
            channels = r
            // 默认选有剧集且集数最多的线路（多为"主线"）
            selChannel = r.indices.maxByOrNull { r[it].episodes.size } ?: 0
        }
    }

    // 进入/切换线路 → 后台预解析该线路。
    // ⚠️ 长番剧实测能到 1000+ 集（如秋之动漫的海贼王 1179 集），全量预解析不可行：
    //    短线路（≤60 集）全解析；长线路只预解析前 40 集，其余"点哪集解析哪集"。
    val episodes = channels?.getOrNull(selChannel)?.episodes.orEmpty()
    val resolveWindow = remember(episodes) {
        if (episodes.size <= 60) episodes else episodes.take(40)
    }
    LaunchedEffect(channels, selChannel) {
        if (resolveWindow.isEmpty()) return@LaunchedEffect
        resolving = true
        resolveDone = false
        val r = runCatching {
            AnimekoClient.resolveAll(source, resolveWindow, concurrency = 4) { pageUrl, videoUrl ->
                scope.launch { resolved[pageUrl] = videoUrl }
            }
        }.getOrDefault(emptyMap())
        // 收尾：把最终结果补齐（onProgress 的协程可能还没跑完）
        r.forEach { (k, v) -> resolved[k] = v }
        resolving = false
        resolveDone = true
    }

    /** 播放选中线路的第 i 集（未解析完则先等待） */
    fun play(i: Int) {
        val eps = episodes
        val ep = eps.getOrNull(i) ?: return
        val url = resolved[ep.pageUrl]
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "该集播放地址解析失败，可换线路或稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        val playedEps = eps.map { VodEpisode(it.name, resolved[it.pageUrl] ?: it.pageUrl, it.pageUrl) }
        PlayerSession.itemId = 0L
        PlayerSession.title = sub.name
        PlayerSession.groups = listOf(VodPlayGroup(channels?.getOrNull(selChannel)?.name ?: "线路1", playedEps))
        PlayerSession.groupIndex = 0
        PlayerSession.startIndex = i
        PlayerSession.startPosMs = SettingsStore.getVodProgress(SettingsStore.vodProgressKey(0L, url))?.first ?: 0L
        PlayerSession.videoHeaders = AnimekoClient.videoHeaders(source, ep.pageUrl)
        nav.safeNavigate("player")
    }

    // 点了还没解析好的集：等预解析窗口跑完；窗口外（长番剧）则单独解析这一集
    LaunchedEffect(pendingIndex, resolveDone) {
        val i = pendingIndex ?: return@LaunchedEffect
        val ep = episodes.getOrNull(i) ?: run { pendingIndex = null; return@LaunchedEffect }
        // 只有"在预解析窗口内"的集才需要等；窗口外（长番剧）直接单集解析
        val inWindow = resolveWindow.any { it.pageUrl == ep.pageUrl }
        if (inWindow) {
            while (!resolveDone && !resolved.containsKey(ep.pageUrl)) {
                delay(300)
            }
        }
        val url = resolved[ep.pageUrl] ?: runCatching {
            AnimekoClient.resolveVideo(source, ep.pageUrl)
        }.getOrNull()
        pendingIndex = null
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "该集播放地址解析失败，可换线路重试", Toast.LENGTH_SHORT).show()
        } else {
            resolved[ep.pageUrl] = url
            play(i)
        }
    }

    // 续播定位：已解析的集里进度最新的一集
    val resume = remember(resolved.size, episodes) {
        var idx = 0
        var pos = 0L
        var bestTs = -1L
        episodes.forEachIndexed { i, ep ->
            val url = resolved[ep.pageUrl] ?: return@forEachIndexed
            val p = SettingsStore.getVodProgress(SettingsStore.vodProgressKey(0L, url)) ?: return@forEachIndexed
            if (p.third > bestTs) {
                bestTs = p.third
                idx = i
                pos = p.first
            }
        }
        idx to pos
    }
    val resolvedCount = episodes.count { resolved.containsKey(it.pageUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sub.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
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
        val chans = channels
        // 用早返回（而不是 when 分支）才能让编译器把 chans 智能转为非空
        if (chans == null && !failed) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (failed) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("加载失败", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "该源可能需要外网环境，或站点结构已变（页面里没找到剧集）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { reloadKey++ }) { Text("重试") }
            }
            return@Scaffold
        }

        if (chans.isNullOrEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("没解析到剧集", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "站点页面结构可能已变，或该条目是合集/简介页。可换一个源试试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { reloadKey++ }) { Text("重新加载") }
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(pad).fillMaxSize()
        ) {
            // 头部：来源 + 解析进度 + 续播按钮
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            source.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "网页源 · 共 ${episodes.size} 集",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sub.pageUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { if (episodes.isNotEmpty()) play(resume.first) },
                            enabled = episodes.isNotEmpty() && resolvedCount > 0
                        ) {
                            Text(
                                if (resume.second > 0L && resolvedCount > 0) "继续观看 第${resume.first + 1}集"
                                else "播放第1集"
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        val windowHint = if (episodes.size > resolveWindow.size) "（长番剧按需解析）" else ""
                        if (resolving) {
                            Text(
                                "解析播放地址 $resolvedCount/${episodes.size}$windowHint",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (episodes.isNotEmpty()) {
                            Text(
                                if (resolvedCount == 0) "解析失败（站点可能需浏览器环境）"
                                else "已解析 $resolvedCount/${episodes.size} 集$windowHint",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (resolvedCount == 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (resolving && episodes.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { resolvedCount.toFloat() / episodes.size.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 线路切换（多线路时；播放页只带当前线路）
            if (chans.size > 1) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "channels") {
                    Column(Modifier.padding(top = 4.dp)) {
                        Text(
                            "线路 · 切换后重新解析该线路",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(chans.indices.toList(), key = { "ch_$it" }) { ci ->
                                FilterChip(
                                    selected = ci == selChannel,
                                    onClick = { if (ci != selChannel) selChannel = ci },
                                    label = { Text("${chans[ci].name}(${chans[ci].episodes.size})") }
                                )
                            }
                        }
                    }
                }
            }

            itemsIndexed(episodes, key = { i, _ -> "ep_$i" }) { i, ep ->
                val ready = resolved.containsKey(ep.pageUrl)
                WebEpisodeButton(
                    name = ep.name,
                    ready = ready,
                    highlight = i == resume.first && resume.second > 0L,
                    onClick = {
                        if (ready) play(i) else pendingIndex = i
                    }
                )
            }
        }
    }

    // 等待解析中的提示（可取消）
    if (pendingIndex != null) {
        AlertDialog(
            onDismissRequest = { pendingIndex = null },
            title = { Text("正在解析播放地址") },
            text = {
                Text(
                    "第${(pendingIndex ?: 0) + 1}集还在解析（$resolvedCount/${episodes.size}）。长番剧首次打开需要一点时间，之后同一线路会走缓存。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingIndex = null }) { Text("取消") } }
        )
    }

    if (showCollect) {
        CollectDialog(
            onDismiss = { showCollect = false },
            collect = { listId ->
                val itemId = Repo.saveCustomItem(
                    category = "动漫",
                    doubanId = "$srcId|ak|${sub.pageUrl}",
                    title = sub.name,
                    coverUrl = null,
                    subTitle = source.name
                )
                if (itemId > 0) { Repo.addItemToList(listId, itemId); true } else false
            }
        )
    }
}

/** 选集按钮：已解析好的集高亮可点，未解析的置灰（点它会等待解析） */
@Composable
private fun WebEpisodeButton(name: String, ready: Boolean, highlight: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    highlight -> MaterialTheme.colorScheme.primary
                    ready -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                highlight -> MaterialTheme.colorScheme.onPrimary
                ready -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
