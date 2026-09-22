package com.shangyin.app.ui.live

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavHostController
import com.shangyin.app.data.live.LiveSource
import com.shangyin.app.data.live.M3uClient
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore

/**
 * 电视源配置（自定义 M3U / M3U8）：
 * - 网络源：填名称 + M3U 地址
 * - 本地文件：从手机选 .m3u / .m3u8 文本文件导入（内容存进配置，卸载重装由配置文件备份带回）
 * - 可启用/停用、改名、删除；导入后到「里世界 → 直播 → 我的源」看频道
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSourcesScreen(nav: NavHostController) {
    val context = LocalContext.current
    var sources by remember { mutableStateOf(SettingsStore.getLiveSources()) }
    var editing by remember { mutableStateOf<LiveSource?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    fun persist(list: List<LiveSource>) {
        sources = list
        SettingsStore.setLiveSources(list)
        // 源变了：丢掉「我的源」会话缓存，回到直播页会重新解析
        LiveCache.customResults = null
        LiveCache.customGroup = null
    }

    // 本地文件导入（m3u/m3u8 都是文本，用 */* 兼容各家文件管理器给的 MIME）
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }.orEmpty()
            val channels = M3uClient.parse(text)
            if (channels.isEmpty()) {
                Toast.makeText(context, "这个文件里没解析到频道（不是 M3U 列表？）", Toast.LENGTH_LONG).show()
                return@runCatching
            }
            val name = DocumentFile.fromSingleUri(context, uri)?.name?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() } ?: "本地直播源"
            persist(
                sources + LiveSource(
                    id = "live_${System.currentTimeMillis()}",
                    name = name,
                    url = LiveSource.LOCAL_PREFIX + name,
                    content = text
                )
            )
            Toast.makeText(context, "已导入「$name」共 ${channels.size} 个频道", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "读取文件失败，请换个文件试试", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电视源配置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = "从文件导入")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("添加电视源") }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "支持 M3U / M3U8 电视源：填网络地址，或点右上角从手机里导入 .m3u 文件。" +
                    "导入后到「直播 → 电视」看频道（按分组展示、点频道直接播放，分组选择窗会显示每组频道数）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (sources.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "还没有电视源\n点右下角「添加电视源」，或从本地导入 m3u 文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sources, key = { it.id }) { src ->
                        val channelCount = remember(src.id) {
                            if (src.isLocal) runCatching { M3uClient.parse(src.content).size }.getOrDefault(0)
                            else null
                        }
                        Card {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { editing = src }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(src.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(
                                        if (src.isLocal) "本地文件 · ${channelCount ?: 0} 个频道"
                                        else src.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = src.enabled,
                                    onCheckedChange = { on ->
                                        persist(sources.map { if (it.id == src.id) it.copy(enabled = on) else it })
                                    }
                                )
                                IconButton(onClick = { editing = src }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "编辑")
                                }
                                IconButton(onClick = { persist(sources.filterNot { it.id == src.id }) }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加 / 编辑
    val target = if (showAdd) LiveSource(id = "", name = "", url = "") else editing
    if (target != null) {
        var name by remember(target.id) { mutableStateOf(target.name) }
        var url by remember(target.id) { mutableStateOf(if (target.isLocal) "" else target.url) }
        AlertDialog(
            onDismissRequest = { showAdd = false; editing = null },
            title = { Text(if (showAdd) "添加电视源" else "编辑电视源") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    if (target.isLocal) {
                        Text(
                            "本地导入的源（内容存在本机配置里），只改名称即可",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("M3U 地址（http/https）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                val enabled = name.isNotBlank() && (target.isLocal || url.trim().startsWith("http"))
                TextButton(
                    enabled = enabled,
                    onClick = {
                        val trimmedUrl = url.trim()
                        if (showAdd) {
                            persist(
                                sources + LiveSource(
                                    id = "live_${System.currentTimeMillis()}",
                                    name = name.trim(),
                                    url = trimmedUrl
                                )
                            )
                        } else {
                            persist(
                                sources.map {
                                    if (it.id == target.id) {
                                        it.copy(name = name.trim(), url = if (it.isLocal) it.url else trimmedUrl)
                                    } else it
                                }
                            )
                        }
                        showAdd = false
                        editing = null
                    }
                ) { Text(if (showAdd) "添加" else "保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; editing = null }) { Text("取消") }
            }
        )
    }
}
