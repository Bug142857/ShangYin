package com.shangyin.app.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.ListWithMeta
import com.shangyin.app.ui.common.AppSearchField
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.common.dragReorderModifier
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack

/**
 * 「清单」模块（v0.316）：原表世界的搜索 + 分类 + 清单整体搬到这里。
 * 顶部搜索跳搜索页，下面按分类检索，主体是根清单卡片（长按拖动排序）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsHomeScreen(nav: NavHostController) {
    val lists by Repo.observeRootListsWithMeta(0)
        .collectAsStateWithLifecycle(initialValue = null)

    var searchCat by rememberSaveable { mutableStateOf("影视") }
    var query by rememberSaveable { mutableStateOf("") }

    fun goSearch() {
        val kw = query.trim()
        nav.safeNavigate(
            if (kw.isNotEmpty()) "search?cat=$searchCat&kw=${android.net.Uri.encode(kw)}"
            else "search?cat=$searchCat"
        )
        query = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("清单") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 搜索栏：回车或点右侧按钮跳搜索页执行搜索
            AppSearchField(
                value = query,
                onValueChange = { query = it },
                onSearch = { goSearch() },
                placeholder = "在${searchCat}中搜索…",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
            // 分类 chips
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                listOf("影视", "图书", "游戏", "人物").forEach { label ->
                    FilterChip(
                        selected = searchCat == label,
                        onClick = { searchCat = label },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            WorldListSection(nav, lists = lists, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun WorldListSection(
    nav: NavHostController,
    lists: List<ListWithMeta>?,
    modifier: Modifier = Modifier
) {
    // lists 为 null = 尚未加载（留白避免闪空态），空 = 真的没有清单
    var draggingId by remember { mutableStateOf<Long?>(null) }
    val currentIds = rememberUpdatedState(lists?.map { it.list.id } ?: emptyList())

    if (lists == null) {
        Box(modifier)
    } else if (lists.isEmpty()) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            EmptyView("还没有清单\n去搜索收藏喜欢的，或到设置里创建清单")
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = modifier
        ) {
            items(lists, key = { it.list.id }) { meta ->
                CategoryTile(
                    meta = meta,
                    isDragging = draggingId == meta.list.id,
                    currentIdsState = currentIds,
                    onDragStateChange = { draggingId = it },
                    onReorder = { from, to -> Repo.reorderRootList(0, from, to) },
                    onClick = { nav.safeNavigate("list/${meta.list.id}") }
                )
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
            // 统一用渐变色块 + 清单名（不展示封面图）
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(c1, c2))),
                contentAlignment = Alignment.Center
            ) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = meta.list.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 音乐清单与普通清单区分开（固定列表布局、无子清单、不拖拽排序）
                    if (meta.list.musicList) {
                        Spacer(Modifier.width(6.dp))
                        com.shangyin.app.ui.common.MusicListTag()
                    }
                }
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
