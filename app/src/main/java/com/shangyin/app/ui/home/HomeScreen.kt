package com.shangyin.app.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.R
import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.ListWithMeta
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.common.dragReorderModifier
import com.shangyin.app.ui.safeNavigate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val lists by Repo.observeRootListsWithMeta().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var draggingId by remember { mutableStateOf<Long?>(null) }
    val currentIds = rememberUpdatedState(lists.map { it.list.id })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("search") }) {
                        Icon(Icons.Rounded.Search, contentDescription = "去搜索")
                    }
                    IconButton(onClick = { nav.safeNavigate("settings") }) {
                        Icon(Icons.Rounded.Menu, contentDescription = "设置")
                    }
                }
            )
        }
    ) { pad ->
        if (lists.isEmpty()) {
            EmptyHomeContent(nav, Modifier.padding(pad).fillMaxSize())
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(pad).fillMaxSize()
            ) {
                items(lists, key = { it.list.id }) { meta ->
                    CategoryTile(
                        meta = meta,
                        isDragging = draggingId == meta.list.id,
                        currentIdsState = currentIds,
                        onDragStateChange = { draggingId = it },
                        onReorder = { from, to -> Repo.reorderRootList(from, to) },
                        onClick = { nav.safeNavigate("list/${meta.list.id}") }
                    )
                }
            }
        }
    }
}

/** 空主页 */
@Composable
private fun EmptyHomeContent(nav: NavHostController, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EmptyView("还没有分类\n去搜索收藏喜欢的，或到设置里创建分类")
        Row(
            modifier = Modifier.padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = { nav.safeNavigate("settings") }) {
                Icon(Icons.Rounded.List, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(6.dp))
                Text("管理分类")
            }
            Button(onClick = { nav.safeNavigate("search") }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(6.dp))
                Text("搜索收藏")
            }
        }
    }
}

/** 清单封面配色组（按清单名 hash 稳定取色，同一名单颜色不变） */
private val TILE_GRADIENTS = listOf(
    Color(0xFF5B8DEF) to Color(0xFF3E63C9), // 蓝
    Color(0xFF7C6FF0) to Color(0xFF5A48C4), // 紫
    Color(0xFF4FBFA1) to Color(0xFF2E9478), // 青
    Color(0xFFF2A65A) to Color(0xFFD0762A), // 橙
    Color(0xFFEF6F8E) to Color(0xFFCB4467), // 粉
    Color(0xFF54B4D3) to Color(0xFF2F85A3), // 天青
    Color(0xFF93B85A) to Color(0xFF6D903C), // 草绿
    Color(0xFFB08BF2) to Color(0xFF8258D1)  // 淡紫
)

private fun tileGradient(name: String) =
    TILE_GRADIENTS[kotlin.math.abs(name.hashCode()) % TILE_GRADIENTS.size]

/** 清单行卡片：左侧 52dp 渐变小方块（清单名）+ 名称 + 条目数 + 箭头；长按拖动可调整顺序 */
@Composable
private fun CategoryTile(
    meta: ListWithMeta,
    isDragging: Boolean,
    currentIdsState: State<List<Long>>,
    onDragStateChange: (Long?) -> Unit,
    onReorder: suspend (fromIdx: Int, toIdx: Int) -> Unit,
    onClick: () -> Unit
) {
    val (c1, c2) = tileGradient(meta.list.name)

    Card(
        modifier = Modifier.fillMaxWidth()
            .then(if (isDragging) Modifier.shadow(24.dp, RoundedCornerShape(12.dp)) else Modifier)
            .then(
                dragReorderModifier(
                    itemId = meta.list.id,
                    isListMode = true,
                    gridColumns = 1,
                    currentIdsState = currentIdsState,
                    onDragStateChange = onDragStateChange,
                    onReorder = onReorder,
                    onTap = onClick,
                    onLongPress = {},
                    itemHeightDp = 72
                )
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(listOf(c1, c2))
                    ),
                contentAlignment = Alignment.Center
            ) {
                // 小方块里放清单名（基本两字；过长的自动缩小）
                val n = meta.list.name
                val fontSize = when {
                    n.length <= 2 -> 16.sp
                    n.length <= 4 -> 12.sp
                    else -> 10.sp
                }
                Text(
                    n,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.95f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = meta.list.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${meta.itemCount} 件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
