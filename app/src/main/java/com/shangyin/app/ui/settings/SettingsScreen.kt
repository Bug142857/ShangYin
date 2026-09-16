package com.shangyin.app.ui.settings

import android.widget.Toast
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SaveAlt
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
import com.shangyin.app.data.db.ItemListEntity
import com.shangyin.app.ui.lists.NameListDialog
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController, onThemeChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showListManager by remember { mutableStateOf(false) }
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
            // 主题管理
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
                        Text("主题管理", style = MaterialTheme.typography.titleSmall)
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

            // 清单管理
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
                        Text("清单管理", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "管理表世界 / 里世界的收藏清单",
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

            // 账号管理
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { nav.safeNavigate("account") }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Person, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("账号管理", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "云端同步 · 豆瓣登录 · 哔咔登录",
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

            // 片源管理（在线观影）
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        nav.safeNavigate("vodSources")
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.OndemandVideo, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("片源管理", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (SettingsStore.getVodSources().any { it.enabled }) {
                                val n = SettingsStore.getVodSources().count { it.enabled }
                                "已配置 $n 个片源 · 影视详情页可在线观看"
                            } else "配置影视采集源，收藏的影视可在线观看",
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

            // 数据管理（导出/导入合并入口）
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        nav.safeNavigate("dataManage")
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.SaveAlt, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("数据管理", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "导出/导入备份文件（收藏、清单、片源配置）",
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

    // 清单管理
    if (showListManager) {
        ListManagerDialog(onDismiss = { showListManager = false })
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

private fun themeLabel(theme: String): String = when (theme) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

// JSON 序列化（buildExportJson / parseExportJson）已抽到 data/ExportJson.kt，与云同步共用

// ---------- 清单管理对话框 ----------

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
    var createWorld by remember { mutableStateOf(0) } // 新建清单归属：0=表世界 1=里世界
    var renameTarget by remember { mutableStateOf<ItemListEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<com.shangyin.app.data.db.ListWithMeta?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清单管理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (lists.isEmpty()) {
                    Text(
                        "还没有清单，点 + 创建一个",
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
                                world = meta.list.world,
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
                Text("新建清单")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    // 新建清单：名称 + 归属世界
    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建清单") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("清单名称") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "归属",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = createWorld == 0,
                            onClick = { createWorld = 0 },
                            label = { Text("表世界") }
                        )
                        FilterChip(
                            selected = createWorld == 1,
                            onClick = { createWorld = 1 },
                            label = { Text("里世界") }
                        )
                    }
                    if (createWorld == 1) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "里世界清单用于收藏番号视频和本子",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch { Repo.createList(name, world = createWorld) }
                        showCreate = false
                    }
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } }
        )
    }

    renameTarget?.let { target ->
        NameListDialog(
            title = "重命名清单",
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
            title = { Text("删除清单") },
            text = {
                Text(
                    buildString {
                        append("确定删除清单「${meta.list.name}」吗？\n")
                        if (childCount > 0) append("⚠️ 其下 $childCount 个子清单也会一并删除！\n")
                        append("⚠️ 该清单下的 ${meta.itemCount} 件条目也会被一并删除！")
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

/** 清单管理里的一行：按 depth 缩进（0=父清单，1=子清单，2=子子清单…），层级一眼可辨；里世界清单带标记 */
@Composable
private fun ListManagerRow(
    name: String,
    count: Int,
    depth: Int = 0,
    world: Int = 0,
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
                buildString {
                    append(if (depth > 0) "└ " else "")
                    append(name)
                    if (world == 1 && depth == 0) append("  ·里世界")
                },
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
