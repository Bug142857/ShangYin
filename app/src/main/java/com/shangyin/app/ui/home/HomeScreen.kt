package com.shangyin.app.ui.home

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.R
import com.shangyin.app.ui.common.INNER_MODULE_ENTRIES
import com.shangyin.app.ui.common.InnerModuleEntry
import com.shangyin.app.ui.common.ModuleIconBox
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.settings.SettingsStore

/**
 * 主页：模块宫格（v0.316 取消「表世界 / 里世界」双 Tab）。
 * 原表世界功能（搜索 + 分类 + 清单）已并入「清单」模块；首页只保留模块入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val context = LocalContext.current

    // 双击返回退出应用（2 秒内按两次）
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

    // 模块开关（设置 → 模块管理）：StateFlow 驱动，关掉的入口不在主页出现
    val disabledModules by SettingsStore.disabledInnerModules.collectAsStateWithLifecycle()
    // 模块顺序（模块管理里长按拖动可调整，主页按保存的顺序排）
    val topOrder by SettingsStore.innerModuleOrderTop.collectAsStateWithLifecycle()
    val bottomOrder by SettingsStore.innerModuleOrderBottom.collectAsStateWithLifecycle()

    fun entriesOf(keys: List<String>) = keys
        .mapNotNull { key -> INNER_MODULE_ENTRIES.firstOrNull { it.key == key } }
        .filter { it.key !in disabledModules }
    val topEntries = entriesOf(topOrder)
    val bottomEntries = entriesOf(bottomOrder)

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
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(4.dp))
            GridModuleRows(topEntries) { e -> nav.safeNavigate(e.route) }
            if (bottomEntries.isNotEmpty()) {
                // 中间分隔：细线 + 小字说明（v0.317：上下留白加大，两部分不再显得紧凑）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
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
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun stringAppName() = androidx.compose.ui.res.stringResource(R.string.app_name)

/** 3 列模块宫格（自动换行，空位补齐对齐） */
@Composable
private fun GridModuleRows(
    entries: List<InnerModuleEntry>,
    onClick: (InnerModuleEntry) -> Unit
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

/** 模块入口（squircle 图标 + 文字） */
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
        ModuleIconBox(icon, size = 64.dp)
        Spacer(Modifier.height(5.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
