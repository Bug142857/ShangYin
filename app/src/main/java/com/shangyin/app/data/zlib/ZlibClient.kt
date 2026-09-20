package com.shangyin.app.data.zlib

import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 图书条目（搜索/详情共用） */
@kotlinx.serialization.Serializable
data class Book(
    val id: String,
    val hash: String,
    val title: String,
    val author: String?,
    val cover: String?,
    val year: String?,
    val language: String?,
    val extension: String?,
    val filesize: String?,
    val publisher: String?,
    val description: String?,
    val rating: String?
) {
    val key: String get() = "$id/$hash"
}

data class BookPage(val books: List<Book>, val totalPages: Int, val currentPage: Int)

/**
 * Z-Library 客户端（eapi 协议），参考 o-lib（github.com/shiyi-0x7f/o-lib）的用法：
 * 登录态用 `remix_userid` + `remix_userkey` 两个 Cookie 表示。
 *
 * 重要约束（实测 2026-09-18）：
 *  Z-Library 全站使用 DiamWall 反爬（JS 挑战）：普通 HTTP 请求会得到
 *  307 重定向环 / HTTP 513 "Verifying your browser" 挑战页，只有真实浏览器引擎能过验证。
 *  因此本 App 走「WebView 登录（顺带完成 DiamWall 验证）→ 取出 Cookie → OkHttp 带同一 UA+Cookie 调 eapi」。
 *  DiamWall 验证有时效，过期后接口会再次返回 513，此时 UI 会提示重新登录刷新验证。
 *
 * 站点域名（线路）可配置：SettingsStore.zlibHost，默认 z-library.sk。
 */
object ZlibClient {

    /**
     * 请求 UA：必须与登录页 WebView 实际发出的 UA 完全一致（DiamWall 的验证 Cookie 与 UA 绑定）。
     * 这里取 WebView 的真实默认 UA，不伪造——伪造桌面 UA 与 WebView 自动发出的 Client Hints
     * （sec-ch-ua-platform: Android / sec-ch-ua-mobile: ?1）矛盾，会被反爬判定为机器人，
     * 表现为「手机浏览器能打开、App 里一直转圈或反复跳转」。
     */
    val UA: String
        get() = com.shangyin.app.App.webViewUa.ifBlank { FALLBACK_UA }

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 内置兜底线路（2026-09 经 getzlib.com 验证为 z-lib.sk）。
     * 站点每日轮换域名，这里只做兜底，优先用 [dailyLoginUrls] 解析出的当日地址。
     */
    private val FALLBACK_HOSTS = listOf("zh.z-lib.sk", "z-lib.sk")

    /** getzlib.com 的每日验证页（页面上列出的才是当前真实可用的域名） */
    private const val DAILY_PAGE = "https://getzlib.com/zh"

    private val HOST_RE = Regex("""https://([a-z0-9.\-]+)/""", RegexOption.IGNORE_CASE)

    /** 动态线路解析用：允许跟随跳转、超时短，避免拖慢登录页打开 */
    private val resolver = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 是否是本站域名（用于「记住可用线路」判断）；顺带排除 getzlib.com / cdn-zlib.sk 这类同名干扰域 */
    fun isZlibHost(host: String): Boolean {
        val h = host.lowercase()
        return h.contains("z-lib") || h.startsWith("zlib.") || h.contains(".zlib.")
    }

    /**
     * 从 getzlib.com 取当日验证可用的域名，展开成「中文子域 + 主域」的**登录页**地址。
     * 站点每日换域名，内置地址容易过期，所以以在线解析结果为准；解析失败回退内置地址。
     */
    suspend fun dailyLoginUrls(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            resolver.newCall(
                Request.Builder().url(DAILY_PAGE)
                    .header("User-Agent", UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val html = resp.body?.string().orEmpty()
                val hosts = HOST_RE.findAll(html)
                    .map { it.groupValues[1].lowercase().removeSuffix(".") }
                    .filter { isZlibHost(it) }
                    .distinct()
                    .take(3)
                    .toList()
                toLoginUrls(hosts)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 登录页候选地址（内置兜底；动态解析结果会在登录页里插到最前）。
     *
     * 直达 `/login` 而不是首页：首页那个「登录」是普通 `<a href="/login?redirectUrl=...">`，
     * 但站点脚本会在 WebView 里接管点击且不产生任何可见结果（实测点击后既不跳转也无请求），
     * 所以直接打开登录页，绕开这次点击。
     */
    fun loginUrls(): List<String> = toLoginUrls(FALLBACK_HOSTS)

    /** Cookie 采集用的站点根地址（登录页所在域名） */
    fun cookieUrls(): List<String> = FALLBACK_HOSTS.map { "https://$it/" }

    /** 「中文子域 + 主域」各生成一条 /login 地址 */
    private fun toLoginUrls(hosts: List<String>): List<String> =
        hosts.flatMap { h ->
            listOf(
                "https://zh.$h/login?redirectUrl=https%3A%2F%2Fzh.$h%2F",
                "https://$h/login?redirectUrl=https%3A%2F%2F$h%2F"
            )
        }.distinct()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        // DiamWall 会用 307 指向自身，自动跟随会导致请求环，这里手动处理
        .followRedirects(false)
        .build()

    private val base: String get() = "https://" + SettingsStore.zlibHost.trim().trimEnd('/').removePrefix("https://")

    /** 登录态是否可用（有 remix_userkey 才算真正登录） */
    val isLoggedIn: Boolean
        get() = SettingsStore.zlibCookie.contains("remix_userkey", ignoreCase = true)

    private fun newRequest(url: String): Request {
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", "$base/")
            .header("X-Requested-With", "XMLHttpRequest")
        val cookie = SettingsStore.zlibCookie
        if (cookie.isNotBlank()) b.header("Cookie", cookie)
        return b.build()
    }

    /** 反爬/线路异常时抛出可直接展示给用户的文案 */
    private fun failFor(code: Int, body: String): Nothing {
        val blocked = code == 513 || code == 403 || code == 503 ||
            body.contains("DiamWall", true) || body.contains("Verifying your browser", true)
        throw Exception(
            if (blocked) "需要重新验证：请在 设置 → 账号管理 → Z-Library 登录 里重新登录一次"
            else if (code == 307 || code == 302) "线路不可用：请更换线路域名后重试"
            else "接口错误 HTTP $code"
        )
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else base + path
        client.newCall(newRequest(url)).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code in 300..399) failFor(resp.code, body)
            if (!resp.isSuccessful) failFor(resp.code, body)
            if (body.isBlank()) throw Exception("站点返回空内容")
            runCatching { JSONObject(body) }.getOrElse {
                if (body.contains("DiamWall", true) || body.contains("Verifying", true)) {
                    failFor(513, body)
                }
                throw Exception("响应格式异常，可能线路已变更")
            }
        }
    }

    private fun JSONObject.toBook(): Book? {
        val id = optString("id").trim()
        val hash = optString("hash").trim()
        if (id.isBlank()) return null
        val publisher = optString("publisher").trim().ifBlank { null }
        return Book(
            id = id,
            hash = hash,
            title = optString("title").trim().ifBlank { "（无标题）" },
            author = optString("author").trim().ifBlank { null },
            cover = optString("cover").trim().ifBlank { null },
            year = optString("year").trim().ifBlank { null },
            language = optString("language").trim().ifBlank { null },
            extension = optString("extension").trim().ifBlank { null },
            filesize = optString("filesize").trim().ifBlank { null },
            publisher = publisher,
            description = optString("description").trim().ifBlank { null },
            rating = optString("rating").trim().ifBlank { null }
        )
    }

    /**
     * 搜索。返回书列表 + 总页数。
     * eapi：`/eapi/book/search?message={kw}&page={n}&limit=20`
     */
    suspend fun search(keyword: String, page: Int): BookPage {
        val e = URLEncoder.encode(keyword, "UTF-8")
        val root = getJson("/eapi/book/search?message=$e&page=$page&limit=20")
        val arr = root.optJSONArray("books") ?: root.optJSONArray("exactMatch") ?: org.json.JSONArray()
        val books = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toBook() }
        val pag = root.optJSONObject("pagination")
        val total = pag?.optInt("total_pages", 0) ?: 0
        val current = pag?.optInt("current_page", page) ?: page
        return BookPage(books, total, current)
    }

    /**
     * 图书详情。eapi：`/eapi/book/{id}/{hash}`
     * 不同线路返回结构有差异，这里做兼容：直接是书对象 / 包在 book 里 / 包在 books 数组里。
     */
    suspend fun detail(id: String, hash: String): Book {
        val root = getJson("/eapi/book/$id/$hash")
        root.optJSONObject("book")?.toBook()?.let { return it }
        root.optJSONArray("books")?.optJSONObject(0)?.toBook()?.let { return it }
        root.toBook()?.let { return it }
        throw Exception("未获取到图书信息")
    }

    /**
     * 取下载直链。eapi：`/eapi/book/{id}/{hash}/file` → `{"file":{"downloadLink":"..."}}`
     * 未登录 / 额度用尽会返回明确错误信息。
     */
    suspend fun downloadLink(id: String, hash: String): String {
        val root = getJson("/eapi/book/$id/$hash/file")
        val link = root.optJSONObject("file")?.optString("downloadLink")?.trim().orEmpty()
        if (link.isNotBlank()) return link
        val msg = root.optString("message").trim().ifBlank { root.optString("error").trim() }
        throw Exception(msg.ifBlank { "未获取到下载链接，请确认已登录且下载额度未用尽" })
    }
}