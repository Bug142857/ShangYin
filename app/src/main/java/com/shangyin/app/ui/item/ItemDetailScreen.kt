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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ThumbUp
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 详情页实时数据缓存：从影人页返回时不重复请求。并发安全。 */
private object DetailCache {
    val celebrities = java.util.concurrent.ConcurrentHashMap<String, List<DoubanCelebrity>>()
    val videos = java.util.concurrent.ConcurrentHashMap<String, List<DoubanVideo>>()
    val photos = java.util.concurrent.ConcurrentHashMap<String, List<DoubanPhoto>>()
    val interests = java.util.concurrent.ConcurrentHashMap<String, List<DoubanInterest>>()

    /** 清空空结果缓存，解决"查不到再查也没有"的问题 */
    fun clearEmptyKeys() {
        listOf(celebrities, videos, photos, interests).forEach { map ->
            map.entries.filter { it.value.isEmpty() }.forEach { map.remove(it.key) }
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
                }
            )
        }
    ) { pad ->
        if (it_ == null) {
            Column(Modifier.padding(pad).fillMaxSize()) {}
            return@Scaffold
        }
        val entity = it_!!
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
            // 清空空结果缓存，解决"查不到再查也没有"
            DetailCache.clearEmptyKeys()
            coroutineScope {
                launch {
                    celebrities = DetailCache.celebrities[cacheKey] ?: run {
                        // 本地作者/开发商名兜底：详情接口失败时仍能搜索并显示带头像的可点击卡片
                        val fallback = entity.directors.split("/").map { it.trim() }.filter { it.isNotBlank() }
                        val v = com.shangyin.app.data.douban.DoubanClient.fetchCelebrities(cat, entity.doubanId, fallback)
                        if (v.isNotEmpty()) DetailCache.celebrities[cacheKey] = v
                        v
                    }
                }
                launch {
                    videos = DetailCache.videos[cacheKey] ?: run {
                        val v = com.shangyin.app.data.douban.DoubanClient.fetchTrailers(cat, entity.doubanId)
                        if (v.isNotEmpty()) DetailCache.videos[cacheKey] = v
                        v
                    }
                }
                launch {
                    photos = DetailCache.photos[cacheKey] ?: run {
                        val v = com.shangyin.app.data.douban.DoubanClient.fetchPhotos(cat, entity.doubanId)
                        if (v.isNotEmpty()) DetailCache.photos[cacheKey] = v
                        v
                    }
                }
                launch {
                    interests = DetailCache.interests[cacheKey] ?: run {
                        val v = com.shangyin.app.data.douban.DoubanClient.fetchInterests(cat, entity.doubanId)
                        if (v.isNotEmpty()) DetailCache.interests[cacheKey] = v
                        v
                    }
                }
            }
        }

        // 关键字段缺失时（快速保存/老数据），后台抓详情补全落库
        LaunchedEffect(entity.id) {
            if (entity.summary.isBlank() || entity.info.isBlank() ||
                (entity.directors.isBlank() && entity.casts.isBlank())
            ) {
                val cat = com.shangyin.app.data.Category.values().firstOrNull { it.label == entity.category }
                if (cat != null) {
                    runCatching {
                        val detail = com.shangyin.app.data.douban.DoubanClient.fetchDetail(cat, entity.doubanId)
                        if (!detail.isEmpty) {
                            Repo.updateItem(entity.copy(
                                title = detail.title ?: entity.title,
                                doubanRating = detail.rating ?: entity.doubanRating,
                                coverUrl = entity.coverUrl ?: detail.coverUrl,
                                summary = entity.summary.ifBlank { detail.summary.orEmpty() },
                                info = entity.info.ifBlank { detail.info.orEmpty() },
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

        // ---------- 全部演职员（网格，自身滚动；严禁外套 verticalScroll） ----------
        if (showAllCelebrities) {
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
                gridItems(celebrities, key = { "ac${it.id}" }) { c ->
                    CelebrityGridCard(c) { openCelebrity(c) }
                }
            }
            return@Scaffold
        }

        // ---------- 全部剧照 / 游戏截图（网格，自身滚动） ----------
        if (showAllPhotos) {
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clickable { openViewer(photoUrls, idx) }
                    )
                }
            }
            return@Scaffold
        }

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
                Box(
                    modifier = Modifier.clickable {
                        com.shangyin.app.data.douban.DoubanClient.largeImageUrl(entity.coverUrl)
                            ?.let { big -> openViewer(listOf(big), 0) }
                    }
                ) {
                    CoverImage(
                        url = entity.coverUrl,
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

            // 评价（点击编辑）
            ReviewSection(entity)

            // 基本信息（制片国家/上映时间/片长等）
            entity.info.takeIf { it.isNotBlank() }?.let { info ->
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
            if (entity.summary.isNotBlank()) {
                Column {
                    Text("简介", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        entity.summary,
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
                        items(celebrities, key = { it.id }) { c ->
                            CelebrityCard(c) { openCelebrity(c) }
                        }
                    }
                }
            } else if (entity.directors.isNotBlank() || entity.casts.isNotBlank()) {
                Column {
                    Text(
                        if (isBook) "作者/译者" else if (isGame) "开发商/平台" else "导演演员",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    if (entity.directors.isNotBlank()) {
                        Text(
                            if (isBook) "作者: ${entity.directors}" else if (isGame) "开发商: ${entity.directors}" else "导演: ${entity.directors}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entity.casts.isNotBlank()) {
                        Text(
                            if (isBook) "译者: ${entity.casts}" else if (isGame) "平台: ${entity.casts}" else "主演: ${entity.casts}",
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
                        items(videos, key = { "v${it.id}" }) { v ->
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
    }
}

/** 演职员卡片：头像 + 名字 + 导演/饰演角色 */
@Composable
private fun CelebrityCard(c: DoubanCelebrity, onClick: () -> Unit) {
    Column(
        Modifier.width(88.dp).clickable(onClick = onClick)
    ) {
        CoverImage(
            url = c.avatarUrl,
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
        modifier = Modifier
            .width(150.dp)
            .height(112.dp)
            .clickable(onClick = onClick),
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
