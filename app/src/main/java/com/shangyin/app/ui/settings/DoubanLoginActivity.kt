package com.shangyin.app.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.ui.theme.ShangYinTheme
import kotlinx.coroutines.launch

/**
 * 豆瓣登录 Activity：提供三种登录方式
 * 1) WebView 浏览器登录（推荐，可处理验证码）
 * 2) 账号密码登录（直调 API，可能触发 captcha_required）
 * 3) 手动粘贴 Cookie（从浏览器开发者工具复制 cookie 字符串）
 */
class DoubanLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShangYinTheme {
                LoginHost(
                    onBack = { finish() },
                    onLoginSuccess = {
                        setResult(Activity.RESULT_OK)
                        Toast.makeText(this, "豆瓣登录成功", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onError = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }
}

/** 登录方式 */
private enum class LoginMode { MENU, WEBVIEW, PASSWORD, MANUAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginHost(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    var mode by remember { mutableStateOf(LoginMode.MENU) }

    when (mode) {
        LoginMode.MENU -> LoginMenu(
            onBack = onBack,
            onPick = { mode = it }
        )
        LoginMode.WEBVIEW -> WebViewLoginScreen(
            onBack = { mode = LoginMode.MENU },
            onLoginSuccess = onLoginSuccess,
            onError = onError
        )
        LoginMode.PASSWORD -> PasswordLoginScreen(
            onBack = { mode = LoginMode.MENU },
            onLoginSuccess = onLoginSuccess,
            onError = onError
        )
        LoginMode.MANUAL -> ManualCookieScreen(
            onBack = { mode = LoginMode.MENU },
            onLoginSuccess = onLoginSuccess,
            onError = onError
        )
    }
}

/** 登录方式选择菜单 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginMenu(
    onBack: () -> Unit,
    onPick: (LoginMode) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("豆瓣登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "选择登录方式",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "登录后搜索结果更全",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Button(
                onClick = { onPick(LoginMode.WEBVIEW) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("浏览器登录（推荐）")
            }
            Text(
                "在内置浏览器中打开豆瓣登录页，支持图形验证码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Button(
                onClick = { onPick(LoginMode.PASSWORD) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("账号密码登录")
            }
            Text(
                "直接提交手机号/邮箱 + 密码，可能触发验证码拦截",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Button(
                onClick = { onPick(LoginMode.MANUAL) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("手动输入 Cookie")
            }
            Text(
                "从桌面浏览器登录豆瓣后，在开发者工具里复制 Cookie 字符串粘贴",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** WebView 登录：打开豆瓣登录页，检测登录成功后自动提取 cookie */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewLoginScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    val cookieManager = CookieManager.getInstance()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("浏览器登录豆瓣") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            // 用户手动触发：从 CookieManager 提取 cookie 并尝试保存
                            val cookie = cookieManager.getCookie("https://accounts.douban.com/")
                                ?: cookieManager.getCookie("https://www.douban.com/")
                            android.util.Log.d("DoubanLogin", "manual extract cookie: $cookie")
                            if (!cookie.isNullOrBlank()) {
                                val ok = DoubanClient.saveCookieString(cookie)
                                if (ok) {
                                    onLoginSuccess()
                                } else {
                                    onError("未检测到登录态，请先完成登录")
                                }
                            } else {
                                onError("未检测到 Cookie")
                            }
                        }
                    ) {
                        Text("已登录")
                    }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                        // 接受所有 cookie
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                android.util.Log.d("DoubanLogin", "WebView onPageFinished: $url")

                                // 检测是否跳转到登录成功后的页面
                                // 豆瓣登录成功后可能跳转到 home 或者 profile 页面
                                val u = url ?: return
                                if (u.contains("douban.com") &&
                                    !u.contains("passport/login") &&
                                    !u.contains("accounts.douban.com/passport") &&
                                    !u.contains("captcha")
                                ) {
                                    // 检查 cookie 里有没有 ck 或 dbcl
                                    run {
                                        val cookie = cookieManager.getCookie(u)
                                        android.util.Log.d("DoubanLogin", "cookie after redirect: $cookie")
                                        if (!cookie.isNullOrBlank()) {
                                            val hasLoginCookie = cookie.contains("dbcl") || cookie.contains("dbcl2")
                                            val hasCk = cookie.contains("ck=")
                                            if (hasCk || hasLoginCookie) {
                                                val ok = DoubanClient.saveCookieString(cookie)
                                                if (ok) {
                                                    onLoginSuccess()
                                                    return@run
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                return super.shouldOverrideUrlLoading(view, request)
                            }
                        }

                        // 加载豆瓣登录页
                        loadUrl("https://accounts.douban.com/passport/login")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

/** 账号密码登录 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordLoginScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号密码登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "使用豆瓣账号密码登录",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "支持手机号或邮箱作为账号\n如果遇到验证码拦截，请改用浏览器登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("手机号或邮箱") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            tryPasswordLogin(
                                account, password, loading, { loading = it },
                                onLoginSuccess, onError, scope
                            )
                        }
                    ),
                    singleLine = true
                )

                Button(
                    onClick = {
                        tryPasswordLogin(
                            account, password, loading, { loading = it },
                            onLoginSuccess, onError, scope
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && account.isNotBlank() && password.isNotBlank()
                ) {
                    if (loading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(0.dp), strokeWidth = 2.dp)
                            Text("登录中…")
                        }
                    } else {
                        Text("登录豆瓣")
                    }
                }
            }
        }
    }
}

/** 手动粘贴 Cookie */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualCookieScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    var cookieText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手动输入 Cookie") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "如何获取 Cookie",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "1) 在电脑浏览器登录 douban.com\n" +
                        "2) 按 F12 打开开发者工具 → Network 标签\n" +
                        "3) 刷新页面，点开第一条请求 → Headers → Request Headers\n" +
                        "4) 复制 Cookie: 后面的整行内容粘贴到下方\n\n" +
                        "Cookie 中必须包含 ck 和 dbcl 才算有效登录态",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )

                OutlinedTextField(
                    value = cookieText,
                    onValueChange = { cookieText = it },
                    label = { Text("粘贴 Cookie 字符串") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 6,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    )
                )

                Button(
                    onClick = {
                        val text = cookieText.trim()
                        if (text.isBlank()) {
                            onError("请先粘贴 Cookie")
                            return@Button
                        }
                        // 如果用户粘贴的是 "Cookie: xxx" 格式，提取 xxx 部分
                        val cleaned = text.removePrefix("Cookie:").removePrefix("cookie:").trim()
                        loading = true
                        try {
                            val ok = DoubanClient.saveCookieString(cleaned)
                            if (ok) {
                                onLoginSuccess()
                            } else {
                                onError("Cookie 中未找到 ck/dbcl，请确认已登录豆瓣后再复制")
                            }
                        } finally {
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && cookieText.isNotBlank()
                ) {
                    Text("保存并登录")
                }
            }
        }
    }
}

private fun tryPasswordLogin(
    account: String,
    password: String,
    loading: Boolean,
    setLoading: (Boolean) -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    if (loading) return
    if (account.isBlank() || password.isBlank()) {
        onError("请输入账号和密码")
        return
    }
    setLoading(true)
    scope.launch {
        try {
            val result = DoubanClient.loginByPassword(account.trim(), password)
            when (result) {
                is com.shangyin.app.data.douban.LoginResult.Success -> onSuccess()
                is com.shangyin.app.data.douban.LoginResult.Failure -> {
                    val msg = result.message
                    if (msg.contains("captcha", ignoreCase = true) ||
                        msg.contains("验证", ignoreCase = true)
                    ) {
                        onError("豆瓣要求验证码，请返回改用【浏览器登录】")
                    } else {
                        onError(msg)
                    }
                }
            }
        } catch (e: Exception) {
            onError("登录异常：${e.message}")
        } finally {
            setLoading(false)
        }
    }
}
