package com.shangyin.app.ui.lists

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.ItemListEntity
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch

/**
 * 清单管理（设置 → 清单管理）：
 * 独立页面，按表/里世界分节展示全部清单树，支持新建 / 重命名 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListManagerScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val lists by Repo.observeListsWithMeta().collectAsStateWithLifecycle(initialValue = emptyList())

    // 父子层级 → 深度优先扁平化（支持任意级子清单，逐级缩进），按表/里世界分两组
    val childrenMap = lists.groupBy { it.list.parentId }
    fun buildTree(world: Int): List<Pair<com.shangyin.app.data.db.ListWithMeta, Int>> = buildList {
        fun push(parentId: Long?, depth: Int) {
            childrenMap[parentId].orEmpty()
                .filter { it.list.world == world }
                .forEach { m ->
                    add(m to depth)
                    if (depth < 5) push(m.list.id, depth + 1) // 深度上限防环
                }
        }
        push(null, 0)
    }
    val surfaceTree = buildTree(0)
    val innerTree = buildTree(1)

    var showCreate by remember { mutableStateOf(false) }
    var createWorld by remember { mutableStateOf(0) } // 新建清单归属：0=表世界 1=里世界
    var renameTarget by remember { mutableStateOf<ItemListEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<com.shangyin.app.data.db.ListWithMeta?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("清单管理") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showCreate = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建清单")
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
                .padding(16.dp)
        ) {
            if (surfaceTree.isEmpty() && innerTree.isEmpty()) {
                Text(
                    "还没有清单，点右上角「新建清单」创建一个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                if (surfaceTree.isNotEmpty()) {
                    WorldSectionHeader("表世界", Icons.Outlined.Public, surfaceTree.size)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        surfaceTree.forEach { (meta, depth) ->
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
                if (innerTree.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    WorldSectionHeader("里世界", Icons.Outlined.Visibility, innerTree.size)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        innerTree.forEach { (meta, depth) ->
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
        }
    }

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
                            "里世界清单用于收藏番号、动漫、本子与漫画",
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

/** 清单管理的分节标题：表世界 / 里世界 */
@Composable
private fun WorldSectionHeader(label: String, icon: ImageVector, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count 个清单",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 清单管理里的一行：按 depth 缩进（0=父清单，1=子清单，2=子子清单…），层级一眼可辨 */
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
