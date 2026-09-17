package com.shangyin.app.data.komiic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class KomiicAuthor(val id: String, val name: String)

@Serializable
data class KomiicCategory(val id: String, val name: String)

@Serializable
data class KomiicComic(
    val id: String,
    val title: String,
    val status: String? = null,
    val imageUrl: String? = null,
    val authors: List<KomiicAuthor> = emptyList(),
    val categories: List<KomiicCategory> = emptyList(),
    val dateUpdated: String? = null,
    val lastChapterUpdate: String? = null,
    val monthViews: Long? = null,
    val views: Long? = null,
    val favoriteCount: Long? = null
)

@Serializable
data class KomiicChapter(
    val id: String,
    val serial: String? = null,
    val type: String? = null,
    val size: Int = 0
)

/**
 * Komiic 漫画 API（komiic.com，GraphQL，Cloudflare 后面）。
 * 已实测（2026-09-17）：POST https://komiic.com/api/query，匿名可访问、无需 Cookie；
 * PC curl（任意 UA/HTTP1.1/2）恒 200 数据完整。
 * 连接策略：
 *  1. Chrome 移动端 UA + Accept-Language（伪装浏览器）
 *  2. 双主机自动切换（komiic.com → komiic.cc，同服务镜像 2026-09-17 实测等价）：失败自动轮换
 *  3. API 请求带浏览器同源上下文头（Origin/Referer/Sec-Fetch-*），与站点网页 fetch 完全一致
 *  4. DNS 用系统解析（与手机浏览器一致，跟随 VPN/代理隧道）；曾用阿里 DoH 兜底，已移除
 * 诊断：空数据错误附带原始响应片段，便于定位服务端到底返回了什么
 * 封面在 public.komiic.com（无需 Referer）；章节图 {主/镜像域}/api/image/{kid}（双域均可，2026-09-17 实测）
 * 防盗链实测必须带完整路径 Referer：https://komiic.com/comic/{comicId}/chapter/{chapterId}
 * —— 因此章节图 URL 末尾追加 fragment "#c/{comicId}/{chapterId}" 编码归属信息，
 * 由 App 全局拦截器 / ImageDownloader 提取后转成 Referer（fragment 不会发送到服务器）。
 * 图片顺序：服务端已按条漫正序返回（从上往下），无需反转。
 */
object KomiicClient {

    /** 双主机：com/komiic.com 为主，cc=komiic.cc 为同服务镜像（2026-09-17 实测 API/章节图完全等价），
     *  传输失败或返回空数据时自动切换 */
    private val HOSTS = listOf("https://komiic.com", "https://komiic.cc")
    private const val PAGE_SIZE = 20

    /** Chrome 移动端 UA（章节图加载 App.kt 也复用） */
    const val CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    /** 最近一次响应的 Cloudflare 边缘节点（cf-ray，如 xxxx-HKG），用于诊断 */
    @Volatile private var lastEdge: String? = null

    /** 最近一次响应的原始 body 片段（诊断用） */
    @Volatile private var lastRaw: String = ""

    /** 当前活跃主机下标（会话内记忆，成功后更新） */
    @Volatile private var activeHostIdx = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", CHROME_UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build()
            )
        }
        .build()

    private val P = "\$"
    private val FIELDS = "id title status imageUrl authors { id name } categories { id name } dateUpdated lastChapterUpdate monthViews views favoriteCount"

    private val Q_RECENT = """query recentUpdate(${P}pagination: Pagination!) {
  recentUpdate(pagination: ${P}pagination) { $FIELDS __typename }
}"""
    private val Q_HOT = """query hotComics(${P}pagination: Pagination!) {
  hotComics(pagination: ${P}pagination) { $FIELDS __typename }
}"""
    private val Q_BY_CATEGORY = """query comicByCategory(${P}categoryId: ID!, ${P}pagination: Pagination!) {
  comicByCategory(categoryId: ${P}categoryId, pagination: ${P}pagination) { $FIELDS __typename }
}"""
    private val Q_SEARCH = """query searchComics(${P}keyword: String!, ${P}pagination: Pagination!) {
  searchComics(keyword: ${P}keyword, pagination: ${P}pagination) { $FIELDS __typename }
}"""
    private val Q_ALL_CATEGORY = """query allCategory {
  allCategory { id name __typename }
}"""
    private val Q_COMIC_BY_ID = """query comicById(${P}comicId: ID!) {
  comicById(comicId: ${P}comicId) { $FIELDS __typename }
}"""
    private val Q_CHAPTERS = """query chapterByComicId(${P}comicId: ID!) {
  chaptersByComicId(comicId: ${P}comicId) { id serial type size __typename }
}"""
    private val Q_IMAGES = """query imagesByChapterId(${P}chapterId: ID!) {
  imagesByChapterId(chapterId: ${P}chapterId) { id kid height width __typename }
}"""

    private fun paginationVars(offset: Int, orderBy: String, status: String) = buildJsonObject {
        put("pagination", buildJsonObject {
            put("limit", PAGE_SIZE)
            put("offset", offset)
            put("orderBy", orderBy)
            put("status", status)
            put("asc", false)
        })
    }

    /** 发送 GraphQL 请求：按 活跃主机→备用主机 顺序尝试，传输失败自动切换；成功后记忆活跃主机 */
    private suspend fun post(operation: String, query: String, variables: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject {
        var lastErr: Exception? = null
        for (i in HOSTS.indices) {
            val idx = (activeHostIdx + i) % HOSTS.size
            try {
                val data = postOnce(HOSTS[idx], operation, query, variables)
                activeHostIdx = idx
                return data
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: Exception("接口错误：所有主机均失败")
    }

    /** 对指定主机发一次 GraphQL 请求（带浏览器同源上下文头：Origin/Referer/Sec-Fetch，与站点网页 fetch 完全一致） */
    private suspend fun postOnce(base: String, operation: String, query: String, variables: kotlinx.serialization.json.JsonObject) =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("operationName", operation)
                put("query", query)
                put("variables", variables)
            }.toString()
            val req = Request.Builder().url("$base/api/query")
                .header("Origin", base)
                .header("Referer", "$base/")
                .header("Accept", "*/*")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    lastEdge = resp.header("cf-ray")
                    val text = resp.body?.string().orEmpty()
                    lastRaw = text.take(300)
                    if (!resp.isSuccessful) throw Exception("接口错误 HTTP ${resp.code}（$base）")
                    val root = json.parseToJsonElement(text).jsonObject
                    root["errors"]?.jsonArray?.firstOrNull()?.let { e ->
                        throw Exception(
                            e.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "接口错误（$base）"
                        )
                    }
                    root["data"]?.jsonObject ?: throw Exception("响应数据为空（$base）｜原始：$lastRaw")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e.message?.contains("HTTP") == true || e.message?.contains("接口") == true ||
                    e.message?.contains("响应数据为空") == true
                ) throw e
                // message 为空时附异常类名，便于定位（如 SocketTimeoutException/UnknownHostException）
                val detail = e.message?.takeIf { m -> m.isNotBlank() } ?: e::class.simpleName ?: "未知异常"
                throw Exception("连接 $base 失败：$detail")
            }
        }

    private fun JsonObject.comics(key: String): List<KomiicComic> =
        (this[key] as? kotlinx.serialization.json.JsonArray)?.let {
            json.decodeFromJsonElement<List<KomiicComic>>(it)
        } ?: emptyList()

    /** 最近更新（orderBy=DATE_UPDATED） */
    suspend fun recentUpdate(offset: Int, status: String = ""): List<KomiicComic> =
        post("recentUpdate", Q_RECENT, paginationVars(offset, "DATE_UPDATED", status)).comics("recentUpdate")

    /** 热门（orderBy=MONTH_VIEWS） */
    suspend fun hotComics(offset: Int, status: String = ""): List<KomiicComic> =
        post("hotComics", Q_HOT, paginationVars(offset, "MONTH_VIEWS", status)).comics("hotComics")

    /** 分类筛选 */
    suspend fun comicByCategory(categoryId: String, offset: Int, status: String = ""): List<KomiicComic> =
        post("comicByCategory", Q_BY_CATEGORY, buildJsonObject {
            put("categoryId", categoryId)
            put("pagination", paginationVars(offset, "DATE_UPDATED", status)["pagination"]!!)
        }).comics("comicByCategory")

    /** 关键词搜索（实测中文可用，如"高達"） */
    suspend fun search(keyword: String, offset: Int): List<KomiicComic> =
        post("searchComics", Q_SEARCH, buildJsonObject {
            put("keyword", keyword)
            put("pagination", paginationVars(offset, "DATE_UPDATED", "")["pagination"]!!)
        }).comics("searchComics")

    /** 全部分类（服务端动态返回，首项需自行加"全部"） */
    suspend fun allCategory(): List<KomiicCategory> =
        (post("allCategory", Q_ALL_CATEGORY, buildJsonObject { })["allCategory"] as? kotlinx.serialization.json.JsonArray)
            ?.let { json.decodeFromJsonElement<List<KomiicCategory>>(it) } ?: emptyList()

    /** 漫画详情（逐主机尝试：某主机返回 null 时切换备用主机，全部失败才报错并附诊断信息） */
    suspend fun comicById(id: String): KomiicComic {
        for (i in HOSTS.indices) {
            val idx = (activeHostIdx + i) % HOSTS.size
            val base = HOSTS[idx]
            val data = try {
                postOnce(base, "comicById", Q_COMIC_BY_ID, buildJsonObject { put("comicId", id) })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            // 注意：GraphQL 响应 key 是字段名 comicById（不是 comicId）
            val obj = data["comicById"] as? kotlinx.serialization.json.JsonObject
            if (obj != null) {
                activeHostIdx = idx
                return json.decodeFromJsonElement(obj)
            }
        }
        throw Exception(
            "站点返回空数据（id=$id，双主机均失败，边缘=${lastEdge ?: "未知"}）\n" +
                "原始响应：${lastRaw.take(200)}\n" +
                "请把此信息截图反馈"
        )
    }

    /** 章节列表（type: chapter/book 等，serial 为话数） */
    suspend fun chapters(comicId: String): List<KomiicChapter> =
        (post("chapterByComicId", Q_CHAPTERS, buildJsonObject { put("comicId", comicId) })["chaptersByComicId"] as? kotlinx.serialization.json.JsonArray)
            ?.let { json.decodeFromJsonElement<List<KomiicChapter>>(it) } ?: emptyList()

    /**
     * 章节图片 URL 列表。
     * URL 追加 fragment "#c/{comicId}/{chapterId}"，由全局拦截器转成防盗链 Referer。
     */
    suspend fun fetchChapterImages(comicId: String, chapterId: String): List<String> {
        val data = post("imagesByChapterId", Q_IMAGES, buildJsonObject { put("chapterId", chapterId) })
        val images = (data["imagesByChapterId"] as? kotlinx.serialization.json.JsonArray)
            ?.let { json.decodeFromJsonElement<List<kotlinx.serialization.json.JsonObject>>(it) }
            ?: emptyList()
        return images.map { img ->
            val kid = img["kid"]?.jsonPrimitive?.contentOrNull ?: ""
            if (kid.isBlank()) null
            else "${HOSTS[activeHostIdx]}/api/image/$kid#c/$comicId/$chapterId"
        }.filterNotNull()
    }
}
