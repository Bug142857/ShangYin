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
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 豆瓣网页接口客户端（非官方）。
 * 详情数据优先走 Rexxar API（豆瓣App内部接口，JSON 含导演/演员/类型/评分/简介/封面/预告片），
 * 失败则回退移动版条目页解析 meta 标签。
 */
object DoubanClient {

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

    /** 随机 bid cookie，移动版条目页需要 */
    private val bid: String = buildString {
        val cs = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        repeat(11) { append(cs.random()) }
    }

    private val mobileClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                // 动态添加 Cookie（含登录态 cookie 时可搜索游戏等）
                val cookie = runCatching { SettingsStore.doubanCookie }.getOrDefault("")
                if (cookie.isNotBlank()) {
                    builder.header("Cookie", "$cookie; bid=$bid")
                } else {
                    builder.header("Cookie", "bid=$bid")
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    private fun httpGetMobile(url: String, referer: String? = null): String {
        val builder = Request.Builder().url(url).get()
        if (referer != null) builder.header("Referer", referer)
        mobileClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    // ---------------- 搜索 ----------------

    /** 按类型搜索豆瓣：走分类 subject_search 页面（解析 window.__DATA__ JSON） */
    suspend fun search(category: Category, query: String): List<DoubanResult> =
        withContext(Dispatchers.IO) {
            runCatching { searchSubjectPage(category, query) }.getOrDefault(emptyList())
        }

    /**
     * 从电影/图书/音乐 subject_search 页面解析 window.__DATA__ JSON。
     * 游戏没有 subject_search 页面，暂时走 www.douban.com/j/search_suggest 关键词建议。
     */
    private fun searchSubjectPage(category: Category, query: String): List<DoubanResult> {
        val (url, referer) = when (category) {
            Category.MOVIE -> "https://movie.douban.com/subject_search?search_text=${URLEncoder.encode(query, "UTF-8")}&cat=1002" to "https://movie.douban.com/"
            Category.TV -> "https://movie.douban.com/subject_search?search_text=${URLEncoder.encode(query, "UTF-8")}&cat=1002" to "https://movie.douban.com/"
            Category.BOOK -> "https://book.douban.com/subject_search?search_text=${URLEncoder.encode(query, "UTF-8")}&cat=1001" to "https://book.douban.com/"
            Category.MUSIC -> "https://music.douban.com/subject_search?search_text=${URLEncoder.encode(query, "UTF-8")}" to "https://music.douban.com/"
            Category.GAME -> return searchGameWeb(query)
        }
        val html = httpGetMobile(url, referer)
        // 提取 window.__DATA__ = { ... };
        val match = Regex("""window\.__DATA__\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return emptyList()
        val jsonStr = match.groupValues[1]
        val o = runCatching { json.parseToJsonElement(jsonStr).jsonObject }.getOrNull() ?: return emptyList()
        val items = o["items"]?.jsonArray ?: return emptyList()
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
                "/music/" in urlStr || category == Category.MUSIC -> Category.MUSIC
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
                        Category.MUSIC -> "https://music.douban.com/subject/$id/"
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

    /** 搜索影人：豆瓣网页搜索（cat=1065 人物），解析 personage 链接 */
    suspend fun searchCelebrities(query: String): List<DoubanCelebrity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://www.douban.com/search?cat=1065&q=${URLEncoder.encode(query, "UTF-8")}"
                val html = httpGetMobile(url, "https://www.douban.com/")
                val doc = Jsoup.parse(html, url)
                doc.select("div.result").mapNotNull { result ->
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
            }.getOrDefault(emptyList())
        }

    // ---------------- 条目详情 ----------------

    /** 抓取条目详情：优先 Rexxar API（含导演/演员/类型/预告片完整JSON），失败回退移动版页面 */
    suspend fun fetchDetail(category: Category, doubanId: String): DoubanDetail =
        withContext(Dispatchers.IO) {
            val rexxar = runCatching { fetchRexxar(category, doubanId) }.getOrNull()
            if (rexxar != null && !rexxar.isEmpty) return@withContext rexxar
            runCatching { fetchMobile(category, doubanId) }.getOrDefault(DoubanDetail())
        }

    /** Rexxar API 端点与对应 Referer */
    private fun rexxarUrl(category: Category, doubanId: String): Pair<String, String>? = when (category) {
        Category.MOVIE -> "https://m.douban.com/rexxar/api/v2/movie/$doubanId" to "https://m.douban.com/movie/subject/$doubanId/"
        Category.TV -> "https://m.douban.com/rexxar/api/v2/tv/$doubanId" to "https://m.douban.com/tv/subject/$doubanId/"
        Category.BOOK -> "https://m.douban.com/rexxar/api/v2/book/$doubanId" to "https://m.douban.com/book/subject/$doubanId/"
        Category.MUSIC -> "https://m.douban.com/rexxar/api/v2/music/$doubanId" to "https://m.douban.com/music/subject/$doubanId/"
        Category.GAME -> "https://m.douban.com/rexxar/api/v2/game/$doubanId" to "https://m.douban.com/game/$doubanId/"
    }

    /** 请求 Rexxar API：移动 UA（客户端自带）+ Referer，无需 apikey */
    private fun httpGetRexxar(apiUrl: String, referer: String): String {
        val req = Request.Builder().url(apiUrl).get()
            .header("Referer", referer)
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
        val info = o["card_subtitle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

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
            // 影视条目：用 celebrities 端点
            if (category == Category.MOVIE || category == Category.TV) {
                runCatching {
                    val (apiUrl, referer) = rexxarUrl(category, doubanId) ?: return@runCatching emptyList()
                    val o = json.parseToJsonElement(
                        httpGetRexxar("$apiUrl/celebrities?start=0&count=20", referer)
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
                "https://m.douban.com/rexxar/api/v2/movie/$doubanId/photos?start=0&count=30",
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

    /** 网友短评（热门在前，取有文字的） */
    suspend fun fetchInterests(category: Category, doubanId: String): List<DoubanInterest> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (apiUrl, referer) = rexxarUrl(category, doubanId) ?: return@runCatching emptyList()
                val o = json.parseToJsonElement(
                    httpGetRexxar("$apiUrl/interests?start=0&count=12&status=done", referer)
                ).jsonObject
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
                        "https://m.douban.com/rexxar/api/v2/celebrity/$cid/photos?start=0&count=30",
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
        val info = doc.selectFirst("div.sub-meta")?.text()?.trim()?.takeIf { it.isNotBlank() }

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
        Category.MUSIC -> "https://m.douban.com/music/subject/$doubanId/"
        Category.GAME -> null
    }

    private fun JsonElement?.obj(key: String): JsonObject? =
        runCatching { this?.jsonObject?.get(key)?.jsonObject }.getOrNull()

    /** 解析豆瓣链接 → (分类, 条目ID) */
    fun parseDoubanUrl(url: String): Pair<Category, String>? {
        val patterns = listOf(
            Regex("movie\\.douban\\.com/subject/(\\d+)") to Category.MOVIE,
            Regex("book\\.douban\\.com/subject/(\\d+)") to Category.BOOK,
            Regex("music\\.douban\\.com/subject/(\\d+)") to Category.MUSIC,
            Regex("douban\\.com/game/(\\d+)") to Category.GAME,
            Regex("douban\\.com/subject/(\\d+)") to Category.MOVIE
        )
        for ((re, cat) in patterns) {
            re.find(url)?.let { return cat to it.groupValues[1] }
        }
        return null
    }
}
