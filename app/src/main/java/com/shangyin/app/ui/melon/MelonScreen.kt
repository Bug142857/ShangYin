package com.shangyin.app.ui.melon

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.melon.MelonBlock
import com.shangyin.app.data.melon.MelonCache
import com.shangyin.app.data.melon.MelonCategory
import com.shangyin.app.data.melon.MelonClient
import com.shangyin.app.data.melon.MelonPost
import com.shangyin.app.data.vod.VodEpisode
import com.shangyin.app.data.vod.VodPlayGroup
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 吃瓜（里世界第八入口）：51爆料，原生列表/详情（v0.219 起，替代旧 WebView 方案）。
 *
 * 原生解析（MelonClient）天然把站点广告挡在门外：列表广告条（article.ad-item）、
 * 详情页按钮墙（.txt-apps）、横幅、播放器贴片全部不进 App。
 * 线路自动发现 + 探活 + 失效自动换线（着陆页解码出镜像列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelonHomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var catPath by remember { mutableStateOf("") }      // ""=最新
    var keyword by remember { mutableStateOf("") }      // 非空=搜索模式
    var input by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<MelonPost>>(emptyList()) }
    var nextPath by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    val lastSeen = remember { MelonCache.last }

    fun load(reset: Boolean, next: String? = null) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            runCatching {
                if (next != null) MelonClient.nextPage(next)
                else when {
                    keyword.isNotBlank() -> MelonClient.search(keyword)
                    catPath.isNotBlank() -> MelonClient.category(catPath)
                    else -> MelonClient.home()
                }
            }.onSuccess { page ->
                items = (if (reset || next == null) page.items else items + page.items)
                    .distinctBy { it.id }
                nextPath = page.nextPath
            }.onFailure { e ->
                if (reset || next == null) items = emptyList()
                error = e.message?.takeIf { it.isNotBlank() } ?: "网络错误"
            }
            loading = false
        }
    }

    // 分类/关键词变化 → 重置加载
    LaunchedEffect(catPath, keyword) {
        val key = "$catPath|$keyword"
        if (key != loadedKey) {
            loadedKey = key
            load(true)
        }
    }

    // 触底自动加载更多（倒数第 4 个条目进入可视区）
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .map { idx -> items.isNotEmpty() && idx >= items.size - 3 }
            .distinctUntilChanged()
            .filter { it }
            .collect { if (!loading && nextPath != null) load(false, nextPath) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("吃瓜") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            // 搜索框
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索吃瓜爆料", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = {
                            input = ""
                            if (keyword.isNotEmpty()) keyword = ""
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    keyword = input.trim()
                })
            )
            // 分类 chips
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MelonClient.CATEGORIES.forEach { c ->
                    FilterChip(
                        selected = catPath == c.path && keyword.isBlank(),
                        onClick = {
                            keyword = ""
                            input = ""
                            catPath = c.path
                        },
                        label = { Text(c.name, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }

            when {
                // 首屏加载中
                loading && items.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                error != null && items.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                ) {
                    Text(
                        error ?: "加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { loadedKey = null; load(true) },
                        modifier = Modifier.padding(top = 16.dp)
                    ) { Text("重试") }
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp, vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 会话内续看：最新页顶部给「上次看到」入口
                    if (catPath.isBlank() && keyword.isBlank() && lastSeen != null) {
                        item(key = "lastSeen") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { nav.safeNavigate("melonDetail/${lastSeen.id}") }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "上次看到：《${lastSeen.title}》",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(items, key = { _, p -> p.id }) { _, post ->
                        MelonPostCard(post) { nav.safeNavigate("melonDetail/${post.id}") }
                    }
                    // 底部状态
                    item(key = "footer") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                loading -> CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                nextPath == null && items.isNotEmpty() -> Text(
                                    "没有更多了",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                items.isEmpty() -> Text(
                                    if (keyword.isNotBlank()) "没有找到相关内容" else "暂无内容",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 列表卡片：封面背景 + 渐变压暗 + 标题/元信息浮层（还原站点卡片观感） */
@Composable
private fun MelonPostCard(post: MelonPost, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        if (post.cover != null) {
            AsyncImage(
                model = post.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.78f))
                        )
                    )
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                post.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (post.cover != null) Color.White else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            val meta = listOf(post.author, post.date).filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank() || post.categories.isNotEmpty()) {
                Text(
                    listOf(meta, post.categories.joinToString(", "))
                        .filter { it.isNotBlank() }
                        .joinToString("  "),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (post.cover != null) Color.White.copy(alpha = 0.75f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 吃瓜详情：标题 + 元信息 + 正文块（文字/小标题/图片/视频）+ 相关推荐。
 * 图片点击进全屏查看器（长按可保存）；视频点击进内置播放器（HLS，支持断点续播）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelonDetailScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<com.shangyin.app.data.melon.MelonDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewerIndex by remember { mutableIntStateOf(-1) } // -1=关闭

    fun reload() {
        loading = true
        error = null
        scope.launch {
            runCatching { MelonClient.detail(id) }
                .onSuccess {
                    detail = it
                    // 会话内记住最后浏览的帖子（首页「上次看到」入口用）
                    MelonCache.last = MelonPost(
                        it.id, it.title, it.cover, it.author, it.date, it.categories
                    )
                }
                .onFailure { e -> error = e.message?.takeIf { it.isNotBlank() } ?: "加载失败" }
            loading = false
        }
    }
    LaunchedEffect(id) { reload() }

    fun openInBrowser(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure {
                android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
    }

    fun play(video: MelonBlock.Video) {
        val d = detail ?: return
        val vids = d.blocks.filterIsInstance<MelonBlock.Video>()
        PlayerSession.clear()
        PlayerSession.itemId = id.toLongOrNull() ?: 0L
        PlayerSession.title = d.title
        PlayerSession.subTitle = ""
        PlayerSession.sourceName = "51爆料"
        PlayerSession.groups = listOf(
            VodPlayGroup("51爆料", vids.map { VodEpisode(it.title, it.url) })
        )
        PlayerSession.groupIndex = 0
        PlayerSession.startIndex = vids.indexOfFirst { it.url == video.url }.coerceAtLeast(0)
        PlayerSession.startPosMs = 0L
        PlayerSession.isLive = false
        PlayerSession.streamHeaders = buildMap {
            put("User-Agent", MelonClient.UA)
            if (MelonClient.lastBase.isNotBlank()) put("Referer", MelonClient.lastBase + "/")
        }
        nav.safeNavigate("player")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail?.title ?: "吃瓜",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { openInBrowser("${MelonClient.lastBase}/archives/$id/") }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "浏览器打开"
                        )
                    }
                }
            )
        }
    ) { pad ->
        when {
            loading -> Box(
                Modifier
                    .padding(pad)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(pad)
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                Text(
                    error ?: "加载失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
                Button(onClick = { reload() }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("重试")
                }
            }

            else -> {
                val d = detail ?: return@Scaffold
                val images = remember(d) {
                    d.blocks.filterIsInstance<MelonBlock.Image>().map { it.url }
                }
                LazyColumn(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)
                ) {
                    if (d.cover != null) {
                        item {
                            AsyncImage(
                                model = d.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                            )
                        }
                    }
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                d.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            val meta = listOf(d.author, d.date).filter { it.isNotBlank() }
                                .joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (d.categories.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    d.categories.forEach { c ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                c,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(
                                                    horizontal = 8.dp, vertical = 3.dp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(d.blocks) { _, block ->
                        when (block) {
                            is MelonBlock.Heading -> Text(
                                block.text,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            is MelonBlock.Text -> Text(
                                block.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            is MelonBlock.Image -> AsyncImage(
                                model = block.url,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        viewerIndex = images.indexOf(block.url)
                                    }
                            )
                            is MelonBlock.Video -> Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clickable { play(block) }
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        block.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "播放",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    if (d.related.isNotEmpty()) {
                        item {
                            Text(
                                "相关推荐",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                        itemsIndexed(d.related) { _, r ->
                            Text(
                                r.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        nav.safeNavigate("melonDetail/${r.id}")
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                // 全屏图片查看（含长按保存）
                if (viewerIndex >= 0 && images.isNotEmpty()) {
                    PhotoViewerDialog(
                        urls = images,
                        initialIndex = viewerIndex.coerceIn(0, images.size - 1),
                        onDismiss = { viewerIndex = -1 }
                    )
                }
            }
        }
    }
}
