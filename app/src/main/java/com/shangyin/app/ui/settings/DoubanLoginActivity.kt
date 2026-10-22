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
import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 豆瓣登录 Activity：内嵌 WebView 加载豆瓣官方登录页
 * 登录成功后自动提取 ck/dbcl 保存到 SettingsStore，下次打开自动保持登录
 */
class DoubanLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.286：进入登录流程即清掉持久化的死 cookie（session 已被豆瓣踢）——
        // 否则账号页误显示已登录、App 请求一直带着无效 cookie
        com.shangyin.app.ui.settings.SettingsStore.clearDoubanLogin()
        DoubanClient.recreateClient()
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

/**
 * v0.286：进入登录页前精确清除豆瓣域的残留 cookie。
 * WebView CookieManager 持久化了上次登录的 dbcl/dbcl2/ck——即使该 session 已被豆瓣
 * 服务端踢掉（指纹污染），一打开登录页自动提取就会拿到这份「死 cookie」→ 误弹
 * 「登录成功」→ 账号页随即显示已过期（用户实测）。清除后提取到的必然是新登录。
 * 用 setCookie(Max-Age=0) 逐个过期：不能 removeAllCookies（会连带清掉 zlib/吃瓜等
 * 其他站的 WebView 登录态）。HttpOnly cookie 同样能被 setCookie 覆盖删除。
 */
private fun clearDoubanCookies(cm: CookieManager) {
    val domains = listOf(
        "https://accounts.douban.com/",
        "https://www.douban.com/",
        "https://movie.douban.com/",
        "https://book.douban.com/",
        "https://m.douban.com/"
    )
    for (d in domains) {
        val raw = cm.getCookie(d) ?: continue
        raw.split(";").forEach { part ->
            val name = part.trim().substringBefore("=").trim()
            if (name.isNotEmpty()) {
                cm.setCookie(d, "$name=; Path=/; Max-Age=0")
                cm.setCookie(d, "$name=; Path=/; Domain=.douban.com; Max-Age=0")
            }
        }
    }
    cm.flush()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoubanLoginContent(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    // 0 = 正在校验登录态；1 = 显示"已登录"页；2 = 显示 WebView（登录 / 换账号）
    // ⚠️ 本地 cookie 只是启发式（含 dbcl 不等于会话有效）：过期后本页会一直显示"已登录"，
    // 用户没有输入账号的机会，同时搜索按匿名走（老片整批搜不到）。所以入口必须问服务端。
    var state by remember { mutableIntStateOf(if (isDoubanLoggedIn) 0 else 2) }

    LaunchedEffect(Unit) {
        if (state != 0) return@LaunchedEffect
        // 加超时：校验通道卡住时按"无法判断"处理（落到登录页），不能一直停在转圈
        val ok = withTimeoutOrNull(15_000L) {
            withContext(Dispatchers.IO) {
                runCatching { DoubanClient.sessionOkBlocking() }.getOrNull()
            }
        }
        // 不主动清本地 cookie：网络抖动同样会验失败，直接清会误删有效登录。
        // 只有服务端**确认有效（true）**才显示"已登录"页；false/null 都进登录页 ——
        // 「测不出来」绝不能挡住登录入口（v2.23.18 的 zlib 就是栽在这：登录页永远打不开）
        state = if (ok == true) 1 else 2
    }

    when (state) {
        0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        1 -> AlreadyLoggedInScreen(
            onBack = onBack,
            onRelogin = {
                // 换账号必须先清掉登录态与 WebView cookie：否则登录页带着旧 Cookie，
                // 站点直接判定已登录，用户根本没有输入新账号的机会
                SettingsStore.clearDoubanLogin()
                DoubanClient.onCookieChanged()
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
                state = 2
            },
            onLogout = {
                SettingsStore.clearDoubanLogin()
                DoubanClient.onCookieChanged()
                // 清掉 WebView 的 cookie
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
                state = 2
            }
        )

        else -> WebViewLoginScreen(
            onBack = onBack,
            onLoginSuccess = onLoginSuccess
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlreadyLoggedInScreen(
    onBack: () -> Unit,
    onRelogin: () -> Unit,
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
            Text("豆瓣已登录（已通过服务端校验）", style = MaterialTheme.typography.titleLarge)
            Text(
                "搜索将使用登录态，结果更全。\n" +
                    "若发现搜索结果变少（老片整批消失），点「切换账号 / 重新登录」重新登录即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Button(onClick = onRelogin, modifier = Modifier.fillMaxWidth()) {
                Text("切换账号 / 重新登录")
            }
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
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
    var currentUrl by remember { mutableStateOf("") }
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
            Column {
                // v0.285：当前页 URL 显示条（诊断用）——豆瓣登录中转页经常渲染成白屏，
                // 用户截图后能直接看到卡在哪个页面
                Text(
                    text = currentUrl.ifBlank { "（加载中）" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        // v0.282：UA 改用真实 WebView 内核 UA（App.webViewUa）。
                        // 豆瓣登录验证码已换成腾讯天御滑块（实测 j/mobile/login/basic 返回
                        // captcha_required + turing.captcha.qcloud.com），天御会做 UA 与
                        // Client Hints 一致性检测——硬编码的假 Chrome/120 与真实内核
                        // （sec-ch-ua 报真实版本）矛盾即判机器人，滑块永远过不去 →
                        // 「登录不上」。与 zlib 的教训同源：WebView 内不能伪造 UA。
                        settings.userAgentString =
                            com.shangyin.app.App.webViewUa.ifBlank {
                                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            }

                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                currentUrl = url.orEmpty()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                currentUrl = url.orEmpty()
                                android.util.Log.d("DoubanLogin", "WebView onPageFinished: $url")

                                val u = url ?: return
                                // v0.283：条件放宽——登录成功后豆瓣可能停留在空白中转页
                                // （accounts.douban.com/passport/* 或 JS 跳转壳，用户实测白屏），
                                // 旧条件把 accounts.douban.com/passport 全排除导致白屏页不自动提取。
                                // 收紧为：douban.com 域 + 非验证码页即尝试提取；
                                // 未登录时 cookie 里没有 dbcl/ck，saveCookieString 自身返回 false，无害
                                if (u.contains("douban.com") &&
                                    !u.contains("captcha") &&
                                    u != "about:blank"
                                ) {
                                    val merged = collectDoubanCookies(cookieManager)
                                    android.util.Log.d("DoubanLogin", "auto check merged: $merged")
                                    val hasLoginCookie = merged.contains("dbcl") || merged.contains("dbcl2")
                                    val hasCk = Regex("""\bck=""").containsMatchIn(merged)
                                    if (hasCk || hasLoginCookie) {
                                        val ok = DoubanClient.saveCookieString(merged)
                                        if (ok) {
                                            cookieManager.flush()
                                            // v0.285：提取成功即关页，不再等服务端校验——
                                            // 刚从 WebView 提取的新鲜 cookie 不可能过期；而
                                            // sessionOkBlocking 的 /mine/ 校验在豆瓣对 App 请求
                                            // 返回 403 风控时得 null → 永不关页 → 卡白屏（实测）。
                                            // 登录态有效性后续由账号页/搜索的 sessionOk 兜底
                                            view?.post { onLoginSuccess() }
                                        }
                                    }
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = super.shouldOverrideUrlLoading(view, request)
                        }

                        // v0.286：清除豆瓣域残留 cookie 必须在 loadUrl 前同步做——
                        // 残留的死 cookie 会被 onPageFinished 自动提取误判成登录成功
                        clearDoubanCookies(cookieManager)
                        loadUrl("https://accounts.douban.com/passport/login")
                    }
                },
                modifier = Modifier.weight(1f)
            )
            }

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
