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

    /**
     * 候选线路：设置里记的域名，以及补/去 `zh.` 前缀的变体。
     * 为什么要多候选：登录页与接口必须落在**同一个 host** 上，否则 host-only 的
     * `remix_userid`/`remix_userkey` 不会一起发送 —— 实测表现为接口返回
     * `{"success":0,"error":"未找到请求的书"}`（这就是"未登录"的误导性文案）。
     */
    private fun candidateHosts(): List<String> {
        val h = SettingsStore.zlibHost.trim()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
            .ifBlank { "z-lib.sk" }
        val bare = h.removePrefix("zh.")
        return listOf(h, "zh.$bare", bare).distinct()
    }

    /** 该线路的 CookieManager 里是否有登录 Cookie */
    private fun hasSession(host: String): Boolean =
        CookieManager.getInstance().getCookie("https://$host/")
            ?.contains("remix_userkey", ignoreCase = true) == true

    /** 记住真正可用的线路（写回设置，UI 显示与下次请求都用它） */
    private fun rememberHost(host: String) {
        if (SettingsStore.zlibHost.trim().removePrefix("https://").trimEnd('/') != host) {
            SettingsStore.zlibHost = host
        }
    }

    /**
     * 站点的「未登录」文案（实测匿名请求任一关键词都返回 `{"success":0,"error":"未找到请求的书"}`，
     * 用户资料接口返回 `{"success":0,"error":"登录到您的账户"}`）。
     */
    private fun notLoggedIn(text: String): Boolean =
        text.contains("登录到您的账户") ||
            (text.contains("\"success\":0") && text.contains("未找到请求的书"))

    /** 登录 / 退出登录后重置：只丢弃线路记忆（页面本身保留，避免又付一次约 7 秒的首页加载） */
    fun reset() {
        pageReady = CompletableDeferred()
    }

    /**
     * 预热：提前把站点首页加载好。
     * 实测（2026-09-20）本站首页 `loadEventEnd` 约 **7.4 秒**（还带一次重定向），
     * 我们只需要「一个同源文档」来发 fetch，所以用 [onPageCommitVisible] 完成等待；
     * 但首次仍需付出这部分时间，故进图书页时就先预热，别让用户搜完再干等。
     */
    suspend fun warmup() = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext
        val host = candidateHosts().firstOrNull { hasSession(it) } ?: candidateHosts().first()
        if (loadedHost != host) loadAndWait(view, host, "https://$host/")
    }

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

            /**
             * 首个内容帧提交就算「页面可用」。
             * 本站首页实测 loadEventEnd ≈ 7.4s，onPageFinished 要等到所有子资源加载完；
             * 而我们只需要一个同源文档来发 fetch，所以用更早的这个回调结束等待。
             */
            override fun onPageCommitVisible(v: WebView?, url: String?) {
                pageReady.complete(Unit)
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

    /** DiamWall 挑战页反复出现时用它区分「反爬没过」与「未登录」两种失败 */
    private class ChallengeException : Exception("diamwall-challenge")

    /**
     * 取接口响应文本。
     * 线路顺序 = 有登录 Cookie 的优先（`zh.` 前缀变体也试），命中就把该线路写回设置；
     * 若某线路返回「未登录」，自动换下一条候选（解决 host 与 Cookie 不一致导致的搜不到东西）；
     * 挑战页则每个线路重试几次（页面里的 chlb 需要几秒算完证明）。
     *
     * [form] 非空时用 **POST + 表单体** 提交 —— 实测（2026-09-20）eapi 的 GET/POST 行为不同：
     * `GET /eapi/book/search?...` 恒返回 `400 {"success":0,"error":"未找到请求的书"}`（误导性报错），
     * 而 `POST`（`application/x-www-form-urlencoded`）返回 `200 {"success":1,"books":[...]}` 正常数据。
     */
    suspend fun fetchText(path: String, form: String? = null): String = withContext(Dispatchers.Main) {
        val view = webView ?: throw Exception("图书会话未就绪，请退出图书页后重新进入")
        val hosts = candidateHosts().sortedByDescending { hasSession(it) }
        var sawChallenge = false
        var notLoggedInText = ""
        for (h in hosts) {
            val text = try {
                fetchOn(view, h, path, form)
            } catch (e: ChallengeException) {
                sawChallenge = true
                continue
            }
            if (notLoggedIn(text)) {
                notLoggedInText = text
                continue
            }
            rememberHost(h)
            return@withContext text
        }
        // 「未登录」的响应原样交回上层，由 ZlibClient 换成可操作的提示
        if (notLoggedInText.isNotBlank()) return@withContext notLoggedInText
        throw Exception(
            if (sawChallenge) "Z-Library 反爬验证未通过：请在 设置 → 账号管理 → Z-Library 登录 里重新登录一次"
            else "Z-Library 请求失败，请稍后重试"
        )
    }

    /** 在某条线路上取接口内容（必要时先加载站点首页建会话） */
    private suspend fun fetchOn(view: WebView, host: String, path: String, form: String?): String {
        if (loadedHost != host) loadAndWait(view, host, "https://$host/")
        val url = if (path.startsWith("http")) path else "https://$host$path"
        repeat(4) {
            val text = evalFetch(view, url, form)
            if (!isChallenge(text)) return text
            // 页面正处于 DiamWall 挑战中：它的 JS 需要几秒完成验证，等一会儿再试
            delay(2000)
        }
        throw ChallengeException()
    }

    /** 是否拿到了 DiamWall 挑战页 / 登录页（HTML），而不是接口 JSON */
    private fun isChallenge(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<") || t.startsWith("<!") ||
            text.contains("DiamWall", true) || text.contains("Verifying your browser", true)
    }

    private suspend fun loadAndWait(view: WebView, host: String, url: String) {
        loadedHost = host
        val ready = CompletableDeferred<Unit>()
        pageReady = ready
        view.loadUrl(url)
        withTimeoutOrNull(20_000) { ready.await() }
    }

    /** 页面内 fetch 并轮询结果（evaluateJavascript 不支持 await Promise，故把结果挂在 window 上） */
    private suspend fun evalFetch(view: WebView, url: String, form: String? = null): String {
        val init = if (form == null) {
            "{credentials:'include',cache:'no-store',headers:{'X-Requested-With':'XMLHttpRequest'}}"
        } else {
            "{method:'POST',credentials:'include',cache:'no-store'," +
                "headers:{'Content-Type':'application/x-www-form-urlencoded'," +
                "'X-Requested-With':'XMLHttpRequest'},body:${JSONObject.quote(form)}}"
        }
        val js = "(function(){try{window.__syZ=null;" +
            "fetch(${JSONObject.quote(url)},$init)" +
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
