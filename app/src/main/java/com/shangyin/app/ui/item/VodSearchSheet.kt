package com.shangyin.app.ui.item

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodEpisode
import com.shangyin.app.data.vod.VodItem
import com.shangyin.app.data.vod.VodPlayGroup
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 在线观影弹层：按条目标题跨采集源并行搜索 → 选结果（多线路再选线路）→ 进播放页。
 * 每源独立协程，完成一个显示一个。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VodSearchSheet(
    nav: NavHostController,
    itemId: Long,
    title: String,
    year: String,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(title) }
    var searching by remember { mutableStateOf(false) }
    var pendingCount by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(0) }
    var noSources by remember { mutableStateOf(false) }

    // 搜索结果行
    class ResRow(val src: VodSource, val item: VodItem, val score: Int)
    val results = remember { mutableStateListOf<ResRow>() }

    // 二级：线路选择
    var pickedGroups by remember { mutableStateOf<List<VodPlayGroup>?>(null) }
    var pickedName by remember { mutableStateOf("") }

    fun doSearch(q: String) {
        val kw = q.trim()
        if (kw.isEmpty() || searching) return
        // 详情页在线观看只搜「国内可访问」目录的源；外网源走搜索页 H1 分类
        val srcs = SettingsStore.getVodSources().filter { it.enabled && it.region != "proxy" }
        noSources = srcs.isEmpty()
        if (srcs.isEmpty()) return
        results.clear()
        pickedGroups = null
        totalCount = srcs.size
        pendingCount = srcs.size
        searching = true
        scope.launch {
            coroutineScope {
                srcs.forEach { src ->
                    launch {
                        val raw = VodClient.searchSource(src, kw)
                        val scored = raw
                            .map { it to VodClient.matchScore(it.vod_name, kw, it.vod_year, year) }
                            .filter { it.second >= 2 }
                            .sortedByDescending { it.second }
                        // 行 key = 源id_影片id：同一源内站点可能重复返回同一部 → 去重防重复 key 崩列表
                        results.addAll(
                            scored.distinctBy { it.first.vod_id }
                                .map { ResRow(src, it.first, it.second) }
                        )
                        pendingCount--
                    }
                }
            }
            // 全部完成：按匹配分+片名稳定排序
            val sorted = results.sortedWith(
                compareByDescending<ResRow> { it.score }.thenBy { it.item.vod_name }
            )
            results.clear()
            results.addAll(sorted)
            searching = false
        }
    }

    LaunchedEffect(itemId) { doSearch(title) }

    /** 计算续播起点：最近看过的一集；看完(≥95%)则下一集 */
    fun computeResume(eps: List<VodEpisode>): Pair<Int, Long> {
        var bestIdx = -1
        var bestPos = 0L
        var bestDur = 0L
        var bestTs = -1L
        eps.forEachIndexed { i, ep ->
            val p = SettingsStore.getVodProgress(SettingsStore.vodProgressKey(itemId, ep.url))
                ?: return@forEachIndexed
            if (p.third > bestTs) {
                bestTs = p.third; bestIdx = i; bestPos = p.first; bestDur = p.second
            }
        }
        if (bestIdx < 0) return 0 to 0L
        return if (bestDur > 0 && bestPos >= bestDur * 0.95) {
            ((bestIdx + 1).coerceAtMost(eps.size - 1)) to 0L
        } else bestIdx to bestPos
    }

    fun play(groups: List<VodPlayGroup>, groupIdx: Int) {
        val eps = groups.getOrNull(groupIdx)?.episodes ?: return
        val (idx, pos) = computeResume(eps)
        PlayerSession.itemId = itemId
        PlayerSession.title = query.trim()
        PlayerSession.groups = groups
        PlayerSession.groupIndex = groupIdx
        PlayerSession.startIndex = idx
        PlayerSession.startPosMs = pos
        onDismiss()
        nav.safeNavigate("player")
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("在线观看", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }

            // 搜索框
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { doSearch(query) }) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索")
                }
            }
            Spacer(Modifier.height(8.dp))

            // 状态行
            when {
                noSources -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "国内可访问目录还没有片源，先去设置里添加或测试归组",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            onDismiss()
                            nav.safeNavigate("vodSources")
                        }) { Text("去配置") }
                    }
                }
                searching -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${totalCount - pendingCount}/$totalCount 个源",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    Text(
                        "共 ${results.size} 条结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // 结果列表 / 线路二级选择
            val gp = pickedGroups
            if (gp != null) {
                // 二级：线路列表
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickedGroups = null }) { Text("← 返回结果") }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "「$pickedName」共 ${gp.size} 条线路",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(gp.size) { gi ->
                        val g = gp[gi]
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { play(gp, gi) }
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                        ) {
                            Text(g.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${g.episodes.size} 集 · 第1集 ${g.episodes.first().name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            } else if (results.isEmpty() && !searching && !noSources) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "没有找到片源\n可能是这个关键词真的没匹配，也可能是线路/网络问题（被墙、源已失效）；\n试试换关键词，或到片源管理里测试/更换线路后再搜",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results, key = { it.src.id + "_" + it.item.vod_id }) { row ->
                        ResultRow(row.src, row.item) {
                            val groups = VodClient.parsePlayGroups(
                                row.item.vod_play_from, row.item.vod_play_url
                            )
                            if (groups.isEmpty()) return@ResultRow
                            if (groups.size == 1) {
                                play(groups, 0)
                            } else {
                                pickedGroups = groups
                                pickedName = row.item.vod_name
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ResultRow(src: VodSource, item: VodItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            item.vod_remarks.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            src.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}
