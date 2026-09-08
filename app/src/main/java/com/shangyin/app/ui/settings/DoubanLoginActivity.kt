package com.shangyin.app.ui.settings

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.ui.settings.SettingsStore.isDoubanLoggedIn
import com.shangyin.app.ui.theme.ShangYinTheme

/**
 * 豆瓣登录 Activity：内嵌 WebView 加载豆瓣官方登录页
 * 登录成功后自动提取 ck/dbcl 保存到 SettingsStore，下次打开自动保持登录
 */
class DoubanLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShangYinTheme {
                DoubanLoginContent(
                    onBack = { finish() },
                    onLoginSuccess = {
                        Toast.makeText(this, "豆瓣登录成功", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }
}

/** 合并多个域名的 cookie 字符串（去重） */
private fun collectDoubanCookies(cm: CookieManager): String {
    val domains = listOf(
        "https://accounts.douban.com/",
        "https://www.douban.com/",
        "https://movie.douban.com/",
        "https://book.douban.com/",
        "https://m.douban.com/"
    )
    val all = LinkedHashMap<String, String>()
    for (d in domains) {
        val raw = cm.getCookie(d) ?: continue
        raw.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val k = part.substring(0, idx).trim()
                val v = part.substring(idx + 1).trim()
                if (k.isNotEmpty() && !all.containsKey(k)) all[k] = v
            }
        }
    }
    return all.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoubanLoginContent(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    // 已登录 → 显示已登录界面；未登录 → WebView
    var alreadyLoggedIn by remember { mutableStateOf(isDoubanLoggedIn) }

    if (alreadyLoggedIn) {
        AlreadyLoggedInScreen(
            onBack = onBack,
            onLogout = {
                SettingsStore.clearDoubanLogin()
                DoubanClient.onCookieChanged()
                // 清掉 WebView 的 cookie
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
                alreadyLoggedIn = false
            }
        )
    } else {
        WebViewLoginScreen(
            onBack = onBack,
            onLoginSuccess = onLoginSuccess
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlreadyLoggedInScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit
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
            Text("豆瓣已登录", style = MaterialTheme.typography.titleLarge)
            Text(
                "搜索将使用登录态，结果更全",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("退出登录")
            }
        }
    }
}

/** WebView 登录：打开豆瓣登录页，检测登录成功后自动提取 cookie */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewLoginScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    val cookieManager = CookieManager.getInstance()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                wv.stopLoading()
                wv.settings.javaScriptEnabled = false
                wv.clearHistory()
                wv.removeAllViews()
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.destroy()
                webViewRef = null
            }
            // 确保 cookie 持久化到磁盘
            cookieManager.flush()
        }
    }

    /** 尝试提取并保存 cookie，成功返回 true */
    fun tryExtractCookies(): Boolean {
        val merged = collectDoubanCookies(cookieManager)
        android.util.Log.d("DoubanLogin", "merged cookie: $merged")
        if (merged.isBlank()) return false
        val ok = DoubanClient.saveCookieString(merged)
        if (ok) cookieManager.flush() // 持久化 WebView cookie
        return ok
    }

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
                    Button(onClick = { webViewRef?.reload() }) { Text("刷新") }
                    Button(
                        onClick = {
                            if (tryExtractCookies()) {
                                onLoginSuccess()
                            } else {
                                Toast.makeText(
                                    ctx,
                                    "未检测到登录态，请先完成登录",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) { Text("已登录") }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                android.util.Log.d("DoubanLogin", "WebView onPageFinished: $url")

                                val u = url ?: return
                                if (u.contains("douban.com") &&
                                    !u.contains("passport/login") &&
                                    !u.contains("accounts.douban.com/passport") &&
                                    !u.contains("captcha")
                                ) {
                                    val merged = collectDoubanCookies(cookieManager)
                                    android.util.Log.d("DoubanLogin", "auto check merged: $merged")
                                    val hasLoginCookie = merged.contains("dbcl") || merged.contains("dbcl2")
                                    val hasCk = Regex("""\bck=""").containsMatchIn(merged)
                                    if (hasCk || hasLoginCookie) {
                                        val ok = DoubanClient.saveCookieString(merged)
                                        if (ok) {
                                            cookieManager.flush()
                                            onLoginSuccess()
                                        }
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = super.shouldOverrideUrlLoading(view, request)
                        }

                        loadUrl("https://accounts.douban.com/passport/login")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
