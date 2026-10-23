package com.shangyin.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.theme.ACCENT_OPTIONS

/**
 * 主题管理（v0.304 独立页面）：
 * 显示模式（跟随系统/浅色/深色）+ 主题色（跟随系统动态/墨绿/紫/蓝/青/橙/粉）。
 * 点选即写入 StateFlow，主界面立刻按新配色重绘（不再 recreate）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeManageScreen(nav: NavHostController) {
    val themeMode by SettingsStore.themeFlow.collectAsStateWithLifecycle()
    val accentKey by SettingsStore.accentFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("主题管理") },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 实时预览：当前主题色 + 容器色
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "预览",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "老郑分享 · 当前主题",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "主色按钮",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("显示模式", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SettingsStore.THEME_FOLLOW to "跟随系统",
                    SettingsStore.THEME_LIGHT to "浅色",
                    SettingsStore.THEME_DARK to "深色"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = themeMode == key,
                        onClick = { SettingsStore.theme = key },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("主题色", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "点颜色立即生效（跟随系统 = Android 12+ 用壁纸取色）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ACCENT_OPTIONS.chunked(2).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { option ->
                            val selected = accentKey == option.key
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { SettingsStore.accent = option.key }
                                    .padding(vertical = 10.dp, horizontal = 8.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .background(
                                            if (option.key == "dynamic") MaterialTheme.colorScheme.primary
                                            else option.lightPrimary,
                                            CircleShape
                                        )
                                        .then(
                                            if (selected) Modifier.border(
                                                2.dp, MaterialTheme.colorScheme.onSurface, CircleShape
                                            ) else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
