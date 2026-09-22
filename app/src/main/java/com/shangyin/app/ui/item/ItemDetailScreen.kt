package com.shangyin.app.ui.item

import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.Repo
import com.shangyin.app.data.douban.DoubanCelebrity
import com.shangyin.app.data.douban.DoubanInterest
import com.shangyin.app.data.douban.DoubanPhoto
import com.shangyin.app.data.douban.DoubanVideo
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.DoubanRating
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 详情页实时数据缓存：从影人页返回时不重复请求。并发安全。 */
private object DetailCache {
    val celebrities = java.util.concurrent.ConcurrentHashMap<String, List<DoubanCelebrity>>()
    val videos = java.util.concurrent.ConcurrentHashMap<String, List<DoubanVideo>>()
    val photos = java.util.concurrent.ConcurrentHashMap<String, List<DoubanPhoto>>()
    val interests = java.util.concurrent.ConcurrentHashMap<String, List<DoubanInterest>>()

    /** 清空空结果缓存，解决"查不到再查也没有"的问题
     *  使用迭代器安全删除，避免多 launch 并发时 entries 视图与 map 修改冲突 */
    fun clearEmptyKeys() {
        listOf(celebrities, videos, photos, interests).forEach { map ->
            val it = map.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.isEmpty()) it.remove()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(nav: NavHostController, itemId: Long) {
    val item by Repo.observeItem(itemId).collectAsStateWithLifecycle(initialValue = null)

    // "查看全部"覆盖页 + 全屏图片浏览器状态（提到 Scaffold 外，顶栏返回键也要访问）
    var showAllPhotos by remember(itemId) { mutableStateOf(false) }
    var showAllCelebrities by remember(itemId) { mutableStateOf(false) }
    var viewerUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    var showVodSearch by remember(itemId) { mutableStateOf(false) }
    // 添加到清单（分类）对话框
    var showAddToList by remember(itemId) { mutableStateOf(false) }

    // 系统返回键：先关"全部"覆盖页，再退出详情
    BackHandler(enabled = showAllPhotos || showAllCelebrities) {
        showAllPhotos = false
        showAllCelebrities = false
    }

    val it_ = item
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(it_?.title.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            showAllPhotos -> showAllPhotos = false
                            showAllCelebrities -> showAllCelebrities = false
                            else -> nav.safePopBackStack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 表世界条目：右上角显示收藏状态（已在清单=对号）/加号添加
                    val cur = it_
                    if (cur != null && cur.category != "番号" && cur.category != "动漫" &&
                        cur.category != "本子" && cur.category != "漫画"
                    ) {
                        CollectStatusAction(
                            itemId = cur.id,
                            onAdd = { showAddToList = true }
                        )
                    }
                }
            )
        }
    ) { pad ->
        if (it_ == null) {
            Column(Modifier.padding(pad).fillMaxSize()) {}
            return@Scaffold
        }
        val entity = it_!!

        // ---- 里世界条目：不走豆瓣详情 ----
        // 自动跳转只执行一次：rememberSaveable 状态在从播放器/漫画页返回时会恢复，
        // 若用普通 LaunchedEffect 重进本页会再次自动跳转，导致播放器永远退不出去（死循环）
        // 番号/本子跳转时都会先弹出本页，播放器/漫画按返回键直接回到来源列表
        var forwarded by rememberSaveable(entity.id) { mutableStateOf(false) }
        // 番号视频：直接恢复播放（doubanId = "srcId|vodId"）
        if (entity.category == "番号") {
            val context = LocalContext.current
            LaunchedEffect(entity.id) {
                if (forwarded) return@LaunchedEffect
                forwarded = true
                val parts = entity.doubanId.split("|")
                val vid = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size != 2 || vid == null || vid <= 0L) {
                    Toast.makeText(context, "条目数据异常，无法播放", Toast.LENGTH_SHORT).show()
                    nav.safePopBackStack()
                    return@LaunchedEffect
                }
                val src = com.shangyin.app.ui.settings.SettingsStore.getVodSources()
                    .firstOrNull { it.id == parts[0] }
                if (src == null) {
                    Toast.makeText(context, "片源「${entity.subTitle}」已被删除，无法播放", Toast.LENGTH_LONG).show()
                    nav.safePopBackStack()
                    return@LaunchedEffect
                }
                val ok = com.shangyin.app.ui.search.openVodAndPlay(
                    nav, context, src,
                    com.shangyin.app.data.vod.VodItem(vod_id = vid, vod_name = entity.title),
                    popCurrent = true
                )
                if (!ok) nav.safePopBackStack()
            }
            Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(60.dp))
                androidx.compose.material3.CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在打开播放…", style = MaterialTheme.typography.bodySmall)
            }
            return@Scaffold
        }
        // 本子：跳哔咔详情（单次原子导航弹出本页，NavGuard 节流会吞掉连续两次调用）
        if (entity.category == "本子") {
            LaunchedEffect(entity.id) {
                if (forwarded) return@LaunchedEffect
                forwarded = true
                val curId = nav.currentBackStackEntry?.destination?.id
                runCatching {
                    nav.navigate("bikaComic/${android.net.Uri.encode(entity.doubanId)}") {
                        curId?.let { popUpTo(it) { inclusive = true } }
                        launchSingleTop = true
                    }
                }
            }
            Column(Modifier.padding(pad).fillMaxSize()) {}
            return@Scaffold
        }

        // 漫画条目（Komiic）：doubanId = komiic 漫画 id，原子导航弹出本过渡页
        if (entity.category == "漫画") {
            LaunchedEffect(entity.id) {
                if (forwarded) return@LaunchedEffect
                forwarded = true
                val curId = nav.currentBackStackEntry?.destination?.id
                runCatching {
                    nav.navigate("comicDetail/${android.net.Uri.encode(entity.doubanId)}") {
                        curId?.let { popUpTo(it) { inclusive = true } }
                        launchSingleTop = true
                    }
                }
            }
            Column(Modifier.padding(pad).fillMaxSize()) {}
            return@Scaffold
        }

        // 动漫条目：跳动漫详情页（doubanId = "srcId|vodId"，与动漫页/详情页收藏同一口径）
        if (entity.category == "动漫") {
            val context = LocalContext.current
            LaunchedEffect(entity.id) {
                if (forwarded) return@LaunchedEffect
                forwarded = true
                val parts = entity.doubanId.split("|")
                val vid = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size != 2 || vid == null || vid <= 0L) {
                    Toast.makeText(context, "条目数据异常，无法打开", Toast.LENGTH_SHORT).show()
                    nav.safePopBackStack()
                    return@LaunchedEffect
                }
                val curId = nav.currentBackStackEntry?.destination?.id
                runCatching {
                    nav.navigate("animeDetail/${android.net.Uri.encode(parts[0])}/$vid") {
                        curId?.let { popUpTo(it) { inclusive = true } }
                        launchSingleTop = true
                    }
                }
            }
            Column(Modifier.padding(pad).fillMaxSize()) {}
            return@Scaffold
        }

        val cacheKey = "${entity.category}/${entity.doubanId}"

        var celebrities by remember(cacheKey) { mutableStateOf(DetailCache.celebrities[cacheKey].orEmpty()) }
        var videos by remember(cacheKey) { mutableStateOf(DetailCache.videos[cacheKey].orEmpty()) }
        var photos by remember(cacheKey) { mutableStateOf(DetailCache.photos[cacheKey].orEmpty()) }
        var interests by remember(cacheKey) { mutableStateOf(DetailCache.interests[cacheKey].orEmpty()) }
        var videoUrl by remember { mutableStateOf<String?>(null) }

        // 并行加载演职员/预告片/剧照/短评（实时抓取，不落库）
        LaunchedEffect(cacheKey) {
            val cat = com.shangyin.app.data.Category.values().firstOrNull { it.label == entity.category }
                ?: return@LaunchedEffect
            // 清空空结果缓存，解决"查不到再查也没有"；如果还是空就重新 fetch
            DetailCache.clearEmptyKeys()
            // 每个请求独立兜底：任一失败不取消其他请求（否则游戏截图/预告片失败会连带短评不显示）
            coroutineScope {
                launch {
                    celebrities = DetailCache.celebrities[cacheKey] ?: run {
                        val fallback = entity.directors.split("/").map { it.trim() }.filter { it.isNotBlank() }
                        val v = runCatching {
                            com.shangyin.app.data.douban.DoubanClient.fetchCelebrities(cat, entity.doubanId, fallback)
                        }.getOrDefault(emptyList())
                        if (v.isNotEmpty()) DetailCache.celebrities[cacheKey] = v
                        v
                    }
                }
                launch {
                    videos = DetailCache.videos[cacheKey] ?: run {
                        val v = runCatching {
                            com.shangyin.app.data.douban.DoubanClient.fetchTrailers(cat, entity.doubanId)
                        }.getOrDefault(emptyList())
                        if (v.isNotEmpty()) DetailCache.videos[cacheKey] = v
                        v
                    }
                }
                launch {
                    photos = DetailCache.photos[cacheKey] ?: run {
                        val v = runCatching {
                            com.shangyin.app.data.douban.DoubanClient.fetchPhotos(cat, entity.doubanId)
                        }.getOrDefault(emptyList())
                        if (v.isNotEmpty()) DetailCache.photos[cacheKey] = v
                        v
                    }
                }
                launch {
                    interests = DetailCache.interests[cacheKey] ?: run {
                        val v = runCatching {
                            com.shangyin.app.data.douban.DoubanClient.fetchInterests(cat, entity.doubanId)
                        }.getOrDefault(emptyList())
                        if (v.isNotEmpty()) DetailCache.interests[cacheKey] = v
                        v
                    }
                }
            }
        }

        // 关键字段缺失或信息过旧时（老数据只有年份没有月日），后台抓详情补全落库
        LaunchedEffect(entity.id) {
            val cat = com.shangyin.app.data.Category.values().firstOrNull { it.label == entity.category }
            if (cat != null) {
                val monthDayRe = Regex("""\d{4}[-/年.]\d{1,2}""")
                // 影视/游戏看 info 行，图书看头部 subTitle（图书不显示基本信息块）
                val dateLine = if (cat == com.shangyin.app.data.Category.BOOK) entity.subTitle else entity.info
                val needRefresh = entity.summary.isBlank() || entity.info.isBlank() ||
                    (entity.directors.isBlank() && entity.casts.isBlank()) ||
                    // 旧数据里可能存着带标签/带「类型: 制片国家/地区:」标签的原始文本（显示为乱码+拼接混乱），
                    // 这种也要重新抓一次换成清理后的内容
                    looksGarbled(entity.info) || looksGarbled(entity.summary) ||
                    (dateLine.isNotBlank() && !monthDayRe.containsMatchIn(dateLine))
                if (needRefresh) {
                    runCatching {
                        val detail = com.shangyin.app.data.douban.DoubanClient.fetchDetail(cat, entity.doubanId)
                        if (!detail.isEmpty) {
                            val freshInfo = detail.info?.takeIf { it.isNotBlank() }
                            Repo.updateItem(entity.copy(
                                title = detail.title ?: entity.title,
                                doubanRating = detail.rating ?: entity.doubanRating,
                                coverUrl = entity.coverUrl ?: detail.coverUrl,
                                summary = if (entity.summary.isBlank() || looksGarbled(entity.summary))
                                    detail.summary.orEmpty().ifBlank { entity.summary }
                                else entity.summary,
                // 影视/游戏：info 行直接替换为含完整日期的新内容；
                                // 图书：头部 subTitle 替换（基本信息块不显示）
                                info = if (cat == com.shangyin.app.data.Category.BOOK)
                                    entity.info.ifBlank { freshInfo.orEmpty() }
                                else freshInfo ?: entity.info,
                                // 图书/游戏：头部副标题也含完整日期；影视保持不变（重复）
                                subTitle = when (cat) {
                                    com.shangyin.app.data.Category.BOOK -> freshInfo ?: entity.subTitle
                                    com.shangyin.app.data.Category.GAME -> freshInfo ?: entity.subTitle
                                    else -> entity.subTitle
                                },
                                directors = entity.directors.ifBlank { detail.directors.orEmpty() },
                                casts = entity.casts.ifBlank { detail.casts.orEmpty() },
                                genres = entity.genres.ifBlank { detail.genres.orEmpty() }
                            ))
                        }
                    }
                }
            }
        }

        // 分类与派生数据
        val isBook = entity.category == "图书"
        val isGame = entity.category == "游戏"
        val photoUrls = photos.mapNotNull { it.largeUrl ?: it.normalUrl }
        val celebTitle = if (isBook) "作者/译者" else if (isGame) "开发商/平台" else "演职员"
        val photoTitle = if (isGame) "游戏截图" else "剧照"

        // 打开全屏图片浏览器（urls + 起始页）
        fun openViewer(urls: List<String>, index: Int) {
            if (urls.isEmpty()) return
            viewerUrls = urls
            viewerIndex = index.coerceIn(0, urls.size - 1)
        }

        // 跳转影人详情（伪 ID 含下划线，豆瓣无人物页，不可点）
        fun openCelebrity(c: DoubanCelebrity) {
            if (c.id.contains("_")) return
            val fromCat = when (entity.category) {
                "图书" -> "book"
                "游戏" -> "game"
                else -> "film"
            }
            val encName = java.net.URLEncoder.encode(c.name, "UTF-8")
            val encAvatar = java.net.URLEncoder.encode(c.avatarUrl.orEmpty(), "UTF-8")
            nav.safeNavigate("celebrity/${c.id}/$fromCat/$encName/$encAvatar")
        }

        // 用 when 分支切换覆盖页与主内容（避免 return@Scaffold 导致重组不生效）
        when {
            // ---------- 全部演职员（网格，自身滚动） ----------
            showAllCelebrities -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(span = { GridItemSpan(3) }) {
                        Text("$celebTitle(${celebrities.size})", style = MaterialTheme.typography.titleMedium)
                    }
                    // key 带上下标：影视演职员里同一个人可能同时是导演和演员（id 相同），
                    // 用 id 当 key 会因重复 key 直接崩溃
                    gridItemsIndexed(celebrities, key = { i, c -> "ac$i${c.id}" }) { i, c ->
                        CelebrityGridCard(c) { openCelebrity(c) }
                    }
                }
            }
            // ---------- 全部剧照 / 游戏截图（网格，自身滚动） ----------
            showAllPhotos -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item(span = { GridItemSpan(3) }) {
                        Text(
                            "全部$photoTitle(${photoUrls.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    gridItemsIndexed(photoUrls, key = { i, _ -> "ap$i" }) { idx, url ->
                        CoverImage(
                            url = url,
                            onClick = { openViewer(photoUrls, idx) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                        )
                    }
                }
            }
            // ---------- 主内容 ----------
            else -> {
                Column(
                    Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
            // 头部：封面（点击查看大图） + 基本信息
            Row {
                Box {
                    CoverImage(
                        url = entity.coverUrl,
                        onClick = {
                            com.shangyin.app.data.douban.DoubanClient.largeImageUrl(entity.coverUrl)
                                ?.let { big -> openViewer(listOf(big), 0) }
                        },
                        modifier = Modifier.width(110.dp).height(154.dp)
                    )
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(entity.title, style = MaterialTheme.typography.titleLarge)
                    if (entity.subTitle.isNotBlank()) {
                        Text(
                            entity.subTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (entity.genres.isNotBlank()) {
                        Text(
                            entity.genres,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    entity.doubanRating?.let { rating ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DoubanRating(rating)
                        }
                    }
                }
            }

            // 在线观影（仅影视条目显示）
            if (entity.category == "电影" || entity.category == "剧集") {
                OutlinedButton(
                    onClick = { showVodSearch = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("在线观看")
                }
            }

            // 评价（点击编辑）
            ReviewSection(entity)

            // 基本信息（制片国家/上映时间/片长等）；图书头部已显示作者等信息，不再重复
            val cleanInfo = com.shangyin.app.data.douban.DoubanClient.cleanText(entity.info)
            cleanInfo?.takeIf { it.isNotBlank() && !isBook }?.let { info ->
                Column {
                    Text("基本信息", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        info,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 简介
            val cleanSummary = com.shangyin.app.data.douban.DoubanClient.cleanText(entity.summary)
            if (!cleanSummary.isNullOrBlank()) {
                Column {
                    Text("简介", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        cleanSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 演职员/作者/开发商（带头像 + 饰演，可点击进影人详情）；无数据时回退纯文本
            if (celebrities.isNotEmpty()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(celebTitle, style = MaterialTheme.typography.titleSmall)
                        if (celebrities.size > 6) {
                            TextButton(onClick = { showAllCelebrities = true }) {
                                Text("查看全部(${celebrities.size})")
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(celebrities, key = { i, c -> "c$i${c.id}" }) { _, c ->
                            CelebrityCard(c) { openCelebrity(c) }
                        }
                    }
                }
            } else if (!isGame && (entity.directors.isNotBlank() || entity.casts.isNotBlank())) {
                Column {
                    Text(
                        if (isBook) "作者/译者" else "导演演员",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    if (entity.directors.isNotBlank()) {
                        Text(
                            if (isBook) "作者: ${entity.directors}" else "导演: ${entity.directors}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entity.casts.isNotBlank()) {
                        Text(
                            if (isBook) "译者: ${entity.casts}" else "主演: ${entity.casts}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 剧照 / 游戏截图（点击进全屏浏览器，可左右滑动翻页）
            if (photos.isNotEmpty()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(photoTitle, style = MaterialTheme.typography.titleSmall)
                        if (photos.size > 6) {
                            TextButton(onClick = { showAllPhotos = true }) {
                                Text("查看全部(${photos.size})")
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(photos, key = { _, p -> "p${p.id}" }) { idx, p ->
                            PhotoCard(p) { openViewer(photoUrls, idx) }
                        }
                    }
                }
            }

            // 预告片（游戏无）
            if (videos.isNotEmpty()) {
                Column {
                    Text("预告片", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(videos, key = { i, v -> "v$i${v.id}" }) { _, v ->
                            VideoCard(v) { videoUrl = v.videoUrl }
                        }
                    }
                }
            }

            // 网友短评
            if (interests.isNotEmpty()) {
                Column {
                    Text("短评", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    interests.forEach { cmt ->
                        InterestItem(cmt)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
                }
            } // end else
            } // end when

        // 全屏图片浏览器：剧照/截图可左右大幅度滑动翻页，双指缩放、双击放大、单击关闭
        if (viewerUrls.isNotEmpty()) {
            PhotoViewerDialog(
                urls = viewerUrls,
                initialIndex = viewerIndex,
                onDismiss = { viewerUrls = emptyList() }
            )
        }

        // 预告片本地播放
        videoUrl?.let { url ->
            VideoPlayerDialog(url, onClose = { videoUrl = null })
        }

        // 在线观影弹层（按标题搜片源）
        if (showVodSearch) {
            VodSearchSheet(
                nav = nav,
                itemId = entity.id,
                title = entity.title,
                year = entity.year,
                onDismiss = { showVodSearch = false }
            )
        }

        // 添加到清单对话框（表世界条目）
        if (showAddToList) {
            AddToListDialog(
                item = entity,
                onDismiss = { showAddToList = false }
            )
        }
    }
}

/**
 * 是否是「脏」文本：带 HTML 标签，或带豆瓣网页那种「类型: / 制片国家/地区:」标签串。
 * 这类内容显示出来就是乱码 + 拼接混乱，需要重抓详情替换掉。
 */
private fun looksGarbled(s: String?): Boolean {
    if (s.isNullOrBlank()) return false
    if (s.contains('<') || s.contains('>') ||
        s.contains("类型:") || s.contains("类型：") || s.contains("制片国家") ||
        s.contains("上映日期:") || s.contains("上映日期：") || s.contains("片长:")
    ) return true
    // 整页文本兜底的产物（游戏页曾被这样污染）：导航/页脚文字被当成「基本信息」
    val junk = listOf("下载豆瓣客户端", "话题广场", "豆瓣社区", "浏览发现", "豆品", "登录 / 注册")
    return junk.count { s.contains(it) } >= 2
}

/** 顶栏收藏状态：已在任意清单 → 对号（点按提示所在清单）；未收藏 → 加号打开添加对话框 */
@Composable
private fun CollectStatusAction(itemId: Long, onAdd: () -> Unit) {
    val context = LocalContext.current
    val memberships by Repo.observeMemberships(itemId).collectAsStateWithLifecycle(initialValue = emptyList())
    val lists by Repo.observeAllLists().collectAsStateWithLifecycle(initialValue = emptyList())
    val names = remember(memberships, lists) {
        memberships.mapNotNull { m -> lists.firstOrNull { it.id == m }?.name }
    }
    if (names.isEmpty()) {
        IconButton(onClick = onAdd) {
            Icon(Icons.Rounded.Add, contentDescription = "添加到分类")
        }
    } else {
        IconButton(onClick = {
            Toast.makeText(context, "已收藏在 ${names.joinToString("、")}", Toast.LENGTH_SHORT).show()
        }) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "已收藏",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 表世界条目"添加到分类"对话框：列出全部清单单选加入 */
@Composable
private fun AddToListDialog(item: com.shangyin.app.data.db.CollectionItemEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lists by Repo.observeAllLists().collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedId by remember(lists) { mutableStateOf(lists.firstOrNull()?.id ?: -1L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到分类") },
        text = {
            if (lists.isEmpty()) {
                Text(
                    "还没有清单，请先到 设置 → 清单管理 创建",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "把《${item.title}》放到哪个分类？",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    lists.forEach { l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedId == l.id,
                                onClick = { selectedId = l.id }
                            )
                            Text(l.name, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedId > 0,
                onClick = {
                    scope.launch {
                        Repo.addItemToList(selectedId, item.id)
                        Toast.makeText(context, "已添加《${item.title}》", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 演职员卡片：头像 + 名字 + 导演/饰演角色 */
@Composable
private fun CelebrityCard(c: DoubanCelebrity, onClick: () -> Unit) {
    Column(
        Modifier.width(88.dp).clickable(onClick = onClick)
    ) {
        CoverImage(
            url = c.avatarUrl,
            onClick = onClick,
            modifier = Modifier.width(88.dp).height(124.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            c.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (c.role.isNotBlank()) {
            Text(
                c.role,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 演职员网格卡片（"查看全部"页用）：头像铺满列宽 + 名字 + 角色 */
@Composable
private fun CelebrityGridCard(c: DoubanCelebrity, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        CoverImage(
            url = c.avatarUrl,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            c.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (c.role.isNotBlank()) {
            Text(
                c.role,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 预告片卡片：封面 + 播放按钮 + 类型角标 + 时长 */
@Composable
private fun VideoCard(v: DoubanVideo, onClick: () -> Unit) {
    Box(
        Modifier
            .width(200.dp)
            .height(112.dp)
            .clickable(onClick = onClick)
    ) {
        CoverImage(
            url = v.coverUrl,
            modifier = Modifier.matchParentSize(),
            corner = 8.dp
        )
        Icon(
            Icons.Rounded.PlayArrow,
            contentDescription = "播放",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.Center)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.large)
                .padding(4.dp)
        )
        Text(
            v.typeName,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color(0xFFE8912D), MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        if (v.runtime.isNotBlank()) {
            Text(
                v.runtime,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.55f), MaterialTheme.shapes.small)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** 剧照缩略卡片 */
@Composable
private fun PhotoCard(p: DoubanPhoto, onClick: () -> Unit) {
    CoverImage(
        url = p.normalUrl,
        onClick = onClick,
        modifier = Modifier
            .width(150.dp)
            .height(112.dp),
        corner = 8.dp
    )
}

/** 网友短评：头像/昵称/评分/时间地点/内容/有用数 */
@Composable
private fun InterestItem(cmt: DoubanInterest) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                url = cmt.avatarUrl,
                modifier = Modifier.size(32.dp),
                corner = 16.dp
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cmt.userName,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(120.dp)
                    )
                    DoubanRating(cmt.rating)
                }
                val meta = listOf(cmt.date, cmt.location).filter { it.isNotBlank() }.joinToString("  ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            cmt.comment,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ThumbUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (cmt.votes > 0) "${cmt.votes}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 评价：默认只显示，点击才出输入框 */
@Composable
private fun ReviewSection(entity: com.shangyin.app.data.db.CollectionItemEntity) {
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable(entity.id) { mutableStateOf(false) }
    var text by rememberSaveable(entity.id, entity.note) { mutableStateOf(entity.note) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("评价", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = {
                if (editing) {
                    // 保存
                    scope.launch { Repo.updateItem(entity.copy(note = text)) }
                    editing = false
                } else {
                    editing = true
                }
            }) {
                Text(if (editing) "保存" else if (entity.note.isBlank()) "写评价" else "编辑")
            }
        }
        Spacer(Modifier.height(6.dp))
        if (editing) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("写下此刻的感受…") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (entity.note.isNotBlank()) {
            Text(
                entity.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                "还没写评价，点右上角「写评价」",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 预告片本地播放 Dialog：用 Android VideoView + MediaPlayer */
@Composable
private fun VideoPlayerDialog(videoUrl: String, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoURI(videoUrl.toUri())
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            start()
                        }
                        setOnErrorListener { _, what, extra ->
                            Toast.makeText(ctx, "视频加载失败", Toast.LENGTH_SHORT).show()
                            onClose()
                            true
                        }
                    }
                },
                update = { vv ->
                    vv.start()
                },
                modifier = Modifier.fillMaxSize()
            )
            // 关闭按钮
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.large)
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "关闭",
                    tint = Color.White
                )
            }
            DisposableEffect(videoUrl) {
                onDispose {
                    // 停止播放
                }
            }
        }
    }
}
