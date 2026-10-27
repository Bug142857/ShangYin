package com.shangyin.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.buildExportJson
import com.shangyin.app.data.parseExportJson
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据管理页：云端同步（WebDAV）/ 导出（选目录保存 JSON）/ 导入（选文件恢复）。
 * 备份包含：收藏条目、清单及层级、清单归属排序、片源配置、里世界爱心收藏、
 * 配置快照（登录态、主题/昵称等设置项、电视源、下载任务记录）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataManageScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    // 导入文件选择器
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { pendingImportUri = it }
    }

    // 导出目录选择器（选目录后写入 老郑分享_时间.json）
    val exportDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { rootUri: Uri? ->
        rootUri?.let { tree ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val json = pendingExportJson ?: run {
                Toast.makeText(context, "导出数据已失效，请重新点「导出数据」", Toast.LENGTH_LONG).show()
                return@let
            }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "老郑分享_$ts.json"
            runCatching {
                val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)
                val newFile = docFile?.createFile("application/json", fileName)
                    ?: throw Exception("无法创建文件")
                val os = context.contentResolver.openOutputStream(newFile.uri)
                    ?: throw Exception("无法写入文件")
                os.use { it.write(json.toByteArray()) }
                Toast.makeText(context, "已导出到 $fileName", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            pendingExportJson = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据管理") },
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
                .padding(16.dp)
        ) {
            Text(
                "备份包含：收藏条目（含评分/笔记）、清单及子清单层级、清单归属排序、片源配置（含目录归属与测试结果）、里世界爱心收藏（吃瓜/X站/P站/番号/本子/漫画/电视/音乐/观影）、漫画/本子阅读进度（上次看到）、配置快照（各站登录态、主题/昵称等设置、电视源、下载任务记录）。本机已下载的视频 / 音乐 / 漫画 / 书籍文件不在 JSON 内。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(12.dp))

            // 云端同步（WebDAV，从账号管理挪来）
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        nav.safeNavigate("cloudsync")
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Cloud, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("云端同步", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (SettingsStore.isWebdavConfigured) {
                                val t = SettingsStore.lastCloudSync
                                if (t > 0L) "已连接 WebDAV · 上次同步 ${
                                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))
                                }"
                                else "已连接 WebDAV · 从未同步"
                            } else "用 WebDAV 网盘备份/恢复，换手机不丢数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(Modifier.size(12.dp))

            // 导出数据
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    buildExportJson(Repo.exportAll())
                                }
                            }.onSuccess { json ->
                                pendingExportJson = json
                                exportDirPicker.launch(null)
                            }.onFailure { e ->
                                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Add, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导出数据", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "选择目录，导出为 JSON 备份文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(Modifier.size(12.dp))

            // 导入数据
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        importPicker.launch("*/*")
                    }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Edit, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导入数据", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "从 JSON 备份文件恢复（会清空当前数据）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).rotate(180f),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    // 导入确认
    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("确认导入") },
            text = { Text("导入会用备份替换当前收藏与清单；备份里包含的爱心收藏、阅读进度与配置也会覆盖（老备份没有的字段保留本机现有值）。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val json = context.contentResolver.openInputStream(uri)
                                    ?.bufferedReader()
                                    ?.use { br -> br.readText() }
                                    ?: throw Exception("无法读取备份文件")
                                val data = parseExportJson(json)
                                Repo.importAll(data)
                            }
                            Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
                        }.onFailure { e ->
                            Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                        pendingImportUri = null
                    }
                }) { Text("确认导入", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingImportUri = null }) { Text("取消") } }
        )
    }
}
