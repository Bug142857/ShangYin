package com.shangyin.app.ui.download

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.download.ComicDownloadManager
import com.shangyin.app.data.download.ComicDownloadStore
import com.shangyin.app.data.download.DownloadedComic
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.safePopBackStack

/**
 * 我的下载：进行中的任务 + 已下载的漫画/本子（离线阅读、单章删除、整套删除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(nav: NavHostController) {
    val context = LocalContext.current
    val library by ComicDownloadManager.library.collectAsStateWithLifecycle()
    val active by ComicDownloadManager.active.collectAsStateWithLifecycle()

    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewer by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(Unit) { ComicDownloadManager.refresh(context) }

    /** 先按当前活跃任务做一次快照（Map 顺序稳定，避免下标越界） */
    val tasks = active.values.toList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的下载") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (tasks.isNotEmpty()) {
                        TextButton(onClick = { ComicDownloadManager.cancelAll() }) { Text("全部取消") }
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad).fillMaxSize()
        ) {
            // 下载目录（存储位置）设置
            item { DownloadDirCard() }

            if (tasks.isEmpty() && library.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 80.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("还没有下载内容", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "在漫画 / 本子详情页点「下载全部」或章节右侧的下载按钮",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (tasks.isNotEmpty()) {
                item {
                    Text("下载中（${tasks.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                items(tasks, key = { "${it.source}/${it.id}/${it.chapterKey}" }) { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "${t.title} · ${t.chapterName}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            if (t.error != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "失败：${t.error}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(onClick = {
                                        ComicDownloadManager.dismiss(t.source, t.id, t.chapterKey)
                                    }) { Text("移除") }
                                }
                            } else if (t.total <= 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("获取图片列表…", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                LinearProgressIndicator(
                                    progress = { t.done.toFloat() / t.total },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(4.dp))
                                Row {
                                    Text(
                                        "${t.done}/${t.total} 张",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        ComicDownloadManager.cancel(t.source, t.id, t.chapterKey)
                                    }) { Text("取消") }
                                }
                            }
                        }
                    }
                }
            }

            if (library.isNotEmpty()) {
                item {
                    Text("已下载（${library.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                items(library.size, key = { "${library[it].source}/${library[it].id}" }) { i ->
                    val comic = library[i]
                    ComicDownloadRow(
                        comic = comic,
                        expanded = "${comic.source}/${comic.id}" in expanded,
                        onToggle = {
                            val k = "${comic.source}/${comic.id}"
                            expanded = if (k in expanded) expanded - k else expanded + k
                        },
                        onDeleteComic = {
                            ComicDownloadManager.deleteComic(context, comic.source, comic.id)
                            Toast.makeText(context, "已删除《${comic.title}》全部下载", Toast.LENGTH_SHORT).show()
                        },
                        onReadChapter = { key ->
                            val pages = ComicDownloadStore.chapterPages(context, comic.source, comic.id, key)
                            if (pages.isEmpty()) Toast.makeText(context, "该章节本地文件缺失", Toast.LENGTH_SHORT).show()
                            else viewer = pages.map { "file://$it" }
                        },
                        onDeleteChapter = { key ->
                            ComicDownloadManager.deleteChapter(context, comic.source, comic.id, key)
                            Toast.makeText(context, "已删除该章下载", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    viewer?.let { urls ->
        PhotoViewerDialog(urls = urls, initialIndex = 0, onDismiss = { viewer = null })
    }
}

/** 单本已下载漫画：封面 + 标题 + 大小，展开后逐章离线阅读 */
@Composable
private fun ComicDownloadRow(
    comic: DownloadedComic,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDeleteComic: () -> Unit,
    onReadChapter: (String) -> Unit,
    onDeleteChapter: (String) -> Unit
) {
    val context = LocalContext.current
    val size = remember(comic) { ComicDownloadStore.size(context, comic.source, comic.id) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(width = 54.dp, height = 72.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                ) {
                    if (!comic.cover.isNullOrBlank()) {
                        AsyncImage(
                            model = comic.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        comic.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${if (comic.source == "bika") "本子" else "漫画"} · 已下载 ${comic.chapters.size} 章 · ${ComicDownloadStore.formatSize(size)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onDeleteComic() }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "删除整本下载")
                }
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                comic.chapters.sortedBy { it.name }.forEach { ch ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onReadChapter(ch.key) }
                            .padding(start = 78.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            ch.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${ch.count} 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = { onDeleteChapter(ch.key) }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "删除该章",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/** 下载目录：只作文字说明（应用私有目录，无需存储权限，卸载即清） */
@Composable
private fun DownloadDirCard() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("下载目录", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "图片保存在应用私有目录（无需存储权限，卸载应用时自动清除）：\n${ComicDownloadStore.root(context).absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}