package com.shangyin.app.data.zlib

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Z-Library 的接口通道：**在常驻隐藏 WebView 的页面上下文里 fetch**。
 *
 * 为什么不走 OkHttp（实测 2026-09-20）：
 *  - DiamWall 对接口请求返回 307 重定向到同一 URL + `Set-Cookie: __diamwall=0x…`（有效期只有 5 分钟），
 *    带上该 cookie 再请求会得到 `Verifying your browser | DiamWall` 挑战页
 *    （内嵌 `/.well-known/diamwall/load/html/5s.html` + `cdn-cgi/mitigation/v2/chl/chlb.lib`，
 *    靠页面 JS 计算证明并改写 `dwid` / `_dwa` cookie）——纯 HTTP 客户端无法完成，只能真浏览器引擎跑。
 *  - 因此登录页（ZlibLoginActivity）过完挑战后，接口调用也必须复用「同一个浏览器环境」，
 *    否则 cookie 一过期（5 分钟）搜索就再也出不来数据（表现为「登录成功但搜不到东西」）。
 *
 * 由 UI 层（ZlibWebHost，挂在 AppNav 根节点）把 WebView 交给这里托管；
 * 页面上下文与登录页共享同一个 CookieManager，所以登录态天然可用，挑战过期时页面自己会重新验证。
 */
object ZlibWeb {

    private var webView: WebView? = null
    private var loadedHost: String? = null
    private var pageReady = CompletableDeferred<Unit>()

    val attached: Boolean get() = webView != null

    /** 当前线路域名（与登录流程共用 SettingsStore.zlibHost，登录时会写入当日可用线路） */
    private fun host(): String = SettingsStore.zlibHost.trim()
        .removePrefix("https://").removePrefix("http://").trimEnd('/')
        .ifBlank { "z-library.sk" }

    /** 由 ZlibWebHost 在主线程挂载；UA 不伪造（与登录页一致，DiamWall 会核对） */
    fun attach(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                pageReady = CompletableDeferred()
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                pageReady.complete(Unit)
            }
        }
        webView = view
    }

    fun detach(view: WebView) {
        if (webView === view) {
            webView = null
            loadedHost = null
            pageReady = CompletableDeferred()
        }
    }

    /**
     * 取接口响应文本。首次调用（或线路变更）先加载站点首页让 DiamWall 完成验证，
     * 之后每次请求都重试到「拿到非挑战页的内容」为止。
     */
    suspend fun fetchText(path: String): String = withContext(Dispatchers.Main) {
        val view = webView ?: throw Exception("图书会话未就绪，请退出图书页后重新进入")
        val base = "https://" + host()
        if (loadedHost != host()) loadAndWait(view, base + "/")
        val url = if (path.startsWith("http")) path else base + path
        repeat(5) {
            val text = evalFetch(view, url)
            if (!isChallenge(text)) return@withContext text
            // 页面正处于 DiamWall 挑战中：它的 JS 需要几秒完成验证，等一会儿再试
            delay(2000)
        }
        throw Exception("反爬验证未通过：请在 设置 → 账号管理 → Z-Library 登录 里重新登录一次")
    }

    /** 是否拿到了 DiamWall 挑战页 / 登录页（HTML），而不是接口 JSON */
    private fun isChallenge(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<") || t.startsWith("<!") ||
            text.contains("DiamWall", true) || text.contains("Verifying your browser", true)
    }

    private suspend fun loadAndWait(view: WebView, url: String) {
        loadedHost = host()
        val ready = CompletableDeferred<Unit>()
        pageReady = ready
        view.loadUrl(url)
        withTimeoutOrNull(20_000) { ready.await() }
    }

    /** 页面内 fetch 并轮询结果（evaluateJavascript 不支持 await Promise，故把结果挂在 window 上） */
    private suspend fun evalFetch(view: WebView, url: String): String {
        val js = "(function(){try{window.__syZ=null;" +
            "fetch(${JSONObject.quote(url)},{credentials:'include',cache:'no-store'," +
            "headers:{'X-Requested-With':'XMLHttpRequest'}})" +
            ".then(function(r){return r.text()})" +
            ".then(function(t){window.__syZ=t})" +
            ".catch(function(e){window.__syZ='__err__'+(e&&e.message?e.message:e)});return 1}" +
            "catch(e){window.__syZ='__err__'+e;return 0}})()"
        view.evaluateJavascript(js, null)
        repeat(60) {
            delay(150)
            val v = evalValue(view, "window.__syZ") ?: return@repeat
            if (v.startsWith("__err__")) {
                throw Exception("请求失败：" + v.removePrefix("__err__").trim().take(80))
            }
            return v
        }
        throw Exception("请求超时（站点无响应）")
    }

    private suspend fun evalValue(view: WebView, js: String): String? =
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(js) { raw ->
                if (!cont.isActive) return@evaluateJavascript
                val s = raw?.trim().orEmpty()
                if (s.isEmpty() || s == "null" || s == "undefined") cont.resume(null)
                else cont.resume(decode(s))
            }
        }

    /** evaluateJavascript 回来的是 JSON 字面量（带引号与转义），还原成原始文本 */
    private fun decode(raw: String): String {
        val s = raw.trim()
        if (s.length < 2 || !s.startsWith("\"")) return s
        return runCatching { JSONArray("[$s]").optString(0) }.getOrDefault(s.removeSurrounding("\""))
    }
}
