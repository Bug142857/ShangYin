package com.shangyin.app.ui.home

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.R
import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.ListWithMeta
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.common.dragReorderModifier
import com.shangyin.app.ui.safeNavigate

/**
 * 主页：底部双标签「表世界 / 里世界」。
 * - 表世界：分类 chips + 搜索栏（跳搜索页）+ 豆瓣收藏清单
 * - 里世界：番号 / 本子 / 漫画 / 游戏 / 图书 / 电视 入口 + 里世界收藏清单
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) } // 0=表世界 1=里世界

    // 两个世界的清单流在顶层收集：Tab 切换不重建流，避免闪一下"还没有清单"
    val surfaceLists by Repo.observeRootListsWithMeta(0)
        .collectAsStateWithLifecycle(initialValue = null)
    val innerLists by Repo.observeRootListsWithMeta(1)
        .collectAsStateWithLifecycle(initialValue = null)

    // 双击返回退出应用（2 秒内按两次）；表/里世界是同级 Tab，返回键不切 Tab
    var lastBackAt by remember { mutableStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 2000) {
            (context as? Activity)?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(context, "再按一次退出应用", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringAppName(), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { nav.safeNavigate("settings") }) {
                        Icon(Icons.Rounded.Menu, contentDescription = "设置")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
                    label = { Text("表世界") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.Visibility, contentDescription = null) },
                    label = { Text("里世界") }
                )
            }
        }
    ) { pad ->
        when (tab) {
            0 -> SurfaceWorld(nav, surfaceLists, Modifier.padding(pad).fillMaxSize())
            else -> InnerWorld(nav, innerLists, Modifier.padding(pad).fillMaxSize())
        }
    }
}

@Composable
private fun stringAppName() = androidx.compose.ui.res.stringResource(R.string.app_name)

// ---------------- 表世界 ----------------

/** 表世界：搜索栏 + 收藏清单 */
@Composable
private fun SurfaceWorld(
    nav: NavHostController,
    lists: List<ListWithMeta>?,
    modifier: Modifier = Modifier
) {
    // 主页选中的搜索分类（跳搜索页时带上）
    var searchCat by rememberSaveable { mutableStateOf("影视") }
    var query by rememberSaveable { mutableStateOf("") }

    /** 跳搜索页（带分类 + 可选关键词） */
    fun goSearch() {
        val kw = query.trim()
        nav.safeNavigate(
            if (kw.isNotEmpty()) "search?cat=$searchCat&kw=${android.net.Uri.encode(kw)}"
            else "search?cat=$searchCat"
        )
        query = ""
    }

    Column(modifier) {
        // 分类 chips
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp)
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
        // 搜索栏：回车或点右侧按钮跳搜索页执行搜索
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("在${searchCat}中搜索…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { goSearch() }),
            trailingIcon = {
                IconButton(onClick = { goSearch() }) {
                    Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = "搜索")
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        WorldListSection(nav, world = 0, lists = lists, modifier = Modifier.weight(1f))
    }
}

// ---------------- 里世界 ----------------

/** 里世界：番号 / 本子 / 漫画 / 游戏 / 图书 / 电视 入口 + 里世界清单 */
@Composable
private fun InnerWorld(
    nav: NavHostController,
    lists: List<ListWithMeta>?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InnerWorldEntry(
                icon = Icons.Rounded.OndemandVideo,
                title = "番号",
                modifier = Modifier.weight(1f),
                onClick = { nav.safeNavigate("h1search") }
            )
            InnerWorldEntry(
                icon = Icons.Rounded.MenuBook,
                title = "本子",
                modifier = Modifier.weight(1f),
                onClick = { nav.safeNavigate("h2search") }
            )
            InnerWorldEntry(
                icon = Icons.Rounded.AutoStories,
                title = "漫画",
                modifier = Modifier.weight(1f),
                onClick = { nav.safeNavigate("comicHome") }
            )
            InnerWorldEntry(
                icon = Icons.Rounded.SportsEsports,
                title = "游戏",
                modifier = Modifier.weight(1f),
                onClick = { nav.safeNavigate("gameHome") }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InnerWorldEntry(
                icon = Icons.Rounded.Book,
                title = "图书",
                modifier = Modifier.weight(1f),
                onClick = { nav.safeNavigate("bookHome") }
            )
            InnerWorldEntry(
                icon = Icons.Rounded.LiveTv,
                title = "电视",
                modifier = Modifier.weight(1f),
                onClick = { nav.safeNavigate("tvHome") }
            )
            InnerWorldEntry(
                icon = Icons.Rounded.MusicNote,
                title = "音乐",
                modifier = Modifier.weight(1f),
                onClick = { nav.safeNavigate("musicHome") }
            )
        }
        WorldListSection(nav, world = 1, lists = lists, modifier = Modifier.weight(1f))
    }
}

/** 里世界入口卡片 */
@Composable
private fun InnerWorldEntry(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(modifier = modifier.clickable(enabled = enabled) { onClick() }) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ---------------- 清单区（两个世界共用） ----------------

@Composable
private fun WorldListSection(
    nav: NavHostController,
    world: Int,
    lists: List<ListWithMeta>?,
    modifier: Modifier = Modifier
) {
    // lists 由 HomeScreen 顶层收集传入：null=尚未加载（留白避免闪空态），空=真的没有清单
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
            EmptyView(
                if (world == 0) "还没有清单\n去搜索收藏喜欢的，或到设置里创建清单"
                else "还没有里世界清单\n到 设置 → 清单管理 创建，用来收藏番号视频和本子"
            )
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
                    onReorder = { from, to -> Repo.reorderRootList(world, from, to) },
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
