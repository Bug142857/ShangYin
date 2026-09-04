package com.shangyin.app.ui.celebrity

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.Category
import com.shangyin.app.data.Repo
import com.shangyin.app.data.douban.CelebrityWork
import com.shangyin.app.data.douban.DoubanCelebrityDetail
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.data.douban.DoubanPhoto
import com.shangyin.app.data.douban.DoubanResult
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.DoubanRating
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 影人详情页（从条目详情的演职员列表点入）
 *  fromCategory：来源类别代号 book/game/film 或空 —— 决定作品列表的数据源与过滤
 *  passedName / passedAvatar：上游（图书/游戏作者卡片）已经搜索命中的真实名字和头像，
 *      作为 detail HTTP 失败时的兜底；图书/游戏作品搜索直接优先用 passedName，避免搜错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CelebrityScreen(
    nav: NavHostController,
    celebrityId: String,
    fromCategory: String = "",
    passedName: String = "",
    passedAvatar: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember(celebrityId) { mutableStateOf<DoubanCelebrityDetail?>(null) }
    var allWorks by remember(celebrityId) { mutableStateOf<List<CelebrityWork>>(emptyList()) }
    var showAllWorks by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("rating") }
    var photos by remember(celebrityId) { mutableStateOf<List<DoubanPhoto>>(emptyList()) }
    // 图片查看器
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var worksLoading by remember(celebrityId) { mutableStateOf(true) }
    var showAllPhotos by remember { mutableStateOf(false) }

    // URL decode 传参
    val decodedName = remember(passedName) {
        runCatching { java.net.URLDecoder.decode(passedName, "UTF-8") }.getOrDefault(passedName)
    }.ifBlank { null }
    val decodedAvatar = remember(passedAvatar) {
        runCatching { java.net.URLDecoder.decode(passedAvatar, "UTF-8") }.getOrDefault(passedAvatar)
    }.ifBlank { null }

    // 显示用：优先上游传的姓名，再用 detail.name（接口失败时兜底 "影人"）
    val displayName: String = decodedName
        ?: detail?.name?.takeIf { it.isNotBlank() && it != "影人" }
        ?: "影人"
    // 头像：优先上游传的
    val displayAvatar: String? = decodedAvatar
        ?: detail?.avatarUrl?.takeIf { !it.isNullOrBlank() }

    // 显示用中文类别 & 数据源代号
    val catLabel = when (fromCategory) {
        "book" -> "图书"
        "game" -> "游戏"
        "film", "电影", "剧集", "影视" -> "影视"
        else -> ""
    }
    val catSource = when (fromCategory) {
        "图书" -> "book"
        "游戏" -> "game"
        "电影", "剧集", "影视" -> "film"
        else -> fromCategory
    }

    // 详情（独立HTTP，失败则保持 null，界面用 passedName/passedAvatar 兜底）
    LaunchedEffect(celebrityId) {
        detail = runCatching { DoubanClient.fetchCelebrityDetail(celebrityId) }.getOrNull()
    }

    // 相关照片
    LaunchedEffect(celebrityId) {
        photos = DoubanClient.fetchCelebrityPhotos(celebrityId)
    }

    // 作品列表（数据源按来源类别分支）：
    // - 图书作者：优先用 passedName（从作者卡片直接传的）搜图书著作，
    //            再 fallback 到 detail.name，绝对禁止使用兜底占位 "影人" 作为关键词
    // - 游戏作者：同上，用游戏搜索页按开发商名字搜
    // - 其它：celebrity works API + 过滤
    LaunchedEffect(celebrityId, sortBy, catSource, decodedName) {
        worksLoading = true
        // 等 detail.name 非占位的有效值（最多 6 秒），或者 decodedName 已经有就不用等
        val searchName: String = run {
            var n = decodedName?.takeIf { it.isNotBlank() }.orEmpty()
            if (n.isNotBlank()) return@run n
            var attempts = 0
            while (attempts < 60) {
                val dn = detail?.name?.takeIf { it.isNotBlank() && it != "影人" }
                if (!dn.isNullOrBlank()) {
                    n = dn; break
                }
                delay(100); attempts++
            }
            n
        }
        allWorks = when {
            catSource == "book" -> {
                val books = DoubanClient.fetchAuthorBooks(searchName)
                if (sortBy == "rating") books.sortedWith(compareByDescending { it.rating ?: 0f })
                else books.sortedWith(compareByDescending { it.year.toIntOrNull() ?: 0 })
            }
            catSource == "game" -> {
                val games = DoubanClient.fetchDeveloperGames(searchName)
                if (sortBy == "rating") games.sortedWith(compareByDescending { it.rating ?: 0f })
                else games.sortedWith(compareByDescending { it.year.toIntOrNull() ?: 0 })
            }
            else -> {
                val raw = DoubanClient.fetchCelebrityWorks(celebrityId, sortBy, 0, 50)
                if (sortBy == "rating") raw.sortedWith(compareByDescending { it.rating ?: 0f })
                else raw.sortedWith(compareByDescending { it.year.toIntOrNull() ?: 0 })
            }
        }
        worksLoading = false
    }

    // 影视来源时过滤掉书/游戏/音乐混合项；其它来源数据源本身已对类
    val filteredWorks: List<CelebrityWork> = if (catSource == "film") {
        allWorks.filter { it.type !in listOf("book", "game", "music", "album") }
    } else {
        allWorks
    }
    // 详情里的初始作品（横滑预览）
    val detailPreview: List<CelebrityWork> = when {
        catSource == "book" -> filteredWorks
        catSource == "game" -> emptyList()
        else -> detail?.works.orEmpty().filter { it.type !in listOf("book", "game", "music", "album") }
    }

    // 相关照片的全部URL列表（大图像顺序）
    val allPhotoUrls: List<String> = photos.mapNotNull { it.largeUrl ?: it.normalUrl }
    // 顶部头像 + 相关照片 的合成索引（viewerIndex = 0 是头像，1+ 是相关照片）
    val galleryUrls: List<String> = listOfNotNull(
        DoubanClient.largeImageUrl(displayAvatar) ?: displayAvatar
    ) + allPhotoUrls

    // 内部跳转：把影人作品导入应用后导航到详情
    fun openWorkInternal(work: CelebrityWork) {
        scope.launch {
            val cat = when (work.type) {
                "book" -> Category.BOOK
                "music", "album" -> Category.MUSIC
                "game" -> Category.GAME
                else -> if ("/tv/" in workDoubanUrl(work)) Category.TV else Category.MOVIE
            }
            val result = DoubanResult(
                category = cat,
                doubanId = work.id,
                title = work.title,
                subTitle = work.roles,
                year = work.year,
                coverUrl = work.coverUrl,
                url = workDoubanUrl(work),
                rating = work.rating
            )
            runCatching { Repo.saveFromDouban(result) }
                .onSuccess { id ->
                    if (id > 0) nav.safeNavigate("item/$id")
                    else Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(context, "加载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    // 系统返回键：先关"全部"覆盖页，再退出影人页
    BackHandler(enabled = showAllPhotos || showAllWorks) {
        showAllPhotos = false
        showAllWorks = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            showAllPhotos -> showAllPhotos = false
                            showAllWorks -> showAllWorks = false
                            else -> nav.safePopBackStack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        val d = detail
        // ---------- 全部照片页面（LazyVerticalGrid 自身滚动，严禁外套 verticalScroll，否则无限高约束崩溃） ----------
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
                        "全部照片(${allPhotoUrls.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (allPhotoUrls.isEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Text(
                            "暂无相关照片",
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 30.dp)
                        )
                    }
                } else {
                    itemsIndexed(allPhotoUrls, key = { i, _ -> "ap$i" }) { idx, url ->
                        CoverImage(
                            url = url,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clickable { viewerIndex = idx + 1 /* 第0位是头像，相关照片从1开始 */ }
                        )
                    }
                }
            }
            return@Scaffold
        }

        // ---------- 全部作品列表页 ----------
        if (showAllWorks) {
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                        Text(
                            "全部作品" + if (catLabel.isNotBlank()) "（${catLabel}类）" else "",
                            style = MaterialTheme.typography.titleMedium
                        )
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Text(if (sortBy == "rating") "按评分" else "按年份")
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("按评分排序") },
                                onClick = { sortBy = "rating"; sortMenuOpen = false }
                            )
                            DropdownMenuItem(
                                text = { Text("按年份排序") },
                                onClick = { sortBy = "year"; sortMenuOpen = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                filteredWorks.forEach { work ->
                    CelebrityWorkRow(work, onClick = { openWorkInternal(work) })
                    Spacer(Modifier.height(10.dp))
                }
                if (filteredWorks.isEmpty() && !worksLoading) {
                    Text(
                        when (catSource) {
                            "game" -> "暂未搜到该开发者的相关游戏（豆瓣未提供按人查游戏接口）"
                            else -> "暂无${if (catLabel.isNotBlank()) catLabel else "相关"}作品数据"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 40.dp)
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 头部：头像（可点击放大） + 名字 + 职业/代表作
            Row {
                CoverImage(
                    url = displayAvatar,
                    modifier = Modifier
                        .width(110.dp)
                        .height(150.dp)
                        .clickable(enabled = !displayAvatar.isNullOrBlank()) { viewerIndex = 0 }
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text(displayName, style = MaterialTheme.typography.titleLarge)
                    if (d?.latinName?.isNotBlank() == true) {
                        Text(
                            d.latinName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (d?.shortInfo?.isNotBlank() == true) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            d.shortInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 详细信息（仅 detail 成功时显示）
            if (d?.infoPairs?.isNotEmpty() == true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.infoPairs.forEach { (label, value) ->
                        Row {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.width(96.dp)
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 相关作品（前 10 条横滑 + 查看全部）
            if (detailPreview.isNotEmpty()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "相关作品" + if (catLabel.isNotBlank()) "（${catLabel}）" else "",
                            style = MaterialTheme.typography.titleSmall
                        )
                        TextButton(onClick = { showAllWorks = true }) {
                            Text("查看全部(${filteredWorks.size})")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(detailPreview, key = { it.id }) { work ->
                            CelebrityWorkCard(work) { openWorkInternal(work) }
                        }
                    }
                }
            } else if (worksLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "正在加载${catLabel}相关作品…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 相关照片（横滑 + 查看全部；头像单张+照片全部可在查看器左右滑动）
            if (photos.isNotEmpty()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("相关照片", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { showAllPhotos = true }) {
                            Text("查看全部(${photos.size})")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(photos, key = { "cp${it.id}" }) { p ->
                            val order = galleryUrls.indexOfFirst { u ->
                                u == p.largeUrl || u == p.normalUrl
                            }.let { if (it < 0) 1 else it }
                            CoverImage(
                                url = p.normalUrl ?: p.largeUrl,
                                modifier = Modifier
                                    .width(130.dp)
                                    .height(98.dp)
                                    .clickable { viewerIndex = order }
                            )
                        }
                    }
                }
            }

        }
    }

    // 全屏图片浏览器：头像+相关照片同一画廊，左右大幅度滑动翻页、双指缩放/双击放大、单击关闭
    val viewer = viewerIndex
    if (viewer != null && galleryUrls.isNotEmpty()) {
        PhotoViewerDialog(
            urls = galleryUrls,
            initialIndex = viewer,
            onDismiss = { viewerIndex = null }
        )
    }
}

/** 构造豆瓣作品详情 URL */
private fun workDoubanUrl(work: CelebrityWork): String {
    return when (work.type) {
        "book" -> "https://book.douban.com/subject/${work.id}/"
        "music", "album" -> "https://music.douban.com/subject/${work.id}/"
        "game" -> "https://www.douban.com/game/${work.id}/"
        else -> "https://movie.douban.com/subject/${work.id}/"
    }
}

/** 影人作品卡片（横滑用） */
@Composable
private fun CelebrityWorkCard(work: CelebrityWork, onClick: () -> Unit) {
    Column(Modifier.width(96.dp).clickable(onClick = onClick)) {
        CoverImage(
            url = work.coverUrl,
            modifier = Modifier.width(96.dp).height(134.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            work.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (work.year.isNotBlank()) {
                Text(
                    work.year,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(4.dp))
            }
            DoubanRating(work.rating)
        }
        if (work.roles.isNotBlank()) {
            Text(
                work.roles,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 影人作品行（全部列表用） */
@Composable
private fun CelebrityWorkRow(work: CelebrityWork, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = work.coverUrl,
            modifier = Modifier.width(64.dp).height(90.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                work.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (work.year.isNotBlank()) {
                    Text(
                        work.year,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.width(6.dp))
                }
                DoubanRating(work.rating)
            }
            if (work.roles.isNotBlank()) {
                Text(
                    work.roles,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
