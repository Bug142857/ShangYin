package com.shangyin.app.ui.search

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FavoriteBorder
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangyin.app.data.Repo
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodItem
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.data.InnerFav
import com.shangyin.app.ui.innerfav.FavHeartButton
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.AppSearchField
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * H1 外网片源浏览页：直接进入（无需先搜索），
 * 每个外网源一个分组，横向卡片展示该源资源（默认第一页，可加载更多）；
 * 页内搜索框可按关键词搜全部外网源；点击影片直接进播放页（可切线路/集数）。
 */
private data class SrcState(
    val status: Int,          // 0=加载中 1=完成 2=失败
    val total: Int,           // 源报告的总量（浏览=库总量，搜索=命中数）
    val items: List<VodItem>,
    val page: Int             // 已加载到的页码
)

/**
 * 番号页会话缓存：跨页面导航（进播放页/详情页再返回）保留搜索词与已加载结果，
 * 返回时不再重新加载（stateKeyword 记录 stateMap 对应的关键词）。
 */
private object H1Cache {
    var stateKeyword: String? = null
    var keyword: String = ""
    var input: String = ""
    var stateMap: Map<String, SrcState> = emptyMap()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H1SearchScreen(nav: NavHostController, kwEncoded: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val initialKw = remember {
        runCatching { java.net.URLDecoder.decode(kwEncoded, "UTF-8") }.getOrDefault(kwEncoded)
    }

    // 只展示「需要外网」目录且启用的源
    val sources = remember {
        SettingsStore.getVodSources().filter { it.enabled && it.region == "proxy" }
    }

    // 从会话缓存恢复（首次进入用路由关键词初始化）
    if (H1Cache.stateKeyword == null) {
        H1Cache.keyword = initialKw.trim()
        H1Cache.input = initialKw.trim()
    }
    var keyword by remember { mutableStateOf(H1Cache.keyword) }
    var input by remember { mutableStateOf(H1Cache.input) }
    // srcId -> 每源加载状态
    var stateMap by remember { mutableStateOf(H1Cache.stateMap) }
    val loadingKeys = remember { mutableSetOf<String>() } // "srcId:page" 防重复加载
    // 点击后正在取详情播放的影片 id（防重复点击）
    var openingId by remember { mutableStateOf<Long?>(null) }

    // 状态变化实时写回缓存（进播放页后返回可完整恢复）
    LaunchedEffect(keyword, input, stateMap) {
        H1Cache.keyword = keyword
        H1Cache.input = input
        H1Cache.stateMap = stateMap
    }

    /** 加载某源某页（kw 变化后返回的旧响应会被丢弃） */
    fun load(src: VodSource, page: Int, kw: String) {
        val key = "${src.id}:$page"
        if (!loadingKeys.add(key)) return
        // 首次进入时先显示加载中
        if (page == 1) {
            stateMap = stateMap + (src.id to SrcState(0, 0, emptyList(), 0))
        }
        scope.launch {
            val resp = VodClient.fetchList(src, kw, page)
            if (kw != keyword) { loadingKeys.remove(key); return@launch } // 关键词已变，丢弃
            stateMap = stateMap.toMutableMap().apply {
                val old = get(src.id)
                if (resp == null) {
                    put(src.id, SrcState(2, old?.total ?: 0, old?.items ?: emptyList(), old?.page ?: 0))
                } else {
                    val merged = ((old?.items ?: emptyList()) + resp.list)
                        .distinctBy { it.vod_id }
                    put(src.id, SrcState(1, resp.total, merged, page))
                }
            }
            loadingKeys.remove(key)
        }
    }

    // 关键词变化：新关键词全部源并行重载；恢复场景只补加载缺失的源
    LaunchedEffect(keyword) {
        if (sources.isEmpty()) return@LaunchedEffect
        if (H1Cache.stateKeyword == keyword) {
            sources.filter { it.id !in stateMap }.forEach { src -> load(src, 1, keyword) }
        } else {
            H1Cache.stateKeyword = keyword
            loadingKeys.clear()
            stateMap = emptyMap()
            coroutineScope {
                sources.forEach { src -> launch { load(src, 1, keyword) } }
            }
        }
    }

    /** 点击影片：公共播放流程（补详情→解析线路→断点续播→跳播放页） */
    fun playItem(src: VodSource, item: VodItem) {
        if (openingId != null) return
        openingId = item.vod_id
        scope.launch {
            val ok = openVodAndPlay(nav, context, src, item)
            if (!ok) openingId = null
        }
    }

    // 爱心快藏（与吃瓜/X站/P站同款）：数据在 InnerFavorites，设置页模块管理不受影响

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("番号", fontWeight = FontWeight.Bold)
                        Text(
                            if (keyword.isBlank()) "共 ${sources.size} 个源 · 页内可搜索" else "搜索「$keyword」",
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
                },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("innerFavs/h1") }) {
                        Icon(Icons.Rounded.FavoriteBorder, contentDescription = "我的收藏")
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
                    "先到 片源管理 里测试链接，测试为「需外网/返回异常」的源会自动归入外网目录；也可以在编辑片源时手动选择「需要外网」目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { nav.safeNavigate("vodSources") }) { Text("去片源管理") }
            }
        } else {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
            ) {
                // 页内搜索框：输入法确认键（搜索）直接触发；右侧放大镜在输入变化后可再点（v0.301 统一样式）
                AppSearchField(
                    value = input,
                    onValueChange = {
                        input = it
                        if (it.isEmpty()) keyword = ""
                    },
                    onSearch = {
                        keyboard?.hide()
                        keyword = input.trim()
                    },
                    placeholder = "搜索外网片源，留空浏览全部",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )

                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    sources.forEach { src ->
                        val state = stateMap[src.id] ?: SrcState(0, 0, emptyList(), 0)
                        item(key = "h_${src.id}") {
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
                                    TextButton(onClick = { nav.safeNavigate("h1source/" + src.id) }) {
                                        Text(
                                            "查看全部",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                        if (state.items.isNotEmpty()) {
                            item(key = "r_${src.id}") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(state.items, key = { "${src.id}_${it.vod_id}" }) { item ->
                                        VodCard(
                                            item = item,
                                            opening = openingId == item.vod_id,
                                            favId = "${src.id}|${item.vod_id}",
                                            favSubtitle = src.name,
                                            onClick = { playItem(src, item) }
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
}

/** 外网片源卡片：海报 + 片名 + 备注，点击播放；右上角爱心快藏（InnerFavorites） */
@Composable
private fun VodCard(
    item: VodItem,
    opening: Boolean,
    favId: String,
    favSubtitle: String,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .width(96.dp)
            .clickable(enabled = !opening, onClick = onClick)
    ) {
        Box {
            CoverImage(
                url = item.vod_pic,
                modifier = Modifier
                    .width(96.dp)
                    .height(128.dp),
                corner = 8.dp
            )
            // 爱心快藏（右上角，不挡海报点击）
            FavHeartButton(
                favModule = "h1",
                favId = favId,
                build = {
                    InnerFav(
                        module = "h1",
                        id = favId,
                        title = item.vod_name,
                        subtitle = favSubtitle,
                        cover = item.vod_pic,
                        savedAt = System.currentTimeMillis()
                    )
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
            )
            if (opening) {
                Box(
                    Modifier
                        .width(96.dp)
                        .height(128.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "打开中",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
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
