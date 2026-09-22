package com.shangyin.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.animeko.AnimekoClient
import com.shangyin.app.data.animeko.KIND_ANIMEKO
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
 * 采集源配置页参数：一套配置 = 一个独立的源列表（影视源 / 动漫源各一份）。
 * 两个页面共用下面同一份实现，避免出现"两份代码、改一处漏一处"的分叉。
 */
data class SourceConfig(
    val title: String,
    val description: String,
    /** 导出文件名前缀，如 vod_sources / anime_sources */
    val exportPrefix: String,
    val getSources: () -> List<VodSource>,
    val setSources: (List<VodSource>) -> Unit,
    /** 一键导入用的内置订阅（名称 → URL），空表示该页不支持订阅导入 */
    val presetSubscriptions: List<Pair<String, String>> = emptyList()
)

/** 影视源配置页（设置 → 片源管理 → 影视源配置） */
@Composable
fun VodSourceScreen(nav: NavHostController) {
    SourceConfigPage(
        nav = nav,
        config = SourceConfig(
            title = "影视源配置",
            description = "配置影视采集源（苹果CMS V10）。测试后自动归目录：需外网的源进「需要外网」目录（搜索页 H1 分类专用），详情页在线观看只用「国内可访问」源。",
            exportPrefix = "vod_sources",
            getSources = { SettingsStore.getVodSources() },
            setSources = { SettingsStore.setVodSources(it) }
        )
    )
}

/** 动漫源配置页（设置 → 片源管理 → 动漫源配置）：与影视源完全独立的另一份源列表 */
@Composable
fun AnimeSourceScreen(nav: NavHostController) {
    SourceConfigPage(
        nav = nav,
        config = SourceConfig(
            title = "动漫源配置",
            description = "配置动漫源，仅供「里世界 → 动漫」使用，与影视源互不影响。两类都支持：" +
                "① 苹果CMS 采集源（带「动漫」分类的站点）；② animeko 网页源（导入 animeko 订阅即可，网页源只支持搜索）。",
            exportPrefix = "anime_sources",
            getSources = { SettingsStore.getAnimeSources() },
            setSources = { SettingsStore.setAnimeSources(it) },
            presetSubscriptions = AnimekoClient.BUILTIN_SUBSCRIPTIONS
        )
    )
}

/**
 * 采集源配置页实现：维护苹果CMS V10 采集源（名称 + API 地址）。
 * 支持：单条添加/编辑/启停/删除 + 批量导入（JSON 数组、一行一个 URL、订阅链接下载）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceConfigPage(nav: NavHostController, config: SourceConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(config.getSources()) }

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
            val fileName = "${config.exportPrefix}_$ts.json"
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
        config.setSources(list)
    }

    /** 按源类型分发测试：苹果CMS 采集源探测接口，animeko 网页源用关键词探测站点搜索页 */
    suspend fun testSourceOf(s: VodSource): VodSource =
        if (s.kind == KIND_ANIMEKO) AnimekoClient.testSource(s) else VodClient.testSource(s)

    /**
     * 合并导入的源：苹果CMS 源按 host 去重，animeko 网页源按名称去重（网页源没有唯一 host）。
     * 返回 (新增数, 跳过数)。
     */
    fun mergeImported(list: List<VodSource>): Pair<Int, Int> {
        val existHosts = sources.filter { it.kind != KIND_ANIMEKO }
            .map { hostOf(VodClient.normalizeBaseUrl(it.baseUrl)) }.toSet()
        val existNames = sources.filter { it.kind == KIND_ANIMEKO }.map { it.name }.toSet()
        val added = list.filter { s ->
            if (s.kind == KIND_ANIMEKO) s.name !in existNames
            else hostOf(VodClient.normalizeBaseUrl(s.baseUrl)) !in existHosts
        }
        if (added.isNotEmpty()) persist(sources + added)
        return added.size to (list.size - added.size)
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
                        applyResult(testSourceOf(src))
                        // 与 applyResult 同一把锁：多协程并发时 testDone++ 是读改写，不加锁会丢计数
                        synchronized(testLock) { testDone++ }
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
            val tested = testSourceOf(src)
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

    /**
     * 从文本导入源，自动识别三种格式：
     * ① **animeko 订阅**（含 `exportedMediaSourceDataList` / `factoryId`）→ 转成本项目源（BT 源跳过）；
     * ② 本项目导出的源 JSON（含 kind/akConfig）；③ KVideo 订阅 JSON / 一行一个地址。
     */
    suspend fun importFromText(text: String) {
        val parsed = withContext(Dispatchers.IO) {
            if (text.contains("exportedMediaSourceDataList") || text.contains("\"factoryId\"")) {
                val r = AnimekoClient.parseImport(text)
                Triple(r.sources, r.error, r.skippedBt)
            } else {
                val (list, err) = VodClient.parseImport(text)
                Triple(list, err, 0)
            }
        }
        val (list, err, skippedBt) = parsed
        if (err != null || list.isEmpty()) {
            Toast.makeText(context, "导入失败：${err ?: "未识别到有效源"}", Toast.LENGTH_LONG).show()
            return
        }
        val (addedN, dupN) = mergeImported(list)
        val btNote = if (skippedBt > 0) "（跳过 $skippedBt 个 BT/磁力源：需 BT 下载引擎，本项目不支持）" else ""
        val msg = when {
            addedN == 0 -> "导入的源均已存在，未新增$btNote"
            dupN > 0 -> "已导入 $addedN 个源（$dupN 个已存在被跳过）$btNote"
            else -> "已导入 $addedN 个源$btNote"
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    /** 从文本导入（文件/粘贴走这里） */
    fun importSourcesFromText(text: String) {
        scope.launch { importFromText(text) }
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
                title = { Text(config.title) },
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
                config.description,
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
            onConfirm = { name, url, region, regionManual ->
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
                        region = region,
                        regionManual = regionManual
                    ))
                } else {
                    persist(sources.map {
                        if (it.id == initial.id) {
                            // 名称/地址/目录变更后旧测试结果不可信，一并清空待重测
                            it.copy(
                                name = name.trim(),
                                baseUrl = normalized,
                                region = region,
                                regionManual = regionManual,
                                testStatus = null,
                                testMsg = null,
                                testAt = 0L
                            )
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
            presets = config.presetSubscriptions,
            onDismiss = { if (!importing) showImport = false },
            onImport = { text, asUrl ->
                scope.launch {
                    importing = true
                    val body = withContext(Dispatchers.IO) {
                        if (asUrl) VodClient.fetchText(text.trim()) else text
                    }
                    importing = false
                    if (body.isNullOrBlank()) {
                        Toast.makeText(context, "订阅下载失败（链接/网络问题）", Toast.LENGTH_LONG).show()
                    } else {
                        showImport = false
                        importFromText(body)
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
                    src.name +
                        (if (src.kind == KIND_ANIMEKO) "（animeko 网页源）" else "") +
                        (if (src.enabled) "" else "（已停用）"),
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
                val manualTag = if (src.regionManual) " · 手动分组" else ""
                Text(
                    when {
                        testing -> "测试中…"
                        src.testStatus == "ok" -> (src.testMsg ?: "可用") + manualTag
                        src.testStatus == "dead" -> (src.testMsg ?: "已失效") + manualTag
                        src.testStatus == "proxy" -> (src.testMsg ?: "需外网") + manualTag
                        else -> "未测试 · 点卡片测试" + manualTag
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
    onConfirm: (name: String, url: String, region: String, regionManual: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember {
        mutableStateOf(initial?.let { VodClient.normalizeBaseUrl(it.baseUrl) }.orEmpty())
    }
    // animeko 网页源的地址来自订阅配置（akConfig），手改会导致配置对不上 → 地址锁定只读
    val urlLocked = initial?.kind == KIND_ANIMEKO
    var region by remember {
        mutableStateOf(if (initial?.region == "proxy") "proxy" else "cn")
    }
    // 用户在对话框里主动点过目录 chip → 视为手动分组（测试不再自动归组）
    var regionTouched by remember { mutableStateOf(false) }
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
                    label = { Text(if (urlLocked) "来源地址（订阅配置，不可改）" else "接口地址") },
                    placeholder = { Text("https://xx.com/api.php/provide/vod") },
                    singleLine = true,
                    enabled = !urlLocked,
                    modifier = Modifier.fillMaxWidth()
                )
                if (urlLocked) {
                    Text(
                        "animeko 网页源的页面地址与选择器都来自订阅配置，重新导入订阅即可更新。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "所属目录" + if (regionTouched) "（已手动选择，测试不再自动归组）" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    FilterChip(
                        selected = region != "proxy",
                        onClick = { region = "cn"; regionTouched = true },
                        label = { Text("国内可访问") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    FilterChip(
                        selected = region == "proxy",
                        onClick = { region = "proxy"; regionTouched = true },
                        label = { Text("需要外网") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(name, url, region, regionTouched || (initial?.regionManual ?: false))
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 批量导入对话框：粘贴内容（JSON/一行一个URL）、订阅链接下载，或一键导入内置 animeko 订阅 */
@Composable
private fun ImportDialog(
    importing: Boolean,
    presets: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onImport: (text: String, asUrl: Boolean) -> Unit
) {
    // 有内置订阅时预填第一条（用户点一下「链接订阅」即可导入）
    var text by remember { mutableStateOf(presets.firstOrNull()?.second.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量导入源") },
        text = {
            Column {
                Text(
                    "支持：animeko 订阅（URL 或导出 JSON，BT/磁力源会自动跳过）、KVideo 订阅 JSON、本项目导出的源 JSON，或一行一个接口地址（名称|地址）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (presets.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "内置订阅（点一下填入链接，再点「链接订阅」下载导入）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    presets.forEach { (name, url) ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { text = url }
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("粘贴内容或订阅链接") },
                    minLines = 3,
                    maxLines = 6,
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
