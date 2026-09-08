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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shangyin.app.ui.theme.ShangYinTheme

/**
 * 豆瓣登录 Activity：用 WebView 加载豆瓣登录页，登录后抓取 Cookie 保存到本地。
 *
 * 关键改进：
 * 1. 加载完成后注入 JS 自动切换到"密码登录" tab（默认是短信登录）
 * 2. 注入 CSS 隐藏微博登录入口（用户明确说不用微博）
 * 3. 顶部加"我已完成登录"按钮，用户可手动触发 Cookie 抓取（微信 OAuth 跳转可能不回跳，需手动）
 * 4. 同时保留 URL 自动检测（账号密码登录成功会跳转，自动抓取）
 */
class DoubanLoginActivity : ComponentActivity() {

    private var webView: WebView? = null
    /** 是否已抓取过 Cookie（避免重复 finish） */
    private var cookieSaved = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CookieManager.getInstance().setAcceptCookie(true)

        setContent {
            ShangYinTheme {
                LoginScreen(
                    onBack = { finish() },
                    onManualComplete = { saveCookieAndFinish() },
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
                    val u = url.orEmpty()
                    // 登录页加载完成后注入 JS：切换密码登录 tab + 隐藏微博
                    if (u.contains("passport/login") || u.contains("accounts.douban.com")) {
                        injectLoginTabScript()
                    }
                    checkLoginSuccess(u)
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl("https://accounts.douban.com/passport/login")
        }.also { webView = it }
    }

    /**
     * 注入 JS：
     * 1. 点击"密码登录" tab（豆瓣默认显示短信登录，用户要手动切 tab）
     * 2. 隐藏微博登录按钮（用户明确说不用微博）
     */
    private fun injectLoginTabScript() {
        val js = """
            (function() {
                try {
                    // 切换到密码登录 tab：找含"密码登录"文字的 li 元素并点击
                    var tabs = document.querySelectorAll('ul.tab-nav li, .account-tab li, li.tab');
                    for (var i = 0; i < tabs.length; i++) {
                        if (tabs[i].textContent.indexOf('密码登录') >= 0) {
                            tabs[i].click();
                            break;
                        }
                    }
                } catch(e) {}
                try {
                    // 隐藏微博登录入口（保留微信和 QQ）
                    var thirdParty = document.querySelectorAll('.third-party, .social-login, .tp-login');
                    thirdParty.forEach(function(el) {
                        var links = el.querySelectorAll('a, span, div');
                        links.forEach(function(link) {
                            var text = link.textContent || '';
                            if (text.indexOf('微博') >= 0 || text.indexOf('weibo') >= 0) {
                                link.style.display = 'none';
                            }
                        });
                    });
                } catch(e) {}
            })();
        """.trimIndent()
        runCatching { webView?.evaluateJavascript(js, null) }
    }

    /** 检测 URL 是否表示登录成功（跳转到 douban.com 主域且不在 login 路径下） */
    private fun checkLoginSuccess(url: String) {
        if (url.isEmpty() || cookieSaved) return
        val isMainDomain = url.contains("www.douban.com") ||
            url.contains("movie.douban.com") ||
            url.contains("search.douban.com") ||
            url.contains("book.douban.com")
        val isLoginPage = url.contains("login") || url.contains("passport")
        if (isMainDomain && !isLoginPage) {
            // 延迟 1.5 秒等 Cookie 完全落地，再读取
            webView?.postDelayed({ saveCookieAndFinish() }, 1500)
        }
    }

    /**
     * 抓取 Cookie 并保存。用户手动点击"我已完成登录"按钮、或 URL 自动检测到登录成功时调用。
     * 优先尝试 .douban.com 域，没有 ck 就试 accounts.douban.com 域。
     */
    private fun saveCookieAndFinish() {
        if (cookieSaved) return
        cookieSaved = true
        val cm = CookieManager.getInstance()
        // 豆瓣 cookie 通常在 .douban.com 域
        var cookie = cm.getCookie(".douban.com").orEmpty()
        var ck = extractCookieValue(cookie, "ck")
        var dbcl = extractCookieValue(cookie, "dbcl")
        // 如果 .douban.com 域没拿到 ck，尝试 accounts.douban.com 域（登录时刚落地）
        if (ck.isBlank() || dbcl.isBlank()) {
            val accountCookie = cm.getCookie("accounts.douban.com").orEmpty()
            if (accountCookie.isNotBlank()) {
                if (ck.isBlank()) ck = extractCookieValue(accountCookie, "ck")
                if (dbcl.isBlank()) dbcl = extractCookieValue(accountCookie, "dbcl")
                cookie = if (cookie.isNotBlank()) "$cookie; $accountCookie" else accountCookie
            }
        }
        // 兜底：尝试无点号的 douban.com 域
        if (ck.isBlank() || dbcl.isBlank()) {
            val plainCookie = cm.getCookie("douban.com").orEmpty()
            if (plainCookie.isNotBlank()) {
                if (ck.isBlank()) ck = extractCookieValue(plainCookie, "ck")
                if (dbcl.isBlank()) dbcl = extractCookieValue(plainCookie, "dbcl")
                cookie = if (cookie.isNotBlank()) "$cookie; $plainCookie" else plainCookie
            }
        }

        if (ck.isNotBlank() && dbcl.isNotBlank()) {
            SettingsStore.doubanCookie = cookie
            SettingsStore.doubanCk = ck
            setResult(android.app.Activity.RESULT_OK)
            Toast.makeText(this, "豆瓣登录成功（ck 已获取）", Toast.LENGTH_SHORT).show()
        } else {
            // 即使没拿到 ck，也保存 cookie（某些登录方式可能不带 ck 但带 dbcl）
            if (dbcl.isNotBlank()) {
                SettingsStore.doubanCookie = cookie
                SettingsStore.doubanCk = ck  // 可能是空，但不影响主流程（dbcl 仍可用于请求头）
                setResult(android.app.Activity.RESULT_OK)
                Toast.makeText(this, "豆瓣登录成功（已获取登录态）", Toast.LENGTH_SHORT).show()
            } else {
                setResult(android.app.Activity.RESULT_CANCELED)
                Toast.makeText(this, "未获取到登录 Cookie，请确认已成功登录", Toast.LENGTH_LONG).show()
            }
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
    onManualComplete: () -> Unit,
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
                },
                actions = {
                    // 顶部"我已完成登录"按钮：微信 OAuth 跳转可能不回跳，需手动触发 Cookie 抓取
                    IconButton(onClick = onManualComplete) {
                        Icon(Icons.Rounded.Check, contentDescription = "我已完成登录")
                    }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                // 顶部提示条：说明登录方式
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "登录完成后点击右上角 ✓ 按钮；或关闭页面自动抓取",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // WebView 占满剩余空间
                Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                    AndroidView(
                        factory = { webViewFactory() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // 底部"我已完成登录"大按钮（更显眼，方便用户点击）
                Button(
                    onClick = onManualComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Text("  我已完成登录")
                }
            }
        }
    }
}
