package com.shangyin.app.data.douban

import com.shangyin.app.data.Category
import kotlinx.coroutines.Dispatchers
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
                val req = chain.request().newBuilder()
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .header("Cookie", "bid=$bid")
                    .build()
                chain.proceed(req)
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

    /** 按类型搜索豆瓣：统一走 Rexxar search/subjects（覆盖影视/游戏/图书/音乐），subject_suggest 兜底 */
    suspend fun search(category: Category, query: String): List<DoubanResult> =
        withContext(Dispatchers.IO) {
            val rexxar = runCatching { searchSubjectsRexxar(query).filter { it.category == category } }
                .getOrDefault(emptyList())
            val seen = rexxar.map { it.doubanId }.toSet()
            val fallback = runCatching {
                val endpoint = when (category) {
                    Category.MOVIE, Category.TV -> "https://movie.douban.com/j/subject_suggest"
                    Category.BOOK -> "https://book.douban.com/j/subject_suggest"
                    Category.MUSIC -> "https://music.douban.com/j/subject_suggest"
                    Category.GAME -> return@runCatching emptyList() // game/suggest 不存在
                }
                searchSubjectSuggest(endpoint, query, category)
            }.getOrDefault(emptyList()).filter { it.category == category && it.doubanId !in seen }
            rexxar + fallback
        }

    /** 全量搜索：Rexxar search/subjects 端点，按 uri 识别 game/book/music/tv/movie */
    private fun searchSubjectsRexxar(query: String): List<DoubanResult> {
        val url = "https://m.douban.com/rexxar/api/v2/search/subjects?q=${URLEncoder.encode(query, "UTF-8")}&count=30"
        val body = httpGetRexxar(url, referer = "https://m.douban.com/search/")
        val items = json.parseToJsonElement(body).jsonObject["subjects"]
            ?.jsonObject?.get("items")?.jsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val t = runCatching { o["target"]?.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = t["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            // 过滤榜单/合集（id 形如 ECL4473JA 非纯数字）
            if (!id.all { it.isDigit() }) return@mapNotNull null
            val title = t["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val uri = t["uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val cat = when {
                "/game/" in uri -> Category.GAME
                "/book/" in uri -> Category.BOOK
                "/music/" in uri || "/album/" in uri -> Category.MUSIC
                "/tv/" in uri -> Category.TV
                else -> Category.MOVIE
            }
            DoubanResult(
                cat, id, title,
                subTitle = t["card_subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                year = (t["year"]?.jsonPrimitive?.contentOrNull
                    ?: t["card_subtitle"]?.jsonPrimitive?.contentOrNull?.take(4)?.filter { it.isDigit() })
                    .orEmpty(),
                coverUrl = t["cover_url"]?.jsonPrimitive?.contentOrNull?.substringBefore('?'),
                url = when (cat) {
                    Category.BOOK -> "https://book.douban.com/subject/$id/"
                    Category.MUSIC -> "https://music.douban.com/subject/$id/"
                    Category.GAME -> "https://www.douban.com/game/$id/"
                    else -> "https://movie.douban.com/subject/$id/"
                },
                rating = t["rating"]?.jsonObject?.get("value")?.jsonPrimitive?.floatOrNull
            )
        }
    }

    /** subject_suggest 兜底：type 映射 movie/tv/b/m，未知类型归到 fallback */
    private fun searchSubjectSuggest(endpoint: String, query: String, fallback: Category): List<DoubanResult> {
        val url = "$endpoint?q=${URLEncoder.encode(query, "UTF-8")}"
        val body = httpGetMobile(url, referer = endpoint.substringBeforeLast('/'))
        val arr = json.parseToJsonElement(body).jsonArray
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val type = o["type"]?.jsonPrimitive?.contentOrNull ?: ""
            val cat = when (type) {
                "movie" -> Category.MOVIE
                "tv" -> Category.TV
                "b" -> Category.BOOK
                "m" -> Category.MUSIC
                else -> fallback
            }
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val cover = (o["img"] ?: o["pic"])?.jsonPrimitive?.contentOrNull
            val sub = (o["sub_title"] ?: o["author_name"])?.jsonPrimitive?.contentOrNull ?: ""
            val year = o["year"]?.jsonPrimitive?.contentOrNull ?: ""
            val itemUrl = o["url"]?.jsonPrimitive?.contentOrNull
                ?: when (cat) {
                    Category.BOOK -> "https://book.douban.com/subject/$id/"
                    Category.MUSIC -> "https://music.douban.com/subject/$id/"
                    else -> "https://movie.douban.com/subject/$id/"
                }
            DoubanResult(cat, id, title, sub, year, cover, itemUrl, null)
        }
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

        // [{"name":"xxx"}] 数组 → "xxx/yyy"
        fun names(key: String, limit: Int = 8): String? =
            o[key]?.jsonArray?.take(limit)
                ?.mapNotNull { runCatching { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                ?.filter { it.isNotBlank() }
                ?.joinToString("/")?.takeIf { it.isNotBlank() }

        val directors = names("directors") ?: names("author") // 图书作者/音乐人回退到 author
        val casts = names("actors")
        val genres = o["genres"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.joinToString("/")?.takeIf { it.isNotBlank() }

        val videos = parseVideos(o)

        return DoubanDetail(title, rating, cover, intro, info, directors, casts, genres, videos)
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

    /** 演职员（仅影视条目有此端点），失败返回空 */
    suspend fun fetchCelebrities(category: Category, doubanId: String): List<DoubanCelebrity> =
        withContext(Dispatchers.IO) {
            if (category != Category.MOVIE && category != Category.TV) return@withContext emptyList()
            runCatching {
                val (apiUrl, referer) = rexxarUrl(category, doubanId) ?: return@runCatching emptyList()
                val o = json.parseToJsonElement(
                    httpGetRexxar("$apiUrl/celebrities?start=0&count=20", referer)
                ).jsonObject
                buildList {
                    fun take(key: String) {
                        o[key]?.jsonArray?.forEach { el ->
                            val c = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
                            val id = c["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            val name = c["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            val character = c["character"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val role = character.ifBlank {
                                c["roles"]?.jsonArray
                                    ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                                    ?.joinToString("/").orEmpty()
                            }
                            val avatar = c["avatar"]?.jsonObject
                            val avatarUrl = (avatar?.get("normal") ?: avatar?.get("large"))
                                ?.jsonPrimitive?.contentOrNull
                            add(DoubanCelebrity(id, name, c["latin_name"]?.jsonPrimitive?.contentOrNull.orEmpty(), role, avatarUrl))
                        }
                    }
                    take("directors")
                    take("actors")
                }
            }.getOrDefault(emptyList())
        }

    /** 剧照（仅影视条目），失败返回空列表 */
    suspend fun fetchPhotos(category: Category, doubanId: String): List<DoubanPhoto> =
        withContext(Dispatchers.IO) {
            if (category != Category.MOVIE && category != Category.TV) return@withContext emptyList()
            runCatching {
                val (apiUrl, referer) = rexxarUrl(category, doubanId) ?: return@runCatching emptyList()
                val o = json.parseToJsonElement(
                    httpGetRexxar("$apiUrl/photos?start=0&count=30", referer)
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
        }

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

    /** 影人详情：rexxar/api/v2/celebrity/{id} + works */
    suspend fun fetchCelebrityDetail(celebrityId: String): DoubanCelebrityDetail =
        withContext(Dispatchers.IO) {
            val baseUrl = "https://m.douban.com/rexxar/api/v2/celebrity/$celebrityId"
            val referer = "https://m.douban.com/celebrity/$celebrityId/"
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
                        ?: "https://movie.douban.com/celebrity/$celebrityId/"
                )
            }.getOrElse {
                DoubanCelebrityDetail(
                    name = "影人",
                    url = "https://movie.douban.com/celebrity/$celebrityId/"
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
        doFetchCelebrityWorks(celebrityId, sortBy, start, count)
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
            CelebrityWork(
                id = id,
                title = w["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                year = w["year"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                type = w["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                roles = roles,
                rating = w["rating"]?.jsonObject?.get("value")?.jsonPrimitive?.floatOrNull,
                coverUrl = w["cover_url"]?.jsonPrimitive?.contentOrNull
            )
        }.orEmpty()
    }.getOrDefault(emptyList())

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
