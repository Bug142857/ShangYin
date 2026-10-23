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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import com.shangyin.app.ui.common.INNER_MODULE_ENTRIES
import com.shangyin.app.ui.common.dragReorderModifier
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.settings.SettingsStore

/**
 * 主页：底部双标签「表世界 / 里世界」。
 * - 表世界：分类 chips + 搜索栏（跳搜索页）+ 豆瓣收藏清单
 * - 里世界：模块入口宫格（设置 → 模块管理 可开关）；清单只在表世界生效
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) } // 0=表世界 1=里世界

    // 表世界清单流在顶层收集：Tab 切换不重建流，避免闪一下"还没有清单"
    val surfaceLists by Repo.observeRootListsWithMeta(0)
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
                    icon = { Icon(Icons.Outlined.WbSunny, contentDescription = null) },
                    label = { Text("表世界") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.DarkMode, contentDescription = null) },
                    label = { Text("里世界") }
                )
            }
        }
    ) { pad ->
        when (tab) {
            0 -> SurfaceWorld(nav, surfaceLists, Modifier.padding(pad).fillMaxSize())
            else -> InnerWorld(nav, Modifier.padding(pad).fillMaxSize())
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
        // 搜索栏：回车或点右侧按钮跳搜索页执行搜索（v0.302：搜索栏在上、分类按钮在下）
        com.shangyin.app.ui.common.AppSearchField(
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

// ---------------- 里世界 ----------------

/** 里世界：模块入口宫格（清单功能只在表世界生效）。
 *  v0.301：每行 3 个模块，分上下两区——上区常规（游戏/音乐/电视/本子/吃瓜），
 *  中间小字分隔（需要外网环境），下区特殊源（图书/漫画/番号/P站/X站）。 */
@Composable
private fun InnerWorld(
    nav: NavHostController,
    modifier: Modifier = Modifier
) {
    // 模块开关（设置 → 模块管理）：StateFlow 驱动，关掉的入口不在主页出现
    val disabledModules by SettingsStore.disabledInnerModules.collectAsStateWithLifecycle()
    // 模块顺序（v0.304：模块管理里长按拖动可调整，主页按保存的顺序排）
    val topOrder by SettingsStore.innerModuleOrderTop.collectAsStateWithLifecycle()
    val bottomOrder by SettingsStore.innerModuleOrderBottom.collectAsStateWithLifecycle()
    val enabledKeys = disabledModules
    fun entriesOf(keys: List<String>) = keys
        .mapNotNull { key -> INNER_MODULE_ENTRIES.firstOrNull { it.key == key } }
        .filter { it.key !in enabledKeys }
    val topEntries = entriesOf(topOrder)
    val bottomEntries = entriesOf(bottomOrder)

    // 里世界只放模块宫格（清单功能只在表世界生效）
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(4.dp))
        GridModuleRows(topEntries) { e -> nav.safeNavigate(e.route) }
        // 中间分隔：细线 + 小字说明（细线 weight 平分剩余宽度，保证文字始终可见）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            HorizontalDivider(
                thickness = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "以下模块需要外网环境",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            HorizontalDivider(
                thickness = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.weight(1f)
            )
        }
        GridModuleRows(bottomEntries) { e -> nav.safeNavigate(e.route) }
        Spacer(Modifier.height(8.dp))
    }
}

/** 3 列模块宫格（自动换行，空位补齐对齐；v0.302 图标加大、行距收紧，一屏放下不滚动） */
@Composable
private fun GridModuleRows(
    entries: List<com.shangyin.app.ui.common.InnerModuleEntry>,
    onClick: (com.shangyin.app.ui.common.InnerModuleEntry) -> Unit
) {
    entries.chunked(3).forEachIndexed { rowIdx, rowEntries ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp, end = 16.dp,
                    top = if (rowIdx == 0) 0.dp else 6.dp
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            rowEntries.forEach { e ->
                InnerWorldEntry(
                    icon = e.icon,
                    title = e.title,
                    modifier = Modifier.weight(1f),
                    onClick = { onClick(e) }
                )
            }
            repeat(3 - rowEntries.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** 里世界入口（squircle 图标 + 文字，v0.302 图标加大到 64dp） */
@Composable
private fun InnerWorldEntry(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp)
    ) {
        com.shangyin.app.ui.common.ModuleIconBox(icon, size = 64.dp)
        Spacer(Modifier.height(5.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

// ---------------- 清单区（表世界） ----------------

@Composable
private fun WorldListSection(
    nav: NavHostController,
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
