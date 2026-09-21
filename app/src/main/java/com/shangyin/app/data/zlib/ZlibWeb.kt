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

    /** 站点把文件地址交给 WebView（DownloadListener）时用它把结果送回 openDownloadPage */
    private var pendingFile = CompletableDeferred<String>()
    private var lastFileName: String? = null
    private var lastFileMime: String? = null

    val attached: Boolean get() = webView != null

    /** 站点交给 WebView 的下载目标（真实文件地址 + 文件名/类型） */
    data class DownloadTarget(val pageUrl: String, val fileUrl: String, val fileName: String?, val mimeType: String?)

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
        // 页面还在（只是换了账号），必须让等待信号处于已完成态，
        // 否则下一次请求会等一个永远不会完成的 pageReady 直到超时
        pageReady = CompletableDeferred<Unit>().apply { complete(Unit) }
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
        // 站点的下载按钮就是 `window.open(href)`，href 是服务端渲染的 `/dl/{token}`（前端不拼地址）。
        // 所以「下载」必须走站点自己的流程：打开 /dl/ 页面，让站点把真实文件地址交给 WebView。
        view.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            lastFileName = contentDisposition
            lastFileMime = mimeType
            pendingFile.complete(url)
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

    /**
     * 在某条线路上取接口内容（必要时先加载站点首页建会话）。
     *
     * ⚠️ 实测（2026-09-20）**必须等页面稳定后再发请求**：首页要 7.4 秒才加载完，
     * 若在导航过程中发 fetch，导航会打断请求 → 页面里报 `Failed to fetch`
     * （这就是「每次打开 App 第一次搜索必失败、重试才行」的根因）。
     * 另外请求地址用**页面自身的 origin**（相对路径）拼接，永远同源，
     * 不会因为站点重定向换域而变成跨域请求失败。
     */
    private suspend fun fetchOn(view: WebView, host: String, path: String, form: String?): String {
        if (loadedHost != host) loadAndWait(view, host, "https://$host/")
        if (!awaitPageUsable(view, host)) {
            // 页面跑到了站外（例如下载后停在 CDN）或一直不可用：回站点首页重建会话
            loadAndWait(view, host, "https://$host/")
            awaitPageUsable(view, host)
        }
        var lastError: Exception? = null
        repeat(4) { attempt ->
            val text = try {
                evalFetch(view, path, form)
            } catch (e: Exception) {
                // 导航打断 / 连接重置这类瞬时失败：等页面稳定后重试
                lastError = e
                delay(900L * (attempt + 1))
                awaitPageUsable(view, host)
                return@repeat
            }
            if (!isChallenge(text)) {
                rememberActualHost(view)
                return text
            }
            // 页面正处于 DiamWall 挑战中：它的 JS 需要几秒完成验证，等一会儿再试
            delay(2000)
            awaitPageUsable(view, host)
        }
        throw lastError ?: ChallengeException()
    }

    /**
     * 走站点自己的下载流程：打开书籍给的 `/dl/{token}` 页面，
     * 等站点把真实文件地址交给 WebView（DownloadListener）。
     * 拿不到就把页面上能读到的原因交回上层（最常见：每日额度用尽 / 未登录 / 反爬）。
     *
     * 为什么不自己拼下载地址：实测站点前端从不拼地址，按钮 href 就是服务端渲染的
     * `/dl/{token}`（10 位短 token），前端只做 `window.open(href)`；
     * 我们拼 `/dl/{id}/{hash}/{文件名}` 只会得到主题化 404 页面（`Requested page not found` 的由来）。
     */
    suspend fun openDownloadPage(path: String, timeoutMs: Long = 25_000): DownloadTarget =
        withContext(Dispatchers.Main) {
            val view = webView ?: throw Exception("图书会话未就绪，请退出图书页后重新进入")
            val host = candidateHosts().firstOrNull { hasSession(it) } ?: candidateHosts().first()
            val url = if (path.startsWith("http")) path else "https://$host$path"
            pendingFile = CompletableDeferred()
            lastFileName = null
            lastFileMime = null
            loadedHost = host
            view.loadUrl(url)
            var file = withTimeoutOrNull(8_000) { pendingFile.await() }
            if (file == null) {
                // 有些线路的下载页要用户点一下「下载」才开始：自动点一次站点的下载入口再等
                runCatching {
                    evalValue(
                        view,
                        "(function(){var a=document.querySelector('a.addDownloadedBook,a.dlButton," +
                            "a[href*=\"/dl/\"],button.dlDropdownBtn');if(!a)return '';a.click();return 'ok'})()"
                    )
                }
                file = withTimeoutOrNull((timeoutMs - 8_000).coerceAtLeast(5_000)) { pendingFile.await() }
            }
            if (file != null) {
                return@withContext DownloadTarget(url, file, lastFileName, lastFileMime)
            }
            val text = runCatching {
                evalValue(view, "(document.body&&document.body.innerText||'').slice(0,600)")
            }.getOrNull().orEmpty()
            throw Exception(pageReason(text))
        }

    /** 下载页没能给出文件地址时，把页面上的原因翻译成可操作提示 */
    private fun pageReason(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return when {
            t.contains("限额") || t.contains("limit", true) ->
                "Z-Library 提示：每日下载额度已用完（站点按账号/IP 限制），额度恢复后再试"
            t.contains("登录") || t.contains("log in", true) || t.contains("sign in", true) ->
                "需要登录 Z-Library 才能下载，请到 设置 → 账号管理 → Z-Library 登录"
            t.isBlank() -> "站点没有给出文件地址（可能被反爬拦截），请重试"
            else -> "站点没有给出文件地址：" + t.take(160)
        }
    }

    private fun isZlibHost(host: String): Boolean {
        val h = host.lowercase()
        return h.contains("z-lib") || h.startsWith("zlib.") || h.contains(".zlib.")
    }

    /** 把页面真实 host 记回来（站点可能从 z-lib.sk 重定向到 zh.z-lib.sk） */
    private suspend fun rememberActualHost(view: WebView) {
        val h = evalValue(view, "location.host")?.trim().orEmpty()
        if (h.isNotBlank() && isZlibHost(h)) rememberHost(h)
    }

    /**
     * 等页面真的可用：文档已提交、`readyState` 脱离 loading，且仍在站点域内。
     * 返回 false 表示页面已跑到站外（例如下载后停在 CDN）或始终没就绪 —— 调用方需要重新加载首页。
     */
    private suspend fun awaitPageUsable(view: WebView, host: String, timeoutMs: Long = 25_000): Boolean {
        withTimeoutOrNull(timeoutMs) { pageReady.await() }
        var usable = false
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val info = evalValue(view, "(document.readyState||'')+'|'+(location.host||'')")
                val ready = info?.substringBefore('|').orEmpty()
                val cur = info?.substringAfter('|', "").orEmpty()
                if (cur.isNotBlank() && !isZlibHost(cur)) return@withTimeoutOrNull
                if (ready.isNotBlank() && ready != "loading" && cur.isNotBlank()) {
                    if (cur != host) rememberHost(cur)
                    usable = true
                    return@withTimeoutOrNull
                }
                delay(200)
            }
        }
        return usable
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

    /**
     * 页面内 fetch 并轮询结果（evaluateJavascript 不支持 await Promise，故把结果挂在 window 上）。
     * [path] 为相对路径时用**页面自身的 origin** 拼接：永远同源，站点重定向换域也不会变成跨域失败
     * （实测未登录请求 `/eapi/book/search` 时站点会 307 到 `zh.` 子域，用绝对地址就是跨域 → `Failed to fetch`）。
     *
     * 用自增序号当令牌，避免上一次请求的迟到结果被这一次误读。
     */
    private suspend fun evalFetch(view: WebView, path: String, form: String? = null): String {
        val init = if (form == null) {
            "{credentials:'include',cache:'no-store',headers:{'X-Requested-With':'XMLHttpRequest'}}"
        } else {
            "{method:'POST',credentials:'include',cache:'no-store'," +
                "headers:{'Content-Type':'application/x-www-form-urlencoded'," +
                "'X-Requested-With':'XMLHttpRequest'},body:${JSONObject.quote(form)}}"
        }
        val target = if (path.startsWith("http")) {
            JSONObject.quote(path)
        } else {
            "(location.origin + " + JSONObject.quote(if (path.startsWith("/")) path else "/$path") + ")"
        }
        val js = "(function(){try{var id=(window.__syZi=(window.__syZi||0)+1);window.__syZ=null;" +
            "fetch($target,$init)" +
            ".then(function(r){return r.text()})" +
            ".then(function(t){if(window.__syZi===id)window.__syZ=t})" +
            ".catch(function(e){if(window.__syZi===id)window.__syZ='__err__'+(e&&e.message?e.message:e)});return 1}" +
            "catch(e){window.__syZ='__err__'+e;return 0}})()"
        view.evaluateJavascript(js, null)
        repeat(100) {
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
