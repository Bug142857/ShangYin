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
 * ⚠️ 接口请求不走 OkHttp，而是走 [ZlibWeb]（常驻隐藏 WebView 的页面内 fetch）：
 * Z-Library 全站 DiamWall 反爬——接口请求会 307 到自身并下发只有 5 分钟有效期的 `__diamwall`，
 * 再请求就变成 `Verifying your browser` 挑战页（页面内嵌 iframe + chlb.lib 算证明）。
 * 实测（2026-09-20）纯 HTTP 客户端无论带不带 cookie 都过不去，只有真浏览器引擎能跑，
 * 所以必须复用登录页完成验证的同一浏览器环境（这也是「登录成功但搜不到东西」的根因：
 * cookie 一过期，OkHttp 请求就全被挑战页挡下）。
 *
 * 站点域名（线路）可配置：SettingsStore.zlibHost，登录成功后由登录页写入当日验证可用的线路。
 */
object ZlibClient {

    /**
     * 请求 UA：与登录页 WebView 的真实默认 UA 保持一致（DiamWall 的验证 Cookie 与 UA 绑定）。
     * 不伪造桌面 UA——伪造的 UA 与 WebView 自动发出的 Client Hints
     * （sec-ch-ua-platform: Android / sec-ch-ua-mobile: ?1）矛盾，会被反爬判定为机器人。
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

    /** Cookie 采集 / 清理用的站点根地址：当前线路（含 `zh.` 变体）优先，再兜底内置域名 */
    fun cookieUrls(): List<String> {
        val h = SettingsStore.zlibHost.trim().removePrefix("https://").trimEnd('/')
            .ifBlank { FALLBACK_HOSTS.first() }
        val bare = h.removePrefix("zh.")
        return (listOf(h, "zh.$bare", bare) + FALLBACK_HOSTS).distinct().map { "https://$it/" }
    }

    /** 「中文子域 + 主域」各生成一条 /login 地址 */
    private fun toLoginUrls(hosts: List<String>): List<String> =
        hosts.flatMap { h ->
            listOf(
                "https://zh.$h/login?redirectUrl=https%3A%2F%2Fzh.$h%2F",
                "https://$h/login?redirectUrl=https%3A%2F%2F$h%2F"
            )
        }.distinct()

    /** 登录态是否可用（有 remix_userkey 才算真正登录） */
    val isLoggedIn: Boolean
        get() = SettingsStore.zlibCookie.contains("remix_userkey", ignoreCase = true)

    /**
     * 调 eapi 并解析 JSON。走 [ZlibWeb]（WebView 页面内 fetch），
     * 挑战页/HTML 响应会被它识别并重试，最终仍拿不到数据时抛出可展示的文案。
     */
    private suspend fun getJson(path: String): JSONObject {
        val text = ZlibWeb.fetchText(path)
        return runCatching { JSONObject(text) }.getOrElse {
            throw Exception("接口未返回数据（未登录或验证已过期）：请在 设置 → 账号管理 → Z-Library 登录 里重新登录一次")
        }
    }

    /**
     * 站点对「未登录 / 会话过期」返回的是误导性文案——实测匿名调搜索接口
     * 无论关键词是什么都回 `{"success":0,"error":"未找到请求的书"}`，
     * 调用户资料接口回 `{"success":0,"error":"登录到您的账户"}`。这里统一换成可操作的提示。
     */
    private fun friendlyError(msg: String): String {
        val notLoggedIn = msg.contains("未找到请求的书") || msg.contains("登录到您的账户") ||
            msg.contains("log in", true) || msg.contains("sign in", true)
        return if (notLoggedIn) {
            "Z-Library 未登录或登录已失效（线路 ${SettingsStore.zlibHost}）\n" +
                "请到 设置 → 账号管理 → Z-Library 登录 里重新登录一次"
        } else msg
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
        val arr = root.optJSONArray("books") ?: root.optJSONArray("exactMatch")
        if (arr == null) {
            // 没有 books 字段 = 服务端错误（未登录最典型），把原因换成可操作的文案
            val msg = root.optString("error").trim().ifBlank { root.optString("message").trim() }
            throw Exception(
                if (msg.isNotBlank()) friendlyError(msg)
                else "搜索接口返回结构异常：" + root.toString().take(120)
            )
        }
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
        throw Exception(if (msg.isNotBlank()) friendlyError(msg) else "未获取到下载链接，请确认已登录且下载额度未用尽")
    }
}