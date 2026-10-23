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
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
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
 * 配置管理（v0.301 由「设置」更名）：
 * 单层紧凑列表，8 个条目统一高度（单行小字说明），常规屏幕不用滚动即可完整展示。
 * 条目顺序：主题管理 / 账号管理 / 数据管理 / 片源管理 / 清单管理 / 模块管理 / 下载管理 / 清理缓存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController, onThemeChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showThemePicker by remember { mutableStateOf(false) }
    var currentTheme by rememberSaveable { mutableStateOf(SettingsStore.theme) }
    var showClearCache by remember { mutableStateOf(false) }

    var cacheSize by remember { mutableStateOf("计算中…") }
    LaunchedEffect(Unit) {
        cacheSize = runCatching {
            val bytes = com.shangyin.app.App.cacheSizeBytes(context)
            when {
                bytes > 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                bytes > 1024 -> "${bytes / 1024} KB"
                else -> "$bytes B"
            }
        }.getOrDefault("未知")
    }

    // 版本号（标题后面直接展示）
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    // 清单总数（清单管理标题后直接展示）
    val lists by Repo.observeListsWithMeta().collectAsStateWithLifecycle(initialValue = emptyList())

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("配置管理")
                        if (versionName.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "v$versionName",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
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
        ) {
            SettingsRow(
                icon = Icons.Rounded.Palette,
                title = "主题管理",
                hint = themeLabel(currentTheme),
                onClick = { showThemePicker = true }
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.Person,
                title = "账号管理",
                hint = "豆瓣·哔咔·无忧·Zlib",
                onClick = { nav.safeNavigate("account") }
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.CloudSync,
                title = "数据管理",
                hint = "云端同步·导出/导入备份",
                onClick = { nav.safeNavigate("dataManage") }
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.LiveTv,
                title = "片源管理",
                hint = "电视源&影视源（观影/番号）",
                onClick = { nav.safeNavigate("sourceHub") }
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.ListAlt,
                title = "清单管理",
                hint = "共 ${lists.size} 个清单",
                onClick = { nav.safeNavigate("listManager") }
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.Tune,
                title = "模块管理",
                hint = "里世界模块入口开关",
                onClick = { nav.safeNavigate("moduleManage") }
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.Download,
                title = "下载管理",
                hint = downloadHint,
                onClick = { nav.safeNavigate("downloads") }
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Rounded.CleaningServices,
                title = "清理缓存",
                hint = "当前缓存：$cacheSize",
                onClick = { showClearCache = true }
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    // 清理缓存确认
    if (showClearCache) {
        AlertDialog(
            onDismissRequest = { showClearCache = false },
            title = { Text("清理缓存") },
            text = { Text("将清除所有图片缓存和网络请求缓存。下次打开 App 或浏览豆瓣内容时会重新下载，不影响已保存的收藏数据。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        com.shangyin.app.App.clearAllCaches(context)
                        cacheSize = "0 B"
                        Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                        showClearCache = false
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { showClearCache = false }) { Text("取消") } }
        )
    }

    // 主题选择
    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text("选择主题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("follow" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                        FilterChip(
                            selected = currentTheme == key,
                            onClick = {
                                currentTheme = key
                                SettingsStore.theme = key
                                onThemeChanged()
                            },
                            label = { Text(label) }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemePicker = false }) { Text("完成") } }
        )
    }
}

/** 统一高度的紧凑条目：图标 + 标题 + 单行小字说明 + 右箭头 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            // 小字说明压成一行（超长省略），保证所有条目等高、整页不滚动
            Text(
                hint,
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
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 条目间细分隔线（起点对齐文字，视觉更整齐） */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        thickness = 0.75.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 58.dp)
    )
}

private fun themeLabel(theme: String): String = when (theme) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

// JSON 序列化（buildExportJson / parseExportJson）已抽到 data/ExportJson.kt，与云同步共用

// 清单管理已改为独立页面：ui/lists/ListManagerScreen.kt（路由 listManager）
