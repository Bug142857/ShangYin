package com.shangyin.app.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.ui.common.INNER_MODULE_ENTRIES
import com.shangyin.app.ui.common.InnerModuleEntry
import com.shangyin.app.ui.common.ModuleIconBox
import com.shangyin.app.ui.common.dragReorderModifier
import com.shangyin.app.ui.safePopBackStack

/**
 * 模块管理：里世界各模块入口的显示开关 + 顺序。
 * 点图标 = 开关（正常 / 变灰），长按图标拖动 = 调整顺序（v0.304，顺序会同步到主页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleManageScreen(nav: NavHostController) {
    val disabled by SettingsStore.disabledInnerModules.collectAsStateWithLifecycle()
    val savedTop by SettingsStore.innerModuleOrderTop.collectAsStateWithLifecycle()
    val savedBottom by SettingsStore.innerModuleOrderBottom.collectAsStateWithLifecycle()

    // 本地顺序（拖动时即时重排），落库后与 StateFlow 保持一致
    var topOrder by remember(savedTop) { mutableStateOf(savedTop) }
    var bottomOrder by remember(savedBottom) { mutableStateOf(savedBottom) }

    // 车机 / 平板横屏：一行放更多模块
    val gridColumns = com.shangyin.app.ui.common.adaptiveModuleColumns()

    fun entriesOf(keys: List<String>) = keys
        .mapNotNull { key -> INNER_MODULE_ENTRIES.firstOrNull { it.key == key } }

    fun persist(newTop: List<String>, newBottom: List<String>) {
        topOrder = newTop
        bottomOrder = newBottom
        SettingsStore.setInnerModuleOrder(newTop, newBottom)
    }

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
                "点图标开关（变灰 = 主页不显示）；长按图标拖动可调整顺序",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(10.dp))

            // 上区（本地模块）
            DraggableModuleGrid(
                entries = entriesOf(topOrder),
                columns = gridColumns,
                disabled = disabled,
                onReorder = { from, to ->
                    val list = topOrder.toMutableList()
                    val item = list.removeAt(from)
                    list.add(to, item)
                    persist(list, bottomOrder)
                }
            )

            // 分隔：细线 + 小字
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                HorizontalDivider(
                    thickness = 0.75.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "需要外网环境",
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

            // 下区（外网模块）
            DraggableModuleGrid(
                entries = entriesOf(bottomOrder),
                columns = gridColumns,
                disabled = disabled,
                onReorder = { from, to ->
                    val list = bottomOrder.toMutableList()
                    val item = list.removeAt(from)
                    list.add(to, item)
                    persist(topOrder, list)
                }
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 模块宫格：点 = 开关，长按拖动 = 排序；列数随屏幕宽度变化 */
@Composable
private fun DraggableModuleGrid(
    entries: List<InnerModuleEntry>,
    columns: Int,
    disabled: Set<String>,
    onReorder: (from: Int, to: Int) -> Unit
) {
    // 拖动换位时用到的 id 列表（key 的 hashCode 作为稳定 id，全部唯一）
    val ids = remember(entries) { entries.map { it.key.hashCode().toLong() } }
    val idsState = rememberUpdatedState(ids)

    entries.chunked(columns).forEachIndexed { rowIdx, rowEntries ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (rowIdx == 0) 0.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            rowEntries.forEach { m ->
                val on = m.key !in disabled
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            dragReorderModifier(
                                itemId = m.key.hashCode().toLong(),
                                isListMode = false,
                                gridColumns = columns,
                                currentIdsState = idsState,
                                onDragStateChange = { },
                                onReorder = { from, to -> onReorder(from, to) },
                                // 直接读实时开关状态再取反：避免闭包里捕获到旧值导致"第二次点击无效"
                                onTap = {
                                    SettingsStore.setInnerModuleEnabled(
                                        m.key,
                                        m.key in SettingsStore.disabledInnerModules.value
                                    )
                                },
                                onLongPress = { },
                                itemHeightDp = 100
                            )
                        )
                        .padding(vertical = 6.dp)
                ) {
                    ModuleIconBox(m.icon, size = 64.dp, enabled = on)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        m.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline
                    )
                }
            }
            repeat(columns - rowEntries.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
