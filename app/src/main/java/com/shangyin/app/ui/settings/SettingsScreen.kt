package com.shangyin.app.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.download.ComicDownloadManager
import com.shangyin.app.data.download.ComicDownloadStore
import com.shangyin.app.data.download.MediaFileStore
import com.shangyin.app.data.download.VideoDownloadManager
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 配置管理（v0.302 恢复 v0.300 卡片风格）：
 * 单列卡片条目（图标 + 标题 + 小字说明 + 右箭头），条目间留白。
 * 条目顺序：主题管理 / 账号管理 / 数据管理 / 片源管理 / 清单管理 / 模块管理 / 下载管理 / 清理缓存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController, onThemeChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeMode by SettingsStore.themeFlow.collectAsStateWithLifecycle()
    val accentKey by SettingsStore.accentFlow.collectAsStateWithLifecycle()

    var showClearCache by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }

    var cacheSize by remember { mutableStateOf("计算中…") }
    LaunchedEffect(Unit) {
        // 目录遍历可能较大，放 IO 线程，避免打开设置页时卡顿
        cacheSize = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = com.shangyin.app.App.cacheSizeBytes(context)
                when {
                    bytes > 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                    bytes > 1024 -> "${bytes / 1024} KB"
                    else -> "$bytes B"
                }
            }.getOrDefault("未知")
        }
    }

    // 清单总数（清单管理标题后直接展示）
    val lists by Repo.observeListsWithMeta().collectAsStateWithLifecycle(initialValue = emptyList())

    // 里世界收藏总数（收藏管理小字）
    val innerFavs by com.shangyin.app.data.InnerFavorites.favs
        .collectAsStateWithLifecycle()

    // 下载管理小字：全部下载文件总数及大小（视频+漫画+本子+音乐+书籍，IO 放后台）
    val downloadMeta by produceState(0 to 0L) {
        val (count, bytes) = withContext(Dispatchers.IO) {
            val comics = ComicDownloadManager.library.value.filterNot { it.source == ComicDownloadStore.BIKA }
            val bikas = ComicDownloadManager.library.value.filter { it.source == ComicDownloadStore.BIKA }
            val comicBytes = (comics + bikas).sumOf { ComicDownloadStore.size(context, it.source, it.id) }
            val videos = MediaFileStore.videoSummary(context)
            val music = MediaFileStore.musicSummary(context)
            val books = MediaFileStore.bookSummary(context)
            val cnt = comics.size + bikas.size + videos.count + music.count + books.count
            val total = comicBytes + videos.totalBytes + music.totalBytes + books.totalBytes
            cnt to total
        }
        value = count to bytes
    }
    val downloadHint = downloadMeta.let { (cnt, bytes) ->
        if (cnt > 0) "已下载 $cnt 个文件 · ${ComicDownloadStore.formatSize(bytes)}"
        else "暂无下载文件"
    }

    // 版本号（仅用于「版本更新」条目小字，标题不再显示）
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置管理") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            val entries = listOf(
                SettingsEntry(
                    Icons.Rounded.Palette, "主题管理",
                    "${themeLabel(themeMode)} · ${com.shangyin.app.ui.theme.accentLabel(accentKey)}"
                ) { nav.safeNavigate("themeManage") },
                SettingsEntry(Icons.Rounded.Person, "账号管理", "豆瓣·Zlib") {
                    nav.safeNavigate("account")
                },
                SettingsEntry(Icons.Rounded.CloudSync, "数据管理", "云端同步·导出/导入备份") {
                    nav.safeNavigate("dataManage")
                },
                SettingsEntry(Icons.Rounded.LiveTv, "片源管理", "电视源&影视源（观影/番号）") {
                    nav.safeNavigate("sourceHub")
                },
                SettingsEntry(Icons.Rounded.ListAlt, "清单管理", "共 ${lists.size} 个清单") {
                    nav.safeNavigate("listManager")
                },
                SettingsEntry(Icons.Rounded.Tune, "模块管理", "模块入口编辑") {
                    nav.safeNavigate("moduleManage")
                },
                SettingsEntry(
                    Icons.Rounded.FavoriteBorder, "收藏管理",
                    if (innerFavs.isNotEmpty()) "共 ${innerFavs.size} 条收藏 · 按模块整理" else "暂无收藏"
                ) { nav.safeNavigate("favoritesManage") },
                SettingsEntry(Icons.Rounded.Download, "下载管理", downloadHint) {
                    nav.safeNavigate("downloads")
                },
                SettingsEntry(Icons.Rounded.CleaningServices, "清理缓存", "当前缓存：$cacheSize") {
                    showClearCache = true
                },
                SettingsEntry(
                    Icons.Rounded.SystemUpdate, "版本更新",
                    if (versionName.isNotBlank()) "当前 v$versionName · 点击检查更新" else "点击检查更新"
                ) { showUpdate = true }
            )
            if (com.shangyin.app.ui.common.isWideScreen()) {
                // 车机 / 平板横屏：双栏排布，避免 10 项在小高度下需要滚动
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        entries.take(5).forEach { SettingsCard(it) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        entries.drop(5).forEach { SettingsCard(it) }
                    }
                }
            } else {
                entries.forEach { SettingsCard(it) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }

    // 清理缓存确认
    if (showClearCache) {
        AlertDialog(
            onDismissRequest = { showClearCache = false },
            title = { Text("清理缓存") },
            text = { Text("将清除图片缓存、网络请求缓存、漫画/本子详情缓存、已下载的更新安装包与分享临时文件。下次使用时会重新下载，不影响收藏、清单、阅读进度等已保存数据。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            com.shangyin.app.App.clearAllCaches(context)
                        }
                        cacheSize = "0 B"
                        Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                        showClearCache = false
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { showClearCache = false }) { Text("取消") } }
        )
    }

    // 版本更新（仅手动触发）
    if (showUpdate) {
        UpdateDialog(context = context, onDismiss = { showUpdate = false })
    }
}

/** 配置项（便于宽屏双栏排布） */
private data class SettingsEntry(
    val icon: ImageVector,
    val title: String,
    val hint: String,
    val onClick: () -> Unit
)

/** 卡片条目（v0.319 折中版）：两行文字 + 适中留白，不滚动的同时尽量填满屏幕 */
@Composable
private fun SettingsCard(entry: SettingsEntry) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clickable { entry.onClick() },
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                entry.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    entry.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun themeLabel(theme: String): String = when (theme) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

// JSON 序列化（buildExportJson / parseExportJson）已抽到 data/ExportJson.kt，与云同步共用

// 清单管理已改为独立页面：ui/lists/ListManagerScreen.kt（路由 listManager）
