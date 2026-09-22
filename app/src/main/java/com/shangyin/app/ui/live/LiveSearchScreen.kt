package com.shangyin.app.ui.live

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.live.DouyuClient
import com.shangyin.app.data.live.HuyaClient
import com.shangyin.app.data.live.LivePlatforms
import com.shangyin.app.data.live.LiveRoom
import com.shangyin.app.data.live.M3uClient
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.launch

/**
 * 直播搜索。
 *
 * 各平台可行性（实测）：
 * - **虎牙**：可用（页面 JS 用的 `search.cdn.huya.com getSearchContent` JSON 接口，见 HuyaClient.search）
 * - **斗鱼**：可用（`japi/search/api/searchShow`，带 `isLive`/`hot` 字段）
 * - **电视**：本地过滤已加载的频道名（M3U 没有远端搜索）
 * - **B站 / 抖音**：没有可用的搜索接口（B站 412 风控、抖音要签名）→ 页面里直接说明，不假装有
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSearchScreen(nav: NavHostController, platform: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LiveRoom>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var openingId by remember { mutableStateOf<String?>(null) }
    var collectTarget by remember { mutableStateOf<LiveRoom?>(null) }

    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val savedIds = remember(allItems) {
        allItems.filter { it.category == LivePlatforms.CATEGORY }.mapNotNull { it.doubanId }.toSet()
    }

    val isTv = platform == LivePlatforms.CUSTOM
    val remoteSupported = platform == LivePlatforms.HUYA || platform == LivePlatforms.DOUYU
    // 斗鱼接口支持翻页；虎牙搜索接口只有一页
    val canLoadMore = platform == LivePlatforms.DOUYU

    fun search(next: Boolean = false) {
        val kw = input.trim()
        if (kw.isEmpty()) {
            results = emptyList()
            searched = false
            error = null
            return
        }
        val targetPage = if (next) page + 1 else 1
        scope.launch {
            loading = true
            error = null
            if (!next) searched = true
            val fetched = when {
                isTv -> {
                    // 电视：本地过滤（加载一次全部频道再按名字匹配）
                    val sources = SettingsStore.getLiveSources().filter { it.enabled }
                    val channels = runCatching { M3uClient.loadAll(sources) }.getOrDefault(emptyList())
                    channels.flatMap { r ->
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

                platform == LivePlatforms.HUYA -> HuyaClient.search(kw, targetPage)
                platform == LivePlatforms.DOUYU -> DouyuClient.search(kw, targetPage)
                else -> emptyList()
            }
            results = if (next) results + fetched else fetched
            page = targetPage
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索 · ${LivePlatforms.label(platform)}", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(if (isTv) "搜索电视频道名" else "搜索直播间 / 主播") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = ""; results = emptyList(); searched = false }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                        }
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(Modifier.padding(horizontal = 16.dp)) {
                Button(enabled = input.isNotBlank() && !loading, onClick = { search() }) {
                    Text(if (loading) "搜索中…" else "搜索")
                }
            }

            when {
                !remoteSupported && !isTv -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyView(
                        "${LivePlatforms.label(platform)} 暂不支持搜索\n" +
                            "该平台没有可用的搜索接口（B站有风控、抖音要签名）\n" +
                            "可回直播页按分区浏览，或用虎牙 / 斗鱼 / 电视搜索"
                    )
                }

                results.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                results.isEmpty() && searched -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyView(error ?: "没有搜到相关直播间")
                        if (isTv) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "电视搜索是在已导入的频道名里找；没有就是源里没有这个频道",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyView("输入关键词开始搜索")
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
                            onClick = {
                                if (openingId == null) {
                                    openingId = room.collectId
                                    scope.launch {
                                        val ok = openLiveAndPlay(nav, context, room)
                                        if (!ok) openingId = null
                                    }
                                }
                            },
                            onCollect = { collectTarget = room }
                        )
                    }
                    if (canLoadMore && results.isNotEmpty()) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Button(enabled = !loading, onClick = { search(next = true) }) {
                                    Text(if (loading) "加载中…" else "加载更多")
                                }
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
