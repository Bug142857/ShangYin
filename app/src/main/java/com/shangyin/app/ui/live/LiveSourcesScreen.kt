package com.shangyin.app.ui.live

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.NetworkCheck
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 导出用的 JSON（prettyPrint 便于用户直接看/手改后再导入） */
private val liveJson = Json { prettyPrint = true }

/**
 * 电视源配置（自定义 M3U / M3U8）：
 * - 网络源：填名称 + M3U 地址
 * - 本地文件：从手机选 .m3u / .m3u8 文本文件导入（内容存进配置，卸载重装由配置文件备份带回）
 * - 可启用/停用、改名、删除，可测试可用性，可从文件 / 批量导入，也可导出成 JSON
 * - 导入后到「里世界 → 直播 → 电视」看频道
 * 交互与「影视源配置」(VodSourceScreen) 保持一致，只是措辞换成「电视源」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveSourcesScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf(SettingsStore.getLiveSources()) }
    var editing by remember { mutableStateOf<LiveSource?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    // 批量导入对话框状态
    var showImport by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    // 测试状态：结果只放内存（LiveSource 没有测试字段，也不写 SharedPreferences）——
    // 网络可用性是「此刻」的事实，落库会让用户以为它是配置的一部分，也会让导出的配置带上过期结论。
    val testResults = remember { mutableStateMapOf<String, LiveTestOutcome>() }
    val testingIds = remember { mutableStateListOf<String>() }
    var testing by remember { mutableStateOf(false) }
    var testDone by remember { mutableIntStateOf(0) }
    var testTotal by remember { mutableIntStateOf(0) }
    val testLock = remember { Any() }

    // 待导出的 JSON（选好目录后落盘）
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    fun persist(list: List<LiveSource>) {
        sources = list
        SettingsStore.setLiveSources(list)
        // 源变了：丢掉「我的源」会话缓存，回到直播页会重新解析
        LiveCache.customResults = null
        LiveCache.customGroup = null
    }

    /** 回写单条测试结果（批量测试时多协程并发写，加锁避免丢更新） */
    fun applyTest(id: String, outcome: LiveTestOutcome) {
        synchronized(testLock) { testResults[id] = outcome }
    }

    /**
     * 把解析出的源合并进现有配置（去重）。
     * 去重的理由：同一份导出文件/订阅被反复导入时不该越堆越多，
     * 否则直播页「电视」分页会出现一堆同名同址的重复源。
     * @return 是否真的新增了源
     */
    fun importSources(incoming: List<LiveSource>, failMsg: String? = null): Boolean {
        if (incoming.isEmpty()) {
            Toast.makeText(context, "导入失败：${failMsg ?: "未识别到有效电视源"}", Toast.LENGTH_LONG).show()
            return false
        }
        val keys = sources.map { dedupeKey(it) }.toMutableSet()
        val usedIds = sources.map { it.id }.toMutableSet()
        val added = ArrayList<LiveSource>()
        incoming.forEachIndexed { index, src ->
            if (!keys.add(dedupeKey(src))) return@forEachIndexed // 已有 / 本批已加过 → 跳过
            // 兜底：外部 JSON 可能带重复 id，而列表用 id 当 key，重复会崩
            added += if (src.id.isNotBlank() && usedIds.add(src.id)) src
            else src.copy(id = "live_${System.currentTimeMillis()}_$index").also { usedIds.add(it.id) }
        }
        when {
            added.isEmpty() -> {
                Toast.makeText(context, "导入的源均已存在，未新增", Toast.LENGTH_SHORT).show()
                return false
            }
            else -> {
                persist(sources + added)
                Toast.makeText(
                    context,
                    "已导入 ${added.size} 个电视源（${incoming.size - added.size} 个已存在被跳过）",
                    Toast.LENGTH_LONG
                ).show()
                return true
            }
        }
    }

    /** 测试全部电视源：Semaphore(4) 限流并发，结果逐条上屏（结束 Toast 汇总） */
    fun testAll() {
        if (testing) return
        val targets = sources
        if (targets.isEmpty()) return
        scope.launch {
            testing = true
            testDone = 0
            testTotal = targets.size
            val sem = Semaphore(4)
            targets.map { src ->
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        applyTest(src.id, probeLiveSource(src))
                        // testDone++ 是读改写，和 applyTest 同一把锁，否则并发会丢计数
                        synchronized(testLock) { testDone++ }
                    }
                }
            }.joinAll()
            testing = false
            val okN = targets.count { testResults[it.id]?.ok == true }
            Toast.makeText(
                context,
                "测试完成：可用 $okN · 已失效 ${targets.size - okN}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 测试单个电视源（点行内按钮触发，只测这一条） */
    fun testOne(src: LiveSource) {
        if (testingIds.contains(src.id)) return
        testingIds.add(src.id)
        scope.launch(Dispatchers.IO) {
            applyTest(src.id, probeLiveSource(src))
            testingIds.remove(src.id)
        }
    }

    // 导出目录选择器（与「数据导出」一致：先选目录再写入）
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
            val fileName = "live_sources_$ts.json"
            runCatching {
                val docFile = DocumentFile.fromTreeUri(context, tree)
                val newFile = docFile?.createFile("application/json", fileName)
                    ?: throw Exception("无法创建文件")
                context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
                    os.write(json.toByteArray())
                }
                // 导出后重写一次配置 + 清会话缓存，和其它导入导出入口保持同一条收尾路径
                persist(sources)
                Toast.makeText(context, "已导出到 $fileName", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            pendingExportJson = null
        }
    }

    /** 导出电视源配置：先选目录，再写 live_sources_时间.json（JSON 数组，本地源的 content 一并导出） */
    fun exportSources() {
        val list = sources
        if (list.isEmpty()) return
        pendingExportJson = liveJson.encodeToString(ListSerializer(LiveSource.serializer()), list)
        exportDirPicker.launch(null)
    }

    // 从文件导入（OpenDocument + */*：m3u 文本、导出/分享的 JSON 都能选）
    val importFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                Toast.makeText(context, "读取文件失败，请换个文件试试", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // M3U 文本存成本地源时用文件名当源名（和原来的单文件导入行为一致）
            val fileName = withContext(Dispatchers.IO) {
                runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
            }?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "本地电视源"
            val incoming = withContext(Dispatchers.IO) {
                parseImportedSources(text, fileName, System.currentTimeMillis())
            }
            importSources(incoming)
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
                    // 顶栏 4 个图标（添加走右下角 FAB，不再占位），手机上点得到
                    IconButton(onClick = { testAll() }, enabled = !testing) {
                        if (testing) {
                            Text("测试中…", style = MaterialTheme.typography.labelSmall)
                        } else {
                            Icon(Icons.Rounded.NetworkCheck, contentDescription = "测试全部电视源")
                        }
                    }
                    IconButton(onClick = { importFilePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = "从文件导入")
                    }
                    IconButton(onClick = { exportSources() }, enabled = sources.isNotEmpty()) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = "导出电视源")
                    }
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = "批量导入电视源")
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
                "支持 M3U / M3U8 电视源：填网络地址，或点右上角从手机里导入 .m3u 文件；" +
                    "右上角还能「测试全部电视源」、导出配置、批量导入（粘贴 JSON/地址列表/订阅链接）。" +
                    "导入后到「直播 → 电视」看频道（按分组展示、点频道直接播放，分组选择窗会显示每组频道数）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (testing) {
                Text(
                    "正在测试电视源… $testDone/$testTotal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sources, key = { it.id }) { src ->
                        val channelCount = remember(src.id) {
                            if (src.isLocal) runCatching { M3uClient.parse(src.content).size }.getOrDefault(0)
                            else null
                        }
                        val probing = testingIds.contains(src.id)
                        val outcome = testResults[src.id]
                        Card {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable { editing = src }
                                ) {
                                    Text(src.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(
                                        if (src.isLocal) "本地文件 · ${channelCount ?: 0} 个频道"
                                        else src.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // 未测过就不显示这行；结果只在本页内存里，重进页面即失效
                                    if (probing || outcome != null) {
                                        Text(
                                            if (probing) "测试中…" else outcome!!.msg,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (probing || outcome!!.ok) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(onClick = { testOne(src) }, enabled = !probing) {
                                    Icon(Icons.Rounded.NetworkCheck, contentDescription = "测试这个电视源")
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
                                IconButton(onClick = {
                                    testResults.remove(src.id)
                                    persist(sources.filterNot { it.id == src.id })
                                }) {
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
                            // 地址可能被改过，旧测试结果不再可信，清掉等重测
                            testResults.remove(target.id)
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

    // 批量导入：粘贴内容（JSON 数组 / 一行一个地址 / M3U 文本），或从订阅链接下载
    if (showImport) {
        ImportDialog(
            importing = importing,
            onDismiss = { if (!importing) showImport = false },
            onImport = { text, asUrl ->
                scope.launch {
                    importing = true
                    val incoming: List<LiveSource>
                    var failMsg: String? = null
                    if (asUrl) {
                        // 订阅链接：用 M3uClient.loadChannels 触发下载+解析（复用现成网络代码，不另写 OkHttp）
                        val link = text.trim()
                        val probe = withContext(Dispatchers.IO) {
                            runCatching {
                                M3uClient.loadChannels(LiveSource(id = "tmp", name = "tmp", url = link))
                            }.getOrNull()
                        }
                        if (probe == null || probe.error != null || probe.channels.isEmpty()) {
                            failMsg = probe?.error ?: "订阅链接下载失败"
                            incoming = emptyList()
                        } else {
                            incoming = listOf(
                                LiveSource(
                                    id = "live_${System.currentTimeMillis()}",
                                    name = nameForSubscription(link),
                                    url = link
                                )
                            )
                        }
                    } else {
                        incoming = withContext(Dispatchers.IO) {
                            parseImportedSources(text, "导入的电视源", System.currentTimeMillis())
                        }
                    }
                    importing = false
                    if (importSources(incoming, failMsg)) showImport = false
                }
            }
        )
    }
}

/** 内存里的测试结论：ok = 可用，msg = 给用户看的一句话 */
private data class LiveTestOutcome(val ok: Boolean, val msg: String)

/** 测试单个电视源：直接用 M3uClient.loadChannels（网络源会真去下载并解析），正常/失败都回给 UI */
private suspend fun probeLiveSource(src: LiveSource): LiveTestOutcome {
    val r = runCatching { M3uClient.loadChannels(src) }.getOrNull()
        ?: return LiveTestOutcome(false, "测试异常，请重试")
    val err = r.error
    return if (err != null && r.channels.isEmpty()) LiveTestOutcome(false, err)
    else LiveTestOutcome(true, "可用 · 共 ${r.channels.size} 个频道")
}

/** 去重键：网络源按 url；本地源没有稳定地址，用 名称+内容指纹（内容一样就视为同一个源） */
private fun dedupeKey(src: LiveSource): String =
    if (src.isLocal) "local|${src.name}|${src.content.hashCode()}" else "url|${src.url}"

/** 订阅链接生成网络源时的名字：优先用域名，取不到就兜底 */
private fun nameForSubscription(url: String): String = runCatching {
    java.net.URI(url).host
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "订阅电视源"

/**
 * 识别导入文本，支持三种内容：
 * 1) LiveSource 的 JSON 数组（本页导出的 live_sources_*.json 或别人分享的配置）
 * 2) 一行一个 http(s) 地址 → 生成「导入源 N」网络源
 * 3) 纯 M3U 文本（#EXTM3U 开头，或至少含 #EXTINF 的片段）→ 存成一个本地源，名字用文件名
 * 识别不出返回空列表，由调用方统一提示失败。
 */
private fun parseImportedSources(text: String, m3uName: String, now: Long): List<LiveSource> {
    val raw = text.trim()
    if (raw.isEmpty()) return emptyList()

    // 3) M3U 文本：整段存进 content，这样源不依赖网络、也能随配置备份带走
    if (raw.startsWith("#EXTM3U", ignoreCase = true)) {
        return if (M3uClient.parse(raw).isEmpty()) emptyList()
        else listOf(
            LiveSource(
                id = "live_$now",
                name = m3uName,
                url = LiveSource.LOCAL_PREFIX + m3uName,
                content = raw
            )
        )
    }

    // 1) JSON 数组（多字段结构对不上就抛异常，交给后面的格式继续判断）
    runCatching { liveJson.decodeFromString(ListSerializer(LiveSource.serializer()), raw) }
        .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

    // 2) 一行一个 http(s) 地址
    val urls = raw.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        .toList()
    if (urls.isNotEmpty()) {
        return urls.mapIndexed { i, u ->
            LiveSource(id = "live_${now}_$i", name = "导入源 ${i + 1}", url = u)
        }
    }

    // 兜底：省了 #EXTM3U 头的 M3U 片段
    return if (M3uClient.parse(raw).isEmpty()) emptyList()
    else listOf(
        LiveSource(
            id = "live_$now",
            name = m3uName,
            url = LiveSource.LOCAL_PREFIX + m3uName,
            content = raw
        )
    )
}

/** 批量导入对话框：粘贴内容（JSON 数组 / 一行一个地址 / M3U 文本）或走订阅链接下载 */
@Composable
private fun ImportDialog(
    importing: Boolean,
    onDismiss: () -> Unit,
    onImport: (text: String, asUrl: Boolean) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量导入电视源") },
        text = {
            Column {
                Text(
                    "支持导出的 JSON 数组、一行一个 M3U / M3U8 地址，或直接粘贴 M3U 文本",
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
