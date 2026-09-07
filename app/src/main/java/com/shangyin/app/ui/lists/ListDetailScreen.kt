package com.shangyin.app.ui.lists

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.CollectionItemEntity
import com.shangyin.app.data.db.ListWithMeta
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.DoubanRating
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 清单内容布局 */
private enum class ListLayoutMode { GRID, LIST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDetailScreen(nav: NavHostController, listId: Long) {
    val scope = rememberCoroutineScope()

    val list by Repo.observeList(listId).collectAsStateWithLifecycle(initialValue = null)
    val items by Repo.observeItemsIn(listId).collectAsStateWithLifecycle(initialValue = emptyList())
    val childLists by Repo.observeSubListsWithMeta(listId).collectAsStateWithLifecycle(initialValue = emptyList())

    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showAddItem by remember { mutableStateOf(false) }
    var showCreateChild by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CollectionItemEntity?>(null) }
    var layoutMode by rememberSaveable { mutableStateOf(ListLayoutMode.GRID) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(list?.name.orEmpty(), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(if (layoutMode == ListLayoutMode.GRID) "切换为列表" else "切换为平铺")
                            },
                            onClick = {
                                menuOpen = false
                                layoutMode =
                                    if (layoutMode == ListLayoutMode.GRID) ListLayoutMode.LIST else ListLayoutMode.GRID
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("添加条目") },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            onClick = { menuOpen = false; showAddItem = true }
                        )
                        DropdownMenuItem(
                            text = { Text("创建子清单") },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            onClick = { menuOpen = false; showCreateChild = true }
                        )
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; showRename = true }
                        )
                        DropdownMenuItem(
                            text = { Text("删除清单") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; showDelete = true }
                        )
                    }
                }
            )
        }
    ) { pad ->
        // 清单有子清单时，顶部显示子清单横条 + 下方条目；无子清单时原有布局
        if (childLists.isNotEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize()) {
                // 子清单横条（卡片带封面拼贴，和父级清单一致）
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(childLists, key = { it.list.id }) { meta ->
                        ChildListTile(meta) { nav.safeNavigate("list/${meta.list.id}") }
                    }
                }
                androidx.compose.material3.HorizontalDivider()
                // 条目列表
                Box(Modifier.fillMaxSize()) {
                    when (layoutMode) {
                        ListLayoutMode.GRID -> LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(items, key = { it.id }) { item ->
                                GridItemCard(item, onClick = { nav.safeNavigate("item/${item.id}") }, onLongPress = { deleteTarget = item })
                            }
                        }
                        ListLayoutMode.LIST -> androidx.compose.foundation.lazy.LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(items, key = { it.id }) { item ->
                                ItemRowInList(item, onClick = { nav.safeNavigate("item/${item.id}") }, onLongPress = { deleteTarget = item })
                            }
                        }
                    }
                }
            }
        } else if (items.isEmpty()) {
            Column(Modifier.padding(pad)) {
                EmptyView("清单还是空的\n点右上角菜单 → 添加条目")
            }
        } else {
            when (layoutMode) {
                ListLayoutMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(pad).fillMaxSize()
                ) {
                    gridItems(items, key = { it.id }) { item ->
                        GridItemCard(
                            item = item,
                            onClick = { nav.safeNavigate("item/${item.id}") },
                            onLongPress = { deleteTarget = item }
                        )
                    }
                }
                ListLayoutMode.LIST -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(pad).fillMaxSize()
                ) {
                    items(items, key = { it.id }) { item ->
                        ItemRowInList(
                            item = item,
                            onClick = { nav.safeNavigate("item/${item.id}") },
                            onLongPress = { deleteTarget = item }
                        )
                    }
                }
            }
        }
    } // close Scaffold content lambda

    // 长按删除确认
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("移出清单") },
            text = { Text("把「${item.title}」从清单中移出？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { Repo.removeItemFromList(listId, item.id) }
                    deleteTarget = null
                }) { Text("移出") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    // 重命名
    if (showRename && list != null) {
        NameListDialog(
            title = "重命名清单",
            initialName = list!!.name,
            onConfirm = { name ->
                scope.launch { Repo.renameList(list!!, name) }
                showRename = false
            },
            onDismiss = { showRename = false }
        )
    }

    // 删除清单
    if (showDelete && list != null) {
        val hasChildren = childLists.isNotEmpty()
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除清单") },
            text = {
                Text(
                    buildString {
                        append("删除清单「${list!!.name}」不会删除收藏的条目本身。")
                        if (hasChildren) append("\n⚠️ 该清单下还有 ${childLists.size} 个子清单，将一并删除。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // 先删所有子清单
                        childLists.forEach { Repo.deleteList(it.list) }
                        Repo.deleteList(list!!)
                        showDelete = false
                        nav.safePopBackStack()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } }
        )
    }

    // 添加条目
    if (showAddItem) {
        AddItemToAlertDialog(
            listId = listId,
            existingIds = remember(items) { items.map { it.id }.toSet() },
            onDismiss = { showAddItem = false }
        )
    }

    // 创建子清单
    if (showCreateChild) {
        NameListDialog(
            title = "创建子清单",
            confirmLabel = "创建",
            onConfirm = { name ->
                scope.launch {
                    Repo.createList(name, parentId = listId)
                    showCreateChild = false
                }
            },
            onDismiss = { showCreateChild = false }
        )
    }
}

/** 子清单卡片：封面拼贴 + 名称 + 条目数，和父级清单布局一致 */
@Composable
private fun ChildListTile(meta: ListWithMeta, onClick: () -> Unit) {
    var covers by remember(meta.list.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(meta.list.id) {
        covers = withContext(Dispatchers.IO) { Repo.getListCovers(meta.list.id, 4) }
    }
    val firstChar = meta.list.name.firstOrNull()?.toString() ?: "清"

    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = Modifier.width(110.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (covers.isNotEmpty()) {
                    // 封面拼贴（1~4张）
                    ChildCoverCollage(covers.take(4))
                } else {
                    // 默认封面：清单名首字
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            firstChar,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    meta.list.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${meta.itemCount} 件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 子清单封面拼贴（1~4张封面） */
@Composable
private fun ChildCoverCollage(covers: List<String>) {
    when (covers.size) {
        1 -> coil.compose.AsyncImage(
            model = covers[0], contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        2 -> Row(Modifier.fillMaxSize()) {
            coil.compose.AsyncImage(model = covers[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
            coil.compose.AsyncImage(model = covers[1], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
        }
        3 -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                coil.compose.AsyncImage(model = covers[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                coil.compose.AsyncImage(model = covers[1], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
            }
            coil.compose.AsyncImage(model = covers[2], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        else -> Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                coil.compose.AsyncImage(model = covers[0], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                coil.compose.AsyncImage(model = covers[1], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                coil.compose.AsyncImage(model = covers[2], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
                coil.compose.AsyncImage(model = covers[3], contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

/** 平铺（海报网格）卡片 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GridItemCard(
    item: CollectionItemEntity,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
    ) {
        CoverImage(
            url = item.coverUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        DoubanRating(item.doubanRating)
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ItemRowInList(
    item: CollectionItemEntity,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongPress
        )
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                url = item.coverUrl,
                modifier = Modifier.width(44.dp).height(62.dp)
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DoubanRating(item.doubanRating)
                    if (item.status.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 从本地收藏里挑选条目加入清单 */
@Composable
private fun AddItemToAlertDialog(
    listId: Long,
    existingIds: Set<Long>,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showClearOrphan by remember { mutableStateOf(false) }
    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val candidates = remember(allItems, query, existingIds) {
        val list = if (query.isBlank()) allItems else allItems.filter {
            it.title.contains(query, ignoreCase = true) || it.subTitle.contains(query, ignoreCase = true)
        }
        // 过滤已添加的条目，只显示未加入清单的
        list.filter { it.id !in existingIds }.take(50)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从收藏中添加") },
        text = {
            Column {
                // 顶部工具栏：搜索 + 清理孤立收藏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索收藏…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { showClearOrphan = true }) {
                        Text("清理", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (allItems.isEmpty()) {
                    Text(
                        "还没有收藏，先去搜索页收藏一些吧",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(candidates, key = { it.id }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CoverImage(
                                url = item.coverUrl,
                                modifier = Modifier.width(36.dp).height(50.dp)
                            )
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            TextButton(onClick = {
                                scope.launch { Repo.addItemToList(listId, item.id) }
                            }) {
                                Text("加入")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )

    // 清理孤立收藏确认
    if (showClearOrphan) {
        AlertDialog(
            onDismissRequest = { showClearOrphan = false },
            title = { Text("清理孤立收藏") },
            text = { Text("删除所有不在任何清单里的收藏？（已在清单里的不会被删）") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val n = Repo.clearOrphanItems()
                        Toast.makeText(context, if (n > 0) "已清理 $n 条孤立收藏" else "没有需要清理的条目", Toast.LENGTH_SHORT).show()
                        showClearOrphan = false
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { showClearOrphan = false }) { Text("取消") } }
        )
    }
}
