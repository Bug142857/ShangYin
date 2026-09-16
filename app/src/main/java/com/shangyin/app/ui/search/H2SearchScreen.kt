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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.pix.PixivClient
import com.shangyin.app.data.pix.PixivIllust
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch

/**
 * H2 搜图页：数据来自 pixiv-viewer-app 同款 HibiAPI 镜像（无需登录），
 * 关键词搜索 Pixiv 插画，双列网格浏览，点击取详情后全屏看原图（可翻页/缩放/长按保存）。
 */
private val MODES = listOf(
    "部分标签" to "partial_match_for_tags",
    "完全标签" to "exact_match_for_tags",
    "标题说明" to "title_and_caption"
)
private val ORDERS = listOf("最新" to "date_desc", "最早" to "date_asc")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H2SearchScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var input by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(MODES[0].second) }
    var order by remember { mutableStateOf(ORDERS[0].second) }

    var items by remember { mutableStateOf<List<PixivIllust>>(emptyList()) }
    var page by remember { mutableStateOf(0) }        // 已加载页码
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var openingId by remember { mutableStateOf<Long?>(null) }  // 正在取详情的作品
    var viewerUrls by remember { mutableStateOf<List<String>?>(null) }
    val gridState = rememberLazyGridState()
    val reqId = remember { androidx.compose.runtime.mutableIntStateOf(0) } // 过期搜索响应丢弃

    fun runSearch(q: String, pg: Int) {
        if (q.isEmpty() || loading || loadingMore) return
        val my = ++reqId.value
        if (pg == 1) {
            keyword = q
            items = emptyList()
            noMore = false
            error = null
        }
        scope.launch {
            if (pg == 1) loading = true else loadingMore = true
            runCatching { PixivClient.searchIllust(q, mode, order, pg) }
                .onSuccess { list ->
                    if (reqId.value != my) return@launch
                    items = if (pg == 1) list else items + list
                    page = pg
                    noMore = list.size < 30
                }
                .onFailure { e ->
                    if (reqId.value == my && pg == 1) {
                        error = "搜索失败：${e.message ?: e.javaClass.simpleName}"
                    }
                }
            if (reqId.value == my) {
                loading = false
                loadingMore = false
            }
        }
    }

    fun doSearch() {
        val q = input.trim()
        if (q.isEmpty()) return
        keyboard?.hide()
        runSearch(q, 1)
    }

    /** 模式/排序变化后用当前关键词重搜 */
    fun reload() {
        if (keyword.isNotBlank()) runSearch(keyword, 1)
    }

    /** 点击卡片：取详情拿全部原图，弹全屏预览 */
    fun openDetail(d: PixivIllust) {
        if (openingId != null) return
        scope.launch {
            openingId = d.id
            runCatching { PixivClient.illustDetail(d.id) }
                .onSuccess { detail ->
                    val urls = detail?.let { PixivClient.allPageImages(it) }.orEmpty()
                        .ifEmpty {
                            listOfNotNull(PixivClient.imgProxy(d.imageUrls?.large))
                                .filter { it.isNotBlank() }
                        }
                    viewerUrls = if (urls.isNotEmpty()) urls
                    else listOf(PixivClient.coverUrl(d)).filter { it.isNotBlank() }
                }
                .onFailure {
                    Toast.makeText(context, "获取图片失败：${it.message ?: "网络错误"}", Toast.LENGTH_SHORT).show()
                }
            openingId = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜图") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 搜索框
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("输入标签 / 画师 / 关键词…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { doSearch() }) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索")
                }
            }
            // 匹配模式
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                MODES.forEach { (label, value) ->
                    FilterChip(
                        selected = mode == value,
                        onClick = { mode = value; reload() },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            // 排序
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Text("排序", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                ORDERS.forEach { (label, value) ->
                    FilterChip(
                        selected = order == value,
                        onClick = { order = value; reload() },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            when {
                // 未搜索空态
                keyword.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(32.dp)
                ) {
                    Text("输入关键词开始搜图", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "支持中文 / 日文 / 英文标签，如：东方、東方、原神",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 首页加载中
                loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("搜索中…", style = MaterialTheme.typography.bodySmall)
                }
                // 首页失败
                error != null && items.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(32.dp)
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { runSearch(keyword, 1) }) { Text("重试") }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items.size) { i ->
                        val d = items[i]
                        IllustCard(
                            d = d,
                            opening = openingId == d.id,
                            onClick = { openDetail(d) }
                        )
                    }
                    // 加载更多 / 没有更多
                    item(span = { GridItemSpan(2) }) {
                        when {
                            loadingMore -> Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("加载中…", style = MaterialTheme.typography.bodySmall)
                            }
                            noMore -> Text(
                                "没有更多了",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            items.isNotEmpty() -> TextButton(
                                onClick = { runSearch(keyword, page + 1) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) { Text("加载更多") }
                        }
                    }
                }
            }
        }
    }

    // 全屏原图预览（可缩放/翻页/长按保存）
    viewerUrls?.let { urls ->
        PhotoViewerDialog(urls = urls, initialIndex = 0, onDismiss = { viewerUrls = null })
    }
}

/** 单个作品卡片：封面 + 多页/AI 角标 + 标题 + 画师 + 收藏数 */
@Composable
private fun IllustCard(d: PixivIllust, opening: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box {
            CoverImage(
                url = PixivClient.coverUrl(d),
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                corner = 10.dp,
                downloadable = true,   // 长按直接保存封面
                onClick = onClick
            )
            // 多页角标
            if (d.pageCount > 1) {
                Text(
                    "${d.pageCount}页",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            // AI 作品角标（illust_ai_type: 2=AI生成 3=AI辅助）
            if (d.illustAiType >= 2) {
                Text(
                    "AI",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            // 详情加载中指示
            if (opening) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp, color = Color.White)
                }
            }
        }
        Text(
            d.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            buildString {
                append(d.user?.name ?: "")
                if (d.totalBookmarks > 0) append(" · ${d.totalBookmarks} 收藏")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
