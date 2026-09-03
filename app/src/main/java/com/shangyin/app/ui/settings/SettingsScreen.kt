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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.List
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.ExportData
import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.ItemListEntity
import com.shangyin.app.ui.lists.NameListDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    var showCookieEditor by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

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
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp),
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

            // 豆瓣 Cookie（用于搜索游戏等需要登录的功能）
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showCookieEditor = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Person, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("豆瓣登录Cookie", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (SettingsStore.doubanCookie.isNotBlank()) "已设置（可搜索游戏）" else "未设置（游戏搜索需登录）",
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

    // 豆瓣 Cookie 编辑
    if (showCookieEditor) {
        var tempCookie by remember { mutableStateOf(SettingsStore.doubanCookie) }
        AlertDialog(
            onDismissRequest = { showCookieEditor = false },
            title = { Text("豆瓣登录Cookie") },
            text = {
                Column {
                    Text(
                        "在浏览器登录豆瓣后，打开开发者工具→Network→任意请求→Headers→Cookie，复制完整值粘贴到这里。设置后可搜索游戏和人物。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempCookie,
                        onValueChange = { tempCookie = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        placeholder = { Text("粘贴 Cookie 值") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    SettingsStore.doubanCookie = tempCookie.trim()
                    showCookieEditor = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showCookieEditor = false }) { Text("取消") }
            }
        )
    }
}

private fun themeLabel(theme: String): String = when (theme) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

// ---------- JSON 序列化 ----------

private fun buildExportJson(data: ExportData): String {
    val root = JSONObject().apply {
        put("version", 1)
        put("exportAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    }

    val itemsArr = JSONArray()
    data.items.forEach { e ->
        itemsArr.put(JSONObject().apply {
            put("id", e.id)
            put("category", e.category)
            put("doubanId", e.doubanId)
            put("title", e.title)
            put("subTitle", e.subTitle)
            put("year", e.year)
            put("doubanRating", e.doubanRating)
            put("coverUrl", e.coverUrl)
            put("summary", e.summary)
            put("info", e.info)
            put("directors", e.directors)
            put("casts", e.casts)
            put("genres", e.genres)
            put("doubanUrl", e.doubanUrl)
            put("status", e.status)
            put("myRating", e.myRating)
            put("note", e.note)
            put("createdAt", e.createdAt)
            put("updatedAt", e.updatedAt)
        })
    }
    root.put("items", itemsArr)

    val listsArr = JSONArray()
    data.lists.forEach { l ->
        listsArr.put(JSONObject().apply {
            put("id", l.id)
            put("name", l.name)
            put("description", l.description)
            put("coverUrl", l.coverUrl)
            put("createdAt", l.createdAt)
        })
    }
    root.put("lists", listsArr)

    val relArr = JSONArray()
    data.listItems.forEach { li ->
        relArr.put(JSONObject().apply {
            put("listId", li.listId)
            put("itemId", li.itemId)
            put("orderIndex", li.orderIndex)
        })
    }
    root.put("listItems", relArr)

    return root.toString(2)
}

private fun parseExportJson(json: String): ExportData {
    val root = JSONObject(json)

    val items = root.optJSONArray("items")?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            com.shangyin.app.data.db.CollectionItemEntity(
                id = o.getLong("id"),
                category = o.optString("category", ""),
                doubanId = o.optString("doubanId", ""),
                title = o.optString("title", ""),
                subTitle = o.optString("subTitle", ""),
                year = o.optString("year", ""),
                doubanRating = o.opt("doubanRating")?.let {
                    if (it is Number) it.toFloat() else null
                },
                coverUrl = o.optString("coverUrl").ifBlank { null },
                summary = o.optString("summary", ""),
                info = o.optString("info", ""),
                directors = o.optString("directors", ""),
                casts = o.optString("casts", ""),
                genres = o.optString("genres", ""),
                doubanUrl = o.optString("doubanUrl").ifBlank { null },
                status = o.optString("status", ""),
                myRating = o.optInt("myRating", 0),
                note = o.optString("note", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    } ?: emptyList()

    val lists = root.optJSONArray("lists")?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ItemListEntity(
                id = o.getLong("id"),
                name = o.optString("name", ""),
                description = o.optString("description", ""),
                coverUrl = o.optString("coverUrl").ifBlank { null },
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    } ?: emptyList()

    val rels = root.optJSONArray("listItems")?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            com.shangyin.app.data.db.ListItemEntity(
                listId = o.getLong("listId"),
                itemId = o.getLong("itemId"),
                orderIndex = o.optInt("orderIndex", 0)
            )
        }
    } ?: emptyList()

    return ExportData(items, lists, rels)
}

// ---------- 分类管理对话框 ----------

@Composable
private fun ListManagerDialog(
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val lists by Repo.observeListsWithMeta().collectAsStateWithLifecycle(initialValue = emptyList())

    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ItemListEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<com.shangyin.app.data.db.ListWithMeta?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类管理") },
        text = {
            Column {
                if (lists.isEmpty()) {
                    Text(
                        "还没有分类，点 + 创建一个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        lists.forEach { meta ->
                            Card(shape = RoundedCornerShape(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(meta.list.name, modifier = Modifier.weight(1f))
                                    Text(
                                        "${meta.itemCount}件",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(onClick = { renameTarget = meta.list }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = null)
                                    }
                                    IconButton(onClick = { deleteTarget = meta }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = null)
                                    }
                                }
                            }
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除分类") },
            text = {
                Text(
                    "确定删除分类「${meta.list.name}」吗？\n" +
                        "⚠️ 该分类下的 ${meta.itemCount} 件条目也会被一并删除！"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val items = Repo.getAllItemsIn(meta.list.id)
                            items.forEach { Repo.deleteItem(it) }
                            Repo.deleteList(meta.list)
                            deleteTarget = null
                        }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
