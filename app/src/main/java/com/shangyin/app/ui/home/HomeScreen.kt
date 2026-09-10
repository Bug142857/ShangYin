package com.shangyin.app.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.shangyin.app.ui.safeNavigate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val lists by Repo.observeRootListsWithMeta().collectAsStateWithLifecycle(initialValue = emptyList())

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
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(pad).fillMaxSize()
            ) {
                items(lists, key = { it.list.id }) { meta ->
                    CategoryTile(meta) { nav.safeNavigate("list/${meta.list.id}") }
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

/** 分类方块：渐变底 + 清单名首字（简洁封面，不使用条目图片）+ 分类名 + 条目数 */
@Composable
private fun CategoryTile(meta: ListWithMeta, onClick: () -> Unit) {
    val (c1, c2) = tileGradient(meta.list.name)

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(listOf(c1, c2))
                    ),
                contentAlignment = Alignment.Center
            ) {
                // 封面显示完整清单名（基本两字；过长的名单自动缩小字号）
                val n = meta.list.name
                val fontSize = when {
                    n.length <= 2 -> 40.sp
                    n.length <= 4 -> 30.sp
                    n.length <= 6 -> 22.sp
                    else -> 17.sp
                }
                Text(
                    n,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = meta.list.name,
                    style = MaterialTheme.typography.titleSmall,
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
        }
    }
}
