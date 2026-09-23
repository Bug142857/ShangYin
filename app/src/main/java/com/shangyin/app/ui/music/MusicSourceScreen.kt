package com.shangyin.app.ui.music

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.music.MusicPlatform
import com.shangyin.app.data.music.MusicSourceScript
import com.shangyin.app.data.music.MusicSourceStore
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 本地导入的占位标签（用于按钮上的进度提示） */
private const val LOCAL_IMPORT_TAG = "本地文件"

/**
 * 音源管理：导入 / 启用 / 重新初始化 / 删除 LX 自定义音源脚本。
 * 音源只负责解析播放直链，搜索/歌单/歌词由 App 内置接口提供。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSourceScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scripts by MusicSourceStore.scripts.collectAsStateWithLifecycle()

    var importing by remember { mutableStateOf<String?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<MusicSourceScript?>(null) }

    LaunchedEffect(Unit) { MusicSourceStore.ensureInitialized() }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /** 从网络地址导入（推荐音源 / 手动粘贴地址共用） */
    fun importRemote(label: String, url: String) {
        if (url.isBlank()) {
            toast("请先填写音源地址")
            return
        }
        if (importing != null) return
        importing = label
        scope.launch {
            val result = MusicSourceStore.importFromUrl(url)
            importing = null
            toast(
                result.fold(
                    onSuccess = { "已导入：$it" },
                    onFailure = { "导入异常：${it.message ?: "未知错误"}（可在列表里重新初始化）" }
                )
            )
        }
    }

    // 本地导入：选 .js 文件，读成文本再入库
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importing = LOCAL_IMPORT_TAG
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                importing = null
                toast("读取文件失败")
                return@launch
            }
            val fileName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "local.js"
            val result = MusicSourceStore.importFromFile(fileName, text)
            importing = null
            toast(
                result.fold(
                    onSuccess = { "已导入：$it" },
                    onFailure = { "导入异常：${it.message ?: "未知错误"}（可在列表里重新初始化）" }
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音源管理") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            item(key = "desc") {
                Text(
                    "音源用于解析播放链接（洛雪音源脚本），搜索/歌单/歌词由 App 内置接口提供。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                )
            }

            // ---------------- 一键导入推荐音源 ----------------
            item(key = "h_rec") {
                MusicSectionTitle("一键导入推荐音源")
            }
            items(MusicSourceStore.RECOMMENDED, key = { "rec_" + it.rawUrl }) { rec ->
                val imported = isImported(scripts, rec)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rec.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            rec.rawUrl.substringAfter("lx-music-source/"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    when {
                        imported -> Text(
                            "已导入",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        importing == rec.name -> CircularProgressIndicator(
                            Modifier
                                .padding(horizontal = 16.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp
                        )

                        else -> TextButton(onClick = { importRemote(rec.name, rec.rawUrl) }) {
                            Text("导入")
                        }
                    }
                }
            }

            // ---------------- 网络导入 ----------------
            item(key = "h_url") {
                MusicSectionTitle("网络导入")
            }
            item(key = "url_input") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("粘贴音源 .js 地址") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = { importRemote("网络导入", urlInput.trim()) },
                        enabled = importing == null && urlInput.isNotBlank()
                    ) {
                        Text("导入")
                    }
                }
            }

            // ---------------- 本地导入 ----------------
            item(key = "h_local") {
                MusicSectionTitle("本地导入")
            }
            item(key = "local_import") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = importing == null
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("选择 .js 文件")
                    }
                    if (importing == LOCAL_IMPORT_TAG) {
                        Spacer(Modifier.size(12.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }

            // ---------------- 已导入 ----------------
            item(key = "h_list") {
                MusicSectionTitle("已导入 · ${scripts.size}")
            }
            if (scripts.isEmpty()) {
                item(key = "empty") {
                    MusicHint("还没有音源，先导入一个（推荐六音音源）")
                }
            } else {
                items(scripts, key = { "src_" + it.id }) { script ->
                    ScriptCard(
                        script = script,
                        onToggle = { enabled -> scope.launch { MusicSourceStore.setEnabled(script, enabled) } },
                        onReinit = {
                            scope.launch {
                                val err = MusicSourceStore.initOne(script)
                                toast(err ?: "「${script.name}」初始化完成")
                            }
                        },
                        onDelete = { deleting = script }
                    )
                }
            }
        }
    }

    // 删除二次确认
    deleting?.let { script ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除音源") },
            text = { Text("确定删除「${script.name}」吗？删除后需要重新导入。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        MusicSourceStore.delete(script)
                        toast("已删除：${script.name}")
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

/** 单个音源卡片：名称 / 版本作者 / 支持平台与音质 / 启用 / 重新初始化 / 删除 */
@Composable
private fun ScriptCard(
    script: MusicSourceScript,
    onToggle: (Boolean) -> Unit,
    onReinit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        script.name + if (script.enabled) "" else "（已停用）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    val meta = buildString {
                        if (script.version.isNotBlank()) append("v${script.version}")
                        if (script.author.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(script.author)
                        }
                        if (script.isLocal) {
                            if (isNotEmpty()) append(" · ")
                            append("本地导入")
                        }
                    }
                    Text(
                        meta.ifBlank { "未知版本" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = script.enabled, onCheckedChange = onToggle)
            }

            // 支持平台与音质；support 为空且有错误时显示红色原因
            val support = supportText(script)
            when {
                support.isNotBlank() -> Text(
                    support,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                !script.lastError.isNullOrBlank() -> Text(
                    "初始化失败：${script.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )

                else -> Text(
                    "尚未初始化或未声明支持的平台，点右侧刷新重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onReinit) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "重新初始化",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 支持情况文案：「网易云 高品/无损 · QQ音乐 高品」 */
private fun supportText(script: MusicSourceScript): String =
    MusicPlatform.entries.mapNotNull { platform ->
        val qualities = script.qualitiesOf(platform)
        if (qualities.isEmpty()) null
        else "${platform.label} ${qualities.joinToString("/") { it.label }}"
    }.joinToString(" · ")

/** 该推荐音源是否已导入（按来源地址或名称粗略匹配） */
private fun isImported(
    scripts: List<MusicSourceScript>,
    rec: MusicSourceStore.Recommended
): Boolean {
    val target = normalizeSourceName(rec.name)
    return scripts.any { script ->
        script.url == rec.rawUrl ||
            normalizeSourceName(script.name) == target ||
            (target.isNotBlank() && normalizeSourceName(script.name).contains(target))
    }
}

private fun normalizeSourceName(name: String): String =
    name.replace("音源", "").replace(" ", "").replace("-", "").lowercase()
