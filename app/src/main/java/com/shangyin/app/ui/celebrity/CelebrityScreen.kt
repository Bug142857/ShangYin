package com.shangyin.app.ui.celebrity

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.Category
import com.shangyin.app.data.Repo
import com.shangyin.app.data.douban.CelebrityWork
import com.shangyin.app.data.douban.DoubanCelebrityDetail
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.data.douban.DoubanResult
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.DoubanRating
import kotlinx.coroutines.launch

/** 影人详情页（从条目详情的演职员列表点入） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CelebrityScreen(nav: NavHostController, celebrityId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var detail by remember(celebrityId) { mutableStateOf<DoubanCelebrityDetail?>(null) }
    var allWorks by remember(celebrityId) { mutableStateOf<List<CelebrityWork>>(emptyList()) }
    var showAllWorks by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf("rating") }

    LaunchedEffect(celebrityId) {
        detail = DoubanClient.fetchCelebrityDetail(celebrityId)
    }

    // 加载全部作品
    LaunchedEffect(celebrityId, sortBy) {
        val raw = DoubanClient.fetchCelebrityWorks(celebrityId, sortBy, 0, 50)
        // 客户端排序（后端 sort_by 参数不生效）
        allWorks = if (sortBy == "rating") {
            raw.sortedWith(compareByDescending { it.rating ?: 0f })
        } else {
            raw.sortedWith(compareByDescending { it.year.toIntOrNull() ?: 0 })
        }
    }

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
                    if (id > 0) nav.navigate("item/$id")
                    else Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(context, "加载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(detail?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showAllWorks) showAllWorks = false else nav.popBackStack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        val d = detail
        if (d == null) {
            Column(
                Modifier.padding(pad).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        // 全部作品列表页
        if (showAllWorks) {
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("全部作品", style = MaterialTheme.typography.titleMedium)
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
                allWorks.forEach { work ->
                    CelebrityWorkRow(work, onClick = { openWorkInternal(work) })
                    Spacer(Modifier.height(10.dp))
                }
                if (allWorks.isEmpty()) {
                    Text(
                        "暂无作品数据",
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
            // 头部：头像 + 名字 + 职业/代表作
            Row {
                CoverImage(
                    url = d.avatarUrl,
                    modifier = Modifier.width(110.dp).height(150.dp)
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleLarge)
                    if (d.latinName.isNotBlank()) {
                        Text(
                            d.latinName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (d.shortInfo.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            d.shortInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 详细信息
            if (d.infoPairs.isNotEmpty()) {
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
            if (d.works.isNotEmpty()) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("相关作品", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { showAllWorks = true }) {
                            Text("查看全部(${allWorks.size})")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(d.works, key = { it.id }) { work ->
                            CelebrityWorkCard(work) { openWorkInternal(work) }
                        }
                    }
                }
            }

            if (d.url.isNotBlank()) {
                TextButton(onClick = { uriHandler.openUri(d.url) }) {
                    Text("在豆瓣打开")
                }
            }
        }
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
