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
 * - 播放：两步法。裸 HTTP 抓 m.douyu.com/{roomId} 实测**拿不到**播放地址 —— 那条 flv 链接是页面 JS
 *   跑完才注入 DOM 的（无 Cookie 的 146KB HTML 里一个 .flv 都没有），而接口 /lapi/live/getH5PlayV1
 *   又要求页面运行时生成的 enc_data 签名。所以先纯 HTTP 碰运气，不行就走隐藏 WebView 被动接住页面自己拿到的地址。
 */
object DouyuClient {

    const val PLATFORM = LivePlatforms.DOUYU

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * 桌面 UA：PC 接口校验 UA + Referer 才给数据；m 端页面也必须用桌面 UA
     * （手机 UA 下拿到的是另一套页面，同样不含播放地址）。
     */
    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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

    /** WebView 通道里 link 标签返回的地址前缀（见 [parseWebResult]） */
    private const val URL_PREFIX = "URL:"

    /**
     * 解析直播间播放地址，两步：
     *  1) 纯 HTTP 抓 m.douyu.com/{roomId} 碰运气：页面里若已内联 .flv / .m3u8 直接用（少数情况）；
     *  2) 否则走隐藏 WebView —— 地址只有页面自身那次带签名的 XHR 才拿得到，裸 HTTP 不行。
     */
    suspend fun resolve(roomId: String): LiveResolveResult = withContext(Dispatchers.IO) {
        val id = roomId.trim()
        if (id.isEmpty()) return@withContext LiveResolveResult(error = "房间号为空")

        // 1) 纯 HTTP 先试一次
        val html = httpGet(URL_ROOM_PAGE + id, UA_DESKTOP, REFERER_MOBILE)
        if (html != null) {
            FLV_REGEX.find(html)?.value?.let { flv ->
                return@withContext LiveResolveResult(
                    info = LivePlayInfo(flv.replace("&amp;", "&"), isHls = false, referer = REFERER_DESKTOP)
                )
            }
            M3U8_REGEX.find(html)?.value?.let { m3u8 ->
                return@withContext LiveResolveResult(
                    info = LivePlayInfo(m3u8.replace("&amp;", "&"), isHls = true, referer = REFERER_DESKTOP)
                )
            }
        }

        // 2) 隐藏 WebView：LiveWeb 已挂好钩子，会把页面自己那次 XHR 的请求地址 + 响应体记到 window.__syLiveHit
        if (!LiveWeb.isReady) return@withContext LiveResolveResult(error = "获取播放地址失败，请重试")
        val js = """
          (function(){
            var hit = window.__syLiveHit || '';
            var i = hit.indexOf('@@BODY@@');
            if (i > 0) {
              var body = hit.substring(i + 8);
              if (body && /rtmp_url|rtmp_live|"error":0|hls_url|\.flv|\.m3u8/.test(body)) return body;
            }
            var l = document.querySelector('link[as="fetch"][href*=".flv"], link[rel="preload"][href*=".flv"], link[href*=".m3u8"]');
            if (l && l.href) return 'URL:' + l.href;
            return '';
          })()
        """
        // 直接 suspend 调用即可：pollJs 内部自己切主线程并带超时，不要再包一层调度器
        val raw = LiveWeb.pollJs("https://m.douyu.com/$id", js, timeoutMs = 18_000)
            ?: return@withContext LiveResolveResult(error = "该房间未开播或暂时拿不到直播地址")
        val info = parseWebResult(raw)
            ?: return@withContext LiveResolveResult(error = "该房间未开播或暂时拿不到直播地址")
        LiveResolveResult(info = info)
    }

    /**
     * 解析 WebView 通道拿到的结果：
     *  a) "URL:xxx" → DOM 里 link 的绝对地址（页面 JS 跑完后注入的直链）；
     *  b) 否则当作页面自己那次 XHR 的响应体 JSON：data.rtmp_url + "/" + data.rtmp_live；
     *  c) 再不行就整体正则捞 .flv / .m3u8。
     */
    private fun parseWebResult(raw: String): LivePlayInfo? {
        if (raw.startsWith(URL_PREFIX)) {
            val u = raw.removePrefix(URL_PREFIX).trim()
            if (u.isEmpty()) return null
            return LivePlayInfo(u, isHls = isHlsUrl(u), referer = REFERER_DESKTOP)
        }

        // 响应体里的 URL 可能是 JSON 转义过的（http:\/\/…），先还原再匹配
        val body = raw.replace("\\/", "/")
        runCatching {
            val data = (json.parseToJsonElement(body) as? JsonObject)?.obj("data")
            val host = data?.str("rtmp_url")?.trimEnd('/')
            val live = data?.str("rtmp_live")
            if (!host.isNullOrBlank() && !live.isNullOrBlank()) {
                val u = "$host/$live"
                return LivePlayInfo(u, isHls = isHlsUrl(live), referer = REFERER_DESKTOP)
            }
        }

        FLV_REGEX.find(body)?.value?.let {
            return LivePlayInfo(it.replace("&amp;", "&"), isHls = false, referer = REFERER_DESKTOP)
        }
        M3U8_REGEX.find(body)?.value?.let {
            return LivePlayInfo(it.replace("&amp;", "&"), isHls = true, referer = REFERER_DESKTOP)
        }
        return null
    }

    /** 按扩展名判断是否 HLS（忽略 query） */
    private fun isHlsUrl(url: String): Boolean =
        url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

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
