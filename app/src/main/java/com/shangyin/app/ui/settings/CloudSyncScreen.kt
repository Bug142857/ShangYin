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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.navigation.NavHostController
import com.shangyin.app.data.Repo
import com.shangyin.app.data.buildExportJson
import com.shangyin.app.data.parseExportJson
import com.shangyin.app.data.sync.CloudSyncClient
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步：WebDAV（坚果云等）手动同步。
 * 每人用自己的网盘账号 → 数据天然隔离；手动点按钮上传/恢复，逻辑可控。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var configured by remember { mutableStateOf(SettingsStore.isWebdavConfigured) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var showUploadConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // 弹 Toast（组合外触发统一走这个）
    fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云同步", fontWeight = FontWeight.Bold) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!configured) {
                // ---------- 配置表单 ----------
                ConfigForm(
                    busy = busy,
                    onTestAndSave = { url, user, pass ->
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            val r = CloudSyncClient.testConnection(url, user, pass)
                            withContext(Dispatchers.Main) {
                                busy = false
                                when (r) {
                                    is CloudSyncClient.SyncResult.Success -> {
                                        SettingsStore.webdavUrl = url.trim()
                                        SettingsStore.webdavUser = user.trim()
                                        SettingsStore.webdavPass = pass
                                        configured = true
                                        showToast("已保存，可以上传备份了")
                                    }
                                    is CloudSyncClient.SyncResult.Error -> showToast(r.message)
                                }
                            }
                        }
                    },
                    showToast = ::showToast
                )
            } else {
                // ---------- 同步面板 ----------
                val lastSync = SettingsStore.lastCloudSync
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("WebDAV 已连接", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "账号：${SettingsStore.webdavUser}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (lastSync > 0L)
                                        "上次同步：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastSync))}"
                                    else "从未同步过",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 上传
                Button(
                    onClick = { showUploadConfirm = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy) "处理中…" else "上传备份到云端")
                }

                // 恢复
                OutlinedButton(
                    onClick = { showRestoreConfirm = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("从云端恢复")
                }

                Text(
                    "说明：上传会把云端旧备份覆盖；恢复会用云端数据替换本机数据（恢复前自动在本机留一份备份）。换手机/多设备时用它搬数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinkText("如何获取坚果云应用密码？（官方教程）", HELP_URL)

                // 清除配置
                TextButton(
                    onClick = { showClearConfirm = true },
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("清除 WebDAV 配置", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    // ---------- 确认弹窗 ----------

    if (showUploadConfirm) {
        AlertDialog(
            onDismissRequest = { showUploadConfirm = false },
            title = { Text("上传备份") },
            text = { Text("将把本机全部收藏数据上传，并覆盖云端旧备份。继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showUploadConfirm = false
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val json = buildExportJson(Repo.exportAll())
                                CloudSyncClient.upload(
                                    SettingsStore.webdavUrl, SettingsStore.webdavUser,
                                    SettingsStore.webdavPass, json
                                )
                            }
                        }
                        busy = false
                        result.fold(
                            onSuccess = { r ->
                                when (r) {
                                    is CloudSyncClient.SyncResult.Success -> {
                                        SettingsStore.lastCloudSync = System.currentTimeMillis()
                                        showToast("上传成功，云端已更新")
                                    }
                                    is CloudSyncClient.SyncResult.Error -> showToast(r.message)
                                }
                            },
                            onFailure = { showToast("上传失败：${it.message}") }
                        )
                    }
                }) { Text("上传") }
            },
            dismissButton = { TextButton(onClick = { showUploadConfirm = false }) { Text("取消") } }
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("从云端恢复") },
            text = { Text("将用云端备份替换本机全部数据（恢复前会先在本机自动留一份备份）。继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val json = CloudSyncClient.download(
                                    SettingsStore.webdavUrl, SettingsStore.webdavUser,
                                    SettingsStore.webdavPass
                                ).getOrThrow()

                                // 恢复前本地自动备份，防误覆盖
                                val backupJson = buildExportJson(Repo.exportAll())
                                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                File(context.filesDir, "auto_backup_$ts.json").writeText(backupJson)

                                Repo.importAll(parseExportJson(json))
                            }
                        }
                        busy = false
                        result.fold(
                            onSuccess = {
                                SettingsStore.lastCloudSync = System.currentTimeMillis()
                                showToast("恢复成功（本机原数据已自动备份）")
                            },
                            onFailure = { showToast("恢复失败：${it.message}") }
                        )
                    }
                }) { Text("恢复", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") } }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清除配置") },
            text = { Text("只清除本机保存的 WebDAV 配置，云端备份文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    SettingsStore.clearWebdavConfig()
                    configured = false
                    showClearConfirm = false
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } }
        )
    }
}

/** 坚果云官网（含注册入口） */
private const val HOME_URL = "https://www.jianguoyun.com/"

/** 坚果云应用密码官方教程 */
private const val HELP_URL = "https://help.jianguoyun.com/?p=2064"

/** 调用系统浏览器打开链接 */
private fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** 可点击链接文本（跳系统浏览器） */
@Composable
private fun LinkText(text: String, url: String) {
    val context = LocalContext.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { openInBrowser(context, url) }
    )
}

/** WebDAV 配置表单（首次使用） */
@Composable
private fun ConfigForm(
    busy: Boolean,
    onTestAndSave: (url: String, user: String, pass: String) -> Unit,
    showToast: (String) -> Unit
) {
    var url by remember { mutableStateOf(SettingsStore.webdavUrl.ifBlank { "https://dav.jianguoyun.com/dav/" }) }
    var user by remember { mutableStateOf(SettingsStore.webdavUser) }
    var pass by remember { mutableStateOf(SettingsStore.webdavPass) }

    Card {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("配置 WebDAV 网盘", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "推荐坚果云（免费），其他支持 WebDAV 的网盘也可",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("账号（坚果云填注册邮箱）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("应用密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "获取应用密码：登录坚果云 → 右上角账户信息 → 安全选项 → 第三方应用管理 → 添加应用密码（生成的是专用随机密码，不是登录密码）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinkText("① 打开坚果云官网（注册 / 登录）", HOME_URL)
            LinkText("② 查看应用密码图文教程", HELP_URL)

            Button(
                onClick = {
                    if (url.isBlank() || user.isBlank() || pass.isBlank()) {
                        showToast("请填写完整")
                        return@Button
                    }
                    onTestAndSave(url, user, pass)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "测试连接中…" else "测试并保存")
            }
        }
    }
}
