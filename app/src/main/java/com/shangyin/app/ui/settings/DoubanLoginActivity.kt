package com.shangyin.app.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.shangyin.app.ui.theme.ShangYinTheme

/**
 * 豆瓣登录 Activity：用 WebView 加载豆瓣登录页，登录后抓取 Cookie 保存到本地。
 *
 * 关键点：
 * 1. WebView 启用 JavaScript + DOM 存储 + 第三方 Cookie（豆瓣登录依赖 JS 跳转）
 * 2. 监听 URL 变化，登录成功跳转到 www.douban.com 后从 CookieManager 提取 Cookie
 * 3. ck 和 dbcl 是登录态关键 token，ck 用于搜索 URL 参数，dbcl 用于识别是否已登录
 */
class DoubanLoginActivity : ComponentActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先初始化 CookieManager（确保能接收 Cookie）
        CookieManager.getInstance().setAcceptCookie(true)

        setContent {
            ShangYinTheme {
                LoginScreen(
                    onBack = { finish() },
                    webViewFactory = { createWebView() }
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                setSupportMultipleWindows(false)
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }
            // 针对此 WebView 启用第三方 Cookie（豆瓣登录依赖）
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    checkLoginSuccess(url)
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    checkLoginSuccess(url.orEmpty())
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl("https://accounts.douban.com/passport/login")
        }.also { webView = it }
    }

    /** 检测 URL 是否表示登录成功（跳转到 douban.com 主域且不在 login 路径下） */
    private fun checkLoginSuccess(url: String) {
        if (url.isEmpty()) return
        // 登录成功后通常会跳转到 www.douban.com 或 movie.douban.com，且 URL 不再含 login
        val isMainDomain = url.contains("www.douban.com") ||
            url.contains("movie.douban.com") ||
            url.contains("search.douban.com")
        val isLoginPage = url.contains("login") || url.contains("passport")
        if (isMainDomain && !isLoginPage) {
            // 延迟 1.5 秒等 Cookie 完全落地，再读取
            webView?.postDelayed({ saveCookieAndFinish() }, 1500)
        }
    }

    private fun saveCookieAndFinish() {
        val cookie = CookieManager.getInstance().getCookie(".douban.com").orEmpty()
        val ck = extractCookieValue(cookie, "ck")
        val dbcl = extractCookieValue(cookie, "dbcl")
        if (ck.isNotBlank() && dbcl.isNotBlank()) {
            SettingsStore.doubanCookie = cookie
            SettingsStore.doubanCk = ck
            setResult(android.app.Activity.RESULT_OK)
            Toast.makeText(this, "豆瓣登录成功", Toast.LENGTH_SHORT).show()
        } else {
            setResult(android.app.Activity.RESULT_CANCELED)
            Toast.makeText(this, "登录失败，未获取到有效 Cookie", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    /** 从 Cookie 字符串里提取指定 key 的 value（格式：key=value; key2=value2） */
    private fun extractCookieValue(cookie: String, key: String): String {
        val parts = cookie.split(";").map { it.trim() }
        for (p in parts) {
            val eq = p.indexOf('=')
            if (eq > 0 && p.substring(0, eq).equals(key, ignoreCase = true)) {
                return p.substring(eq + 1).trim()
            }
        }
        return ""
    }

    override fun onDestroy() {
        runCatching {
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.destroy()
            webView = null
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreen(
    onBack: () -> Unit,
    webViewFactory: () -> WebView
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
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                factory = { webViewFactory() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
