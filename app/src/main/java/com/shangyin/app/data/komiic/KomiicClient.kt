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
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.chromium.net.CronetEngine
import com.google.net.cronet.okhttptransport.CronetInterceptor
import com.shangyin.app.App
import java.net.InetAddress
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
 * PC curl（任意 UA/HTTP1.1/2）恒 200 数据完整 —— 服务器软拒的是客户端 TLS/HTTP 指纹而非 UA。
 * 连接策略（v2.19.0）：
 *  1. Cronet（Chromium 同款网络栈，cronet-embedded）：TLS 指纹(JA3)/HTTP2 设置帧与真实 Chrome 完全一致，
 *     从根上绕过 Cloudflare 对 OkHttp(Conscrypt) 指纹的软拒（200 + data.comicById=null）；Cronet 初始化失败自动回退纯 OkHttp
 *  2. Chrome/119 移动端 UA（与 Cronet 版本一致，防 UA-指纹交叉校验）+ Accept-Language
 *  3. 强制 HTTP/1.1 仅作为 Cronet 不可用时的 OkHttp 兜底行为
 *  4. DoH 加密 DNS（阿里 dns.alidns.com）：直连场景下系统 DNS 解析可能被污染；Cronet 激活时用它自带的 DNS，DoH 仅兜底路径生效
 *  5. 双主机自动切换（komiic.com → komiic.cc，同服务镜像 2026-09-17 实测等价）：传输失败自动轮换；详情空数据逐主机重试并记忆可用主机
 *  6. API 请求带浏览器同源上下文头（Origin/Referer/Sec-Fetch-*）：与站点网页 fetch 完全一致，规避对非浏览器上下文的软拒
 * 诊断：详情空数据错误附带 网络栈/边缘节点/失败主机 与浏览器对比指引
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

    /** Chrome/119 移动端 UA（与 cronet-embedded 119 版本一致）；章节图加载（App.kt）也复用 */
    const val CHROME_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    /** DoH（阿里，国内可直连）：绕过 DNS 污染；DoH 失败自动回退系统 DNS，不影响可用性 */
    private val doh = DnsOverHttps.Builder()
        .client(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build())
        .url("https://dns.alidns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("223.5.5.5"),
            InetAddress.getByName("223.6.6.6")
        )
        .build()

    private val smartDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            try {
                doh.lookup(hostname)
            } catch (e: Exception) {
                okhttp3.Dns.SYSTEM.lookup(hostname)
            }
    }

    /** 最近一次响应的 Cloudflare 边缘节点（cf-ray，如 xxxx-HKG），用于空数据诊断 */
    @Volatile private var lastEdge: String? = null

    /** 当前活跃主机下标（会话内记忆，成功后更新） */
    @Volatile private var activeHostIdx = 0

    /** 传输栈名（诊断用）：Cronet 是否可用在 client 构建时已确定 */
    private val transportName: String
        get() = if (cronetEngine != null) "Cronet" else "OkHttp兜底"

    /**
     * Cronet（Chromium 同款网络栈）：TLS 指纹/HTTP2 与真实 Chrome 完全一致，
     * 绕过 Cloudflare 对 OkHttp 指纹的软拒。初始化失败返回 null → 自动回退纯 OkHttp。
     */
    private val cronetEngine: CronetEngine? by lazy {
        try {
            CronetEngine.Builder(App.instance.applicationContext)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .build()
        } catch (t: Throwable) {
            null
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .dns(smartDns)
        // 兜底路径（Cronet 不可用时）强制 HTTP/1.1，与 curl 行为一致；Cronet 激活时此设置被绕过
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .addInterceptor { chain ->
            // 浏览器级请求头：UA 与 Cronet 版本一致（Chrome/119），防指纹-UA 交叉校验
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", CHROME_UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build()
            )
        }
        // Cronet 拦截器必须放在 UA 拦截器之后（保证带浏览器头发出），引擎为 null 时自动跳过
        .apply { cronetEngine?.let { e -> addInterceptor(CronetInterceptor.newBuilder(e).build()) } }
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
                    if (!resp.isSuccessful) throw Exception("接口错误 HTTP ${resp.code}（$base）")
                    val root = json.parseToJsonElement(text).jsonObject
                    root["errors"]?.jsonArray?.firstOrNull()?.let { e ->
                        throw Exception(
                            e.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "接口错误（$base）"
                        )
                    }
                    root["data"]?.jsonObject ?: throw Exception("响应数据为空（$base）")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e.message?.contains("HTTP") == true || e.message?.contains("接口") == true) throw e
                // message 为空时附异常类名，便于定位（如 SocketTimeoutException/UnknownHostException）
                val detail = e.message?.takeIf { m -> m.isNotBlank() } ?: e::class.simpleName ?: "未知异常"
                throw Exception("连接 $base 失败，可能需要外网环境：$detail")
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
        var firstFailMsg: String? = null
        for (i in HOSTS.indices) {
            val idx = (activeHostIdx + i) % HOSTS.size
            val base = HOSTS[idx]
            val data = try {
                postOnce(base, "comicById", Q_COMIC_BY_ID, buildJsonObject { put("comicId", id) })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstFailMsg == null) firstFailMsg = e.message ?: "连接失败"
                continue
            }
            val obj = data["comicId"] as? kotlinx.serialization.json.JsonObject
            if (obj != null) {
                activeHostIdx = idx
                return json.decodeFromJsonElement(obj)
            }
            if (firstFailMsg == null)
                firstFailMsg = "${base.removePrefix("https://")} 返回空数据（边缘节点=${lastEdge ?: "未知"}）"
        }
        throw Exception(
            "站点返回空数据（id=$id，网络栈=$transportName）：$firstFailMsg，备用主机同样失败。" +
                "请用手机浏览器打开 komiic.cc 测试：浏览器正常→请截图反馈此错误；" +
                "浏览器也异常→当前网络/VPN 节点被站点限制，请更换节点或切换 WiFi/流量"
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
