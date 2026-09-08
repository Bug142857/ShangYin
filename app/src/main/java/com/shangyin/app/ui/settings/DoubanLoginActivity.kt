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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shangyin.app.ui.theme.ShangYinTheme

/**
 * 豆瓣登录 Activity：用 WebView 加载豆瓣登录页，登录后抓取 Cookie 保存到本地。
 *
 * 设计：
 * - 不自动检测 URL 跳转（微信 OAuth 跳转/短信登录跳转都可能误判，导致登录没完成就弹回）
 * - 只在用户手动点击"我已完成登录"按钮时才抓取 Cookie
 * - 注入 CSS 隐藏 QQ 登录入口（只保留短信验证和微信）
 * - Cookie 抓取多域兜底：.douban.com → accounts.douban.com → m.douban.com → douban.com
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
                    // 不做任何自动跳转检测，全部交给 WebView 自然加载
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val u = url.orEmpty()
                    // 只在登录页注入 CSS 隐藏 QQ 入口
                    if (u.contains("passport/login") || u.contains("accounts.douban.com")) {
                        injectHideQQScript()
                    }
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl("https://accounts.douban.com/passport/login")
        }.also { webView = it }
    }

    /**
     * 注入 CSS：隐藏 QQ 登录入口，只保留短信验证登录和微信登录。
     * 豆瓣第三方登录按钮通常含 .ic-qq 或 alt="QQ" 等标识。
     */
    private fun injectHideQQScript() {
        val js = """
            (function() {
                try {
                    var style = document.createElement('style');
                    style.textContent = '
                        a[href*="qq"], a[href*="QQ"],
                        .ic-qq, .qq-login, .tp-link[data-type="qq"],
                        span[class*="qq"], div[class*="qq"] {
                            display: none !important;
                        }
                    ';
                    document.head.appendChild(style);
                } catch(e) {}
                try {
                    // 二次保险：遍历第三方登录区域，找含 QQ/qq 文字的元素隐藏
                    var links = document.querySelectorAll('a, span, div, li');
                    links.forEach(function(el) {
                        var text = (el.textContent || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('alt') || '');
                        if (text.indexOf('QQ') >= 0 || text.indexOf('qq') >= 0 || text.indexOf('腾讯') >= 0) {
                            el.style.display = 'none';
                        }
                    });
                } catch(e) {}
            })();
        """.trimIndent()
        runCatching { webView?.evaluateJavascript(js, null) }
    }

    /**
     * 抓取 Cookie 并保存。用户手动点击"我已完成登录"按钮时调用。
     * Cookie 抓取多域兜底：.douban.com → accounts.douban.com → m.douban.com → douban.com
     */
    private fun saveCookieAndFinish() {
        if (cookieSaved) return
        cookieSaved = true
        // 先 flush CookieManager 确保所有 Cookie 已写入（WebView 异步写入有时延迟）
        CookieManager.getInstance().flush()

        val cm = CookieManager.getInstance()
        val cookies = mutableMapOf<String, String>()
        // 依次尝试所有可能域
        listOf(".douban.com", "accounts.douban.com", "m.douban.com", "douban.com").forEach { domain ->
            val c = cm.getCookie(domain).orEmpty()
            if (c.isNotBlank()) {
                c.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { p ->
                    val key = p.substring(0, p.indexOf('=')).trim()
                    if (key !in cookies) cookies[key] = p.substring(p.indexOf('=') + 1).trim()
                }
            }
        }

        // 拼接完整 Cookie 字符串
        val cookie = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val ck = cookies["ck"].orEmpty()
        val dbcl = cookies["dbcl"].orEmpty()

        if (dbcl.isNotBlank()) {
            // 有 dbcl 就算登录成功（ck 可能为空，但请求头带 Cookie 仍有效）
            SettingsStore.doubanCookie = cookie
            SettingsStore.doubanCk = ck
            setResult(android.app.Activity.RESULT_OK)
            val msg = if (ck.isNotBlank()) "豆瓣登录成功" else "豆瓣登录成功（已获取登录态）"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            // 调试：打印拿到的所有 Cookie key，方便定位问题
            android.util.Log.w("DoubanLogin", "Cookie keys: ${cookies.keys.joinToString(",")}")
            Toast.makeText(this, "未获取到登录 Cookie，请确认已成功登录后重试", Toast.LENGTH_LONG).show()
            // 失败时不 finish，让用户重试
            cookieSaved = false
        }
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
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                // 顶部提示条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "登录完成后点击下方按钮抓取 Cookie",
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
                // 底部"我已完成登录"大按钮
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
