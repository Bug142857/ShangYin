package com.shangyin.app.ui.live

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
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.OndemandVideo
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore

/**
 * 片源管理（总入口）：把两类源配置收在一处
 * - 影视源配置：苹果 CMS 采集源（在线观影 / 番号，原来就叫「片源管理」）
 * - 电视源配置：自定义 M3U / M3U8 电视源（里世界 → 直播 → 电视）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceHubScreen(nav: NavHostController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("片源管理", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 影视源配置（原「片源管理」）
            SourceHubCard(
                icon = Icons.Rounded.OndemandVideo,
                title = "影视源配置",
                subtitle = SettingsStore.getVodSources().let { list ->
                    val n = list.count { it.enabled }
                    if (n > 0) "已启用 $n 个采集源 · 影视详情页可在线观看"
                    else "配置苹果 CMS 采集源，收藏的影视可在线观看"
                },
                onClick = { nav.safeNavigate("vodSources") }
            )

            // 电视源配置
            SourceHubCard(
                icon = Icons.Rounded.LiveTv,
                title = "电视源配置",
                subtitle = SettingsStore.getLiveSources().let { list ->
                    val n = list.count { it.enabled }
                    if (n > 0) "已启用 $n 个电视源 · 里世界「直播 → 电视」看频道"
                    else "导入 M3U / M3U8 电视源（网络地址或本地文件）"
                },
                onClick = { nav.safeNavigate("liveSources") }
            )
        }
    }
}

/** 片源管理里的入口卡片：图标 + 标题 + 副标题 + 右箭头 */
@Composable
private fun SourceHubCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
