package com.shangyin.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack

/**
 * 片源管理页（设置入口）：只做分流，不放具体配置。
 * - 影视源配置：影视详情页在线观看 + H1 番号浏览用的采集源
 * - 动漫源配置：里世界「动漫」模块专用的采集源（与影视源完全独立）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManageScreen(nav: NavHostController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("片源管理") },
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
            // 影视源配置
            SourceEntryCard(
                icon = Icons.Rounded.OndemandVideo,
                title = "影视源配置",
                subtitle = run {
                    val n = SettingsStore.getVodSources().count { it.enabled }
                    if (n > 0) "已启用 $n 个源 · 影视在线观看 / 番号浏览"
                    else "配置影视采集源，收藏的影视可在线观看"
                },
                onClick = { nav.safeNavigate("vodSources") }
            )

            // 动漫源配置
            SourceEntryCard(
                icon = Icons.Rounded.PlayCircle,
                title = "动漫源配置",
                subtitle = run {
                    val n = SettingsStore.getAnimeSources().count { it.enabled }
                    if (n > 0) "已启用 $n 个源 · 仅供里世界「动漫」使用"
                    else "配置动漫采集源，里世界动漫模块使用"
                },
                onClick = { nav.safeNavigate("animeSources") }
            )
        }
    }
}

/** 片源管理里的单行入口卡片（与设置页卡片同款样式） */
@Composable
private fun SourceEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
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
