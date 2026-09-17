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
 * Komiic 漫画 API（komiic.com，GraphQL）。
 * 已实测（2026-09-17）：POST https://komiic.com/api/query，匿名可访问，无特殊请求头要求。
 * 封面在 public.komiic.com（无需 Referer）；章节图 https://komiic.com/api/image/{kid}
 * 防盗链实测必须带完整路径 Referer：https://komiic.com/comic/{comicId}/chapter/{chapterId}
 * —— 因此章节图 URL 末尾追加 fragment "#c/{comicId}/{chapterId}" 编码归属信息，
 * 由 App 全局拦截器 / ImageDownloader 提取后转成 Referer（fragment 不会发送到服务器）。
 * 图片顺序：服务端已按条漫正序返回（从上往下），无需反转。
 */
object KomiicClient {

    private const val API = "https://komiic.com/api/query"
    private const val PAGE_SIZE = 20

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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

    /** 发送 GraphQL 请求，返回 data 对象（errors 时抛异常，提示需外网环境） */
    private suspend fun post(operation: String, query: String, variables: kotlinx.serialization.json.JsonObject) =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("operationName", operation)
                put("query", query)
                put("variables", variables)
            }.toString()
            val req = Request.Builder().url(API)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw Exception("接口错误 HTTP ${resp.code}")
                    val root = json.parseToJsonElement(text).jsonObject
                    root["errors"]?.jsonArray?.firstOrNull()?.let { e ->
                        throw Exception(
                            e.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "接口错误"
                        )
                    }
                    root["data"]?.jsonObject ?: throw Exception("响应数据为空")
                }
            } catch (e: Exception) {
                if (e.message?.contains("HTTP") == true || e.message?.contains("接口") == true) throw e
                throw Exception("连接 Komiic 失败，可能需要外网环境：${e.message ?: "网络错误"}")
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

    /** 漫画详情 */
    suspend fun comicById(id: String): KomiicComic =
        json.decodeFromJsonElement(
            post("comicById", Q_COMIC_BY_ID, buildJsonObject { put("comicId", id) })["comicId"]!!.jsonObject
        )

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
            else "https://komiic.com/api/image/$kid#c/$comicId/$chapterId"
        }.filterNotNull()
    }
}
