package com.shangyin.app.ui.download

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.ReadProgressStore
import com.shangyin.app.data.download.ComicDownloadManager
import com.shangyin.app.data.download.ComicDownloadStore
import com.shangyin.app.data.download.DownloadedComic
import com.shangyin.app.data.download.MediaFileStore
import com.shangyin.app.ui.common.PhotoViewerDialog
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 我的下载：进行中的任务 + 四类已下载资源（漫画 / 本子 / 音乐 / 书籍）。
 * 四类各自一个独立目录，每节展示数量与占用大小，目录说明可点击跳转到该文件夹。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(nav: NavHostController) {
    val context = LocalContext.current
    val library by ComicDownloadManager.library.collectAsStateWithLifecycle()
    val active by ComicDownloadManager.active.collectAsStateWithLifecycle()

    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 离线阅读：漫画 + 章节 key + 页面（支持"下一章"继续阅读已下载章节）
    var viewer by remember { mutableStateOf<Triple<DownloadedComic, String, List<String>>?>(null) }
    // 删除确认（避免误触直接删除文件）
    var pendingComicDelete by remember { mutableStateOf<DownloadedComic?>(null) }
    var pendingChapterDelete by remember { mutableStateOf<Pair<DownloadedComic, String>?>(null) }

    LaunchedEffect(Unit) { ComicDownloadManager.refresh(context) }

    // 音乐 / 书籍：文件系统枚举（Android 10+ 走 MediaStore，8/9 走私有目录），IO 放后台线程
    val music by produceState(MediaFileStore.EMPTY, context) {
        value = withContext(Dispatchers.IO) { MediaFileStore.musicSummary(context) }
    }
    val books by produceState(MediaFileStore.EMPTY, context) {
        value = withContext(Dispatchers.IO) { MediaFileStore.bookSummary(context) }
    }

    // 漫画 / 本子按来源拆分：bika = 本子（独立目录），其余 = 漫画
    val comics = remember(library) { library.filterNot { it.source == ComicDownloadStore.BIKA } }
    val bikas = remember(library) { library.filter { it.source == ComicDownloadStore.BIKA } }
    val comicsSize = remember(comics) { comics.sumOf { ComicDownloadStore.size(context, it.source, it.id) } }
    val bikasSize = remember(bikas) { bikas.sumOf { ComicDownloadStore.size(context, it.source, it.id) } }

    /** 先按当前活跃任务做一次快照（Map 顺序稳定，避免下标越界） */
    val tasks = active.values.toList()

    // 目录卡：Android 10+ 展示公共目录（可点开），8/9 展示私有目录绝对路径
    val comicsDir = remember(context) { ComicDownloadStore.comicDirInfo(context) }
    val bikasDir = remember(context) { ComicDownloadStore.bikaDirInfo(context) }

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
            // 下载中（保持原有功能）
            if (tasks.isNotEmpty()) {
                item(key = "tasks-header") {
                    SectionHeader("下载中（${tasks.size}）", "")
                }
                items(tasks, key = { "task-${it.source}/${it.id}/${it.chapterKey}" }) { t ->
                    DownloadTaskCard(t)
                }
            }

            // 漫画
            comicSection(
                title = "漫画",
                unit = "部",
                emptyHint = "还没有下载漫画，去漫画详情页点「下载全部」或章节右侧的下载按钮",
                keyPrefix = "comic",
                comics = comics,
                totalBytes = comicsSize,
                dirInfo = comicsDir,
                onOpenDir = {
                    if (comicsDir.isPublic) openPublicDir(context, comicsDir.relative.orEmpty())
                    else openPrivateDir(context, File(comicsDir.path))
                },
                expanded = expanded,
                onToggle = { c ->
                    val k = "${c.source}/${c.id}"
                    expanded = if (k in expanded) expanded - k else expanded + k
                },
                onDeleteComic = { pendingComicDelete = it },
                onReadChapter = { c, chapterKey ->
                    val pages = ComicDownloadStore.chapterPages(context, c.source, c.id, chapterKey)
                    if (pages.isEmpty()) Toast.makeText(context, "该章节本地文件缺失", Toast.LENGTH_SHORT).show()
                    else {
                        viewer = Triple(c, chapterKey, pages)
                        ReadProgressStore.record(context, c.source, c.id, chapterKey)
                    }
                },
                onDeleteChapter = { c, chapterKey -> pendingChapterDelete = c to chapterKey }
            )

            // 本子
            comicSection(
                title = "本子",
                unit = "本",
                emptyHint = "还没有下载本子，去哔咔详情页点「下载全部」或章节右侧的下载按钮",
                keyPrefix = "bika",
                comics = bikas,
                totalBytes = bikasSize,
                dirInfo = bikasDir,
                onOpenDir = {
                    if (bikasDir.isPublic) openPublicDir(context, bikasDir.relative.orEmpty())
                    else openPrivateDir(context, File(bikasDir.path))
                },
                expanded = expanded,
                onToggle = { c ->
                    val k = "${c.source}/${c.id}"
                    expanded = if (k in expanded) expanded - k else expanded + k
                },
                onDeleteComic = { pendingComicDelete = it },
                onReadChapter = { c, chapterKey ->
                    val pages = ComicDownloadStore.chapterPages(context, c.source, c.id, chapterKey)
                    if (pages.isEmpty()) Toast.makeText(context, "该章节本地文件缺失", Toast.LENGTH_SHORT).show()
                    else {
                        viewer = Triple(c, chapterKey, pages)
                        ReadProgressStore.record(context, c.source, c.id, chapterKey)
                    }
                },
                onDeleteChapter = { c, chapterKey -> pendingChapterDelete = c to chapterKey }
            )

            // 音乐（目录由 MusicDownloader 维护，路径不变）
            mediaSection(
                title = "音乐",
                emptyHint = "还没有下载音乐，去音乐页点下载按钮吧",
                keyPrefix = "music",
                summary = music,
                onOpenDir = {
                    if (music.isPublic) openPublicDir(context, music.path)
                    else openPrivateDir(context, MediaFileStore.musicAppDir(context))
                }
            )

            // 书籍（固定目录 Download/老郑分享/书籍/）
            mediaSection(
                title = "书籍",
                emptyHint = "还没有下载书籍，去图书详情页点「下载」吧",
                keyPrefix = "book",
                summary = books,
                onOpenDir = {
                    if (books.isPublic) openPublicDir(context, books.path)
                    else openPrivateDir(context, MediaFileStore.bookAppDir(context))
                }
            )
        }
    }

    // 离线阅读器：末页"下一章"跳到下一个已下载章节（未下载则提示）
    viewer?.let { (c, chapterKey, urls) ->
        // 按章节名中的数字正序（"第 1192 话"排在"第 999 话"之后），与下载先后无关
        val chapters = remember(c.id) { c.chapters.sortedBy { chapterNo(it.name) } }
        val idx = chapters.indexOfFirst { it.key == chapterKey }
        key(idx) {
            PhotoViewerDialog(
                urls = urls,
                initialIndex = 0,
                onDismiss = { viewer = null },
                chapterLabel = chapters.getOrNull(idx)?.name,
                // 下一章话数必须严格递增（chapterNo 解析不到数字的章节视为排在最后），
                // 防止重复/特殊章节导致从"真正的最后一章"误跳
                hasNextChapter = idx in 0 until chapters.lastIndex &&
                    chapterNo(chapters[idx + 1].name) > chapterNo(chapters[idx].name),
                onOpenNextChapter = {
                    val next = chapters.getOrNull(idx + 1)
                    val pages = next?.let { ComicDownloadStore.chapterPages(context, c.source, c.id, it.key) }
                    if (next == null || pages.isNullOrEmpty()) {
                        Toast.makeText(context, "下一章尚未下载：${next?.name ?: "没有更多章节"}", Toast.LENGTH_SHORT).show()
                    } else {
                        viewer = Triple(c, next.key, pages)
                        ReadProgressStore.record(context, c.source, c.id, next.key)
                    }
                }
            )
        }
    }

    // 删除整本确认
    pendingComicDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingComicDelete = null },
            title = { Text("删除整本下载") },
            text = { Text("确定删除《${c.title}》的全部 ${c.chapters.size} 章下载文件吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    ComicDownloadManager.deleteComic(context, c.source, c.id)
                    Toast.makeText(context, "已删除《${c.title}》全部下载", Toast.LENGTH_SHORT).show()
                    pendingComicDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingComicDelete = null }) { Text("取消") }
            }
        )
    }
    // 删除单章确认
    pendingChapterDelete?.let { (c, key0) ->
        val name = c.chapters.firstOrNull { it.key == key0 }?.name ?: "该章"
        AlertDialog(
            onDismissRequest = { pendingChapterDelete = null },
            title = { Text("删除章节下载") },
            text = { Text("确定删除《${c.title}》「$name」的下载文件吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    ComicDownloadManager.deleteChapter(context, c.source, c.id, key0)
                    Toast.makeText(context, "已删除「$name」下载", Toast.LENGTH_SHORT).show()
                    pendingChapterDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingChapterDelete = null }) { Text("取消") }
            }
        )
    }
}

private const val PRIVATE_DIR_TITLE = "应用私有目录（无需存储权限，卸载应用时自动清除）"
private const val PUBLIC_DIR_TITLE = "公共下载目录（无需存储权限）"
private const val APP_DIR_TITLE = "应用私有目录（Android 8/9，无需存储权限）"

/** 每节标题：左右分别是「标题」与「数量 · 大小」 */
@Composable
private fun SectionHeader(title: String, meta: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (meta.isNotBlank()) {
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 目录说明卡：整块可点击 → 跳到该文件夹 */
@Composable
private fun DirCard(title: String, path: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Text(path, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
                Text("点击打开文件夹", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

/** 音乐 / 书籍的一节：标题（数量 · 大小）+ 目录说明（可点）+ 文件列表（文件名 + 大小） */
private fun LazyListScope.mediaSection(
    title: String,
    emptyHint: String,
    keyPrefix: String,
    summary: MediaFileStore.Summary,
    onOpenDir: () -> Unit
) {
    item(key = "$keyPrefix-header") {
        SectionHeader(title, "${summary.count} 个文件 · ${ComicDownloadStore.formatSize(summary.totalBytes)}")
    }
    item(key = "$keyPrefix-dir") {
        DirCard(
            title = if (summary.isPublic) PUBLIC_DIR_TITLE else APP_DIR_TITLE,
            path = summary.path.ifBlank { "（暂无）" },
            onClick = onOpenDir
        )
    }
    if (summary.entries.isEmpty()) {
        item(key = "$keyPrefix-empty") { EmptyHint(emptyHint) }
    } else {
        items(summary.entries, key = { "$keyPrefix-${it.name}" }) { e ->
            MediaFileRow(e)
        }
    }
}

/** 漫画 / 本子的一节：标题（数量 · 大小）+ 目录说明（可点）+ 已下载条目卡片 */
private fun LazyListScope.comicSection(
    title: String,
    unit: String,
    emptyHint: String,
    keyPrefix: String,
    comics: List<DownloadedComic>,
    totalBytes: Long,
    dirInfo: ComicDownloadStore.DirInfo,
    onOpenDir: () -> Unit,
    expanded: Set<String>,
    onToggle: (DownloadedComic) -> Unit,
    onDeleteComic: (DownloadedComic) -> Unit,
    onReadChapter: (DownloadedComic, String) -> Unit,
    onDeleteChapter: (DownloadedComic, String) -> Unit
) {
    item(key = "$keyPrefix-header") {
        SectionHeader(title, "${comics.size} $unit · ${ComicDownloadStore.formatSize(totalBytes)}")
    }
    item(key = "$keyPrefix-dir") {
        DirCard(
            title = if (dirInfo.isPublic) PUBLIC_DIR_TITLE else PRIVATE_DIR_TITLE,
            path = dirInfo.path,
            onClick = onOpenDir
        )
    }
    if (comics.isEmpty()) {
        item(key = "$keyPrefix-empty") { EmptyHint(emptyHint) }
    } else {
        items(comics, key = { "$keyPrefix-${it.source}/${it.id}" }) { comic ->
            ComicDownloadRow(
                comic = comic,
                expanded = "${comic.source}/${comic.id}" in expanded,
                onToggle = { onToggle(comic) },
                onDeleteComic = { onDeleteComic(comic) },
                onReadChapter = { key -> onReadChapter(comic, key) },
                onDeleteChapter = { key -> onDeleteChapter(comic, key) }
            )
        }
    }
}

/** 音乐 / 书籍的单个文件行 */
@Composable
private fun MediaFileRow(entry: MediaFileStore.Entry) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                ComicDownloadStore.formatSize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 进行中的下载任务卡片（保持原功能） */
@Composable
private fun DownloadTaskCard(t: ComicDownloadManager.Task) {
    val context = LocalContext.current
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
            val progressMap by ReadProgressStore.all.collectAsStateWithLifecycle(ReadProgressStore.all.value)
            if (expanded) {
                comic.chapters.sortedBy { chapterNo(it.name) }.forEach { ch ->
                    val isLast = ch.key == progressMap["${comic.source}/${comic.id}"]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isLast) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
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
                        if (isLast) {
                            Text(
                                "上次看到",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
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

/** 章节名排序键：取名字里第一串数字（"第 1192 话"排在"第 999 话"之后），无数字排最后 */
private fun chapterNo(name: String): Long =
    Regex("\\d+").find(name)?.value?.toLongOrNull() ?: Long.MAX_VALUE

/** 外部存储提供者（系统文件管理器）的 authority */
private const val EXTERNAL_STORAGE_AUTH = "com.android.externalstorage.documents"

/**
 * 打开公共目录（如 Download/老郑分享/音乐）。
 * `primary:` 后面是**相对外部存储根**的路径，不能有前导斜杠。
 */
private fun openPublicDir(context: Context, relative: String) {
    val docId = "primary:" + relative.trim('/')
    launchDir(context, docId) {
        Toast.makeText(context, "系统文件管理器打不开该目录：$relative", Toast.LENGTH_LONG).show()
    }
}

/**
 * 打开应用私有目录。Android 11+ 基本打不开，先尝试同样的 ACTION_VIEW，
 * 失败就 Toast 说明（含绝对路径），绝不崩。
 */
private fun openPrivateDir(context: Context, dir: File) {
    val abs = dir.absolutePath
    val extRoot = Environment.getExternalStorageDirectory().absolutePath
    val docId = if (abs.startsWith("$extRoot/")) {
        "primary:" + abs.removePrefix("$extRoot/").trim('/')
    } else null
    val fail: () -> Unit = {
        Toast.makeText(context, "该目录在应用私有空间，系统文件管理器无法直接打开：$abs", Toast.LENGTH_LONG).show()
    }
    if (docId == null) fail() else launchDir(context, docId, fail)
}

/**
 * 用系统文件管理器打开一个 documents 目录，**多级兜底**（部分国产 ROM 不认通用 ACTION_VIEW）：
 * 1. 通用 `ACTION_VIEW`（原生系统文件管理器认它）；
 * 2. 显式指定 DocumentsUI 包名再试（部分系统把文件管理器从隐式匹配里排除了）；
 * 3. `ACTION_OPEN_DOCUMENT_TREE` + `EXTRA_INITIAL_URI`（系统文件选择器直接定位到该目录，全部 ROM 都有）；
 * 全失败才走 onFail（Toast 提示路径）。任何一级成功即返回。
 */
private fun launchDir(context: Context, docId: String, onFail: () -> Unit) {
    val uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTH, docId)
    // 1. 通用 ACTION_VIEW
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (tryStartActivity(context, view)) return
    // 2. 显式指定系统文件管理器（DocumentsUI 的两个常见包名）
    for (pkg in listOf("com.android.documentsui", "com.google.android.documentsui")) {
        if (tryStartActivity(context, Intent(view).apply { setPackage(pkg) })) return
    }
    // 3. 文件选择器定位到该目录（R+ 支持 INITIAL_URI，低版本会打开选择器根目录）
    val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }
    }
    if (tryStartActivity(context, picker)) {
        Toast.makeText(context, "已在文件选择器中定位该目录", Toast.LENGTH_SHORT).show()
        return
    }
    onFail()
}

/** 起 Activity，任何异常（没有可处理的应用等）都算失败 */
private fun tryStartActivity(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent) }.isSuccess
