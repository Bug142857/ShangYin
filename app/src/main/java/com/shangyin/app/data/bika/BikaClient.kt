package com.shangyin.app.data.bika

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 哔咔漫画条目（列表项 / 详情共用，详情字段可能不全） */
data class BikaComic(
    val id: String,
    val title: String,
    val author: String = "",
    val thumbUrl: String = "",
    val likes: Int = 0,
    val views: Int = 0,
    val finished: Boolean = false,
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val epsCount: Int = 0,
    val pagesCount: Int = 0,
    val description: String = "",
    val chineseTeam: String = "",
    val updatedAt: String = ""
)

/** 哔咔章节 */
data class BikaChapter(val id: String, val title: String?, val order: Int)

/** 哔咔分类 */
data class BikaCategory(val id: String?, val title: String)

/** 列表分页信息 */
data class BikaComicsPage(val docs: List<BikaComic>, val page: Int, val pages: Int, val total: Int)

/**
 * 哔咔漫画客户端。
 * 数据源来自 haka_comic（raoxwup/haka_comic）内置的哔咔 API：
 * - 主站 https://picaapi.go2778.com/（哔咔官方备用域名，大陆可直连；picaapi.picacomic.com 被墙）
 * - 请求签名：HmacSHA256(secret, lowercase(path+query + time + nonce + METHOD + api-key))
 * - 图片防盗链：thumb/media 的 fileServer+path 拼接，域名 picacomic→go2778（与 haka 的 proxyUrl 一致）
 * - 需要哔咔账号登录（POST auth/sign-in → JWT token）
 */
object BikaClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val HOST = "https://picaapi.go2778.com/"
    private const val API_KEY = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
    private const val SECRET = "~d}\$Q7\$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"
    private const val NONCE = "4ce7a7aa759b40f794d189a88b84aba8"

    /** 登录失效（token 过期，需重新登录） */
    class BikaAuthException(msg: String) : Exception(msg)

    // ---------- 签名与请求 ----------

    private fun hmacSha256(msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun headers(pathWithQuery: String, method: String, token: String): Map<String, String> {
        val time = (System.currentTimeMillis() / 1000).toString()
        val msg = (pathWithQuery + time + NONCE + method + API_KEY).lowercase()
        return mapOf(
            "accept" to "application/vnd.picacomic.com.v1+json",
            "User-Agent" to "okhttp/3.8.1",
            "Content-Type" to "application/json; charset=UTF-8",
            "api-key" to API_KEY,
            "app-build-version" to "45",
            "app-platform" to "android",
            "app-uuid" to "defaultUuid",
            "app-version" to "2.2.1.3.3.4",
            "nonce" to NONCE,
            "app-channel" to "1",
            "time" to time,
            "signature" to hmacSha256(msg),
            "authorization" to token,
            "image-quality" to "original"
        )
    }

    /** 发请求并解析 data 字段；code!=1 抛异常（401 归为登录失效） */
    private suspend fun request(
        method: String,
        pathWithQuery: String,
        token: String,
        bodyJson: String? = null
    ): JsonObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(HOST + pathWithQuery)
        headers(pathWithQuery, method, token).forEach { (k, v) -> builder.header(k, v) }
        if (bodyJson != null) {
            builder.method(method, bodyJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        } else {
            builder.get()
        }
        val text = runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                resp.body?.string().orEmpty()
            }
        }.getOrElse { throw Exception("网络错误：${it.message ?: it.javaClass.simpleName}") }
        if (text.isBlank()) throw Exception("空响应")
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw Exception("响应解析失败")
        }
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code == 401) throw BikaAuthException("哔咔登录已失效，请重新登录")
        if (code != 1) {
            throw Exception(root["message"]?.jsonPrimitive?.contentOrNull ?: "接口错误(code=$code)")
        }
        root["data"]?.jsonObject ?: throw Exception("响应缺少 data")
    }

    // ---------- 图片 URL 拼接（与 haka_comic 一致） ----------

    /**
     * fileServer + path → 可访问图片地址：
     * - fileServer 已含 static：直接拼 path
     * - 否则插一段 /static/
     * - 多余斜杠规范化（保护 scheme://）
     * - 域名 picacomic → go2778（主站走备用域名时图片同样走备用，防被墙）
     */
    fun imageUrl(fileServer: String, path: String): String {
        if (fileServer.isBlank() || path.isBlank()) return ""
        val raw = if (fileServer.contains("static")) "$fileServer/$path" else "$fileServer/static/$path"
        val normalized = if (raw.startsWith("http")) {
            val idx = raw.indexOf("://")
            raw.substring(0, idx + 3) + raw.substring(idx + 3).replace(Regex("/{2,}"), "/")
        } else {
            raw.replace(Regex("/{2,}"), "/")
        }
        return normalized.replaceFirst("picacomic", "go2778")
    }

    // ---------- 接口 ----------

    /** 登录，返回 JWT token */
    suspend fun signIn(email: String, password: String): String = withContext(Dispatchers.IO) {
        val body = """{"email":"${jsonEncode(email)}","password":"${jsonEncode(password)}"}"""
        val data = request("POST", "auth/sign-in", token = "", bodyJson = body)
        data["token"]?.jsonPrimitive?.content ?: throw Exception("登录响应缺少 token")
    }

    /** 分类列表（过滤 isWeb 网页分类，与 haka 一致） */
    suspend fun fetchCategories(token: String): List<BikaCategory> = withContext(Dispatchers.IO) {
        val data = request("GET", "categories", token)
        val arr = data["categories"]?.jsonArray ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val isWeb = o["isWeb"]?.jsonPrimitive?.contentOrNull == "true" || o["isWeb"]?.toString() == "true"
            if (isWeb) return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            BikaCategory(id = o["_id"]?.jsonPrimitive?.contentOrNull, title = title)
        }.filter { it.title.isNotBlank() }
    }

    /** 分类/全部漫画列表：GET comics?page&c&sort（c=分类标题，空=全部） */
    suspend fun fetchComics(token: String, page: Int, category: String?, sort: String): BikaComicsPage =
        withContext(Dispatchers.IO) {
            val q = buildString {
                append("comics?page=").append(page)
                if (!category.isNullOrBlank()) append("&c=").append(urlEncode(category))
                append("&s=").append(sort)
            }
            val data = request("GET", q, token)
            parseComicsPage(data["comics"]?.jsonObject)
        }

    /** 关键词搜索：POST comics/advanced-search?page（sort: dd新到旧/da旧到新/ld最多喜欢/vd最多观看） */
    suspend fun searchComics(token: String, keyword: String, page: Int, sort: String): BikaComicsPage =
        withContext(Dispatchers.IO) {
            val body = """{"keyword":"${jsonEncode(keyword)}","categories":[],"sort":"$sort"}"""
            val data = request("POST", "comics/advanced-search?page=$page", token, body)
            parseComicsPage(data["comics"]?.jsonObject)
        }

    /** 漫画详情 */
    suspend fun fetchComicDetail(token: String, id: String): BikaComic = withContext(Dispatchers.IO) {
        val data = request("GET", "comics/$id", token)
        val c = data["comic"]?.jsonObject ?: throw Exception("响应缺少 comic")
        parseComic(c) ?: throw Exception("详情解析失败")
    }

    /** 章节列表（自动拉全部分页，按 order 升序） */
    suspend fun fetchChapters(token: String, id: String): List<BikaChapter> = withContext(Dispatchers.IO) {
        val first = request("GET", "comics/$id/eps?page=1", token)
        val eps = first["eps"]?.jsonObject ?: return@withContext emptyList()
        val totalPages = eps["pages"]?.jsonPrimitive?.intOrNull ?: 1
        val docs = mutableListOf<JsonObject>()
        eps["docs"]?.jsonArray?.forEach { docs.add(it.jsonObject) }
        if (totalPages > 1) {
            coroutineScope {
                (2..totalPages).map { p ->
                    async {
                        runCatching { request("GET", "comics/$id/eps?page=$p", token) }
                            .getOrNull()?.get("eps")?.jsonObject
                    }
                }.awaitAll().forEach { e -> e?.get("docs")?.jsonArray?.forEach { docs.add(it.jsonObject) } }
            }
        }
        docs.mapNotNull { o ->
            val cid = o["_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val order = o["order"]?.jsonPrimitive?.intOrNull ?: 0
            BikaChapter(id = cid, title = o["title"]?.jsonPrimitive?.contentOrNull, order = order)
        }.sortedBy { it.order }
    }

    /** 某一章节全部图片地址（自动拉全部分页） */
    suspend fun fetchChapterImages(token: String, id: String, order: Int): List<String> =
        withContext(Dispatchers.IO) {
            val path = "comics/$id/order/$order/pages"
            val first = request("GET", "$path?page=1", token)
            val pages = first["pages"]?.jsonObject ?: return@withContext emptyList()
            val totalPages = pages["pages"]?.jsonPrimitive?.intOrNull ?: 1
            val docs = mutableListOf<JsonObject>()
            pages["docs"]?.jsonArray?.forEach { docs.add(it.jsonObject) }
            if (totalPages > 1) {
                coroutineScope {
                    (2..totalPages).map { p ->
                        async {
                            runCatching { request("GET", "$path?page=$p", token) }
                                .getOrNull()?.get("pages")?.jsonObject
                        }
                    }.awaitAll().forEach { e -> e?.get("docs")?.jsonArray?.forEach { docs.add(it.jsonObject) } }
                }
            }
            docs.mapNotNull { o ->
                val media = o["media"]?.jsonObject ?: return@mapNotNull null
                val fs = media["fileServer"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val p = media["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                imageUrl(fs, p).takeIf { it.isNotBlank() }
            }
        }

    // ---------- 解析工具 ----------

    /** thumb 宽松提取（可能是对象/字符串/null） */
    private fun thumbUrlOf(el: kotlinx.serialization.json.JsonElement?): String {
        val o = el as? JsonObject ?: return ""
        val fs = o["fileServer"]?.jsonPrimitive?.contentOrNull ?: return ""
        val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return ""
        return imageUrl(fs, path)
    }

    private fun parseComic(o: JsonObject): BikaComic? {
        val id = o["_id"]?.jsonPrimitive?.contentOrNull ?: return null
        return BikaComic(
            id = id,
            title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
            author = o["author"]?.jsonPrimitive?.contentOrNull ?: "",
            thumbUrl = thumbUrlOf(o["thumb"]),
            likes = o["totalLikes"]?.jsonPrimitive?.intOrNull
                ?: o["likesCount"]?.jsonPrimitive?.intOrNull ?: 0,
            views = o["totalViews"]?.jsonPrimitive?.intOrNull ?: 0,
            finished = o["finished"]?.toString() == "true",
            categories = o["categories"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            tags = o["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            epsCount = o["epsCount"]?.jsonPrimitive?.intOrNull ?: 0,
            pagesCount = o["pagesCount"]?.jsonPrimitive?.intOrNull ?: 0,
            description = o["description"]?.jsonPrimitive?.contentOrNull ?: "",
            chineseTeam = o["chineseTeam"]?.jsonPrimitive?.contentOrNull ?: "",
            updatedAt = o["updated_at"]?.jsonPrimitive?.contentOrNull ?: ""
        )
    }

    private fun parseComicsPage(o: JsonObject?): BikaComicsPage {
        if (o == null) return BikaComicsPage(emptyList(), 1, 1, 0)
        val docs = o["docs"]?.jsonArray?.mapNotNull { parseComic(it.jsonObject) } ?: emptyList()
        return BikaComicsPage(
            docs = docs,
            page = o["page"]?.jsonPrimitive?.intOrNull ?: 1,
            pages = o["pages"]?.jsonPrimitive?.intOrNull ?: 1,
            total = o["total"]?.jsonPrimitive?.intOrNull ?: docs.size
        )
    }

    private fun jsonEncode(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
