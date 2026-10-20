package com.shangyin.app.data.xsite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * X站（里世界第九入口）：pektino.com —— X(Twitter) 免费成人视频保存排行榜。
 *
 * 与其它模块不同，本站是 Next.js 站点，列表/详情全部走官方 JSON API（2026-10-20 实测）：
 *  - 排行列表：GET /api/media?range={daily|weekly|monthly|all}&page=N&per_page=50
 *      &sort={favorite|pv|time|created}&category={tagCode}&lang=zh-CN
 *      → { items[], currentPage, lastPage, total, perPage }（数字分页，天然支持页码条）
 *  - 详情：GET /api/media/{url_cd} → 直链 mp4 + 封面 + 标签 + 评论 + 原推链接
 *  - 标签：GET /api/tags → 本地化名称（name_zh_cn）+ code（列表 category 参数用）
 *
 * 视频直链在 video.twimg.com（无 Referer/UA 要求，CORS *），封面在 pbs.twimg.com，
 * 均为推文永久 CDN 资源，直连可播可下。
 *
 * 条目无标题字段（站点本身就是纯缩略图排行榜）：展示名用「@推主」构造。
 */
object XSiteClient {

    const val BASE = "https://pektino.com"
    private const val API = "$BASE/api"

    /** 收藏 doubanId 前缀（XSiteScreens 存、ItemDetailScreen 恢复时按此前缀识别） */
    const val COLLECT_PREFIX = "pektino|"

    /** 播放器/下载请求头要用，公开 */
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 排行范围（与站点 tab 一致：每日/每周/每月/总榜） */
    val RANGES = listOf("每日" to "daily", "每周" to "weekly", "每月" to "monthly", "总榜" to "all")

    /** 排序（与站点一致：按点赞/按观看/按时长/最新添加） */
    val SORTS = listOf("按点赞" to "favorite", "按观看" to "pv", "按时长" to "time", "最新" to "created")

    /** 每页条数（站点 web 端同款） */
    const val PER_PAGE = 50

    // ---------------- 数据模型 ----------------

    /** 排行条目（无标题，展示名 = @推主） */
    data class XPost(
        val urlCd: String,       // 详情代码（API 路径参数）
        val mediaId: Long,       // 数字 id（播放进度 key 用）
        val videoUrl: String,    // twimg 直链 mp4
        val thumbnail: String?,  // 封面
        val durationSec: Int,    // 时长（秒）
        val favorite: Int,       // 点赞数
        val pv: Int,             // 观看数
        val comments: Int,       // 评论数
        val account: String,     // 推主 handle（不带 @）
        val tweetUrl: String?    // 原推链接
    )

    /** 一页列表：items + 数字分页元信息 */
    data class XPage(
        val items: List<XPost>,
        val page: Int,
        val lastPage: Int,   // 0 = 未知
        val total: Int
    )

    /** 标签分类（id = tags API 的数字 id（详情 tag_id 指向它），code 传给列表 API 的 category 参数） */
    data class XCategory(val id: Int, val name: String, val code: String)

    /** 详情：条目 + 标签名 + 发布时间 */
    data class XDetail(
        val post: XPost,
        val tags: List<String>,
        val postedAt: String   // ISO 时间原样（展示前截日期）
    )

    // ---------------- 分类（内存缓存，进程内一次） ----------------

    @Volatile
    private var cachedCategories: List<XCategory>? = null

    @Volatile
    private var cachedTagNamesById: Map<Int, String>? = null

    /** 全部标签分类（is_language=false 的内容标签；失败时回退内置常用表） */
    suspend fun categories(): List<XCategory> {
        ensureTags()
        return cachedCategories ?: FALLBACK_CATEGORIES
    }

    /** 详情页 tag_id → 标签名（未命中返回 null，UI 跳过该标签） */
    suspend fun tagNameById(tagId: Int): String? {
        ensureTags()
        return cachedTagNamesById?.get(tagId)
    }

    /** 拉取并缓存标签表（进程内一次；并发只放行首个，其余等结果） */
    private suspend fun ensureTags() {
        if (cachedCategories != null) return
        synchronized(this) {
            if (cachedCategories != null) return
            val parsed = runCatching {
                // tags 返回顶层数组：[{ id, code, name, name_zh_cn, is_language, ... }]
                val arr = org.json.JSONArray(httpGet("$API/tags"))
                val cats = ArrayList<XCategory>()
                val namesById = HashMap<Int, String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val code = o.optString("code").takeIf { it.isNotBlank() } ?: continue
                    val id = o.optInt("id", -1)
                    val name = o.optString("name_zh_cn").takeIf { it.isNotBlank() }
                        ?: o.optString("name").takeIf { it.isNotBlank() }
                        ?: code
                    if (id >= 0) namesById[id] = name
                    if (!o.optBoolean("is_language")) cats += XCategory(id, name, code)
                }
                cats to namesById
            }.getOrNull()
            if (parsed != null && parsed.first.isNotEmpty()) {
                cachedCategories = parsed.first
                cachedTagNamesById = parsed.second
            }
        }
    }

    /** 标签接口不可达时的兜底分类（2026-10-20 热门标签实测；id=-1 表示未知，详情页标签解析会跳过） */
    private val FALLBACK_CATEGORIES = listOf(
        XCategory(-1, "业余", "shirouto"),
        XCategory(-1, "丰胸", "kyonyu"),
        XCategory(-1, "自我表达", "masturbation"),
        XCategory(-1, "美少女", "beautiful-girl"),
        XCategory(-1, "女高中生", "jk"),
        XCategory(-1, "角色扮演", "cosplay"),
        XCategory(-1, "姐姐", "big-sister"),
        XCategory(-1, "自摄", "hamedori"),
        XCategory(-1, "私人拍摄", "personal-filming"),
        XCategory(-1, "户外场景", "outdoor"),
        XCategory(-1, "群体场景", "orgy"),
        XCategory(-1, "动漫 / 二次元", "anime"),
        XCategory(-1, "男同性恋・男娘", "gay"),
        XCategory(-1, "SM 题材", "sm"),
        XCategory(-1, "家庭主题", "incest"),
        XCategory(-1, "口部艺术", "fellatio")
    )

    // ---------------- 排行列表 ----------------

    /**
     * 拉取排行列表。
     * @param range 范围代码（RANGES 第二列）
     * @param sort 排序代码（SORTS 第二列）
     * @param category 标签 code；空 = 全部
     */
    suspend fun list(
        range: String,
        page: Int,
        sort: String,
        category: String = ""
    ): XPage = withContext(Dispatchers.IO) {
        val q = buildList {
            add("range=" + URLEncoder.encode(range, "UTF-8"))
            add("page=${page.coerceAtLeast(1)}")
            add("per_page=$PER_PAGE")
            add("sort=" + URLEncoder.encode(sort, "UTF-8"))
            if (category.isNotBlank()) add("category=" + URLEncoder.encode(category, "UTF-8"))
            add("lang=zh-CN")
        }.joinToString("&")
        val json = JSONObject(httpGet("$API/media?$q"))
        val arr = json.optJSONArray("items") ?: throw IOException("响应缺少 items")
        val items = ArrayList<XPost>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            val cd = o.optString("url_cd")
            if (url.isBlank() || cd.isBlank()) continue
            items += XPost(
                urlCd = cd,
                mediaId = o.optLong("id", 0L),
                videoUrl = url,
                thumbnail = o.optString("thumbnail").takeIf { it.startsWith("http") },
                durationSec = o.optInt("time", 0),
                favorite = o.optString("favorite").toIntOrNull() ?: o.optInt("favorite", 0),
                pv = o.optString("pv").toIntOrNull() ?: o.optInt("pv", 0),
                comments = o.optInt("commentCount", 0),
                account = o.optString("tweet_account").removePrefix("@"),
                tweetUrl = o.optString("tweet_url").takeIf { it.startsWith("http") }
            )
        }
        XPage(
            items = items,
            page = json.optInt("currentPage", page),
            lastPage = json.optInt("lastPage", 0),
            total = json.optInt("total", 0)
        )
    }

    // ---------------- 详情 ----------------

    suspend fun detail(urlCd: String): XDetail = withContext(Dispatchers.IO) {
        val json = JSONObject(httpGet("$API/media/${android.net.Uri.encode(urlCd)}"))
        val url = json.optString("url")
        val cd = json.optString("url_cd").ifBlank { urlCd }
        if (url.isBlank()) throw IOException("视频不存在或已被删除")
        // 详情 tags 数组只有 tag_id（数字，指向 /api/tags 的 id），名称需查缓存表
        val tagIds = json.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.optInt("tag_id", -1)?.takeIf { it >= 0 } }
        }.orEmpty()
        if (tagIds.isNotEmpty()) ensureTags()
        val names = cachedTagNamesById.orEmpty()
        val tags = tagIds.mapNotNull { names[it] }
        val post = XPost(
            urlCd = cd,
            mediaId = json.optLong("id", 0L),
            videoUrl = url,
            thumbnail = json.optString("thumbnail").takeIf { it.startsWith("http") },
            durationSec = json.optInt("time", 0),
            favorite = json.optString("favorite").toIntOrNull() ?: json.optInt("favorite", 0),
            pv = json.optString("pv").toIntOrNull() ?: json.optInt("pv", 0),
            comments = json.optJSONArray("comments")?.length() ?: json.optInt("commentCount", 0),
            account = json.optString("tweet_account").removePrefix("@"),
            tweetUrl = json.optString("tweet_url").takeIf { it.startsWith("http") }
        )
        XDetail(post = post, tags = tags, postedAt = json.optString("posted_at"))
    }

    // ---------------- HTTP ----------------

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Referer", "$BASE/zh-CN")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    // ---------------- 工具 ----------------

    /** 秒 → mm:ss / h:mm:ss */
    fun formatDuration(sec: Int): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** 计数缩写：2185 → 2185；21500 → 2.2万；139558 → 14.0万 */
    fun formatCount(n: Int): String = when {
        n >= 10000 -> {
            val w = n / 10000.0
            val s = if (w >= 100) "%.0f".format(w) else "%.1f".format(w)
            s.removeSuffix(".0") + "万"
        }
        else -> n.toString()
    }
}
