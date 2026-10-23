package com.shangyin.app.ui.settings

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.ui.common.INNER_MODULE_ENTRIES
import com.shangyin.app.ui.common.ModuleIconBox
import com.shangyin.app.ui.safePopBackStack

/**
 * 模块管理：里世界各模块入口的显示开关。
 * v0.301 与里世界主页同款布局：每行 3 个模块，上区（游戏/音乐/电视/本子/吃瓜）
 * + 中间小字分隔（需要外网环境）+ 下区（图书/漫画/番号/P站/X站），
 * 每格图标下方挂开关。关闭的模块不再出现在主页「里世界」宫格，数据不受影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleManageScreen(nav: NavHostController) {
    val disabled by SettingsStore.disabledInnerModules.collectAsStateWithLifecycle()

    fun entriesOf(keys: List<String>) = keys
        .mapNotNull { key -> INNER_MODULE_ENTRIES.firstOrNull { it.key == key } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模块管理") },
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
            Spacer(Modifier.height(6.dp))
            Text(
                "控制「里世界」各模块入口的显示，关闭后不在主页出现（收藏数据保留）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(14.dp))
            GridManageRows(entriesOf(listOf("game", "music", "tv", "h2", "melon")), disabled)
            // 中间分隔：细线 + 小字说明
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 14.dp)
            ) {
                HorizontalDivider(thickness = 0.75.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "需要外网环境",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                HorizontalDivider(thickness = 0.75.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            GridManageRows(entriesOf(listOf("book", "comic", "h1", "psite", "xsite")), disabled)
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 3 列模块宫格（图标 + 名称 + 开关），空位补齐对齐 */
@Composable
private fun GridManageRows(
    entries: List<com.shangyin.app.ui.common.InnerModuleEntry>,
    disabled: Set<String>
) {
    entries.chunked(3).forEachIndexed { rowIdx, rowEntries ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (rowIdx == 0) 0.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            rowEntries.forEach { m ->
                val on = m.key !in disabled
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { SettingsStore.setInnerModuleEnabled(m.key, !on) }
                        .padding(vertical = 8.dp)
                ) {
                    ModuleIconBox(m.icon, size = 52.dp, enabled = on)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        m.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline
                    )
                    // 紧凑开关（不占满宽度，避免格子过高）
                    Switch(
                        checked = on,
                        onCheckedChange = { enabled -> SettingsStore.setInnerModuleEnabled(m.key, enabled) },
                        modifier = Modifier.padding(top = 2.dp).height(30.dp)
                    )
                }
            }
            repeat(3 - rowEntries.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
