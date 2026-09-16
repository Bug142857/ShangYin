package com.shangyin.app.data.pix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ---------- 数据模型 ----------

@Serializable
data class PixivTag(
    val name: String,
    @SerialName("translated_name") val translatedName: String? = null
)

@Serializable
data class ProfileImg(val medium: String? = null)

@Serializable
data class PixivUser(
    val id: Long,
    val name: String,
    @SerialName("profile_image_urls") val profileImageUrls: ProfileImg? = null
)

@Serializable
data class IllustImages(
    @SerialName("square_medium") val squareMedium: String? = null,
    val medium: String? = null,
    val large: String? = null
)

@Serializable
data class PageImages(
    val medium: String? = null,
    val large: String? = null,
    val original: String? = null
)

@Serializable
data class MetaPage(@SerialName("image_urls") val imageUrls: PageImages? = null)

@Serializable
data class MetaSinglePage(@SerialName("original_image_url") val originalImageUrl: String? = null)

@Serializable
data class PixivIllust(
    val id: Long,
    val title: String,
    val type: String = "illust",
    @SerialName("image_urls") val imageUrls: IllustImages? = null,
    val caption: String = "",
    val user: PixivUser? = null,
    val tags: List<PixivTag> = emptyList(),
    @SerialName("create_date") val createDate: String? = null,
    @SerialName("page_count") val pageCount: Int = 1,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("total_view") val totalView: Int = 0,
    @SerialName("total_bookmarks") val totalBookmarks: Int = 0,
    @SerialName("x_restrict") val xRestrict: Int = 0,
    @SerialName("illust_ai_type") val illustAiType: Int = 0,
    @SerialName("meta_single_page") val metaSinglePage: MetaSinglePage? = null,
    @SerialName("meta_pages") val metaPages: List<MetaPage> = emptyList()
)

@Serializable
data class IllustListResp(val illusts: List<PixivIllust> = emptyList())

@Serializable
data class IllustDetailResp(val illust: PixivIllust? = null)

/**
 * Pixiv 插画搜索客户端。
 * 数据源来自 pixiv-viewer-app（asadahimeka/pixiv-viewer）使用的 HibiAPI 兼容镜像：
 * - 搜索：GET {API_BASE}/search?word=&mode=&order=&page=&size=   （HibiAPI 别名端点）
 * - 详情：GET {API_BASE}/illust?id=
 * - 鉴权由镜像站内置公共 token 完成，匿名可用，无需登录
 * - 请求头校验：镜像站要求浏览器级 UA + Accept + Origin/Referer，缺任一返回 400/500
 * - 图片防盗链：i.pximg.net 直连不通，统一替换为 i.pixiv.re 代理（无需 Referer）
 */
object PixivClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** HibiAPI 兼容镜像（pixiv-viewer-app 默认实例） */
    private const val API_BASE = "https://api.cocomi.eu.org/api/pixiv"

    /** pximg 图片代理域名（pixiv-viewer-app 默认图床） */
    const val IMG_PROXY_HOST = "i.pixiv.re"

    /** 镜像站要求的浏览器级 UA（实测 curl 默认 UA 会被拒） */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** i.pximg.net → 代理域名（所有展示图片必须过这一步） */
    fun imgProxy(url: String?): String =
        url?.replace("i.pximg.net", IMG_PROXY_HOST).orEmpty()

    /** 列表封面（medium 约 540px） */
    fun coverUrl(d: PixivIllust): String = imgProxy(d.imageUrls?.medium)

    /** 详情页全部原图（代理化）：多页取 meta_pages[].original，单页取 meta_single_page.original，兜底 large */
    fun allPageImages(d: PixivIllust): List<String> {
        val urls = d.metaPages.mapNotNull { it.imageUrls?.original ?: it.imageUrls?.large }
            .filter { it.isNotBlank() }
            .map { imgProxy(it) }
        if (urls.isNotEmpty()) return urls
        return listOfNotNull(
            imgProxy(d.metaSinglePage?.originalImageUrl ?: d.imageUrls?.large)
        ).filter { it.isNotBlank() }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private inline fun <reified T> parse(body: String?): T? = runCatching {
        body?.let { json.decodeFromString<T>(it) }
    }.getOrNull()

    private fun httpGet(url: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://pixiv.pictures")
                .header("Referer", "https://pixiv.pictures/")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    /**
     * 关键词搜索插画。
     * @param mode partial_match_for_tags=标签部分一致 / exact_match_for_tags=标签完全一致 / title_and_caption=标题说明文
     * @param order date_desc=最新 / date_asc=最早
     * @param blockR18 过滤 R-18 作品（默认开启）
     */
    suspend fun searchIllust(
        word: String,
        mode: String = "partial_match_for_tags",
        order: String = "date_desc",
        page: Int = 1,
        size: Int = 30,
        blockR18: Boolean = true
    ): List<PixivIllust> = withContext(Dispatchers.IO) {
        val url = "$API_BASE/search?word=${enc(word)}&mode=${enc(mode)}&order=${enc(order)}&page=$page&size=$size"
        val list = parse<IllustListResp>(httpGet(url))?.illusts ?: emptyList()
        if (blockR18) list.filter { it.xRestrict == 0 } else list
    }

    /** 作品详情（含全部原图 meta_pages） */
    suspend fun illustDetail(id: Long): PixivIllust? = withContext(Dispatchers.IO) {
        parse<IllustDetailResp>(httpGet("$API_BASE/illust?id=$id"))?.illust
    }
}
