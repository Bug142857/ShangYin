package com.shangyin.app.data.zlib

import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val rating: String?,
    /** 站点给出的下载入口（实测形如 `/dl/w5X6O61Zmo`，10 位短 token，前端从不自己拼地址） */
    val dl: String? = null,
    /** 在线阅读地址（有则可跳站点阅读器） */
    val readOnlineUrl: String? = null
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
     * 调 eapi 并解析 JSON。走 [ZlibWeb]（WebView 页面内 fetch）。
     * [form] 非空 → POST（表单体）；null → GET。
     */
    private suspend fun getJson(path: String, form: String? = null): JSONObject {
        val text = ZlibWeb.fetchText(path, form)
        return runCatching { JSONObject(text) }.getOrElse {
            throw Exception("接口未返回数据（未登录或验证已过期）：请在 设置 → 账号管理 → Z-Library 登录 里重新登录一次")
        }
    }

    /**
     * 服务端校验登录态：在页面里问 `/eapi/user/profile`。
     *
     * ⚠️ **Cookie 里存在 `remix_userkey` ≠ 会话有效**（服务端可能早已让旧会话失效），
     * 所以登录页的「自动判定成功」与搜索的报错文案都以这个结果为准，
     * 否则会出现「App 一直显示已登录、接口却当成匿名请求」的死结。
     *
     * @return true = 有效；false = 服务端明确说未登录/已失效；
     *         **null = 通道不可用或拿到的是挑战页/HTML，无法判断**
     *         （⚠️ 不能把"取不到"当成"已过期"，否则反爬挑战一抖动就骗用户重新登录）
     */
    suspend fun sessionOk(): Boolean? {
        val text = try {
            ZlibWeb.fetchText("/eapi/user/profile")
        } catch (e: Exception) {
            return null
        }
        if (text.isBlank()) return null
        if (text.contains("\"success\":0") ||
            text.contains("登录到您的账户") ||
            text.contains("log in to your account", true)
        ) {
            return false
        }
        // 只有真正的接口 JSON 才算"确认有效"；HTML（反爬挑战页等）一律算无法判断
        return if (text.trimStart().startsWith("{")) true else null
    }

    /** 预热：进图书页时把站点首页先加载好（实测约 7.4 秒），省得用户搜完还要等页面加载 */
    suspend fun warmup() = ZlibWeb.warmup()

    // ---------------- 搜索预取（解决「第一次搜索很慢」） ----------------

    /**
     * 搜索串行化：预取与用户点击的搜索共用一把锁，避免同一关键词并发发两次请求
     * （站点对密集请求会 429 限流）。
     */
    private val searchLock = Mutex()

    /** 预取缓存：用户敲字停顿时先搜一次，按下搜索直接命中 */
    @Volatile
    private var prefetched: Pair<String, BookPage>? = null

    @Volatile
    private var prefetchedAt = 0L

    private const val PREFETCH_TTL_MS = 120_000L

    private fun cacheKey(keyword: String, page: Int) = "$page|${keyword.trim()}"

    private fun prefetchFresh() = System.currentTimeMillis() - prefetchedAt < PREFETCH_TTL_MS

    /**
     * 预取：输入停顿后先替用户把这次搜索打出去。
     * 实测同一关键词的第二次请求只要 <1 秒（第一次是服务端冷启动，约 6 秒），
     * 所以预热过的关键词，按下搜索几乎立刻出结果。
     */
    suspend fun prefetch(keyword: String, page: Int = 1) {
        val kw = keyword.trim()
        if (kw.length < 2) return
        val key = cacheKey(kw, page)
        searchLock.withLock {
            if (prefetched?.first == key && prefetchFresh()) return@withLock
            runCatching { searchNetwork(kw, page) }.onSuccess {
                prefetched = key to it
                prefetchedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * 账号每日下载额度。Z-Library 按账号限制每日下载次数，
     * 站点在 `/eapi/user/profile` 里给（字段名各线路不完全一致，这里按关键字宽解析：
     * 含 limit/max/quota 的当上限、含 today/used/count 的当已用、含 reach 的当「已用完」）。
     * 拿不到任何 download 字段就返回 null（界面不显示避免误导）。
     */
    data class DownloadQuota(
        val used: Int?,
        val limit: Int?,
        val reached: Boolean,
        val raw: List<Pair<String, String>>
    ) {
        /** 给界面用的一句话 */
        val text: String
            get() = when {
                reached -> "今日下载额度已用完" + (limit?.let { "（上限 $it 次）" } ?: "")
                limit != null && used != null -> "今日下载额度：已用 $used / $limit 次"
                limit != null -> "今日下载额度：上限 $limit 次"
                used != null -> "今日已下载 $used 次"
                else -> raw.joinToString(" · ") { "${it.first}=${it.second}" }
            }
    }

    private fun collectDownloadFields(o: JSONObject, out: MutableMap<String, String>) {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = o.opt(k)) {
                is JSONObject -> collectDownloadFields(v, out)
                is org.json.JSONArray -> for (i in 0 until v.length()) {
                    (v.opt(i) as? JSONObject)?.let { collectDownloadFields(it, out) }
                }
                else -> if (k.contains("download", ignoreCase = true)) out[k] = v?.toString().orEmpty()
            }
        }
    }

    suspend fun downloadQuota(): DownloadQuota? = runCatching {
        val root = JSONObject(ZlibWeb.fetchText("/eapi/user/profile"))
        if (root.optString("success").trim() == "0") return@runCatching null
        val fields = LinkedHashMap<String, String>()
        collectDownloadFields(root, fields)
        if (fields.isEmpty()) return@runCatching null
        var used: Int? = null
        var limit: Int? = null
        var reached = false
        fields.forEach { (k, v) ->
            val lk = k.lowercase()
            val n = v.toIntOrNull()
            val truthy = v.equals("true", true) || v == "1"
            when {
                lk.contains("reach") -> if (truthy) reached = true
                lk.contains("limit") || lk.contains("max") || lk.contains("quota") -> if (n != null) limit = n
                lk.contains("today") || lk.contains("used") || lk.contains("count") -> if (n != null) used = n
            }
        }
        DownloadQuota(used, limit, reached, fields.toList())
    }.getOrNull()

    /** 站点对「未登录 / 会话失效」返回的文案（中英两版都实测过） */
    private val NOT_LOGGED_IN_WORDS = listOf(
        "未找到请求的书", "requested book not found",
        "登录到您的账户", "log in to your account"
    )

    /** 未登录/会话失效时的可操作提示 */
    private fun notLoggedInHint(): String =
        "Z-Library 未登录或登录已失效（线路 ${SettingsStore.zlibHost}）\n" +
            "请到 设置 → 账号管理 → Z-Library 登录 里重新登录一次"

    /**
     * 出错时的可展示文案：命中「未登录」类关键字时再用 [sessionOk] 向服务端确认，
     * **只有服务端明确说未登录（== false）才提示重新登录**；
     * 无法判断（null，如反爬挑战抖动）一律按站点原话展示，不当成登录失效。
     */
    private suspend fun errorMessage(msg: String): String {
        if (msg.isBlank()) return if (sessionOk() == false) notLoggedInHint() else "站点未返回数据"
        if (NOT_LOGGED_IN_WORDS.none { msg.contains(it, true) }) return msg
        return if (sessionOk() == false) notLoggedInHint() else msg
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
            description = cleanHtml(optString("description")).ifBlank { null },
            rating = optString("rating").trim().ifBlank { null },
            dl = optString("dl").trim().ifBlank { null },
            readOnlineUrl = optString("readOnlineUrl").trim().ifBlank { null }
        )
    }

    /**
     * 站点简介是带标签的 HTML（实测形如 `《搏击俱乐部》作者<br><p>当代最负盛名的…</p><p>…`），
     * 直接显示会看到一堆 `<p>` `<br>`，这里清成可读纯文本。
     */
    fun cleanHtml(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw
        s = s.replace(Regex("(?i)<\\s*br\\s*/?\\s*>"), "\n")
        s = s.replace(Regex("(?i)</\\s*(p|div|li|h[1-6])\\s*>"), "\n")
        s = s.replace(Regex("(?i)<\\s*(p|div|li|h[1-6])[^>]*>"), "")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        s = s.replace(Regex("[ \\t\\u00a0]+"), " ")
        s = s.replace(Regex(" *\n *"), "\n")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    /**
     * 搜索。返回书列表 + 总页数。
     * eapi：`/eapi/book/search?message={kw}&page={n}&limit=20`
     */
    suspend fun search(keyword: String, page: Int): BookPage = searchLock.withLock {
        // 命中预取结果就直接用（用掉即失效，避免翻页/换词时误用旧数据）
        val key = cacheKey(keyword, page)
        prefetched?.let { (k, v) ->
            if (k == key && prefetchFresh()) {
                prefetched = null
                return@withLock v
            }
        }
        searchNetwork(keyword, page)
    }

    private suspend fun searchNetwork(keyword: String, page: Int): BookPage {
        val e = URLEncoder.encode(keyword, "UTF-8")
        val path = "/eapi/book/search?message=$e&page=$page&limit=20"
        // ⚠️ 必须用 POST（表单体）：实测同一路径 `GET` 恒返回
        // `400 {"success":0,"error":"未找到请求的书"}`（误导性报错，与登录态无关），
        // 只有 POST 才返回 `200 {"success":1,"books":[...]}` —— 这是「搜不到东西」的真根因。
        val root = getJson(path, "message=$e&page=$page&limit=20")
        val arr = root.optJSONArray("books") ?: root.optJSONArray("exactMatch")
        if (arr == null) {
            // 没有 books 字段 = 服务端错误（未登录最典型）：先向服务端确认会话是否还有效，再决定文案
            val msg = root.optString("error").trim().ifBlank { root.optString("message").trim() }
            throw Exception(
                if (msg.isNotBlank()) errorMessage(msg)
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
     * ⚠️ 实测（2026-09-20）**这个接口只有 GET 可用**：
     * `POST /eapi/book/{id}/{hash}` 恒返回 `404 {"success":0,"error":"Requested page not found"}`
     * （详情页那个 "Requested page not found" 提示就是这么来的），改用 GET。
     * 不同线路返回结构有差异，这里做兼容：直接是书对象 / 包在 book 里 / 包在 books 数组里。
     */
    suspend fun detail(id: String, hash: String): Book {
        val root = getJson("/eapi/book/$id/$hash", null)
        root.optJSONObject("book")?.toBook()?.let { return it }
        root.optJSONArray("books")?.optJSONObject(0)?.toBook()?.let { return it }
        root.toBook()?.let { return it }
        val msg = root.optString("error").trim().ifBlank { root.optString("message").trim() }
        throw Exception(errorMessage(msg.ifBlank { "未获取到图书信息" }))
    }

    /**
     * 拿到下载目标：走**站点自己的下载入口**（`dl` 字段 → `/dl/{token}` 页面 → WebView 交出的真实文件地址）。
     *
     * 为什么不用 `/eapi/book/{id}/{hash}/file`：实测该路径返回
     * `400 {"success":0,"error":"登录到您的账户"}`，而前端真实按钮是服务端渲染的 `/dl/{token}`，
     * 自己拼 `/dl/{id}/{hash}/{文件名}` 只会拿到主题化 404 页面。
     */
    suspend fun downloadTarget(id: String, hash: String, dl: String?): ZlibWeb.DownloadTarget {
        val token = dl?.takeIf { it.isNotBlank() } ?: detail(id, hash).dl
        if (token.isNullOrBlank()) throw Exception("站点未给出下载入口（该书可能仅支持在线阅读）")
        return ZlibWeb.openDownloadPage(token)
    }
}