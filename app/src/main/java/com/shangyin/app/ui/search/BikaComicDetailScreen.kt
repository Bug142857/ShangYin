package com.shangyin.app.ui.search

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.bika.BikaChapter
import com.shangyin.app.data.bika.BikaClient
import com.shangyin.app.data.bika.BikaComic
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch

/**
 * 哔咔漫画详情页：封面/简介/标签/章节列表，点章节取全部图片后全屏翻页阅读。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BikaComicDetailScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var comic by remember { mutableStateOf<BikaComic?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var chapters by remember { mutableStateOf<List<BikaChapter>>(emptyList()) }
    var chaptersLoading by remember { mutableStateOf(true) }

    var loadingEp by remember { mutableStateOf<Int?>(null) }   // 正在取图的章节 order
    var viewerUrls by remember { mutableStateOf<List<String>?>(null) }

    /** 登录失效已由 BikaClient.withAuth 自动重新注册，二次失败提示返回 */
    fun handleAuthError() {
        Toast.makeText(context, "哔咔账号异常，请重新进入", Toast.LENGTH_LONG).show()
        nav.safePopBackStack()
    }

    // 详情 + 章节并行加载，互不影响
    LaunchedEffect(id) {
        scope.launch {
            runCatching { BikaClient.withAuth { t -> BikaClient.fetchComicDetail(t, id) } }
                .onSuccess { comic = it }
                .onFailure {
                    if (it is BikaClient.BikaAuthException) handleAuthError()
                    else detailError = it.message ?: "加载失败"
                }
        }
        scope.launch {
            runCatching { BikaClient.withAuth { t -> BikaClient.fetchChapters(t, id) } }
                .onSuccess { chapters = it }
                .onFailure { if (it is BikaClient.BikaAuthException) handleAuthError() }
            chaptersLoading = false
        }
    }

    /** 取某章节全部图片并打开阅读器 */
    fun readChapter(order: Int) {
        if (loadingEp != null) return
        scope.launch {
            loadingEp = order
            runCatching { BikaClient.withAuth { t -> BikaClient.fetchChapterImages(t, id, order) } }
                .onSuccess { urls ->
                    if (urls.isEmpty()) Toast.makeText(context, "该章节暂无图片", Toast.LENGTH_SHORT).show()
                    else viewerUrls = urls
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        if (it is BikaClient.BikaAuthException) "哔咔账号异常，请重新进入" else "获取图片失败：${it.message ?: "网络错误"}",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (it is BikaClient.BikaAuthException) handleAuthError()
                }
            loadingEp = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        comic?.title ?: "漫画详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        when {
            // 详情加载失败
            detailError != null && comic == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(pad).fillMaxSize().padding(32.dp)
            ) {
                Text(detailError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            // 加载中
            comic == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(pad).fillMaxSize()
            ) {
                CircularProgressIndicator()
            }
            else -> {
                val c = comic!!
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(pad).fillMaxSize()
                ) {
                    // 头部：封面 + 信息
                    item {
                        Row {
                            CoverImage(
                                url = c.thumbUrl,
                                modifier = Modifier.width(110.dp).height(147.dp),
                                corner = 10.dp
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (c.author.isNotBlank()) {
                                    Text(
                                        "作者：${c.author}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                if (c.chineseTeam.isNotBlank()) {
                                    Text(
                                        "汉化：${c.chineseTeam}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    buildString {
                                        val parts = mutableListOf<String>()
                                        parts.add(if (c.finished) "已完结" else "连载中")
                                        if (c.epsCount > 0) parts.add("${c.epsCount} 话")
                                        if (c.pagesCount > 0) parts.add("${c.pagesCount} 页")
                                        if (c.likes > 0) parts.add("${c.likes} 赞")
                                        if (c.views > 0) parts.add("${c.views} 看")
                                        append(parts.joinToString(" · "))
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                    // 分类 + 标签
                    if (c.categories.isNotEmpty() || c.tags.isNotEmpty()) {
                        item {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                (c.categories + c.tags).forEach { t ->
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            t,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 简介
                    if (c.description.isNotBlank()) {
                        item {
                            Text(
                                c.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 章节区
                    item {
                        Text(
                            when {
                                chaptersLoading -> "章节加载中…"
                                chapters.isEmpty() -> "本篇"
                                else -> "章节（${chapters.size}）"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!chaptersLoading && chapters.isEmpty()) {
                        // 无章节：单本漫画，直接开始阅读
                        item {
                            Button(
                                onClick = { if (loadingEp == null) readChapter(1) },
                                enabled = loadingEp == null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (loadingEp == null) "开始阅读" else "获取图片中…")
                            }
                        }
                    } else {
                        items(chapters.size) { i ->
                            val ch = chapters[i]
                            val reading = loadingEp == ch.order
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                onClick = { if (!reading) readChapter(ch.order) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        "第 ${ch.order} 话",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (!ch.title.isNullOrBlank()) {
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            ch.title!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (reading) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(
                                            "阅读",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
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

    // 全屏阅读器（可缩放/翻页/长按保存当前页）
    viewerUrls?.let { urls ->
        PhotoViewerDialog(urls = urls, initialIndex = 0, onDismiss = { viewerUrls = null })
    }
}
