package com.shangyin.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.Repo
import com.shangyin.app.data.buildExportJson
import com.shangyin.app.data.parseExportJson
import com.shangyin.app.data.db.ItemListEntity
import com.shangyin.app.ui.lists.NameListDialog
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController, onThemeChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var nickname by rememberSaveable { mutableStateOf(SettingsStore.nickname) }
    var avatarUri by rememberSaveable { mutableStateOf(SettingsStore.avatarUri) }
    var showEditName by remember { mutableStateOf(false) }
    var showListManager by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var currentTheme by rememberSaveable { mutableStateOf(SettingsStore.theme) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var showClearCache by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf("计算中…") }
    var showDoubanLogout by remember { mutableStateOf(false) }
    // 豆瓣登录状态（keyInvalidate 触发重组）
    var doubanLoginKey by remember { mutableStateOf(0) }
    val isDoubanLoggedIn = remember(doubanLoginKey) { SettingsStore.isDoubanLoggedIn }
    // 登录 Activity 回调：登录成功后刷新状态并重建 OkHttpClient
    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        doubanLoginKey++  // 触发重组重新读登录状态
        // 登录态变化后强制重建 OkHttpClient，让新 Cookie 立即生效
        com.shangyin.app.data.douban.DoubanClient.onCookieChanged()
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "豆瓣登录成功", Toast.LENGTH_SHORT).show()
        }
    }

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

    // 头像选择器
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 持久化 URI 权限（Android 10+ 需要）
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            avatarUri = it.toString()
            SettingsStore.avatarUri = it.toString()
        }
    }

    // 导入选择器
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { pendingImportUri = it }
    }

    // 导出目录选择器
    val exportDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { rootUri: Uri? ->
        rootUri?.let { tree ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val json = pendingExportJson ?: return@let
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "老郑分享_$ts.json"
            runCatching {
                val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)
                val newFile = docFile?.createFile("application/json", fileName)
                if (newFile == null) throw Exception("无法创建文件")
                context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
                    os.write(json.toByteArray())
                }
                Toast.makeText(context, "已导出到 $fileName", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            pendingExportJson = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 用户信息区（头像可点击换，整行可改昵称）
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showEditName = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { avatarPicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarUri.isNotBlank()) {
                            AsyncImage(
                                model = avatarUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            nickname.ifBlank { "点击设置昵称" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "点击头像更换图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 分类管理
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showListManager = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.List, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("分类管理", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "管理主页展示的自定义分类",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 主题
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showThemePicker = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Star, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("主题", style = MaterialTheme.typography.titleSmall)
                        Text(
                            themeLabel(currentTheme),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 豆瓣登录
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (isDoubanLoggedIn) {
                            // 已登录：长按提示退出登录（这里用点击弹确认框更直观）
                            showDoubanLogout = true
                        } else {
                            // 未登录：启动登录 Activity
                            loginLauncher.launch(Intent(context, DoubanLoginActivity::class.java))
                        }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = if (isDoubanLoggedIn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("豆瓣登录", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (isDoubanLoggedIn) "已登录，搜索结果更全" else "未登录，登录后搜索结果更全",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDoubanLoggedIn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 云同步
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        nav.safeNavigate("cloudsync")
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Cloud, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("云同步", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (SettingsStore.isWebdavConfigured) {
                                val t = SettingsStore.lastCloudSync
                                if (t > 0L) "已连接 WebDAV · 上次同步 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(t))}"
                                else "已连接 WebDAV · 从未同步"
                            } else "用 WebDAV 网盘备份/恢复，换手机不丢数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 导出
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            runCatching {
                                val data = Repo.exportAll()
                                buildExportJson(data)
                            }.onSuccess { json ->
                                pendingExportJson = json
                                exportDirPicker.launch(null)
                            }.onFailure { e ->
                                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Add, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导出数据", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "把收藏的内容导出为 JSON 文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 导入
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { importPicker.launch("*/*") }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Edit, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导入数据", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "从 JSON 文件恢复（会清空当前数据）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 图片缓存清理
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showClearCache = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Delete, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("清理缓存", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "当前缓存：$cacheSize（图片 + 网络请求）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    // 编辑昵称
    if (showEditName) {
        var tempName by remember { mutableStateOf(nickname) }
        AlertDialog(
            onDismissRequest = { showEditName = false },
            title = { Text("设置昵称") },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    singleLine = true,
                    label = { Text("昵称") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = tempName.trim()
                    if (n.isNotBlank()) {
                        nickname = n
                        SettingsStore.nickname = n
                    }
                    showEditName = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditName = false }) { Text("取消") } }
        )
    }

    // 分类管理
    if (showListManager) {
        ListManagerDialog(onDismiss = { showListManager = false })
    }

    // 导入确认
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("确认导入") },
            text = { Text("导入会清空当前所有数据后替换，确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching {
                            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { br -> br.readText() }
                                ?: return@runCatching
                            val data = parseExportJson(json)
                            Repo.importAll(data)
                            Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                        pendingImportUri = null
                    }
                }) { Text("确认导入", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingImportUri = null }) { Text("取消") } }
        )
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

    // 退出豆瓣登录确认
    if (showDoubanLogout) {
        AlertDialog(
            onDismissRequest = { showDoubanLogout = false },
            title = { Text("退出豆瓣登录") },
            text = { Text("退出后搜索结果可能不完整（部分条目需要登录才能搜到），确认退出？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        SettingsStore.clearDoubanLogin()
                        doubanLoginKey++
                        com.shangyin.app.data.douban.DoubanClient.onCookieChanged()
                        showDoubanLogout = false
                        Toast.makeText(context, "已退出豆瓣登录", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDoubanLogout = false }) { Text("取消") } }
        )
    }
}

private fun themeLabel(theme: String): String = when (theme) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

// JSON 序列化（buildExportJson / parseExportJson）已抽到 data/ExportJson.kt，与云同步共用

// ---------- 分类管理对话框 ----------

@Composable
private fun ListManagerDialog(
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val lists by Repo.observeListsWithMeta().collectAsStateWithLifecycle(initialValue = emptyList())

    // 父子层级 → 深度优先扁平化（支持任意级子清单，逐级缩进）
    val childrenMap = lists.groupBy { it.list.parentId }
    val flatTree: List<Pair<com.shangyin.app.data.db.ListWithMeta, Int>> = buildList {
        fun push(parentId: Long?, depth: Int) {
            childrenMap[parentId].orEmpty().forEach { m ->
                add(m to depth)
                if (depth < 5) push(m.list.id, depth + 1) // 深度上限防环
            }
        }
        push(null, 0)
    }

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ItemListEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<com.shangyin.app.data.db.ListWithMeta?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类管理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (lists.isEmpty()) {
                    Text(
                        "还没有分类，点 + 创建一个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        flatTree.forEach { (meta, depth) ->
                            ListManagerRow(
                                name = meta.list.name,
                                count = meta.itemCount,
                                depth = depth,
                                onRename = { renameTarget = meta.list },
                                onDelete = { deleteTarget = meta }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建分类")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    if (showCreate) {
        NameListDialog(
            title = "新建分类",
            confirmLabel = "创建",
            onConfirm = { name ->
                scope.launch { Repo.createList(name) }
                showCreate = false
            },
            onDismiss = { showCreate = false }
        )
    }

    renameTarget?.let { target ->
        NameListDialog(
            title = "重命名分类",
            initialName = target.name,
            onConfirm = { name ->
                scope.launch { Repo.renameList(target, name) }
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { meta ->
        val childCount = lists.count { it.list.parentId == meta.list.id }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除分类") },
            text = {
                Text(
                    buildString {
                        append("确定删除分类「${meta.list.name}」吗？\n")
                        if (childCount > 0) append("⚠️ 其下 $childCount 个子清单也会一并删除！\n")
                        append("⚠️ 该分类下的 ${meta.itemCount} 件条目也会被一并删除！")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // 级联：先删子清单（含子清单里的条目），再删自身
                            val toDelete = ArrayDeque<com.shangyin.app.data.db.ListWithMeta>()
                            toDelete.addLast(meta)
                            while (toDelete.isNotEmpty()) {
                                val cur = toDelete.removeFirst()
                                lists.filter { it.list.parentId == cur.list.id }.forEach { toDelete.addLast(it) }
                                Repo.getAllItemsIn(cur.list.id).forEach { Repo.deleteItem(it) }
                                Repo.deleteList(cur.list)
                            }
                            deleteTarget = null
                        }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

/** 分类管理里的一行：按 depth 缩进（0=父清单，1=子清单，2=子子清单…），层级一眼可辨 */
@Composable
private fun ListManagerRow(
    name: String,
    count: Int,
    depth: Int = 0,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (depth > 0) Modifier.padding(start = (28 * depth).dp) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (depth > 0) "└ $name" else name,
                modifier = Modifier.weight(1f),
                style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (depth == 0) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 1f - depth * 0.12f)
            )
            Text(
                "${count}件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRename) {
                Icon(Icons.Rounded.Edit, contentDescription = "重命名")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "删除")
            }
        }
    }
}
