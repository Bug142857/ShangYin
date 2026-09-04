package com.shangyin.app.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val lists by Repo.observeListsWithMeta().collectAsStateWithLifecycle(initialValue = emptyList())

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<com.shangyin.app.data.db.ItemListEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<com.shangyin.app.data.db.ListWithMeta?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("我的清单") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "新建清单")
            }
        }
    ) { pad ->
        if (lists.isEmpty()) {
            Column(Modifier.padding(pad)) {
                EmptyView("还没有自定义清单\n点右下角 + 新建，把收藏整理成想分享的样子")
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(pad).fillMaxSize()
            ) {
                items(lists, key = { it.list.id }) { meta ->
                    Card(onClick = { nav.safeNavigate("list/${meta.list.id}") }) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverImage(
                                url = meta.list.coverUrl,
                                modifier = Modifier.width(48.dp).height(66.dp)
                            )
                            Column(
                                Modifier.weight(1f).padding(horizontal = 12.dp)
                            ) {
                                Text(meta.list.name, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${meta.itemCount} 件收藏",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            var menuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("重命名") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        renameTarget = meta.list
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除清单") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        deleteTarget = meta
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameListDialog(
            title = "新建清单",
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除清单") },
            text = { Text("删除清单「${meta.list.name}」不会删除收藏的条目本身。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            Repo.deleteList(meta.list)
                            deleteTarget = null
                        }
                    }
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
