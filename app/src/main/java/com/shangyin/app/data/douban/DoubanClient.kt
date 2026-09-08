package com.shangyin.app.data.douban

import com.shangyin.app.data.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.shangyin.app.ui.settings.SettingsStore
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 豆瓣网页接口客户端（非官方）。
 * 详情数据优先走 Rexxar API（豆瓣App内部接口，JSON 含导演/演员/类型/评分/简介/封面/预告片），
 * 失败则回退移动版条目页解析 meta 标签。
 */
object DoubanClient {

    /** 桌面 Chrome UA：和浏览器一致，避免被豆瓣按移动 UA 区别拦截 */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    /** Rexxar API 用移动 UA（豆瓣 App 内部接口需要移动 UA） */
    private const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

    /**
     * 将豆瓣图片 URL 升级为大图/原图，解决封面放大后模糊的问题。
     * 豆瓣图片路径含尺寸段：/view/<类型>/<尺寸>/public/<id>.jpg
     * 尺寸等级（小→大）：albumicon/thumb/s/m < square/l/sqxs < raw(原图)
     */
    fun largeImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return url
        var u = url
        // 去掉可能的尺寸/格式查询参数
        u = u.substringBefore('?')
        return u
            .replace("/view/subject/s/", "/view/subject/l/")
            .replace("/view/subject/m/", "/view/subject/l/")
            .replace("/s_ratio_poster/", "/l_ratio_poster/")
            .replace("/m_ratio_poster/", "/l_ratio_poster/")
            .replace("/s_ratio_celebrity/", "/l_ratio_celebrity/")
            .replace("/view/celebrity/s/", "/view/celebrity/l/")
            .replace("/view/celebrity/m/", "/view/celebrity/l/")
            .replace("/view/personage/s/", "/view/personage/l/")
            .replace("/view/personage/m/", "/view/personage/l/")
            .replace("/albumicon/", "/sqxs/")
            .replace("/thumb/", "/sqxs/")
            .replace("/icon/", "/sqxs/")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 在文本中找到 marker 之后的第一个 '{'，然后用括号匹配提取完整的 JSON 对象字符串。
     * 不能用正则 \{.*?\}（非贪婪只匹配到第一个 }，嵌套 JSON 会截断）。
     */
    private fun extractJsonObjectAfter(text: String, marker: String): String? {
        val start = text.indexOf(marker)
        if (start < 0) return null
        val braceStart = text.indexOf('{', start + marker.length)
        if (braceStart < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in braceStart until text.length) {
            val c = text[i]
            if (escaped) { escaped = false; continue }
            when {
                c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(braceStart, i + 1)
                }
            }
        }
        return null
    }

    /** 启动时生成一次固定 bid，模拟真实用户（真实用户的 bid 不会每次请求都变） */
    private val fixedBid: String = buildString {
        val cs = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        repeat(11) { append(cs.random()) }
    }

    /**
     * OkHttpClient 实例：用 @Volatile + 双重检查锁实现可重建。
     * evictAll() 只清连接，但 OkHttp 内部连接池状态可能仍被污染，
     * 彻底重建 client（含新的 ConnectionPool + 新的 Cache 实例）才是"核武器"级别清理。
     * 在连续失败 N 次后调用 recreateClient()，下次请求会拿全新客户端。
     */
    @Volatile
    private var clientRef: OkHttpClient? = null

    private val clientLock = Any()

    private val mobileClient: OkHttpClient
        get() = clientRef ?: synchronized(clientLock) {
            clientRef ?: buildClient().also { clientRef = it }
        }

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(com.shangyin.app.App.instance.cacheDir, "http"), 20 * 1024 * 1024L))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .addInterceptor { chain ->
            val req = chain.request()
            val isRexxar = req.url.host == "m.douban.com" && req.url.encodedPath.contains("/rexxar/")
            val builder = req.newBuilder()
                .header("User-Agent", if (isRexxar) MOBILE_UA else DESKTOP_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                // 不设 Accept-Encoding：OkHttp 自动加 gzip 并解压；手动设会导致不解压
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
            // 动态添加 Cookie（含登录态 cookie 时可搜索游戏等）
            val cookie = runCatching { SettingsStore.doubanCookie }.getOrDefault("")
            if (cookie.isNotBlank()) {
                builder.header("Cookie", "$cookie; bid=$fixedBid")
            } else {
                builder.header("Cookie", "bid=$fixedBid")
            }
            // 同主机请求限流：间隔至少 500ms，避免短时间大量请求触发反爬
            throttleHost(req.url.host)
            chain.proceed(builder.build())
        }
        .build()

    /** 清理连接池：检测到反爬时调用，断开所有被污染的连接，下次请求重建 */
    private fun evictConnections() {
        runCatching { mobileClient.connectionPool.evictAll() }
    }

    /**
     * 彻底重建 OkHttpClient（核武器级清理）：
     * 关闭旧 client（连接池 + 缓存），用全新的 ConnectionPool 和 Cache 实例替换。
     * 适用于：连续多次失败、登录态变化、Cookie 更新后。
     */
    fun recreateClient() {
        synchronized(clientLock) {
            runCatching { clientRef?.connectionPool?.evictAll() }
            runCatching {
                // 关闭旧 cache（否则旧 cache 文件句柄不释放，新 client 无法复用同一目录）
                clientRef?.cache?.close()
            }
            // 重建新 client（下次请求时会懒加载新连接池和新 Cache 实例）
            clientRef = null
        }
    }

    /** 登录状态或 Cookie 变化时，强制重建客户端让新 Cookie 立即生效 */
    fun onCookieChanged() = recreateClient()

    /** 每个主机上次请求时间戳 */
    private val hostLastRequest = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
    private const val HOST_MIN_INTERVAL_MS = 400L

    private fun throttleHost(host: String) {
        val last = hostLastRequest.getOrPut(host) { AtomicLong(0) }
        val now = System.currentTimeMillis()
        val prev = last.get()
        val wait = HOST_MIN_INTERVAL_MS - (now - prev)
        if (wait > 0) {
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        last.set(System.currentTimeMillis())
    }

    /** 检测豆瓣反爬/限流页面并抛出明确异常
     *  豆瓣被触发反爬时返回的页面通常包含以下关键词之一，此时 HTTP 200 但不是搜索结果。
     *  实测还有一种"空壳反爬"：HTTP 200，页面只有页脚版权（约 1KB），无搜索结果、无 window.__DATA__。
     *  这种页面包含 "all rights reserved" + "北京豆网科技" 但不含任何搜索结果标志性元素。 */
    private fun detectBlockPageAndThrow(html: String) {
        if (html.isBlank()) {
            evictConnections()
            throw IOException("返回内容为空（疑似被限流，请稍后重试）")
        }
        val blockKeywords = listOf(
            "sec.douban.com", "异常请求", "访问过于频繁", "请稍后再试",
            "请输入验证码", "captcha", "robot", "机器人验证",
            "检测到", "安全验证", "页面不存在", "forbidden"
        )
        val lower = html.lowercase()
        val hit = blockKeywords.firstOrNull { lower.contains(it.lowercase()) }
        if (hit != null) {
            // 清理被污染的连接池，下次请求重建连接（否则复用被标记的连接会持续失败）
            evictConnections()
            throw IOException("豆瓣反爬拦截（$hit），请稍后重试或在设置里配置登录 Cookie")
        }
        // 空壳反爬：页面很短且只含版权页脚，没有搜索结果元素
        // 实测 subject_search 被反爬时返回 ~1.5KB 页面，含 "all rights reserved" 但无 window.__DATA__ / 无 div.result
        if (html.length < 3000 &&
            lower.contains("all rights reserved") &&
            !html.contains("window.__DATA__") &&
            !html.contains("div class=\"result\"")
        ) {
            evictConnections()
            throw IOException("豆瓣反爬拦截（空壳页面），请稍后重试或在设置里配置登录 Cookie")
        }
    }

    private fun httpGetMobile(url: String, referer: String? = null): String {
        val builder = Request.Builder().url(url).get()
        if (referer != null) builder.header("Referer", referer)
        // 完全禁用缓存：搜索/网页详情不拿陈旧缓存（maxAge(0) 可能返回 304 空响应）
        builder.cacheControl(CacheControl.Builder().noCache().noStore().build())
        mobileClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    // ---------------- 搜索 ----------------

    /** 按类型搜索豆瓣：走分类 subject_search 页面（解析 window.__DATA__ JSON）
     *  失败时抛 IOException，由调用方决定如何提示用户（避免静默返回空结果让用户以为没搜到）
     *  自带 3 次重试，最后 1 次会彻底重建 OkHttpClient（核武器级清理，避免持续失败） */
    suspend fun search(category: Category, query: String): List<DoubanResult> =
        withContext(Dispatchers.IO) {
            var lastErr: Throwable? = null
            for (attempt in 0..3) {
                if (attempt > 0) {
                    // 第1次轻清理+1秒延迟；第2次2秒；第3次彻底重建client+3秒
                    if (attempt >= 3) {
                        android.util.Log.w("Douban", "search attempt=$attempt: recreating OkHttpClient (nuclear)")
                        recreateClient()
                        Thread.sleep(3000)
                    } else {
                        evictConnections()
                        Thread.sleep(1000L * attempt)
                    }
                }
                try {
                    return@withContext searchSubjectPage(category, query)
                } catch (e: Throwable) {
                    lastErr = e
                    android.util.Log.w("Douban", "search ${category.name}/$query attempt $attempt failed: ${e.message}")
                }
            }
            // 所有重试失败后，最后再重建一次 client（避免下次搜索沿用被污染的连接）
            recreateClient()
            throw lastErr ?: IOException("搜索失败")
        }

    /**
     * 从电影/图书 subject_search 页面解析 window.__DATA__ JSON。
     * 游戏走 www.douban.com/search?cat=3114 HTML 解析。
     * 登录态时 URL 自动加 ck 参数（豆瓣登录用户的搜索结果更全）。
     */
    private fun searchSubjectPage(category: Category, query: String): List<DoubanResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // 登录态时加 ck 参数（豆瓣搜索 API 要求 ck 跟在 URL 查询串里）
        val ckParam = runCatching {
            val ck = SettingsStore.doubanCk
            if (ck.isNotBlank()) "&ck=$ck" else ""
        }.getOrDefault("")
        val (url, referer) = when (category) {
            Category.MOVIE -> "https://movie.douban.com/subject_search?search_text=$encodedQuery&cat=1002$ckParam" to "https://movie.douban.com/"
            Category.TV -> "https://movie.douban.com/subject_search?search_text=$encodedQuery&cat=1002$ckParam" to "https://movie.douban.com/"
            Category.BOOK -> "https://book.douban.com/subject_search?search_text=$encodedQuery&cat=1001$ckParam" to "https://book.douban.com/"
            Category.GAME -> return searchGameWeb(query)
        }
        val html = httpGetMobile(url, referer)
        // 检测反爬/限流页面：豆瓣被触发时返回不含 window.__DATA__ 的页面
        if (!html.contains("window.__DATA__")) {
            detectBlockPageAndThrow(html)
            // 没命中已知反爬关键词但确实不是搜索结果页 → 抛异常触发重试
            evictConnections()
            throw IOException("返回页面非搜索结果（可能被反爬拦截），已自动重试")
        }
        // 提取 window.__DATA__ = { ... }; —— 用括号匹配提取完整 JSON（不能用非贪婪正则，嵌套{}会截断）
        val jsonStr = extractJsonObjectAfter(html, "window.__DATA__")
            ?: run {
                evictConnections()
                throw IOException("解析搜索数据失败，已自动重试")
            }
        val o = runCatching { json.parseToJsonElement(jsonStr).jsonObject }.getOrNull() ?: run {
            evictConnections()
            throw IOException("搜索数据 JSON 解析失败，已自动重试")
        }
        val items = o["items"]?.jsonArray ?: run {
            // 豆瓣反爬时可能返回带 window.__DATA__ 的 JSON，但里面没有 items 字段
            // 抛异常触发上层重试，不要静默返回空列表（否则用户以为没搜到，Toast 也不显示）
            android.util.Log.w("Douban", "searchSubjectPage: no 'items' key, keys=${o.keys.joinToString(",")}, html_len=${html.length}")
            evictConnections()
            throw IOException("搜索结果为空（可能被反爬，已重试）")
        }
        if (items.isEmpty()) {
            // items 是空数组：可能是真实无结果，也可能是反爬。打印日志便于诊断
            android.util.Log.w("Douban", "searchSubjectPage: items is empty, html_len=${html.length}, first 300=${html.take(300)}")
        }
        val wantMovie = category == Category.MOVIE
        val wantTv = category == Category.TV
        return items.mapNotNull { el ->
            val it = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = (it["id"]?.jsonPrimitive?.intOrNull ?: it["id"]?.jsonPrimitive?.contentOrNull)?.toString()
                ?: return@mapNotNull null
            if (!id.all { it.isDigit() }) return@mapNotNull null
            val title = it["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val urlStr = it["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // 电影/电视剧 同页，按 labels[0].text 或 url 里是否有 tv 区分
            val labels = it["labels"]?.jsonArray?.mapNotNull { l ->
                runCatching { l.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }.orEmpty()
            val isTvLabel = labels.any { it.contains("剧集") || it.contains("电视") }
            val isTvUrl = "/tv/" in urlStr
            val isTv = isTvLabel || isTvUrl
            // 过滤：电影页只返回电影，电视剧页只返回电视剧
            if (wantMovie && isTv) return@mapNotNull null
            if (wantTv && !isTv) return@mapNotNull null
            val rating = it["rating"]?.jsonObject?.get("value")?.jsonPrimitive?.floatOrNull
            val abstract = it["abstract"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val coverUrl = it["cover_url"]?.jsonPrimitive?.contentOrNull
            val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)
                ?: abstract.take(4).filter { it.isDigit() }
            // 从 abstract 提取地区/类型/年份作为 subTitle
            val subTitle = abstract.replace(Regex("""\s+"""), " ").trim()
            val cat = when {
                "/book/" in urlStr || category == Category.BOOK -> Category.BOOK
                isTv -> Category.TV
                else -> Category.MOVIE
            }
            DoubanResult(
                category = cat,
                doubanId = id,
                title = title.replace("($year)", "").trim(),
                subTitle = subTitle,
                year = year.orEmpty(),
                coverUrl = coverUrl,
                url = urlStr.ifBlank {
                    when (cat) {
                        Category.BOOK -> "https://book.douban.com/subject/$id/"
                        else -> "https://movie.douban.com/subject/$id/"
                    }
                },
                rating = rating
            )
        }
    }

    /** 游戏搜索：解析 www.douban.com/search?cat=3114 网页结果（无需登录） */
    private fun searchGameWeb(query: String): List<DoubanResult> {
        val url = "https://www.douban.com/search?cat=3114&q=${URLEncoder.encode(query, "UTF-8")}"
        val html = httpGetMobile(url, "https://www.douban.com/")
        // 检测反爬页面
        detectBlockPageAndThrow(html)
        val doc = Jsoup.parse(html, url)
        return doc.select("div.result").mapNotNull { result ->
            val link = result.selectFirst("a[title]") ?: return@mapNotNull null
            val href = link.attr("abs:href")
            // link2 跳转链接内部用 &amp; 分隔参数，要先还原
            val normalizedHref = href.replace("&amp;", "&")
            val decoded = runCatching {
                java.net.URLDecoder.decode(normalizedHref, "UTF-8")
            }.getOrDefault(normalizedHref)
            val onclick = runCatching { link.attr("onclick") }.getOrDefault("")
            val combined = "$decoded $onclick"
            val id = Regex("""game/(\d+)""").find(decoded)?.groupValues?.get(1)
                ?: Regex("""sid[:\s]+(\d+)""").find(combined)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val title = link.attr("title").trim().ifBlank { link.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            val rating = result.selectFirst("span.rating_nums")?.text()?.trim()?.toFloatOrNull()
            val info = result.selectFirst("span.subject-cast")?.text()?.trim().orEmpty()
            val intro = result.selectFirst("p")?.text()?.trim().orEmpty()
            val cover = result.selectFirst("img[src]")?.attr("abs:src")
            DoubanResult(
                category = Category.GAME,
                doubanId = id,
                title = title,
                subTitle = info,
                year = Regex("""(\d{4})""").findAll(info).lastOrNull()?.groupValues?.get(1).orEmpty(),
                coverUrl = cover,
                url = "https://www.douban.com/game/$id/",
                rating = rating,
                intro = intro
            )
        }
    }

    // ---------------- 人物搜索 ----------------

    /** 搜索影人：豆瓣网页搜索（cat=1065 人物），解析 personage 链接
     *  失败时抛 IOException，让 UI 提示用户网络异常。自带 3 次重试，最后一次彻底重建客户端。 */
    suspend fun searchCelebrities(query: String): List<DoubanCelebrity> =
        withContext(Dispatchers.IO) {
            var lastErr: Throwable? = null
            for (attempt in 0..3) {
                if (attempt > 0) {
                    if (attempt >= 3) {
                        recreateClient()
                        Thread.sleep(3000)
                    } else {
                        evictConnections()
                        Thread.sleep(1000L * attempt)
                    }
                }
                try {
                    return@withContext doSearchCelebrities(query)
                } catch (e: Throwable) {
                    lastErr = e
                    android.util.Log.w("Douban", "searchCelebrities $query attempt $attempt failed: ${e.message}")
                }
            }
            recreateClient()
            throw lastErr ?: IOException("搜索失败")
        }

    private fun doSearchCelebrities(query: String): List<DoubanCelebrity> {
        // 登录态时加 ck 参数（人物搜索对登录态更友好）
        val ckParam = runCatching {
            val ck = SettingsStore.doubanCk
            if (ck.isNotBlank()) "&ck=$ck" else ""
        }.getOrDefault("")
        val url = "https://www.douban.com/search?cat=1065&q=${URLEncoder.encode(query, "UTF-8")}$ckParam"
        val html = httpGetMobile(url, "https://www.douban.com/")
        detectBlockPageAndThrow(html)
        val doc = Jsoup.parse(html, url)
        return doc.select("div.result").mapNotNull { result ->
            val link = result.selectFirst("h3 a[href]") ?: return@mapNotNull null
            val rawHref = link.attr("abs:href")
            // 1) 先把整段 HTML (含 onclick) URL 解码，豆瓣的 link2 跳转链接内部还做了 HTML entity &amp;
            val combined = rawHref + " " + (runCatching {
                java.net.URLDecoder.decode(link.attr("onclick"), "UTF-8")
            }.getOrDefault("")) + " " + java.net.URLDecoder.decode(
                rawHref.replace("&amp;", "&"), "UTF-8"
            )
            val id = Regex("""(?:personage|celebrity)/(\d+)""").find(combined)?.groupValues?.get(1)
                ?: Regex("""sid[:\s]+(\d+)""").find(combined)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val name = link.text().trim()
            if (name.isBlank()) return@mapNotNull null
            val avatar = result.selectFirst("div.pic img[src]")?.attr("abs:src")
            // 副标题："作者 编剧 / 肠子 搏击俱乐部" 等
            val sub = result.select("div.content > p").map { it.text().trim() }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            DoubanCelebrity(
                id = id,
                name = name,
                latinName = "",
                role = sub,
                avatarUrl = avatar
            )
        }.distinctBy { it.id }
    }

    // ---------------- 条目详情 ----------------

    /** 抓取条目详情：优先 Rexxar API（含导演/演员/类型/预告片完整JSON），失败回退移动版页面 */
    suspend fun fetchDetail(category: Category, doubanId: String): DoubanDetail =
        withContext(Dispatchers.IO) {
            val rexxarResult = runCatching { fetchRexxar(category, doubanId) }
            if (rexxarResult.isFailure) {
                android.util.Log.e("Douban", "fetchRexxar failed for ${category.name}/$doubanId: ${rexxarResult.exceptionOrNull()?.message}")
            }
            val rexxar = rexxarResult.getOrNull()
            if (rexxar != null && !rexxar.isEmpty) {
                android.util.Log.d("Douban", "fetchRexxar ok: info='${rexxar.info?.take(80)}'")
                return@withContext rexxar
            }
            val mobileResult = runCatching { fetchMobile(category, doubanId) }
            if (mobileResult.isFailure) {
                android.util.Log.e("Douban", "fetchMobile failed for ${category.name}/$doubanId: ${mobileResult.exceptionOrNull()?.message}")
            }
            val mobile = mobileResult.getOrDefault(DoubanDetail())
            android.util.Log.d("Douban", "fetchMobile result: title=${mobile.title}, info='${mobile.info?.take(80)}'")
            mobile
        }

    /** Rexxar API 端点与对应 Referer */
    private fun rexxarUrl(category: Category, doubanId: String): Pair<String, String>? = when (category) {
        Category.MOVIE -> "https://m.douban.com/rexxar/api/v2/movie/$doubanId" to "https://m.douban.com/movie/subject/$doubanId/"
        Category.TV -> "https://m.douban.com/rexxar/api/v2/tv/$doubanId" to "https://m.douban.com/tv/subject/$doubanId/"
        Category.BOOK -> "https://m.douban.com/rexxar/api/v2/book/$doubanId" to "https://m.douban.com/book/subject/$doubanId/"
        Category.GAME -> "https://m.douban.com/rexxar/api/v2/game/$doubanId" to "https://m.douban.com/game/$doubanId/"
    }

    /** 请求 Rexxar API：移动 UA（客户端自带）+ Referer，无需 apikey；
     * 完全禁用缓存（force-network），因为 OkHttp 缓存可能保存过期响应导致发行日期/短评为空 */
    private fun httpGetRexxar(apiUrl: String, referer: String): String {
        val req = Request.Builder().url(apiUrl).get()
            .header("Referer", referer)
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .build()
        mobileClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    /** 解析 Rexxar JSON：directors/actors/genres/rating/cover_url/intro/card_subtitle/trailers */
    private fun fetchRexxar(category: Category, doubanId: String): DoubanDetail {
        val (apiUrl, referer) = rexxarUrl(category, doubanId) ?: throw IOException("no rexxar url")
        val body = httpGetRexxar(apiUrl, referer)
        val o = json.parseToJsonElement(body).jsonObject

        val title = o["title"]?.jsonPrimitive?.contentOrNull
        val rating = o["rating"]?.jsonObject?.get("value")?.jsonPrimitive?.floatOrNull
        val cover = o["cover_url"]?.jsonPrimitive?.contentOrNull
        val intro = o["intro"]?.jsonPrimitive?.contentOrNull
        val baseInfo = o["card_subtitle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        // 统一提取最完整的日期（pubdate / release_date 可能是数组或字符串）
        val pubdate: String? = extractFullDate(o)
        // 把日期替换进 card_subtitle：去掉年份/日期片段，完整日期放在最前，不加任何标签
        val info = mergeDateIntoInfo(baseInfo, pubdate)

        // [{"name":"xxx"}] 或 ["xxx"] 数组 → "xxx/yyy"
        fun names(key: String, limit: Int = 8): String? =
            o[key]?.jsonArray?.take(limit)
                ?.mapNotNull { el ->
                    // 先尝试对象格式 {"name":"xxx"}，再尝试纯字符串
                    runCatching { el.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                        ?: runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
                }
                ?.filter { it.isNotBlank() }
                ?.joinToString("/")?.takeIf { it.isNotBlank() }

        val directors = names("directors") ?: names("author") // 图书作者/音乐人回退到 author
        val casts = names("actors") ?: names("translators") // 图书译者回退
        // 游戏：developers / publishers 是字符串，platforms 是对象数组
        val gameDeveloper = (o["developers"]?.jsonPrimitive?.contentOrNull
            ?: names("developers"))
            ?.takeIf { it.isNotBlank() }
        val gamePlatforms = o["platforms"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
            ?.joinToString("/")?.takeIf { it.isNotBlank() }

        val genres = o["genres"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.joinToString("/")?.takeIf { it.isNotBlank() }

        val videos = parseVideos(o)

        return DoubanDetail(
            title, rating, cover, intro, info,
            directors = directors ?: gameDeveloper,
            casts = casts ?: gamePlatforms,
            genres = genres,
            videos = videos
        )
    }

    /**
     * 从详情 JSON 提取最完整的日期字符串。
     * 电影 pubdate 是数组（["2003-11-21(韩国)"]），游戏 release_date 是字符串（"2019-11-08"），
     * 电影 release_date 也可能是数组。优先取不带"电影节"标注的正式上映日期。
     */
    private fun extractFullDate(o: JsonObject): String? {
        val candidates = mutableListOf<String>()
        fun collect(key: String) {
            val el = o[key] ?: return
            runCatching {
                el.jsonArray.mapNotNull { it.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
            }.getOrNull()?.let { candidates.addAll(it) }
                ?: runCatching { el.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
                    .getOrNull()?.let { candidates.add(it) }
        }
        // release_date 对游戏是主字段；pubdate 对影视/图书是主字段
        collect("release_date")
        collect("pubdate")
        collect("date")
        collect("publish_date")
        if (candidates.isEmpty()) return null
        // 优先正式上映（不带"电影节"标注），其次取第一条；保留最前面的年月日
        return candidates.firstOrNull { !it.contains("电影节") } ?: candidates.first()
    }

    /**
     * 把完整日期并入 card_subtitle：
     * 去掉其中的纯年份段/日期段，完整日期放在最前，各段用 " / " 连接，不加任何标签文字。
     * 例："2016 / 韩国 / 剧情..." + "2016-05-14(戛纳电影节)" → "2016-05-14(戛纳电影节) / 韩国 / 剧情..."
     */
    private fun mergeDateIntoInfo(baseInfo: String?, fullDate: String?): String? {
        if (baseInfo.isNullOrBlank()) return fullDate
        if (fullDate.isNullOrBlank()) return baseInfo
        val datePart = fullDate.substringBefore("(").substringBefore("（").trim()
        val segments = baseInfo.split("/").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        // 移除：纯年份段（如 2016）、完整日期段、已含目标日期的段
        val yearOnly = Regex("""^\d{4}$""")
        val fullDateRe = Regex("""^\d{4}[-/年.]\d{1,2}(?:[-/月.]\d{1,2})?日?(?:[（(].*)?$""")
        val removed = segments.removeAll { seg ->
            yearOnly.matches(seg) || fullDateRe.matches(seg) ||
                (datePart.length >= 7 && seg.contains(datePart.take(7)))
        }
        // 没有可替换的年份段且原文已包含该日期 → 不重复添加；前缀用去掉"(电影节/地区)"后缀的干净日期
        val alreadyHas = baseInfo.contains(datePart.take(7))
        val finalSegs = if (removed || !alreadyHas) listOf(datePart) + segments else segments
        // 去重
        return finalSegs.distinct().joinToString(" / ")
    }

    /** 详情 JSON 的 trailers 数组 → 预告片列表（含 mp4 直链/封面/时长） */
    private fun parseVideos(o: JsonObject): List<DoubanVideo> = runCatching {
        o["trailers"]?.jsonArray?.mapNotNull { el ->
            val t = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val videoUrl = t["video_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            DoubanVideo(
                id = t["id"]?.jsonPrimitive?.contentOrNull ?: videoUrl,
                title = t["title"]?.jsonPrimitive?.contentOrNull ?: "预告片",
                typeName = t["type_name"]?.jsonPrimitive?.contentOrNull ?: "预告片",
                coverUrl = t["cover_url"]?.jsonPrimitive?.contentOrNull,
                videoUrl = videoUrl,
                runtime = t["runtime"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** 单独拉预告片（详情页实时展示用，不落库） */
    suspend fun fetchTrailers(category: Category, doubanId: String): List<DoubanVideo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (apiUrl, referer) = rexxarUrl(category, doubanId) ?: return@runCatching emptyList()
                parseVideos(json.parseToJsonElement(httpGetRexxar(apiUrl, referer)).jsonObject)
            }.getOrDefault(emptyList())
        }

    // ---------------- 演职员 / 剧照 / 短评 ----------------

    /**
     * 演职员/作者（影视条目有 celebrities 端点，图书/游戏从详情 API 构造再搜索富化）。
     * fallbackNames：本地已保存的作者/开发商名（rexxar 详情拉取失败时兜底，保证卡片仍能显示头像）。
     */
    suspend fun fetchCelebrities(
        category: Category,
        doubanId: String,
        fallbackNames: List<String> = emptyList()
    ): List<DoubanCelebrity> =
        withContext(Dispatchers.IO) {
            // 游戏不再抓取开发商（用户要求移除）
            if (category == Category.GAME) return@withContext emptyList()
            // 影视条目：用 celebrities 端点
            if (category == Category.MOVIE || category == Category.TV) {
                runCatching {
                    val (apiUrl, referer) = rexxarUrl(category, doubanId) ?: return@runCatching emptyList()
                    val o = json.parseToJsonElement(
                        httpGetRexxar("$apiUrl/celebrities?start=0&count=100", referer)
                    ).jsonObject
                    buildList {
                        fun take(key: String, roleLabel: String) {
                            o[key]?.jsonArray?.forEach { el ->
                                val c = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
                                val id = c["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                                val name = c["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                                val character = c["character"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                val role = character.ifBlank {
                                    c["roles"]?.jsonArray
                                        ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                                        ?.joinToString("/").orEmpty()
                                }.ifBlank { roleLabel }
                                val avatar = c["avatar"]?.jsonObject
                                val avatarUrl = (avatar?.get("normal") ?: avatar?.get("large"))
                                    ?.jsonPrimitive?.contentOrNull
                                add(DoubanCelebrity(id, name, c["latin_name"]?.jsonPrimitive?.contentOrNull.orEmpty(), role, avatarUrl))
                            }
                        }
                        take("directors", "导演")
                        take("actors", "演员")
                    }
                }.getOrDefault(emptyList())
            } else if (category == Category.BOOK) {
                // 图书：两步走 — 先用详情 API 的 author/translator 数组返回基本卡片（保证一定显示），
                // 再后台并发搜索真实影人替换为可点击+有头像的版本；详情接口失败时用本地保存的名字兜底。
                try {
                    // 优先从 rexxar 详情拿 author/translator 数组；失败时用本地保存的名字兜底
                    val parsed: Pair<List<String>, List<String>>? = runCatching {
                        val (apiUrl, referer) = rexxarUrl(category, doubanId)
                            ?: return@runCatching null
                        val o = json.parseToJsonElement(httpGetRexxar(apiUrl, referer)).jsonObject
                        val authors = o["author"]?.jsonArray
                            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                            ?.map { cleanPersonName(it) }
                            ?.filter { it.isNotBlank() }
                            .orEmpty()
                        val trans = o["translators"]?.jsonArray
                            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                            ?.map { cleanPersonName(it) }
                            ?.filter { it.isNotBlank() }
                            .orEmpty()
                        if (authors.isNotEmpty() || trans.isNotEmpty()) authors to trans else null
                    }.getOrNull()
                    val authorNames = parsed?.first ?: fallbackNames.filter { it.isNotBlank() }
                    val transNames = parsed?.second ?: emptyList()
                    // Step 1: 先生成基础卡片（保证有内容展示，就算后续匹配都失败也能显示）
                    val baseList = buildList {
                        authorNames.forEachIndexed { idx, n ->
                            add(DoubanCelebrity(id = "${doubanId}_a$idx", name = n, role = "作者"))
                        }
                        transNames.forEachIndexed { idx, n ->
                            add(DoubanCelebrity(id = "${doubanId}_t$idx", name = n, role = "译者"))
                        }
                    }
                    if (baseList.isEmpty()) return@withContext emptyList()
                    // Step 2: 搜索每个真实影人，搜索成功替换头像和ID，失败保留基础卡片
                    coroutineScope {
                        baseList.map { base ->
                            async { runCatching { enrichBookPersonWithSearch(base) }.getOrDefault(base) }
                        }.awaitAll()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            } else if (category == Category.GAME) {
                // 游戏：详情 JSON 的 developers 数组 → 基础卡片 → personage 搜索富化（同图书作者逻辑）。
                // 豆瓣对游戏公司（如卡普空）也有 personage 人物页（带头像），可点击进详情。
                try {
                    val devNames: List<String> = runCatching {
                        val (apiUrl, referer) = rexxarUrl(category, doubanId)
                            ?: return@runCatching emptyList()
                        val o = json.parseToJsonElement(httpGetRexxar(apiUrl, referer)).jsonObject
                        // developers 兼容对象数组 [{"name":"x"}] 和字符串数组 ["x"]
                        o["developers"]?.jsonArray
                            ?.mapNotNull { el ->
                                runCatching { el.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                                    ?: runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
                            }
                            ?.map { cleanPersonName(it) }
                            ?.filter { it.isNotBlank() }
                            ?.distinct()
                            .orEmpty()
                    }.getOrDefault(emptyList()).ifEmpty {
                        fallbackNames.filter { it.isNotBlank() }
                    }
                    if (devNames.isEmpty()) return@withContext emptyList()
                    val baseList = devNames.mapIndexed { idx, n ->
                        DoubanCelebrity(id = "${doubanId}_d$idx", name = n, role = "开发商")
                    }
                    coroutineScope {
                        baseList.map { base ->
                            async { runCatching { enrichBookPersonWithSearch(base) }.getOrDefault(base) }
                        }.awaitAll()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }

    private val countryChars = "中英美法德日俄韩意西印加澳荷瑞挪丹芬巴阿波捷匈土伊以爱蘭兰港台以加"

    /** 清理作者名前缀，如 "（美）恰克·帕拉尼克" → "恰克·帕拉尼克"；仅去除带括号/空格的国籍前缀，避免误伤名字首字 */
    private fun cleanPersonName(name: String): String {
        var s = name.trim()
        // 带括号/方括号的国籍前缀：（美） [日] (法) 等
        s = Regex("""^[【\[\(（]\s*[$countryChars]{1,3}\s*[】\]\)）]""").replace(s, "")
        // 无括号但形如 "美 作者名" / "美·作者名"
        s = Regex("""^[$countryChars](?=[\s·.])""").replace(s, "")
        // 去掉结尾的 著/编/译 等
        s = s.removeSuffix("著").removeSuffix(" 著").trim()
        return s.trim()
    }

    /** 归一化人名：统一间隔符、去空格，便于模糊匹配 */
    private fun normPersonName(s: String): String =
        s.replace(Regex("[·・・•.．・\\s\\[\\]【】（）()【】]"), "")
            .replace("　", "")
            .lowercase()

    /** 尝试搜索影人页面，替换基础卡片的 id/头像/角色（搜索失败原样返回） */
    private suspend fun enrichBookPersonWithSearch(base: DoubanCelebrity): DoubanCelebrity {
        if (base.name.isBlank()) return base
        val results = try {
            searchCelebrities(base.name)
        } catch (_: Exception) {
            return base
        }
        val target = normPersonName(base.name)
        val matched = results.firstOrNull { normPersonName(it.name) == target }
            ?: results.firstOrNull {
                val n = normPersonName(it.name)
                n.isNotBlank() && (n.contains(target) || target.contains(n))
            }
        return if (matched != null) {
            DoubanCelebrity(
                id = matched.id,
                name = matched.name.ifBlank { base.name },
                latinName = matched.latinName,
                role = "${base.role} · ${matched.role}".trim(' ', '·'),
                avatarUrl = largeImageUrl(matched.avatarUrl) ?: matched.avatarUrl
            )
        } else {
            base
        }
    }

    /** 剧照（影视条目走 Rexxar photos；游戏条目解析网页端截图页），失败返回空列表 */
    suspend fun fetchPhotos(category: Category, doubanId: String): List<DoubanPhoto> =
        withContext(Dispatchers.IO) {
            when (category) {
                Category.MOVIE, Category.TV -> fetchMoviePhotos(doubanId)
                Category.GAME -> fetchGamePhotos(doubanId)
                else -> emptyList()
            }
        }

    private fun fetchMoviePhotos(doubanId: String): List<DoubanPhoto> = runCatching {
        val o = json.parseToJsonElement(
            httpGetRexxar(
                "https://m.douban.com/rexxar/api/v2/movie/$doubanId/photos?start=0&count=100",
                "https://m.douban.com/movie/subject/$doubanId/"
            )
        ).jsonObject
        o["photos"]?.jsonArray?.mapNotNull { el ->
            val p = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = p["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val img = p["image"]?.jsonObject
            val large = img?.obj("large")?.get("url")?.jsonPrimitive?.contentOrNull
            val normal = img?.obj("normal")?.get("url")?.jsonPrimitive?.contentOrNull
            DoubanPhoto(id, large ?: normal, normal ?: large)
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** 游戏截图：解析 www.douban.com/game/{id}/photos 页（缩略 albumicon → 展示 sqxs / 大图 raw） */
    private fun fetchGamePhotos(doubanId: String): List<DoubanPhoto> = runCatching {
        val url = "https://www.douban.com/game/$doubanId/photos"
        val html = httpGetMobile(url, "https://www.douban.com/game/$doubanId/")
        val ids = Regex("""view/photo/(?:albumicon|thumb)/public/(p\d+)\.(?:jpg|png|webp)""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        ids.map { pid ->
            DoubanPhoto(
                id = pid,
                largeUrl = "https://img9.doubanio.com/view/photo/raw/public/$pid.jpg",
                normalUrl = "https://img1.doubanio.com/view/photo/sqxs/public/$pid.jpg"
            )
        }
    }.getOrDefault(emptyList())

    /** 网友短评（热门在前，取有文字的）；多端点兜底，游戏/影视都能拿到 */
    suspend fun fetchInterests(category: Category, doubanId: String): List<DoubanInterest> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (apiUrl, referer) = rexxarUrl(category, doubanId) ?: return@runCatching emptyList()
                // 依次尝试：不带 status（全量）→ done（看过）→ collect（玩过），任一拿到评论即返回
                val urls = listOf(
                    "$apiUrl/interests?start=0&count=12",
                    "$apiUrl/interests?start=0&count=12&status=done",
                    "$apiUrl/interests?start=0&count=12&status=collect"
                )
                for (url in urls) {
                    val parsed = runCatching {
                        val o = json.parseToJsonElement(httpGetRexxar(url, referer)).jsonObject
                        o["interests"]?.jsonArray?.mapNotNull { el ->
                            val i = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                            val comment = i["comment"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (comment.isBlank()) return@mapNotNull null
                            val u = i["user"]?.jsonObject
                            DoubanInterest(
                                userName = u?.get("name")?.jsonPrimitive?.contentOrNull ?: "匿名用户",
                                avatarUrl = u?.get("avatar")?.jsonPrimitive?.contentOrNull,
                                rating = i["rating"]?.jsonObject?.get("value")?.jsonPrimitive?.floatOrNull,
                                comment = comment,
                                date = i["create_time"]?.jsonPrimitive?.contentOrNull?.take(10).orEmpty(),
                                location = u?.obj("loc")?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                                votes = i["vote_count"]?.jsonPrimitive?.intOrNull ?: 0
                            )
                        }.orEmpty()
                    }.getOrDefault(emptyList())
                    if (parsed.isNotEmpty()) return@runCatching parsed
                }
                emptyList()
            }.getOrDefault(emptyList())
        }

    // ---------------- 影人详情 ----------------

    /** personage ID → 旧版 celebrity ID 的映射缓存（搜索结果为 personage ID） */
    private val celebrityIdCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 影人 ID 归一化：personage ID 自动解析为 celebrity ID。
     * 解析端点 rexxar/api/v2/personage/{id} 返回 celebrity_id 字段。
     */
    private suspend fun resolveCelebrityId(id: String): String = withContext(Dispatchers.IO) {
        celebrityIdCache[id] ?: run {
            val mapped = runCatching {
                val o = json.parseToJsonElement(
                    httpGetRexxar(
                        "https://m.douban.com/rexxar/api/v2/personage/$id",
                        "https://m.douban.com/personage/$id/"
                    )
                ).jsonObject
                o["celebrity_id"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (mapped.isNullOrBlank()) id else {
                celebrityIdCache[id] = mapped
                mapped
            }
        }
    }

    /** 影人详情：rexxar/api/v2/celebrity/{id} + works（personage ID 自动映射） */
    suspend fun fetchCelebrityDetail(celebrityId: String): DoubanCelebrityDetail =
        withContext(Dispatchers.IO) {
            val cid = resolveCelebrityId(celebrityId)
            val baseUrl = "https://m.douban.com/rexxar/api/v2/celebrity/$cid"
            val referer = "https://m.douban.com/celebrity/$cid/"
            val base = runCatching {
                val o = json.parseToJsonElement(httpGetRexxar(baseUrl, referer)).jsonObject
                val extra = o["extra"]?.jsonObject
                val infoPairs = extra?.get("info")?.jsonArray
                    ?.mapNotNull { row ->
                        val arr = runCatching { row.jsonArray }.getOrNull() ?: return@mapNotNull null
                        if (arr.size >= 2) {
                            val k = runCatching { arr[0].jsonPrimitive.contentOrNull }.getOrNull().orEmpty()
                            val v = runCatching { arr[1].jsonPrimitive.contentOrNull }.getOrNull().orEmpty()
                            if (k.isNotBlank()) k to v else null
                        } else null
                    }.orEmpty()
                DoubanCelebrityDetail(
                    name = o["title"]?.jsonPrimitive?.contentOrNull ?: "影人",
                    latinName = o["latin_title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    avatarUrl = o["cover_img"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,
                    shortInfo = extra?.get("short_info")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    infoPairs = infoPairs,
                    url = o["url"]?.jsonPrimitive?.contentOrNull
                        ?: "https://movie.douban.com/celebrity/$cid/"
                )
            }.getOrElse {
                DoubanCelebrityDetail(
                    name = "影人",
                    url = "https://movie.douban.com/celebrity/$cid/"
                )
            }
            // 追加相关作品（默认按评分排序取前 10 条）
            val works = runCatching { fetchCelebrityWorks(celebrityId, "rating", 0, 10) }.getOrDefault(emptyList())
            base.copy(works = works)
        }

    /** 影人相关作品列表：rexxar/api/v2/celebrity/{id}/works，支持按评分或年份排序。公开 API */
    suspend fun fetchCelebrityWorks(
        celebrityId: String,
        sortBy: String = "rating",   // rating / year
        start: Int = 0,
        count: Int = 20
    ): List<CelebrityWork> = withContext(Dispatchers.IO) {
        doFetchCelebrityWorks(resolveCelebrityId(celebrityId), sortBy, start, count)
    }

    private fun doFetchCelebrityWorks(
        celebrityId: String,
        sortBy: String,
        start: Int,
        count: Int
    ): List<CelebrityWork> = runCatching {
        val sortParam = if (sortBy == "year") "year" else "rating"
        val url = "https://m.douban.com/rexxar/api/v2/celebrity/$celebrityId/works?start=$start&count=$count&sort_by=$sortParam"
        val referer = "https://m.douban.com/celebrity/$celebrityId/"
        val o = json.parseToJsonElement(httpGetRexxar(url, referer)).jsonObject
        o["works"]?.jsonArray?.mapNotNull { el ->
            val it = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val roles = it["roles"]?.jsonArray
                ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.joinToString(", ").orEmpty()
            val w = runCatching { it["work"]?.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = w["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val type = w["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // 影人作品里的电影/电视剧条目 id 直接对应，图书等跳到详情统一处理
            CelebrityWork(
                id = id,
                title = w["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                year = w["year"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                type = type,
                roles = roles,
                rating = w["rating"]?.jsonObject?.get("value")?.jsonPrimitive?.floatOrNull,
                coverUrl = w["cover_url"]?.jsonPrimitive?.contentOrNull
            )
        }.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * 图书作者的图书作品：豆瓣影人 works 接口只含影视（不含书），
     * 改用图书搜索按作者名检索（实测"恰克·帕拉尼克"能返回其全部著作）。
     */
    suspend fun fetchAuthorBooks(authorName: String): List<CelebrityWork> =
        withContext(Dispatchers.IO) {
            if (authorName.isBlank()) return@withContext emptyList()
            runCatching {
                searchSubjectPage(Category.BOOK, authorName).map { r ->
                    CelebrityWork(
                        id = r.doubanId,
                        title = r.title,
                        year = r.year,
                        type = "book",
                        roles = "作者",
                        rating = r.rating,
                        coverUrl = r.coverUrl
                    )
                }
            }.getOrDefault(emptyList())
        }

    /**
     * 游戏开发者（人名/公司名）的游戏作品：豆瓣没有"按作者查游戏"的独立接口，
     * 走 www.douban.com/search?cat=3114 关键词搜索做兜底，结果为标题匹配的相关游戏。
     * 部分公司名作开发者（如卡普空、任天堂、小岛秀夫）能返回其开发的系列作品。
     */
    suspend fun fetchDeveloperGames(devName: String): List<CelebrityWork> =
        withContext(Dispatchers.IO) {
            if (devName.isBlank()) return@withContext emptyList()
            runCatching {
                searchGameWeb(devName).map { r ->
                    CelebrityWork(
                        id = r.doubanId,
                        title = r.title,
                        year = r.year,
                        type = "game",
                        roles = "开发商",
                        rating = r.rating,
                        coverUrl = r.coverUrl
                    )
                }
            }.getOrDefault(emptyList())
        }

    /** 影人相关照片：rexxar celebrity/{id}/photos（personage ID 自动映射，仅返回有图的结果） */
    suspend fun fetchCelebrityPhotos(celebrityId: String): List<DoubanPhoto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cid = resolveCelebrityId(celebrityId)
                val o = json.parseToJsonElement(
                    httpGetRexxar(
                        "https://m.douban.com/rexxar/api/v2/celebrity/$cid/photos?start=0&count=100",
                        "https://m.douban.com/celebrity/$cid/"
                    )
                ).jsonObject
                o["photos"]?.jsonArray?.mapNotNull { el ->
                    val p = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val img = p["image"]?.jsonObject ?: return@mapNotNull null
                    val large = img.obj("large")?.get("url")?.jsonPrimitive?.contentOrNull
                    val normal = img.obj("normal")?.get("url")?.jsonPrimitive?.contentOrNull
                    val url = large ?: normal ?: return@mapNotNull null
                    DoubanPhoto(
                        id = p["id"]?.jsonPrimitive?.contentOrNull ?: url,
                        largeUrl = large,
                        normalUrl = normal ?: large
                    )
                }.orEmpty()
            }.getOrDefault(emptyList())
        }

    // ---------------- 旧版页面解析兜底 ----------------

    private fun fetchMobile(category: Category, doubanId: String): DoubanDetail {
        val url = mobileUrl(category, doubanId) ?: throw IOException("no mobile url")
        val html = httpGetMobile(url, referer = url)
        val doc = Jsoup.parse(html, url)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
        val cover = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
        val rating = doc.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.trim()
            ?.toFloatOrNull()
        val desc = doc.selectFirst("meta[itemprop=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
        val summary = desc?.substringAfter("简介：")?.trim()?.takeIf { it.isNotBlank() } ?: desc
        val baseInfo = doc.selectFirst("div.sub-meta")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("#link-report")?.selectFirst("span[property=summary]")?.text()?.trim()
            ?: doc.body()?.text()?.substring(0, 500)?.trim()

        // 从网页提取发行/出版日期：优先 datePublished meta，其次正则
        val metaDate = doc.selectFirst("meta[itemprop=datePublished]")?.attr("content")
            ?: doc.selectFirst("meta[property=article:published_time]")?.attr("content")
        val dateRe = Regex("""(\d{4}[-/年.]\d{1,2}[-/月.]\d{1,2})""")
        val regexDate = baseInfo?.let { dateRe.find(it)?.groupValues?.getOrNull(1)?.trim() }
        val pubdate = metaDate ?: regexDate
        val info = mergeDateIntoInfo(baseInfo, pubdate)

        // 从简介文本提取演员：匹配 "角色名（演员中文名 英文名 饰）" 模式
        val casts = extractCastsFromSummary(summary.orEmpty())

        return DoubanDetail(title, rating, cover, summary, info, casts = casts)
    }

    /** 从剧情简介里正则提取演员名字
     *  匹配模式：角色名（演员中文名 英文名 饰）或 角色名（演员中文名 饰）
     */
    private fun extractCastsFromSummary(summary: String): String? {
        val pattern = Regex("""[^\s（）""]+（([^\s（）]+)\s*(?:[^）]*?)饰[）\)]""")
        val names = mutableListOf<String>()
        pattern.findAll(summary).forEach { m ->
            m.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && it !in names }?.let { names.add(it) }
        }
        return if (names.isNotEmpty()) names.take(6).joinToString("/") else null
    }

    private fun mobileUrl(category: Category, doubanId: String): String? = when (category) {
        Category.MOVIE, Category.TV -> "https://m.douban.com/movie/subject/$doubanId/"
        Category.BOOK -> "https://m.douban.com/book/subject/$doubanId/"
        Category.GAME -> "https://www.douban.com/game/$doubanId/"
    }

    private fun JsonElement?.obj(key: String): JsonObject? =
        runCatching { this?.jsonObject?.get(key)?.jsonObject }.getOrNull()

    /** 解析豆瓣链接 → (分类, 条目ID) */
    fun parseDoubanUrl(url: String): Pair<Category, String>? {
        val patterns = listOf(
            Regex("movie\\.douban\\.com/subject/(\\d+)") to Category.MOVIE,
            Regex("book\\.douban\\.com/subject/(\\d+)") to Category.BOOK,
            Regex("douban\\.com/game/(\\d+)") to Category.GAME,
            Regex("douban\\.com/subject/(\\d+)") to Category.MOVIE
        )
        for ((re, cat) in patterns) {
            re.find(url)?.let { return cat to it.groupValues[1] }
        }
        return null
    }
}
