package com.shangyin.app.data.bika

import com.shangyin.app.ui.settings.SettingsStore
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

/** 哔咔分类（thumbUrl 用于首页分类封面网格，参考 haka_comic） */
data class BikaCategory(val id: String?, val title: String, val thumbUrl: String = "")

/** 列表分页信息 */
data class BikaComicsPage(val docs: List<BikaComic>, val page: Int, val pages: Int, val total: Int)

/**
 * 哔咔漫画客户端。
 * 数据源来自 haka_comic（raoxwup/haka_comic）内置的哔咔 API：
 * - 双域名自动回退（与 haka_comic 一致）：
 *   picacomic（https://picaapi.picacomic.com/，官方主域名，需外网环境，默认优先）
 *   go2778（https://picaapi.go2778.com/，CDN 中转备用域名，速度较慢，官方失败时兜底）
 *   网络失败自动换域名重试，成功域名会话内记忆优先使用
 * - 请求签名：HmacSHA256(secret, lowercase(path+query + time + nonce + METHOD + api-key))，不含域名
 * - 图片防盗链：thumb/media 的 fileServer+path 拼接，图片域名跟随 API 域名
 *   （走官方 picacomic → 保留原图床；走 go2778 → 图片同换 go2778，与 haka 的 directUrl/proxyUrl 一致）
 * - 需要哔咔账号登录（POST auth/sign-in → JWT token）
 * - 哔咔在大陆属于被墙资源，网络层失败时提示"需外网环境"（与番号外网源规则一致）
 */
object BikaClient {

    private val json = Json { ignoreUnknownKeys = true }

    // 快速失败：连接 6s / 读写 15s，每域名只试 1 次，两域名总耗时上限约 27s 出错误态（可重试），
    // 避免 8s+20s×2次×2域名 最坏 2 分钟的"一直转圈"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 双域名：官方主域名优先（需外网），失败再试备用中转 go2778；成功域名会话内记忆优先 */
    private val HOSTS = listOf("https://picaapi.picacomic.com/", "https://picaapi.go2778.com/")

    /** 会话内记住的成功域名：后续请求优先走它 */
    @Volatile
    private var activeHost: String = HOSTS.first()

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

    /** 发请求并解析 data 字段；code!=1/200 抛异常（401 归为登录失效）；requireData=false 时允许无 data 响应（如 auth/register 只回 code/message） */
    private suspend fun request(
        method: String,
        pathWithQuery: String,
        token: String,
        bodyJson: String? = null,
        requireData: Boolean = true
    ): JsonObject = withContext(Dispatchers.IO) {
        // 成功域名优先，其余域名兜底；签名只含 path 不含域名，跨域名复用合法
        val ordered = (listOf(activeHost) + HOSTS).distinct()
        val headers = headers(pathWithQuery, method, token)
        var text: String? = null
        var okHost = ""
        var lastNetErr: Exception? = null
        // 每域名只试 1 次（快速失败），全部域名失败才报错（提示需外网环境）
        outer@ for (host in ordered) {
            val builder = Request.Builder().url(host + pathWithQuery)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (bodyJson != null) {
                builder.method(method, bodyJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            } else {
                builder.get()
            }
            try {
                text = client.newCall(builder.build()).execute().use { resp ->
                    resp.body?.string().orEmpty()
                }
                okHost = host
                break@outer
            } catch (e: Exception) {
                lastNetErr = e
            }
        }
        if (text == null) {
            throw Exception("连接哔咔失败，可能需要外网环境：${lastNetErr?.message ?: lastNetErr?.javaClass?.simpleName ?: "网络异常"}")
        }
        activeHost = okHost
        if (text!!.isBlank()) throw Exception("空响应")
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw Exception("响应解析失败")
        }
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code == 401) throw BikaAuthException("哔咔登录已失效")
        // 成功码：1（部分接口）/ 200（auth/register、categories 等实测均为 200）
        if (code != 1 && code != 200) {
            throw Exception(root["message"]?.jsonPrimitive?.contentOrNull ?: "接口错误(code=$code)")
        }
        root["data"]?.jsonObject
            ?: (if (requireData) throw Exception("响应缺少 data") else JsonObject(emptyMap()))
    }

    // ---------- 图片 URL 拼接（与 haka_comic 一致） ----------

    /**
     * fileServer + path → 可访问图片地址：
     * - fileServer 已含 static：直接拼 path
     * - 否则插一段 /static/
     * - 多余斜杠规范化（保护 scheme://）
     * - 图片域名跟随 API 域名（与 haka_comic 的 url 逻辑一致）：
     *   走官方 picacomic → 保留原图床（需外网）；走 go2778 中转 → 图片同换 go2778
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
        return if (activeHost.contains("go2778")) normalized.replaceFirst("picacomic", "go2778") else normalized
    }

    // ---------- 接口 ----------

    /**
     * 带 token 的调用包装：未登录直接抛 BikaAuthException（登录入口在 设置→账号管理→哔咔登录）；
     * 登录失效时清除本地 token 并抛出，由界面引导用户重新登录。
     */
    suspend fun <T> withAuth(block: suspend (String) -> T): T {
        val token = SettingsStore.bikaToken
        if (token.isBlank()) throw BikaAuthException("未登录哔咔账号")
        return try {
            block(token)
        } catch (e: BikaAuthException) {
            SettingsStore.clearBikaToken()
            throw BikaAuthException("哔咔登录已失效")
        }
    }

    /** 登录，返回 JWT token */
    suspend fun signIn(email: String, password: String): String = withContext(Dispatchers.IO) {
        val body = """{"email":"${jsonEncode(email)}","password":"${jsonEncode(password)}"}"""
        val data = request("POST", "auth/sign-in", token = "", bodyJson = body)
        data["token"]?.jsonPrimitive?.content ?: throw Exception("登录响应缺少 token")
    }

    /** 分类列表（过滤 isWeb 网页分类，与 haka 一致；含分类封面图） */
    suspend fun fetchCategories(token: String): List<BikaCategory> = withContext(Dispatchers.IO) {
        val data = request("GET", "categories", token)
        val arr = data["categories"]?.jsonArray ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val isWeb = o["isWeb"]?.jsonPrimitive?.contentOrNull == "true" || o["isWeb"]?.toString() == "true"
            if (isWeb) return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val thumb = o["thumb"]?.jsonObject
            val thumbUrl = thumb?.let { t ->
                imageUrl(
                    t["fileServer"]?.jsonPrimitive?.contentOrNull ?: "",
                    t["path"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }.orEmpty()
            BikaCategory(id = o["_id"]?.jsonPrimitive?.contentOrNull, title = title, thumbUrl = thumbUrl)
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
