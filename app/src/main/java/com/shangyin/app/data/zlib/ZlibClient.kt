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
     * 候选线路：登录页加载失败会自动换下一个，成功后写回 SettingsStore.zlibHost。
     * 各家镜像在不同网络下可用性差异很大，故列全一份；用户也可在登录页手动粘贴地址。
     */
    val ALT_HOSTS = listOf(
        "z-library.sk", "zh.z-library.sk",
        "1lib.sk", "zh.1lib.sk",
        "z-lib.fm", "z-lib.gs", "zh.z-lib.gs",
        "singlelogin.re"
    )

    /** 登录页候选地址（当前设置的线路优先；设置值兼容整串 URL，只取域名） */
    fun candidateUrls(): List<String> {
        val cur = SettingsStore.zlibHost.trim()
            .substringAfter("://").substringBefore("/").substringBefore("?")
        return (listOf(cur) + ALT_HOSTS.filter { it != cur }).map { "https://$it/" }
    }

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