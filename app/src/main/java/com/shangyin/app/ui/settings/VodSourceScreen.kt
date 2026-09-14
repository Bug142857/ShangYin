package com.shangyin.app.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 片源管理页：维护苹果CMS V10 采集源（名称 + API 地址）。
 * 支持：单条添加/编辑/启停/删除 + 批量导入（JSON 数组、一行一个 URL、订阅链接下载）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VodSourceScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(SettingsStore.getVodSources()) }

    // 对话框状态
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VodSource?>(null) }
    var deleting by remember { mutableStateOf<VodSource?>(null) }
    var importing by remember { mutableStateOf(false) }

    // 链接测试状态
    var testing by remember { mutableStateOf(false) }
    var testDone by remember { mutableIntStateOf(0) }
    var testTotal by remember { mutableIntStateOf(0) }
    val testingIds = remember { mutableStateListOf<String>() }
    val testLock = remember { Any() }

    // 待导出的片源 JSON（选好目录后落盘）
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    // 导出目录选择器（与数据导出一致：先选目录再写入）
    val exportDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { rootUri: Uri? ->
        rootUri?.let { tree ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val json = pendingExportJson ?: return@let
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val fileName = "vod_sources_$ts.json"
            runCatching {
                val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)
                val newFile = docFile?.createFile("application/json", fileName)
                    ?: throw Exception("无法创建文件")
                context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
                    os.write(json.toByteArray())
                }
                Toast.makeText(context, "已导出到 $fileName", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            pendingExportJson = null
        }
    }

    fun persist(list: List<VodSource>) {
        sources = list
        SettingsStore.setVodSources(list)
    }

    /** 应用单源测试结果（线程安全：批量测试时多协程并发回写） */
    fun applyResult(tested: VodSource) {
        synchronized(testLock) {
            sources = sources.map { if (it.id == tested.id) tested else it }
        }
    }

    /** 测试全部源（并发 6，结果逐条上屏，结束统一落盘） */
    fun testAll() {
        if (testing) return
        val targets = sources
        if (targets.isEmpty()) return
        scope.launch {
            testing = true
            testDone = 0
            testTotal = targets.size
            val sem = Semaphore(6)
            targets.map { src ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        applyResult(VodClient.testSource(src))
                        testDone++
                    }
                }
            }.joinAll()
            persist(sources)
            testing = false
            val t = sources
            Toast.makeText(
                context,
                "测试完成：可用 ${t.count { it.testStatus == "ok" }} · " +
                    "已失效 ${t.count { it.testStatus == "dead" }} · " +
                    "需外网 ${t.count { it.testStatus == "proxy" }}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 测试单个源（点卡片触发） */
    fun testOne(src: VodSource) {
        if (testingIds.contains(src.id)) return
        testingIds.add(src.id)
        scope.launch(Dispatchers.IO) {
            val tested = VodClient.testSource(src)
            applyResult(tested)
            persist(sources)
            testingIds.remove(src.id)
        }
    }

    /** 导出片源配置：先选目录（与数据导出一致），写入 vod_sources_时间.json（JSON 数组，可直接再导入） */
    fun exportSources() {
        val list = sources
        if (list.isEmpty()) return
        pendingExportJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(VodSource.serializer()),
            list
        )
        exportDirPicker.launch(null)
    }

    /** 从文本导入片源（导出文件 JSON / 订阅 JSON / 一行一个地址），host 去重合并 */
    fun importSourcesFromText(text: String) {
        scope.launch {
            val (list, err) = withContext(Dispatchers.IO) { VodClient.parseImport(text) }
            if (err != null || list.isEmpty()) {
                Toast.makeText(context, "导入失败：${err ?: "未识别到有效源"}", Toast.LENGTH_LONG).show()
                return@launch
            }
            val existHosts = sources.map { hostOf(VodClient.normalizeBaseUrl(it.baseUrl)) }.toSet()
            val added = list.filter { hostOf(VodClient.normalizeBaseUrl(it.baseUrl)) !in existHosts }
            when {
                added.isEmpty() -> Toast.makeText(context, "导入的源均已存在，未新增", Toast.LENGTH_SHORT).show()
                added.size < list.size -> {
                    persist(sources + added)
                    Toast.makeText(
                        context,
                        "已导入 ${added.size} 个源（${list.size - added.size} 个已存在被跳过）",
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> {
                    persist(sources + added)
                    Toast.makeText(context, "已导入 ${added.size} 个源", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 导入文件选择器（用导出的 vod_sources_*.json 文件导入）
    val importFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                Toast.makeText(context, "读取文件失败", Toast.LENGTH_SHORT).show()
            } else {
                importSourcesFromText(text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("片源管理") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { testAll() }, enabled = !testing) {
                        Icon(Icons.Rounded.NetworkCheck, contentDescription = "测试全部")
                    }
                    IconButton(onClick = { importFilePicker.launch("*/*") }) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = "导入片源文件")
                    }
                    IconButton(onClick = { exportSources() }, enabled = sources.isNotEmpty()) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = "导出片源")
                    }
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = "批量导入")
                    }
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "配置影视采集源（苹果CMS V10）。测试后自动归目录：需外网的源进「需要外网」目录（搜索页 H1 分类专用），详情页在线观看只用「国内可访问」源。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // 测试进度/汇总
            if (testing) {
                Text(
                    "正在测试链接… $testDone/$testTotal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
            } else {
                val okN = sources.count { it.testStatus == "ok" }
                val deadN = sources.count { it.testStatus == "dead" }
                val proxyN = sources.count { it.testStatus == "proxy" }
                if (okN + deadN + proxyN > 0) {
                    Text(
                        "可用 $okN · 已失效 $deadN · 需外网 $proxyN",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            if (sources.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("还没有片源", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点右上角 ⬇ 批量导入，或 + 添加单个源",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 按目录分组展示：国内可访问在前，需要外网在后
            val cnList = sources.filter { it.region != "proxy" }
            val pxList = sources.filter { it.region == "proxy" }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (cnList.isNotEmpty()) {
                    item(key = "hdr_cn") { SectionHeader("国内可访问", cnList.size) }
                    items(cnList, key = { "cn_" + it.id }) { src ->
                        SourceCard(
                            src = src,
                            testing = testingIds.contains(src.id),
                            onTest = { testOne(src) },
                            onEdit = { editing = src },
                            onDelete = { deleting = src },
                            onToggle = { on ->
                                persist(sources.map {
                                    if (it.id == src.id) it.copy(enabled = on) else it
                                })
                            }
                        )
                    }
                }
                if (pxList.isNotEmpty()) {
                    item(key = "hdr_px") { SectionHeader("需要外网环境", pxList.size) }
                    items(pxList, key = { "px_" + it.id }) { src ->
                        SourceCard(
                            src = src,
                            testing = testingIds.contains(src.id),
                            onTest = { testOne(src) },
                            onEdit = { editing = src },
                            onDelete = { deleting = src },
                            onToggle = { on ->
                                persist(sources.map {
                                    if (it.id == src.id) it.copy(enabled = on) else it
                                })
                            }
                        )
                    }
                }
            }
        }
    }

    // ---------- 添加 / 编辑对话框 ----------
    if (showAdd || editing != null) {
        val initial = editing
        SourceEditDialog(
            initial = initial,
            onDismiss = { showAdd = false; editing = null },
            onConfirm = { name, url, region ->
                val normalized = VodClient.normalizeBaseUrl(url)
                if (name.isBlank() || !normalized.startsWith("http")) {
                    Toast.makeText(context, "请填写名称和有效的接口地址", Toast.LENGTH_SHORT).show()
                    return@SourceEditDialog
                }
                if (initial == null) {
                    // host 去重：已有同 host 源则提示
                    val newHost = hostOf(normalized)
                    if (sources.any { hostOf(VodClient.normalizeBaseUrl(it.baseUrl)) == newHost }) {
                        Toast.makeText(context, "已存在相同域名的源", Toast.LENGTH_SHORT).show()
                        return@SourceEditDialog
                    }
                    persist(sources + VodSource(
                        id = UUID.randomUUID().toString().take(12),
                        name = name.trim(),
                        baseUrl = normalized,
                        region = region
                    ))
                } else {
                    persist(sources.map {
                        if (it.id == initial.id) {
                            it.copy(name = name.trim(), baseUrl = normalized, region = region)
                        } else it
                    })
                }
                showAdd = false
                editing = null
            }
        )
    }

    // ---------- 删除确认 ----------
    deleting?.let { src ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除片源") },
            text = { Text("确定删除「${src.name}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    persist(sources.filterNot { it.id == src.id })
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }

    // ---------- 批量导入对话框 ----------
    if (showImport) {
        ImportDialog(
            importing = importing,
            onDismiss = { if (!importing) showImport = false },
            onImport = { text, asUrl ->
                scope.launch {
                    importing = true
                    val result = withContext(Dispatchers.IO) {
                        if (asUrl) VodClient.fetchSubscription(text.trim())
                        else VodClient.parseImport(text)
                    }
                    importing = false
                    val (list, err) = result
                    if (err != null || list.isEmpty()) {
                        Toast.makeText(context, "导入失败：${err ?: "未识别到有效源"}", Toast.LENGTH_LONG).show()
                    } else {
                        // host 去重合并
                        val existHosts = sources.map { hostOf(VodClient.normalizeBaseUrl(it.baseUrl)) }.toSet()
                        val added = list.filter { hostOf(VodClient.normalizeBaseUrl(it.baseUrl)) !in existHosts }
                        if (added.isEmpty()) {
                            Toast.makeText(context, "导入的源均已存在", Toast.LENGTH_SHORT).show()
                        } else {
                            persist(sources + added)
                            Toast.makeText(context, "已导入 ${added.size} 个源", Toast.LENGTH_SHORT).show()
                            showImport = false
                        }
                    }
                }
            }
        )
    }
}

private fun hostOf(url: String): String = runCatching {
    java.net.URI(url).host ?: url
}.getOrDefault(url)

/** 目录分组标题 */
@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        "$title · $count",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/** 单个片源卡片（点卡片 = 测试链接） */
@Composable
private fun SourceCard(
    src: VodSource,
    testing: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(onClick = onTest) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    src.name + if (src.enabled) "" else "（已停用）",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (src.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    VodClient.normalizeBaseUrl(src.baseUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when {
                        testing -> "测试中…"
                        src.testStatus == "ok" -> src.testMsg ?: "可用"
                        src.testStatus == "dead" -> src.testMsg ?: "已失效"
                        src.testStatus == "proxy" -> src.testMsg ?: "需外网"
                        else -> "未测试 · 点卡片测试"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        testing -> MaterialTheme.colorScheme.primary
                        src.testStatus == "ok" -> Color(0xFF2E7D32)
                        src.testStatus == "dead" -> MaterialTheme.colorScheme.error
                        src.testStatus == "proxy" -> Color(0xFFEF6C00)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Rounded.DriveFileRenameOutline,
                    contentDescription = "编辑",
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
            Switch(checked = src.enabled, onCheckedChange = onToggle)
        }
    }
}

/** 单条添加/编辑对话框 */
@Composable
private fun SourceEditDialog(
    initial: VodSource?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, region: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember {
        mutableStateOf(initial?.let { VodClient.normalizeBaseUrl(it.baseUrl) }.orEmpty())
    }
    var region by remember {
        mutableStateOf(if (initial?.region == "proxy") "proxy" else "cn")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加片源" else "编辑片源") },
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
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("接口地址") },
                    placeholder = { Text("https://xx.com/api.php/provide/vod") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "所属目录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    FilterChip(
                        selected = region != "proxy",
                        onClick = { region = "cn" },
                        label = { Text("国内可访问") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    FilterChip(
                        selected = region == "proxy",
                        onClick = { region = "proxy" },
                        label = { Text("需要外网") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url, region) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 批量导入对话框：粘贴内容（JSON/一行一个URL）或订阅链接下载 */
@Composable
private fun ImportDialog(
    importing: Boolean,
    onDismiss: () -> Unit,
    onImport: (text: String, asUrl: Boolean) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量导入片源") },
        text = {
            Column {
                Text(
                    "支持 KVideo 订阅 JSON、或一行一个接口地址（名称|地址）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("粘贴内容或订阅链接") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = !importing && text.isNotBlank(),
                    onClick = { onImport(text, true) }
                ) {
                    Text(if (importing) "下载中…" else "链接订阅")
                }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    enabled = !importing && text.isNotBlank(),
                    onClick = { onImport(text, false) }
                ) { Text("内容导入") }
            }
        },
        dismissButton = {
            TextButton(enabled = !importing, onClick = onDismiss) { Text("取消") }
        }
    )
}
