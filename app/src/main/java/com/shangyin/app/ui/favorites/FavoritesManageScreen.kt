package com.shangyin.app.ui.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.InnerFavorites
import com.shangyin.app.ui.common.FAV_MODULE_ENTRIES
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack

/**
 * 收藏管理（v0.316，与下载管理同款）：按模块整理里世界收藏。
 * 卡片显示该模块收藏条数，点进对应模块的收藏页；清单收藏仍由「清单管理」负责。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesManageScreen(nav: NavHostController) {
    val favs by InnerFavorites.favs.collectAsStateWithLifecycle()
    val counts = remember(favs) { favs.groupingBy { it.module }.eachCount() }
    val total = favs.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收藏管理") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        // 车机 / 平板横屏：卡片自动分多列；手机竖屏保持单列
        val columns = com.shangyin.app.ui.common.adaptiveGridColumns(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(pad).fillMaxSize()
        ) {
            item(key = "fav-total", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "共 $total 条收藏 · 按模块整理",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(FAV_MODULE_ENTRIES, key = { it.key }) { m ->
                FavFolderCard(
                    icon = m.icon,
                    title = m.title,
                    meta = counts[m.key]?.let { "$it 条收藏" } ?: "暂无收藏",
                    onClick = { nav.safeNavigate("innerFavs/${m.key}") }
                )
            }
        }
    }
}

/** 模块收藏卡：图标 + 标题 + 数量 + 右箭头 */
@Composable
private fun FavFolderCard(
    icon: ImageVector,
    title: String,
    meta: String,
    onClick: () -> Unit
) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
