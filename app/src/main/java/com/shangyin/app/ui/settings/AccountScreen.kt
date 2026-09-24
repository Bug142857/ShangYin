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
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SportsEsports
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.bika.BikaClient
import com.shangyin.app.ui.common.PasswordField
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 登录态校验（带超时）：通道卡住（如 zlib 走隐藏 WebView、站点无响应）时按「无法确认」处理，
 * 不能一直停在"正在检测登录状态…"，更不能因此误报"登录已失效"。
 */
private suspend fun checkSession(block: suspend () -> Boolean?): Boolean? =
    runCatching { withTimeoutOrNull(25_000L) { block() } }.getOrNull()

/**
 * 账号管理：云端同步（坚果云 WebDAV）、豆瓣登录、哔咔登录、
 * 无忧游戏库登录、Z-Library 登录（线路域名在登录页内切换，登录成功后自动写回设置）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 豆瓣登录状态
    var doubanLoginKey by remember { mutableStateOf(0) }
    val isDoubanLoggedIn = remember(doubanLoginKey) { SettingsStore.isDoubanLoggedIn }
    // 服务端校验的登录态：null = 未确认（可能无法判断）；false = Cookie 已过期（本地仍显示已登录）
    // checked：区分"还在检测"与"检测完了但测不出来"——否则后者会永远停在"正在检测…"
    var doubanSessionOk by remember(doubanLoginKey) { mutableStateOf<Boolean?>(null) }
    var doubanChecked by remember(doubanLoginKey) { mutableStateOf(false) }
    LaunchedEffect(doubanLoginKey) {
        doubanChecked = false
        if (isDoubanLoggedIn) {
            doubanSessionOk = checkSession { com.shangyin.app.data.douban.DoubanClient.sessionOk(force = true) }
        }
        doubanChecked = true
    }
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

    // 无忧游戏库登录状态
    var wygamerLoginKey by remember { mutableStateOf(0) }
    val isWygamerLoggedIn = remember(wygamerLoginKey) { SettingsStore.isWygamerLoggedIn }
    var showWygamerLogout by remember { mutableStateOf(false) }
    val wygamerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        wygamerLoginKey++
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "无忧游戏库登录成功", Toast.LENGTH_SHORT).show()
        }
    }

    // Z-Library 登录状态
    var zlibLoginKey by remember { mutableStateOf(0) }
    val isZlibLoggedIn = remember(zlibLoginKey) { com.shangyin.app.data.zlib.ZlibClient.isLoggedIn }
    var showZlibLogout by remember { mutableStateOf(false) }

    // 其余三个账号同样做「服务端登录态校验」：null = 未确认，false = 已失效（本地却显示已登录）
    var bikaSessionOk by remember(bikaLoginKey) { mutableStateOf<Boolean?>(null) }
    var wygamerSessionOk by remember(wygamerLoginKey) { mutableStateOf<Boolean?>(null) }
    var zlibSessionOk by remember(zlibLoginKey) { mutableStateOf<Boolean?>(null) }
    var bikaChecked by remember(bikaLoginKey) { mutableStateOf(false) }
    var wygamerChecked by remember(wygamerLoginKey) { mutableStateOf(false) }
    var zlibChecked by remember(zlibLoginKey) { mutableStateOf(false) }
    LaunchedEffect(bikaLoginKey) {
        bikaChecked = false
        if (SettingsStore.bikaToken.isNotBlank()) {
            bikaSessionOk = checkSession { com.shangyin.app.data.bika.BikaClient.sessionOk() }
        }
        bikaChecked = true
    }
    LaunchedEffect(wygamerLoginKey) {
        wygamerChecked = false
        if (SettingsStore.isWygamerLoggedIn) {
            wygamerSessionOk = checkSession { com.shangyin.app.data.wygamer.WygamerClient.sessionOk() }
        }
        wygamerChecked = true
    }
    LaunchedEffect(zlibLoginKey) {
        zlibChecked = false
        if (com.shangyin.app.data.zlib.ZlibClient.isLoggedIn) {
            zlibSessionOk = checkSession { com.shangyin.app.data.zlib.ZlibClient.sessionOk() }
        }
        zlibChecked = true
    }
    val zlibLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        zlibLoginKey++
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "Z-Library 登录成功", Toast.LENGTH_SHORT).show()
        }
    }

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
                        tint = if (isDoubanLoggedIn && doubanSessionOk != false) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                },
                title = "豆瓣登录",
                subtitle = when {
                    !isDoubanLoggedIn -> "未登录，登录后搜索结果更全（老片需要登录才搜得到）"
                    !doubanChecked -> "已登录，正在检测登录状态…"
                    doubanSessionOk == false -> "登录已过期！搜索结果会变少（老片搜不到），点这里重新登录"
                    doubanSessionOk == null -> "已登录（暂时无法确认状态，点这里可重新登录）"
                    else -> "已登录，搜索结果更全"
                },
                onClick = {
                    // 只有服务端**确认有效**才弹退出确认；未确认/已过期都直达登录页
                    // （未确认时也可能只是网络问题，让用户能直接重登，别只给一个「退出」）
                    if (isDoubanLoggedIn && doubanSessionOk == true) showDoubanLogout = true
                    else doubanLauncher.launch(Intent(context, DoubanLoginActivity::class.java))
                }
            )

            // 哔咔登录
            SettingCard(
                icon = {
                    Icon(
                        Icons.Rounded.Person, contentDescription = null,
                        tint = if (isBikaLoggedIn && bikaSessionOk != false) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                },
                title = "哔咔登录",
                subtitle = when {
                    !isBikaLoggedIn -> "未登录，登录后可在里世界浏览本子"
                    !bikaChecked -> "已登录，正在检测登录状态…"
                    bikaSessionOk == false -> "登录已失效！点这里重新登录（否则本子页会一直报错）"
                    bikaSessionOk == null -> "已登录（暂时无法确认状态，点这里可重新登录）"
                    else -> "已登录，可浏览本子漫画"
                },
                onClick = {
                    if (isBikaLoggedIn && bikaSessionOk == true) showBikaLogout = true
                    else showBikaLogin = true
                }
            )

            // 无忧游戏库登录
            SettingCard(
                icon = {
                    Icon(
                        Icons.Rounded.SportsEsports, contentDescription = null,
                        tint = if (isWygamerLoggedIn && wygamerSessionOk != false) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                },
                title = "无忧游戏库登录",
                subtitle = when {
                    !isWygamerLoggedIn -> "未登录，登录后可查看部分资源下载链接"
                    !wygamerChecked -> "已登录，正在检测登录状态…"
                    wygamerSessionOk == false -> "登录已失效！部分资源看不到下载链接，点这里重新登录"
                    wygamerSessionOk == null -> "已登录（暂时无法确认状态，点这里可重新登录）"
                    else -> "已登录，可查看资源下载链接"
                },
                onClick = {
                    if (isWygamerLoggedIn && wygamerSessionOk == true) showWygamerLogout = true
                    else wygamerLauncher.launch(Intent(context, WygamerLoginActivity::class.java))
                }
            )

            // Z-Library 登录
            SettingCard(
                icon = {
                    Icon(
                        Icons.Rounded.Book, contentDescription = null,
                        tint = if (isZlibLoggedIn && zlibSessionOk != false) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
                },
                title = "Z-Library 登录",
                subtitle = when {
                    !isZlibLoggedIn -> "未登录，登录后才能下载电子书"
                    !zlibChecked -> "已登录，正在检测登录状态…"
                    zlibSessionOk == false -> "登录已失效！搜索/下载会失败，点这里重新登录"
                    zlibSessionOk == null -> "已登录（暂时无法确认状态，点这里可重新登录）"
                    else -> "已登录，可搜索并下载电子书"
                },
                onClick = {
                    if (isZlibLoggedIn && zlibSessionOk == true) showZlibLogout = true
                    else zlibLauncher.launch(Intent(context, ZlibLoginActivity::class.java))
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
                        SettingsStore.clearBikaCredentials()
                        bikaLoginKey++
                        showBikaLogout = false
                        Toast.makeText(context, "已退出哔咔登录", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showBikaLogout = false }) { Text("取消") } }
        )
    }

    // 无忧游戏库登出确认
    if (showWygamerLogout) {
        AlertDialog(
            onDismissRequest = { showWygamerLogout = false },
            title = { Text("退出无忧游戏库登录") },
            text = { Text("退出后部分资源可能看不到下载链接，确认退出？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        SettingsStore.clearWygamerLogin()
                        wygamerLoginKey++
                        showWygamerLogout = false
                        Toast.makeText(context, "已退出无忧游戏库登录", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showWygamerLogout = false }) { Text("取消") } }
        )
    }

    // Z-Library 登出确认
    if (showZlibLogout) {
        AlertDialog(
            onDismissRequest = { showZlibLogout = false },
            title = { Text("退出 Z-Library 登录") },
            text = { Text("退出后无法搜索/下载电子书（里世界 → 图书），确认退出？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        SettingsStore.clearZlibLogin()
                        zlibLoginKey++
                        showZlibLogout = false
                        Toast.makeText(context, "已退出 Z-Library 登录", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("退出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showZlibLogout = false }) { Text("取消") } }
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
    // 预填上次保存的账密：打开即可直接点「登录」，也便于 token 过期后静默重登
    var email by remember { mutableStateOf(SettingsStore.bikaAccount) }
    var password by remember { mutableStateOf(SettingsStore.bikaPassword) }
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
                    SettingsStore.bikaAccount = email.trim()
                    SettingsStore.bikaPassword = password
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
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "密码",
                    enabled = !busy,
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
