package com.shangyin.app.ui.search

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodItem
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * H1 外网源搜索页：调用片源管理里「需要外网」目录的采集源并行搜索，
 * 按源分组展示结果（渐进上屏），点击直接进播放页（默认第一线路，播放页内可切线路/集数）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H1SearchScreen(nav: NavHostController, kwEncoded: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyword = remember {
        runCatching { java.net.URLDecoder.decode(kwEncoded, "UTF-8") }.getOrDefault(kwEncoded)
    }

    // 只搜「需要外网」目录且启用的源
    val sources = remember {
        SettingsStore.getVodSources().filter { it.enabled && it.region == "proxy" }
    }
    // srcId -> (状态 0=搜索中 1=完成, 结果列表)
    var stateMap by remember {
        mutableStateOf<Map<String, Pair<Int, List<VodItem>>>>(emptyMap())
    }
    // 点击后正在取详情播放的影片 id（防重复点击）
    var openingId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        if (sources.isEmpty()) return@LaunchedEffect
        coroutineScope {
            sources.forEach { src ->
                launch {
                    val items = runCatching { VodClient.searchSource(src, keyword) }
                        .getOrDefault(emptyList())
                    stateMap = stateMap + (src.id to (1 to items))
                }
            }
        }
    }

    /** 点击结果：补详情（无播放地址时）→ 默认第一线路进播放页（播放页内可切线路/集数） */
    fun playItem(src: VodSource, item: VodItem) {
        if (openingId != null) return
        openingId = item.vod_id
        scope.launch {
            val full = if (item.vod_play_url.isBlank()) {
                runCatching { VodClient.fetchDetail(src, item.vod_id) }.getOrDefault(item)
            } else item
            val groups = VodClient.parsePlayGroups(full.vod_play_from, full.vod_play_url)
            if (groups.isEmpty()) {
                Toast.makeText(context, "「${full.vod_name}」暂无可用播放地址", Toast.LENGTH_SHORT).show()
                openingId = null
                return@launch
            }
            // 断点续播：进度按播放地址记忆（itemId 用 0，地址本身全局唯一）
            var idx = 0
            var pos = 0L
            var bestTs = -1L
            groups[0].episodes.forEachIndexed { i, ep ->
                val p = SettingsStore.getVodProgress(SettingsStore.vodProgressKey(0L, ep.url))
                    ?: return@forEachIndexed
                if (p.third > bestTs) {
                    bestTs = p.third; idx = i; pos = p.first
                }
            }
            PlayerSession.itemId = 0L
            PlayerSession.title = full.vod_name
            PlayerSession.groups = groups
            PlayerSession.groupIndex = 0
            PlayerSession.startIndex = idx
            PlayerSession.startPosMs = pos
            openingId = null
            nav.safeNavigate("player")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("H1 · 外网源搜索")
                        Text(
                            keyword,
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
                Text("外网目录还没有片源", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "先到 片源管理 里测试链接，测试为「需外网」的源会自动归入外网目录；也可以在编辑片源时手动选择「需要外网」目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { nav.safeNavigate("vodSources") }) { Text("去片源管理") }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                sources.forEach { src ->
                    val state = stateMap[src.id] ?: (0 to emptyList())
                    val status = state.first
                    val items = state.second
                    item(key = "h_${src.id}") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        ) {
                            Text(
                                src.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                when {
                                    status == 0 -> "搜索中…"
                                    items.isEmpty() -> "无结果"
                                    else -> "共 ${items.size} 条"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (items.isNotEmpty()) {
                        items(items, key = { "${src.id}_${it.vod_id}" }) { item ->
                            H1ResultRow(
                                item = item,
                                opening = openingId == item.vod_id,
                                onClick = { playItem(src, item) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** H1 搜索结果行：海报 + 片名/年份/类型/备注，点击播放 */
@Composable
private fun H1ResultRow(
    item: VodItem,
    opening: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !opening, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = item.vod_pic,
            modifier = Modifier
                .width(56.dp)
                .height(76.dp),
            corner = 6.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.vod_name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val meta = listOf(item.vod_year, item.type_name, item.vod_area)
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                when {
                    opening -> "正在打开…"
                    item.vod_remarks.isNotBlank() -> item.vod_remarks
                    else -> "点击播放"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (opening) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
