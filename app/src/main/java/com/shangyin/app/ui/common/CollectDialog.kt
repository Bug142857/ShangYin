package com.shangyin.app.ui.common

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shangyin.app.data.Repo
import kotlinx.coroutines.launch

/**
 * 收藏到里世界清单的对话框（番号视频 / 本子漫画 / 音乐共用）：
 * - 列出所有里世界（world=1）清单，点选即收藏
 * - 底部可快捷新建里世界清单；[music] = true（收藏音乐）时新建还能直接建「音乐清单」
 * item 数据由调用方通过 collect lambda 落库
 */
@Composable
fun CollectDialog(
    onDismiss: () -> Unit,
    collect: suspend (listId: Long) -> Boolean, // 返回是否成功
    music: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lists by Repo.observeRootListsWithMeta(1).collectAsStateWithLifecycle(initialValue = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun doCollect(listId: Long) {
        if (busy) return
        scope.launch {
            busy = true
            val ok = runCatching { collect(listId) }.getOrDefault(false)
            busy = false
            if (ok) {
                Toast.makeText(context, "已收藏", Toast.LENGTH_SHORT).show()
                onDismiss()
            } else {
                Toast.makeText(context, "收藏失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏到清单") },
        text = {
            Column {
                Text(
                    "选择一个里世界清单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (lists.isEmpty()) {
                    Text(
                        "还没有里世界清单，点下方新建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    LazyColumn(modifier = Modifier.height((lists.size * 52).coerceAtMost(280).dp)) {
                        items(lists, key = { it.list.id }) { meta ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { doCollect(meta.list.id) }
                                    .padding(vertical = 12.dp, horizontal = 4.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(meta.list.name, style = MaterialTheme.typography.bodyLarge)
                                        if (meta.list.musicList) {
                                            Spacer(Modifier.width(6.dp))
                                            MusicListTag()
                                        }
                                    }
                                    Text(
                                        "${meta.itemCount} 件",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建清单")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    // 新建里世界清单
    if (showCreate) {
        var name by remember { mutableStateOf("") }
        // 收藏音乐时默认新建「音乐清单」（可切成普通清单），其它内容不需要这个类型
        var asMusic by remember { mutableStateOf(music) }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建里世界清单") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("清单名称") },
                        singleLine = true
                    )
                    if (music) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "清单类型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = asMusic,
                                onClick = { asMusic = true },
                                label = { Text("音乐清单") }
                            )
                            FilterChip(
                                selected = !asMusic,
                                onClick = { asMusic = false },
                                label = { Text("普通清单") }
                            )
                        }
                    }
                    Text(
                        if (music && asMusic) "音乐清单：专放歌曲，固定列表布局、无子清单"
                        else "用于收藏番号视频 / 本子 / 漫画",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val id = Repo.createList(name, world = 1, musicList = music && asMusic)
                            showCreate = false
                            if (id > 0) doCollect(id)
                        }
                    }
                ) { Text("创建并收藏") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } }
        )
    }
}
