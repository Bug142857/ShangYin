package com.shangyin.app.ui.wygamer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.wygamer.GameCategory
import com.shangyin.app.data.wygamer.GameDetail
import com.shangyin.app.data.wygamer.GameDownload
import com.shangyin.app.data.wygamer.GameItem
import com.shangyin.app.data.wygamer.WygamerClient
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 会话级缓存（返回里世界再进不重新加载） */
@Serializable
private data class GameHomeCache(
    val catUrl: String,
    val keyword: String,
    val nextPage: Int,
    val items: List<GameItem>
)

private val gameJson = Json { ignoreUnknownKeys = true }

/** 游戏详情页缓存的下载解析结果，避免重复请求 */
private val resolvedCache = mutableMapOf<String, String>()

/**
 * 游戏主页（无忧游戏库）：搜索框 + 分类 chips + 两列封面网格 + 加载更多。
 * 数据源是 WordPress 站点 HTML（见 WygamerClient 注释），分类表走 WP REST。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameHomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var cacheJson by rememberSaveable { mutableStateOf<String?>(null) }

    var catUrl by rememberSaveable { mutableStateOf("") }     // ""=最新
    var keyword by rememberSaveable { mutableStateOf("") }    // 非空=搜索模式
    var input by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<GameItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var categories by remember { mutableStateOf<List<GameCategory>>(emptyList()) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }

    fun load(reset: Boolean) {
        loadJob?.cancel()
        val startPage = if (reset) 1 else page
        if (reset) {
            page = 1
            items = emptyList()
        }
        error = null
        loading = true
        loadJob = scope.launch {
            runCatching {
                when {
                    keyword.isNotBlank() -> WygamerClient.search(keyword, startPage)
                    catUrl.isNotBlank() -> WygamerClient.category(catUrl, startPage)
                    else -> WygamerClient.home(startPage)
                }
            }.onSuccess { list ->
                items = if (reset) list else items + list
                page = startPage + 1
                cacheJson = gameJson.encodeToString(GameHomeCache(catUrl, keyword, page, items))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                error = (e.message?.takeIf { m -> m.isNotBlank() } ?: e::class.simpleName) ?: "网络错误"
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (categories.isEmpty()) {
            runCatching { WygamerClient.categories() }.onSuccess { categories = it }
        }
        // 会话缓存恢复：取消首帧发起的那次请求，避免结果覆盖已恢复的列表
        cacheJson?.let { json ->
            runCatching { gameJson.decodeFromString<GameHomeCache>(json) }.onSuccess { c ->
                loadJob?.cancel()
                loading = false
                catUrl = c.catUrl; keyword = c.keyword; input = c.keyword
                page = c.nextPage; items = c.items
                loadedKey = "${c.catUrl}|${c.keyword}"
            }
        }
    }
    LaunchedEffect(catUrl, keyword) {
        val k = "$catUrl|$keyword"
        if (k != loadedKey) {
            loadedKey = k
            load(true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏") },
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("搜索游戏", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    keyword = input.trim()
                }),
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = ""; keyword = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空", modifier = Modifier.padding(4.dp))
                        }
                    }
                }
            )

            if (keyword.isBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                ) {
                    FilterChip(
                        selected = catUrl.isBlank(),
                        onClick = { catUrl = "" },
                        label = { Text("最新") },
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    categories.forEach { c ->
                        FilterChip(
                            selected = catUrl == c.url,
                            onClick = { catUrl = c.url },
                            label = { Text(c.name) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("「$keyword」的搜索结果", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { input = ""; keyword = "" }) { Text("返回浏览") }
                }
            }

            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && items.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(error ?: "加载失败", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { load(true) }) { Text("重试") }
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到游戏", color = MaterialTheme.colorScheme.outline)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items.size, key = { items[it].id }) { i ->
                        GameGridCard(items[i]) { nav.safeNavigate("gameDetail/${items[i].id}") }
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                                error != null -> TextButton(onClick = { load(false) }) { Text("重试") }
                                else -> TextButton(onClick = { load(false) }, enabled = items.isNotEmpty()) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 列表卡片：16:9 封面（站点封面多为 Steam 横版 header 图）+ 标题 + 分类 */
@Composable
private fun GameGridCard(item: GameItem, onClick: () -> Unit) {
    Column(Modifier.clickable { onClick() }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = item.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            item.badge?.takeIf { it.isNotBlank() }?.let { b ->
                Surface(
                    color = if (b.contains("免费")) Color(0xFF2E7D32) else Color(0xFFC62828),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        b,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (item.categories.isNotEmpty()) {
            Text(
                item.categories.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 游戏详情：封面 + 属性（大小/版本/更新日期）+ 正文/截图 + 下载。
 * 下载解析出网盘分享链接后，弹窗提供「用浏览器打开」和「复制链接」（网盘链接无法直链下载）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(nav: NavHostController, gameId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<GameDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    var showDownloads by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<String?>(null) }   // 正在解析的 payUrl
    var resolved by remember { mutableStateOf<String?>(null) }    // 解析结果（网盘分享链接）

    LaunchedEffect(gameId, retryKey) {
        loading = true
        error = null
        runCatching { WygamerClient.detail(gameId) }
            .onSuccess { detail = it }
            .onFailure { error = (it.message?.takeIf { m -> m.isNotBlank() } ?: it::class.simpleName) ?: "加载失败" }
        loading = false
    }

    fun resolveDownload(d: GameDownload) {
        if (resolving != null) return
        resolvedCache[d.url]?.let {
            resolved = it
            showDownloads = false
            return
        }
        resolving = d.url
        scope.launch {
            runCatching { WygamerClient.resolveDownload(d.url) }
                .onSuccess {
                    resolvedCache[d.url] = it
                    showDownloads = false
                    resolved = it
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        it.message?.takeIf { m -> m.isNotBlank() } ?: "解析失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            resolving = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        when {
            loading -> Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { retryKey++ }) { Text("重试") }
            }
            else -> {
                val d = detail ?: return@Scaffold
                LazyColumn(
                    Modifier.padding(pad).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        AsyncImage(
                            model = d.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                    item {
                        Text(d.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (d.categories.isNotEmpty()) {
                        item {
                            Text(
                                d.categories.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    if (d.attrs.isNotEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                d.attrs.forEach { a ->
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(
                                            a.key,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.width(80.dp)
                                        )
                                        Text(a.value, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    if (d.downloads.isNotEmpty() || d.unzipPassword != null) {
                        item {
                            Button(
                                onClick = {
                                    if (d.downloads.isEmpty()) {
                                        Toast.makeText(context, "该资源暂未提供下载链接，登录后可能可见", Toast.LENGTH_LONG).show()
                                    } else {
                                        showDownloads = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (d.downloads.isEmpty()) "暂无下载链接" else "下载（共 ${d.downloads.size} 个链接）")
                            }
                        }
                    }
                    if (d.unzipPassword != null) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "解压密码：${d.unzipPassword}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { copyToClipboard(context, "解压密码", d.unzipPassword!!) }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("复制")
                                }
                            }
                        }
                    }
                    // 正文（小标题 + 段落）
                    items(d.blocks.size) { i ->
                        val b = d.blocks[i]
                        if (b.isHeading) {
                            Text(
                                b.text,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            Text(b.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // 截图
                    if (d.screenshots.isNotEmpty()) {
                        item {
                            Text(
                                "游戏截图",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        items((d.screenshots.size + 1) / 2) { row ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (col in 0..1) {
                                    val idx = row * 2 + col
                                    if (idx < d.screenshots.size) {
                                        AsyncImage(
                                            model = d.screenshots[idx],
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.weight(1f).aspectRatio(4f / 3f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 下载链接列表
    if (showDownloads) {
        val d = detail
        AlertDialog(
            onDismissRequest = { if (resolving == null) showDownloads = false },
            title = { Text("选择下载链接") },
            text = {
                Column {
                    Text(
                        "链接指向网盘分享页（迅雷/百度等），需要跳转到对应 App 或浏览器打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    d?.downloads?.forEach { dl ->
                        TextButton(
                            onClick = { resolveDownload(dl) },
                            enabled = resolving == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (resolving == dl.url) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在解析…")
                            } else {
                                Text(dl.label)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDownloads = false }, enabled = resolving == null) { Text("取消") }
            }
        )
    }

    // 解析结果：用浏览器打开 / 复制链接
    resolved?.let { url ->
        AlertDialog(
            onDismissRequest = { resolved = null },
            title = { Text("网盘链接") },
            text = {
                Column {
                    Text(url, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "提示：用「浏览器打开」可跳转到网盘 App；保存链接后可稍后再取。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    openInBrowser(context, url)
                    resolved = null
                }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("用浏览器打开")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    copyToClipboard(context, "网盘链接", url)
                    resolved = null
                }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制链接")
                }
            }
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}