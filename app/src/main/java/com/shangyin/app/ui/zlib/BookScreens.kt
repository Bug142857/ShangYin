package com.shangyin.app.ui.zlib

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.zlib.Book
import com.shangyin.app.data.zlib.BookDownload
import com.shangyin.app.data.zlib.ZlibClient
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class BookSearchCache(
    val keyword: String,
    val nextPage: Int,
    val totalPages: Int,
    val items: List<Book>
)

private val bookJson = Json { ignoreUnknownKeys = true }

/**
 * 图书主页（Z-Library）：搜索框 + 三列封面网格 + 加载更多。
 * 依赖登录态（Cookie 内含反爬验证票据），未登录时给出引导。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookHomeScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    var cacheJson by rememberSaveable { mutableStateOf<String?>(null) }

    var keyword by rememberSaveable { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Book>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var loggedIn by remember { mutableStateOf(ZlibClient.isLoggedIn) }

    fun load(reset: Boolean) {
        if (keyword.isBlank()) return
        loadJob?.cancel()
        val startPage = if (reset) 1 else page
        if (reset) {
            page = 1
            items = emptyList()
            totalPages = 0
        }
        error = null
        loading = true
        loadJob = scope.launch {
            runCatching { ZlibClient.search(keyword, startPage) }
                .onSuccess { r ->
                    items = if (reset) r.books else items + r.books
                    totalPages = r.totalPages
                    page = startPage + 1
                    cacheJson = bookJson.encodeToString(BookSearchCache(keyword, page, totalPages, items))
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    error = (e.message?.takeIf { m -> m.isNotBlank() } ?: e::class.simpleName) ?: "网络错误"
                }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        cacheJson?.let { json ->
            runCatching { bookJson.decodeFromString<BookSearchCache>(json) }.onSuccess { c ->
                loadJob?.cancel()
                loading = false
                keyword = c.keyword; input = c.keyword
                page = c.nextPage; totalPages = c.totalPages; items = c.items
                loadedKey = c.keyword
            }
        }
    }

    // 预热站点页面：首页加载实测约 7.4 秒，提前做掉，别让第一次搜索把时间花在页面加载上
    LaunchedEffect(Unit) { runCatching { ZlibClient.warmup() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图书") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        loggedIn = ZlibClient.isLoggedIn
                        nav.safeNavigate("account")
                    }) {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = "账号管理",
                            tint = if (loggedIn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
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
                placeholder = { Text("搜索书名 / 作者 / ISBN", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    val k = input.trim()
                    if (k.isNotBlank() && k != keyword) {
                        keyword = k
                        loadedKey = k
                        load(true)
                    }
                }),
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = { input = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空", modifier = Modifier.padding(4.dp))
                        }
                    }
                }
            )

            if (!loggedIn) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "未登录 Z-Library，搜索可能受限",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { nav.safeNavigate("account") }) { Text("去登录") }
                }
            }

            when {
                keyword.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "输入关键词搜索电子书\n线路：${SettingsStore.zlibHost}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && items.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(error ?: "加载失败", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { load(true) }) { Text("重试") }
                    TextButton(onClick = { nav.safeNavigate("account") }) { Text("去登录 / 换线路") }
                }
                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到图书", color = MaterialTheme.colorScheme.outline)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items.size, key = { items[it].key }) { i ->
                        val b = items[i]
                        val h = b.hash.ifBlank { "-" }
                        Column(Modifier.clickable { nav.safeNavigate("bookDetail/${b.id}/$h") }) {
                            AsyncImage(
                                model = b.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f / 1.4f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                b.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                b.author ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                                error != null -> TextButton(onClick = { load(false) }) {
                                    Text("重试", color = MaterialTheme.colorScheme.error)
                                }
                                totalPages > 0 && page > totalPages -> Text(
                                    "没有更多了",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                else -> TextButton(onClick = { load(false) }) { Text("加载更多") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 图书详情：封面 + 标题/作者 + 出版信息 + 下载（走站点自己的 `/dl/{token}` 入口，存到系统下载目录）+ 简介。
 * 简介来自站点，是带 `<p>`/`<br>` 的 HTML，已在 [ZlibClient] 里清成纯文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(nav: NavHostController, bookId: String, hash: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 路由里用 "-" 占位表示搜索结果的 hash 为空
    val hashId = hash.takeIf { it != "-" }.orEmpty()

    var book by remember { mutableStateOf<Book?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    LaunchedEffect(bookId, hashId, retryKey) {
        loading = true
        error = null
        if (hashId.isBlank()) {
            error = "缺少书籍标识，请返回重新搜索"
            loading = false
            return@LaunchedEffect
        }
        runCatching { ZlibClient.detail(bookId, hashId) }
            .onSuccess { book = it }
            .onFailure { error = (it.message?.takeIf { m -> m.isNotBlank() } ?: it::class.simpleName) ?: "加载失败" }
        loading = false
    }

    fun download() {
        val b = book ?: return
        if (downloading) return
        downloading = true
        progress = null
        scope.launch {
            runCatching {
                // 站点下载入口 → WebView 交出真实文件地址 → OkHttp 存进系统下载目录
                val target = ZlibClient.downloadTarget(b.id, b.hash, b.dl)
                val fallback = b.extension?.takeIf { it.isNotBlank() }?.let { "${b.title}.$it" } ?: b.title
                BookDownload.save(context, target, fallback) { done, total -> progress = done to total }
            }.onSuccess { r ->
                Toast.makeText(context, "已保存到 ${r.where}", Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(
                    context,
                    e.message?.takeIf { m -> m.isNotBlank() } ?: "下载失败",
                    Toast.LENGTH_LONG
                ).show()
            }
            downloading = false
            progress = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图书详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { retryKey++ }) { Text("重试") }
                TextButton(onClick = { nav.safeNavigate("account") }) { Text("去登录 / 换线路") }
            }
            else -> {
                val b = book ?: return@Scaffold
                val rows = listOfNotNull(
                    b.publisher?.takeIf { it.isNotBlank() }?.let { "出版社" to it },
                    b.year?.takeIf { it.isNotBlank() }?.let { "出版年份" to "$it 年" },
                    b.language?.takeIf { it.isNotBlank() }?.let { "语言" to it },
                    b.extension?.takeIf { it.isNotBlank() }?.let { "格式" to it.uppercase() },
                    b.filesize?.takeIf { it.isNotBlank() }?.let { "大小" to fmtSize(it) },
                    b.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "评分" to it }
                )
                LazyColumn(
                    Modifier.padding(pad).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Row {
                            AsyncImage(
                                model = b.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(118.dp).aspectRatio(1f / 1.4f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    b.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                b.author?.takeIf { it.isNotBlank() }?.let {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (rows.isNotEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rows.forEach { (label, value) ->
                                    Row {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.width(72.dp)
                                        )
                                        Text(value, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Button(
                            onClick = { download() },
                            enabled = !downloading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (downloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                val (done, total) = progress ?: (0L to -1L)
                                Text(if (total > 0) "下载中 ${done * 100 / total}%" else "下载中 ${fmtSize(done.toString())}")
                            } else {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("下载")
                            }
                        }
                    }

                    if (!ZlibClient.isLoggedIn) {
                        item {
                            Text(
                                "未登录：Z-Library 下载需登录账号并消耗每日额度。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    b.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        item {
                            Text("简介", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(desc, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 字节数格式化：Z-Library 的 filesize 是字符串字节数 */
private fun fmtSize(raw: String): String {
    val n = raw.toLongOrNull() ?: return raw
    return when {
        n >= 1024L * 1024 * 1024 -> String.format("%.1f GB", n / 1024.0 / 1024 / 1024)
        n >= 1024L * 1024 -> String.format("%.1f MB", n / 1024.0 / 1024)
        n >= 1024L -> String.format("%.0f KB", n / 1024.0)
        else -> "$n B"
    }
}