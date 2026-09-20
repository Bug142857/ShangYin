package com.shangyin.app.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.shangyin.app.data.wygamer.WygamerClient
import com.shangyin.app.data.zlib.ZlibClient
import com.shangyin.app.ui.theme.ShangYinTheme

/**
 * 「网站账号」类登录的统一实现：内嵌 WebView 打开站点，登录成功后抓 Cookie 存进 SettingsStore。
 *
 * 两个站点都需要真实浏览器环境：
 *  - 无忧游戏库：Zibll 登录表单带 slider 滑块验证码，无法程序化提交
 *  - Z-Library：全站 DiamWall JS 反爬（普通 HTTP 请求返回 513 挑战页）
 * 因此统一走 WebView，且 Cookie 必须与 WebView 同款 UA 一起使用才有效。
 *
 * 约定：
 *  - 返回键 = 放弃登录（不返回 RESULT_OK，设置页不会提示"登录成功"）
 *  - 候选线路可多个，主框架加载失败会自动换下一条，也可在右上角手动切换
 */

/** 一个站点的登录参数 */
private class LoginSpec(
    val title: String,
    /** WebView 打开的登录地址（多个 = 候选线路，加载失败自动换下一条） */
    val startUrls: List<String>,
    /** 抓取 Cookie 的站点地址（按域名分别取，再合并去重） */
    val cookieUrls: List<String>,
    val hint: String,
    /** 判断 cookie 字符串是否代表登录态 */
    val loginDetect: (String) -> Boolean,
    val isLoggedIn: () -> Boolean,
    val save: (String) -> Unit,
    val logout: () -> Unit,
    /** 用桌面版 UA（站点反爬对移动端 WebView 更敏感） */
    val desktopUa: Boolean = false,
    /** 主框架加载成功后的回调，用于记住真正可用的线路 */
    val onHostResolved: ((String) -> Unit)? = null
)

/** 无忧游戏库登录（直达站点独立登录页，避免首页弹窗遮罩） */
class WygamerLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShangYinTheme {
                WebLoginContent(
                    spec = LoginSpec(
                        title = "无忧游戏库登录",
                        startUrls = listOf(
                            WYGAMER_LOGIN_PAGE,
                            "${WygamerClient.BASE}/"
                        ),
                        cookieUrls = listOf("${WygamerClient.BASE}/"),
                        hint = "登录后可查看部分需要登录才能显示的资源下载链接。\n登录信息仅保存在本机。",
                        loginDetect = { c -> c.contains("wordpress_logged_in", true) },
                        isLoggedIn = { SettingsStore.isWygamerLoggedIn },
                        save = { SettingsStore.wygamerCookie = it },
                        logout = { SettingsStore.clearWygamerLogin() }
                    ),
                    onBack = { finish() },
                    onLoginSuccess = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }

    private companion object {
        /** Zibll 主题的独立登录页（首页那个弹窗在 WebView 里只剩黑色遮罩） */
        const val WYGAMER_LOGIN_PAGE =
            "https://www.wygamer.com/user-sign-2?tab=signin&redirect_to=https%3A%2F%2Fwww.wygamer.com%2F"
    }
}

/** Z-Library 登录（顺带完成 DiamWall 反爬验证） */
class ZlibLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShangYinTheme {
                WebLoginContent(
                    spec = LoginSpec(
                        title = "Z-Library 登录",
                        startUrls = ZlibClient.candidateUrls(),
                        cookieUrls = ZlibClient.candidateUrls(),
                        hint = "登录后即可搜索并下载电子书。\n" +
                            "站点有反爬验证，若接口提示「需要重新验证」，回到这里重新登录一次即可。\n" +
                            "登录信息仅保存在本机。",
                        loginDetect = { c -> c.contains("remix_userkey", true) },
                        isLoggedIn = { SettingsStore.zlibCookie.contains("remix_userkey", true) },
                        save = { SettingsStore.zlibCookie = it },
                        logout = { SettingsStore.clearZlibLogin() },
                        desktopUa = true,
                        onHostResolved = { host ->
                            if (host.isNotBlank() && ZlibClient.ALT_HOSTS.contains(host)) {
                                SettingsStore.zlibHost = host
                            }
                        }
                    ),
                    onBack = { finish() },
                    onLoginSuccess = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }
}

/** 按域名取 Cookie 合并去重（同名保留首次出现的值） */
private fun collectCookies(cm: CookieManager, urls: List<String>): String {
    val all = LinkedHashMap<String, String>()
    for (u in urls) {
        val raw = cm.getCookie(u) ?: continue
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

/** 退出登录时把已保存的 Cookie 逐个置为过期，避免 WebView 仍处于登录态 */
private fun expireCookies(cm: CookieManager, urls: List<String>, cookieString: String) {
    val names = cookieString.split(";").mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx > 0) part.substring(0, idx).trim().takeIf { it.isNotEmpty() } else null
    }
    for (u in urls) for (n in names) cm.setCookie(u, "$n=; Max-Age=0; path=/")
    cm.flush()
}

private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore("/").substringBefore("?")

@Composable
private fun WebLoginContent(spec: LoginSpec, onBack: () -> Unit, onLoginSuccess: () -> Unit) {
    // 0 = 显示"已登录"页；1 = 显示 WebView（登录或重新验证）
    var mode by remember { mutableIntStateOf(if (spec.isLoggedIn()) 0 else 1) }

    if (mode == 0) {
        val ctx = LocalContext.current
        AlreadyLoggedInScreen(
            title = spec.title,
            onBack = onBack,
            onRefresh = { mode = 1 },
            onLogout = {
                val saved = collectCookies(CookieManager.getInstance(), spec.cookieUrls)
                spec.logout()
                expireCookies(CookieManager.getInstance(), spec.cookieUrls, saved)
                Toast.makeText(ctx, "已退出登录", Toast.LENGTH_SHORT).show()
                onBack()
            }
        )
    } else {
        WebViewLoginScreen(spec = spec, onBack = onBack, onLoginSuccess = onLoginSuccess)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlreadyLoggedInScreen(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("已登录", style = MaterialTheme.typography.titleLarge)
            Text(
                "搜索 / 详情 / 下载会使用登录态。若接口提示需要重新验证，点下方「重新验证」刷新一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("重新验证 / 切换账号")
            }
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("退出登录")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewLoginScreen(
    spec: LoginSpec,
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val ctx = LocalContext.current
    var urlIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var lineMenu by remember { mutableStateOf(false) }
    val cookieManager = CookieManager.getInstance()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun loadLine(wv: WebView?, index: Int) {
        val url = spec.startUrls.getOrNull(index) ?: return
        urlIndex = index
        pageError = null
        loading = true
        wv?.loadUrl(url)
    }

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
            cookieManager.flush()
        }
    }

    /** 尝试提取并保存 cookie，成功返回 true */
    fun tryExtractCookies(): Boolean {
        val merged = collectCookies(cookieManager, spec.cookieUrls)
        if (!spec.loginDetect(merged)) return false
        spec.save(merged)
        cookieManager.flush()
        return true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(spec.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                    if (spec.startUrls.size > 1) {
                        Box {
                            IconButton(onClick = { lineMenu = true }) {
                                Icon(Icons.Rounded.SwapHoriz, contentDescription = "切换线路")
                            }
                            DropdownMenu(expanded = lineMenu, onDismissRequest = { lineMenu = false }) {
                                spec.startUrls.forEachIndexed { i, u ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                hostOf(u) + if (i == urlIndex) "（当前）" else "",
                                                maxLines = 1
                                            )
                                        },
                                        onClick = {
                                            lineMenu = false
                                            loadLine(webViewRef, i)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            if (tryExtractCookies()) onLoginSuccess()
                            else Toast.makeText(ctx, "未检测到登录态，请先完成登录", Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("已登录") }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                factory = { c ->
                    var lastUrl = ""
                    var repeatCount = 0
                    WebView(c).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.userAgentString =
                            if (spec.desktopUa) ZlibClient.UA else WygamerClient.UA
                        if (spec.desktopUa) {
                            // 桌面版页面在手机上需要缩放查看
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                        }

                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                // 反爬验证可能自我重定向若干次，超过阈值说明该线路过不去
                                if (url == lastUrl) {
                                    repeatCount++
                                } else {
                                    lastUrl = url ?: ""
                                    repeatCount = 0
                                }
                                if (repeatCount >= 8) {
                                    view?.stopLoading()
                                    loading = false
                                    pageError = "该线路反复重定向（反爬验证未通过），请切换到其他线路重试。"
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                pageError = null
                                url?.let { spec.onHostResolved?.invoke(hostOf(it)) }
                                if (tryExtractCookies()) onLoginSuccess()
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame != true) return
                                loading = false
                                // 自动换下一条线路
                                val next = urlIndex + 1
                                if (next <= spec.startUrls.lastIndex) {
                                    loadLine(view, next)
                                    return
                                }
                                val desc = error?.description?.toString().orEmpty()
                                pageError = "页面打不开" + (if (desc.isNotBlank()) "（$desc）" else "") +
                                    "。可尝试右上角切换线路；Z-Library 需要外网网络环境。"
                            }
                        }

                        loadUrl(spec.startUrls.first())
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            pageError?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = {
                                val next = (urlIndex + 1) % spec.startUrls.size
                                loadLine(webViewRef, next)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (spec.startUrls.size > 1) "换线路重试" else "重试")
                        }
                        TextButton(onClick = { loadLine(webViewRef, urlIndex) }) { Text("重新加载本线路") }
                        Text(
                            spec.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}