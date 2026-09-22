package com.shangyin.app.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 斗鱼直播客户端。
 * - 分类：GET japi/weblist/apinc/newDirectory（一级 cateList[].id + 二级 list[].cid2 拼成 "cid1_cid2"）
 * - 房间：GET gapi/rknc/directory/mixListV1/{cid1_cid2}/{page}（页码必须带，page 从 1 开始，缺页码 404）
 * - 播放：GET m.douyu.com/{roomId}，SSR HTML 里直接内嵌可播放的 FLV 直链，正则提取
 */
object DouyuClient {

    const val PLATFORM = LivePlatforms.DOUYU

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 桌面 UA（PC 接口校验 UA + Referer 才给数据） */
    private const val UA_DESKTOP =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** 移动端 UA（m.douyu.com 抓取用） */
    private const val UA_MOBILE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    private const val REFERER_DESKTOP = "https://www.douyu.com/"
    private const val REFERER_MOBILE = "https://m.douyu.com/"

    private const val URL_CATEGORY = "https://www.douyu.com/japi/weblist/apinc/newDirectory"
    private const val URL_ROOM_PREFIX = "https://www.douyu.com/gapi/rknc/directory/mixListV1/"
    private const val URL_ROOM_PAGE = "https://m.douyu.com/"

    /** 播放地址：FLV 直链（渐进式） */
    private val FLV_REGEX = Regex("""https?://[^"'\s<>]*?\.flv\?[^"'\s<>]*""")

    /** 播放地址：HLS（m3u8）兜底 */
    private val M3U8_REGEX = Regex("""https?://[^"'\s<>]*?\.m3u8[^"'\s<>]*""")

    // ---------- 请求 ----------

    /** 统一 GET：非 200 / 异常都返回 null（异常不外抛） */
    private fun httpGet(url: String, ua: String, referer: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Referer", referer)
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    // ---------- 分类 ----------

    /**
     * 拉取全部分类（一级 cateList[].id + 二级 list[].cid2 → "cid1_cid2"）。
     * 保持接口原有顺序；cn2 为空 / 字段缺失的项直接跳过。
     */
    suspend fun categories(): List<LiveCategory> = withContext(Dispatchers.IO) {
        val body = httpGet(URL_CATEGORY, UA_DESKTOP, REFERER_DESKTOP) ?: return@withContext emptyList()
        runCatching<List<LiveCategory>> {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching emptyList()
            val cateList = root.obj("data")?.obj("leftNavV2")?.arr("cateList")
                ?: return@runCatching emptyList()
            val out = mutableListOf<LiveCategory>()
            cateList.forEach { cate ->
                val cateObj = cate as? JsonObject ?: return@forEach
                val cid1 = cateObj.str("id") ?: return@forEach
                val subs = cateObj.arr("list") ?: return@forEach
                subs.forEach inner@{ sub ->
                    val subObj = sub as? JsonObject ?: return@inner
                    val cid2 = subObj.str("cid2") ?: return@inner
                    val cn2 = subObj.str("cn2")
                    if (!cn2.isNullOrBlank()) out.add(LiveCategory("${cid1}_${cid2}", cn2))
                }
            }
            out
        }.getOrElse { emptyList() }
    }

    // ---------- 房间列表 ----------

    /**
     * 拉取某分类下的房间列表（categoryId 形如 "2_1"，由 categories() 得到）。
     * page 从 1 开始且必须带上；无更多数据（rl 为空）/ 解析失败都返回空列表。
     */
    suspend fun rooms(categoryId: String, page: Int): List<LiveRoom> = withContext(Dispatchers.IO) {
        val cid = categoryId.trim()
        if (cid.isEmpty()) return@withContext emptyList()
        val safePage = if (page < 1) 1 else page
        val body = httpGet("$URL_ROOM_PREFIX$cid/$safePage", UA_DESKTOP, REFERER_DESKTOP)
            ?: return@withContext emptyList()
        runCatching<List<LiveRoom>> {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching emptyList()
            val rl = root.obj("data")?.arr("rl") ?: return@runCatching emptyList()
            rl.mapNotNull { (it as? JsonObject)?.toLiveRoom() }
        }.getOrElse { emptyList() }
    }

    /** 单条房间 JSON → LiveRoom；缺 rid 视为无效条目 */
    private fun JsonObject.toLiveRoom(): LiveRoom? {
        val rid = str("rid") ?: return null
        if (rid.isBlank()) return null
        return LiveRoom(
            platform = LivePlatforms.DOUYU,
            roomId = rid,
            title = str("rn").orEmpty(),
            streamer = str("nn").orEmpty(),
            cover = pickCover(),
            hot = formatLiveHot(str("ol")),
            categoryName = str("c2name").orEmpty(),
            isLive = true
        )
    }

    /**
     * 选封面：优先 rs_ext[] 里 type == "image/webp" 且 ratio 最大的 rs16
     * （顶层 rs16 是 avif，老机型 Coil 解不出来）；无 webp 退回 rs16，再退回 av。
     */
    private fun JsonObject.pickCover(): String {
        val webp = (this["rs_ext"] as? JsonArray)
            ?.mapNotNull { ext ->
                val o = ext as? JsonObject ?: return@mapNotNull null
                if (o.str("type") != "image/webp") return@mapNotNull null
                val rs = o.str("rs16") ?: return@mapNotNull null
                (o.num("ratio") ?: 0L) to rs
            }
            ?.maxByOrNull { it.first }
            ?.second
        return webp ?: str("rs16") ?: str("av") ?: ""
    }

    // ---------- 播放地址解析 ----------

    /**
     * 解析直播间播放地址：m 端页面 SSR 里直接内嵌 FLV 直链（&amp; 需还原成 &）。
     * FLV 找不到再兜底找 m3u8（isHls = true）；都没有给出明确失败原因。
     */
    suspend fun resolve(roomId: String): LiveResolveResult = withContext(Dispatchers.IO) {
        val id = roomId.trim()
        if (id.isEmpty()) return@withContext LiveResolveResult(error = "房间号为空")
        val html = httpGet(URL_ROOM_PAGE + id, UA_MOBILE, REFERER_MOBILE)
            ?: return@withContext LiveResolveResult(error = "网络请求失败，请重试")

        val flv = FLV_REGEX.find(html)?.value
        if (flv != null) {
            return@withContext LiveResolveResult(
                info = LivePlayInfo(
                    url = flv.replace("&amp;", "&"),
                    isHls = false,
                    referer = REFERER_DESKTOP
                )
            )
        }

        val m3u8 = M3U8_REGEX.find(html)?.value
        if (m3u8 != null) {
            return@withContext LiveResolveResult(
                info = LivePlayInfo(
                    url = m3u8.replace("&amp;", "&"),
                    isHls = true,
                    referer = REFERER_DESKTOP
                )
            )
        }

        LiveResolveResult(error = "该房间未开播或页面结构有变，暂时拿不到直播地址")
    }

    // ---------- JSON 容错读取 ----------

    /** 取子对象（缺失 / 类型不符返回 null） */
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** 取子数组（缺失 / 类型不符返回 null） */
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    /** 取字符串（缺失 / JSON null / 非原始值都返回 null，不崩） */
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** 取整数（数字或数字字符串都能解析，其余返回 null） */
    private fun JsonObject.num(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}
