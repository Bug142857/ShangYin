package com.shangyin.app.ui.settings

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.bika.BikaClient
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账号管理：云端同步（坚果云 WebDAV）、豆瓣登录、哔咔登录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 豆瓣登录状态
    var doubanLoginKey by remember { mutableStateOf(0) }
    val isDoubanLoggedIn = remember(doubanLoginKey) { SettingsStore.isDoubanLoggedIn }
    var showDoubanLogout by remember { mutableStateOf(false) }
    val doubanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        doubanLoginKey++
        com.shangyin.app.data.douban.DoubanClient.onCookieChanged()
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "豆瓣登录成功", Toast.LENGTH_SHORT).show()
        }
    }

    // 哔咔登录状态
    var bikaLoginKey by remember { mutableStateOf(0) }
    val isBikaLoggedIn = remember(bikaLoginKey) { SettingsStore.bikaToken.isNotBlank() }
    var showBikaLogin by remember { mutableStateOf(false) }
    var showBikaLogout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号管理") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 云端同步
            SettingCard(
                icon = { Icon(Icons.Rounded.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "云端同步",
                subtitle = if (SettingsStore.isWebdavConfigured) {
                    val t = SettingsStore.lastCloudSync
                    if (t > 0L) "已连接 WebDAV · 上次同步 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(t))}"
                    else "已连接 WebDAV · 从未同步"
                } else "用 WebDAV 网盘备份/恢复，换手机不丢数据",
                onClick = { nav.safeNavigate("cloudsync") }
            )

            // 豆瓣登录
            SettingCard(
                icon = {
                    Icon(
                        Icons.Rounded.Person, contentDescription = null,
                        tint = if (isDoubanLoggedIn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                },
                title = "豆瓣登录",
                subtitle = if (isDoubanLoggedIn) "已登录，搜索结果更全" else "未登录，登录后搜索结果更全",
                onClick = {
                    if (isDoubanLoggedIn) showDoubanLogout = true
                    else doubanLauncher.launch(Intent(context, DoubanLoginActivity::class.java))
                }
            )

            // 哔咔登录
            SettingCard(
                icon = {
                    Icon(
                        Icons.Rounded.Person, contentDescription = null,
                        tint = if (isBikaLoggedIn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                },
                title = "哔咔登录",
                subtitle = if (isBikaLoggedIn) "已登录，可浏览本子漫画" else "未登录，登录后可在里世界浏览本子",
                onClick = {
                    if (isBikaLoggedIn) showBikaLogout = true
                    else showBikaLogin = true
                }
            )
        }
    }

    // 豆瓣登出确认
    if (showDoubanLogout) {
        AlertDialog(
            onDismissRequest = { showDoubanLogout = false },
            title = { Text("退出豆瓣登录") },
            text = { Text("退出后搜索结果可能不完整（部分条目需要登录才能搜到），确认退出？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        SettingsStore.clearDoubanLogin()
                        doubanLoginKey++
                        com.shangyin.app.data.douban.DoubanClient.onCookieChanged()
                        showDoubanLogout = false
                        Toast.makeText(context, "已退出豆瓣登录", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDoubanLogout = false }) { Text("取消") } }
        )
    }

    // 哔咔登录对话框
    if (showBikaLogin) {
        BikaLoginDialog(
            onDismiss = { showBikaLogin = false },
            onLoggedIn = {
                showBikaLogin = false
                bikaLoginKey++
                Toast.makeText(context, "哔咔登录成功", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 哔咔登出确认
    if (showBikaLogout) {
        AlertDialog(
            onDismissRequest = { showBikaLogout = false },
            title = { Text("退出哔咔登录") },
            text = { Text("退出后无法浏览本子漫画（里世界 → 本子），确认退出？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        SettingsStore.clearBikaToken()
                        bikaLoginKey++
                        showBikaLogout = false
                        Toast.makeText(context, "已退出哔咔登录", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showBikaLogout = false }) { Text("取消") } }
        )
    }
}

/** 设置页通用行卡片：图标 + 标题 + 副标题 + 右箭头 */
@Composable
private fun SettingCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
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

/** 哔咔登录对话框（用户名/邮箱 + 密码，凭据与哔咔官方 App、haka_comic 等客户端通用） */
@Composable
fun BikaLoginDialog(onDismiss: () -> Unit, onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun doLogin() {
        if (busy || email.isBlank() || password.isBlank()) return
        scope.launch {
            busy = true
            error = null
            runCatching { BikaClient.signIn(email.trim(), password) }
                .onSuccess {
                    SettingsStore.bikaToken = it
                    onLoggedIn()
                }
                .onFailure { error = it.message ?: "登录失败" }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("哔咔登录") },
        text = {
            Column {
                Text(
                    "哔咔账号与哔咔官方 App、haka_comic 等客户端通用。\n登录信息仅保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = { Text("用户名 / 邮箱") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = { Text("密码") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { doLogin() }),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { doLogin() }, enabled = email.isNotBlank() && password.isNotBlank()) {
                    Text("登录")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }) { Text("取消") }
        }
    )
}
