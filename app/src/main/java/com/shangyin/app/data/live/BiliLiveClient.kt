package com.shangyin.app.data.live

import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * B 站直播客户端。
 * - 分区：GET Area/getList（一级 12 项，每项 list[] 是二级），展平为 "一级id_二级id"
 * - 房间：GET Area/getRoomList（匿名可用，按分区拉在播房间）
 * - 播放地址：GET getRoomPlayInfo，取 http_hls / ts / codec[0] 的 {host}{base_url}{extra}
 *
 * 注意：extra 里带短时效的 expires/sign 签名，播放地址不能缓存，每次播放都要重新请求。
 */
object BiliLiveClient {

    const val PLATFORM = LivePlatforms.BILI

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** 所有 B 站接口都要求带 Referer，播放防盗链也用这个 */
    private const val REFERER = "https://live.bilibili.com/"

    private const val API_BASE = "https://api.live.bilibili.com"

    /** 原画 qn：info 一直用这一档（改造前写死在 URL 里的那个 qn） */
    private const val QN_ORIGINAL = 10000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    // ---------- 登录态 ----------

    /** 是否已登录（有 SESSDATA）；未初始化存储等异常按未登录处理 */
    fun isLoggedIn(): Boolean = runCatching {
        SettingsStore.biliCookie.contains("SESSDATA", ignoreCase = true)
    }.getOrDefault(false)

    // ---------- 请求 ----------

    /** 统一 GET：带 UA / Referer，有登录 Cookie 时带上；失败或非 200 返回 null */
    private fun httpGet(url: String): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
        val cookie = SettingsStore.biliCookie
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    // ---------- 分区 ----------

    /**
     * 拉取直播分区（展平一二级）：
     * 每个一级分区下的每个二级分区 → LiveCategory("${一级id}_${二级id}", 二级 name)。
     * 二级 id 是字符串，过滤 name 为空的项；code != 0 或异常 → 空列表。
     */
    suspend fun categories(): List<LiveCategory> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$API_BASE/room/v1/Area/getList?need_entrance=1&parent_id=0"
            val body = httpGet(url) ?: return@runCatching emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            if (root.int("code") != 0) return@runCatching emptyList()
            val data = root.arr("data") ?: return@runCatching emptyList()
            val out = mutableListOf<LiveCategory>()
            data.forEach parentLoop@{ parentEl ->
                val parent = parentEl as? JsonObject ?: return@parentLoop
                val parentId = parent.str("id")
                val list = parent.arr("list") ?: return@parentLoop
                list.forEach childLoop@{ childEl ->
                    val child = childEl as? JsonObject ?: return@childLoop
                    val name = child.str("name")
                    val childId = child.str("id")
                    if (name.isBlank() || childId.isBlank()) return@childLoop
                    out.add(LiveCategory(id = "${parentId}_$childId", name = name))
                }
            }
            out
        }.getOrElse { emptyList() }
    }

    // ---------- 房间列表 ----------

    /**
     * 按分区拉在播房间（第 [page] 页，每页 30）。
     * categoryId 形如 "1_0"：拆成 parent_area_id 与 area_id，拆不出返回空列表。
     * 注意：xlive/web-interface/v1/second/getList 长期返回 -352 风控，不要用它。
     */
    suspend fun rooms(categoryId: String, page: Int): List<LiveRoom> = withContext(Dispatchers.IO) {
        runCatching {
            val parts = categoryId.split('_')
            if (parts.size < 2) return@runCatching emptyList()
            val parentAreaId = parts[0]
            val areaId = parts[1]
            if (parentAreaId.isBlank() || areaId.isBlank()) return@runCatching emptyList()
            val url = "$API_BASE/room/v1/Area/getRoomList" +
                "?platform=web&parent_area_id=$parentAreaId&area_id=$areaId" +
                "&sort_type=online&page=$page&page_size=30"
            val body = httpGet(url) ?: return@runCatching emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            if (root.int("code") != 0) return@runCatching emptyList()
            val data = root.arr("data") ?: return@runCatching emptyList()
            data.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val roomId = o.str("roomid")
                if (roomId.isBlank()) return@mapNotNull null
                // 列表项里的 online 是当前人气，实测可以直接用来判在播：online <= 0 视为未开播
                val online = o.str("online")
                LiveRoom(
                    platform = LivePlatforms.BILI,
                    roomId = roomId,
                    title = o.str("title"),
                    streamer = o.str("uname"),
                    // system_cover 是官方系统封面，兜底用主播自定义封面
                    cover = o.str("system_cover").ifBlank { o.str("user_cover") },
                    hot = formatLiveHot(online),
                    categoryName = o.str("area_name"),
                    isLive = (online.toLongOrNull() ?: 0L) > 0
                )
            }
                // 未开播的房间不要排在前面（sortedByDescending 是稳定排序，在播之间保持原有相对顺序）
                .sortedByDescending { it.isLive }
        }.getOrElse { emptyList() }
    }

    // ---------- 播放地址解析 ----------

    /**
     * 解析房间的真实播放地址（每次播放都要重新调用，地址里有短时效签名）。
     * 成功 → info 是 qn=10000 那次请求的结果，qualities 是按「服务端实际档位」去重后的多档；
     * 未开播 / 风控 / 其它失败都返回带原因的 error（错误分支与单档时代保持一致）。
     */
    suspend fun resolve(roomId: String): LiveResolveResult = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet(playInfoUrl(roomId, QN_ORIGINAL))
                ?: return@runCatching LiveResolveResult(error = "获取播放地址失败，请重试")
            val root = json.parseToJsonElement(body).jsonObject
            when {
                root.int("code") == -352 ->
                    return@runCatching LiveResolveResult(error = "B站风控拦截，请稍后重试；登录 B 站账号后更稳定")

                root.int("code") != 0 ->
                    return@runCatching LiveResolveResult(error = "获取播放地址失败，请重试")
            }
            val data = root.obj("data")
                ?: return@runCatching LiveResolveResult(error = "获取播放地址失败，请重试")
            // live_status：1 = 在播，0 = 未开播
            if (data.int("live_status") != 1) {
                return@runCatching LiveResolveResult(error = "主播未开播")
            }
            val playUrl = pickHlsUrl(data)
            if (playUrl.isBlank()) {
                return@runCatching LiveResolveResult(error = "获取播放地址失败，请重试")
            }
            // info 那一档的 label 也要按服务端实际给的档位（匿名时 10000 会被降到 250）
            val infoQn = pickCurrentQn(data) ?: QN_ORIGINAL
            LiveResolveResult(
                info = LivePlayInfo(url = playUrl, isHls = true, referer = REFERER),
                qualities = resolveQualities(roomId, data, infoQn, playUrl)
            )
        }.getOrElse { LiveResolveResult(error = "获取播放地址失败，请重试") }
    }

    // ---------- 清晰度分档 ----------

    /**
     * 多档清晰度：取当前协议（http_hls → ts → codec 优先 avc）的 accept_qn 候选（最多前 4 个，
     * 按数值从大到小），每个候选 qn 各请求一次 getRoomPlayInfo（并发，复用同一个 OkHttpClient）；
     * **任何一档失败只跳过该档，绝不拖垮整体**。
     *
     * label 必须按**响应里的 codec[].current_qn（服务端实际下发的档位）** 决定，不能按请求的 qn：
     * 匿名时服务端把任何 qn 都降级到 250，若按请求 qn 命名就会出现「原画/蓝光/超清/高清」四个
     * 名字其实都是同一档（用户实测反馈「名称不对或者没写全」）。所以再按 current_qn 去重
     * （同一 current_qn 只保留先出现的 = 请求 qn 更大的那档）；匿名时通常只剩「超清」一档，
     * 播放器因此自动不显示画质菜单，这是正确行为；登录后才会真正出现原画 / 蓝光等多档。
     *
     * 实测：avc 的 accept_qn = [10000,400,250,150]；g_qn_desc 是 30000 杜比 / 20000 4K /
     * 15000 2K / 10000 原画 / 400 蓝光 / 250 超清 / 150 高清 / 80 流畅。
     * 返回顺序：info 那一档在前（qn=10000），随后按请求 qn 从大到小。
     */
    private suspend fun resolveQualities(
        roomId: String,
        data: JsonObject,
        infoQn: Int,
        infoUrl: String
    ): List<LiveQuality> = coroutineScope {
        val descMap = gQnDescMap(data)
        // 服务端实际档位 → 地址；先放 info 那一档（qn=10000 的请求刚刚已成功，保证它一定在列表里）
        val byRealQn = LinkedHashMap<Int, String>()
        byRealQn[infoQn] = infoUrl

        val candidates = pickAcceptQn(data).take(4)
        if (candidates.isNotEmpty()) {
            candidates
                .map { qn -> async { fetchQuality(roomId, qn) } }
                .awaitAll()
                .mapNotNull { it }
                .forEach { (realQn, url) ->
                    // 同一 current_qn 只保留先出现的（candidates 已按 qn 从大到小）
                    if (!byRealQn.containsKey(realQn)) byRealQn[realQn] = url
                }
        }
        byRealQn.map { (qn, url) -> LiveQuality(qnLabel(qn, descMap), url, isHls = true) }
    }

    /**
     * getRoomPlayInfo 地址（qn 可换）：protocol=0,1 / format=0,1,2 / codec=0,1 都带上，
     * 服务端才会返回 http_hls 的 ts 流与 accept_qn 列表。
     */
    private fun playInfoUrl(roomId: String, qn: Int): String =
        "$API_BASE/xlive/web-room/v2/index/getRoomPlayInfo" +
            "?room_id=$roomId&protocol=0,1&format=0,1,2&codec=0,1&qn=$qn&platform=web&ptype=8"

    /**
     * 单档 qn 请求一次，返回 (服务端实际档位 current_qn, HLS 地址)；
     * 任何失败（请求失败 / code != 0 / 拿不到地址）返回 null（该档直接跳过）。
     */
    private fun fetchQuality(roomId: String, qn: Int): Pair<Int, String>? = runCatching {
        val body = httpGet(playInfoUrl(roomId, qn)) ?: return@runCatching null
        val root = json.parseToJsonElement(body).jsonObject
        if (root.int("code") != 0) return@runCatching null
        val data = root.obj("data") ?: return@runCatching null
        val url = pickHlsUrl(data).ifBlank { return@runCatching null }
        // 服务端实际下发的档位；字段缺失时退回请求 qn
        (pickCurrentQn(data) ?: qn) to url
    }.getOrNull()

    /**
     * codec[].current_qn：服务端**实际下发**的档位（与 [pickHlsUrl] 走同一条协议/格式/codec 选择路径）。
     * 取不到返回 null，由调用方退回请求的 qn。
     */
    private fun pickCurrentQn(data: JsonObject): Int? {
        val streams = data.obj("playurl_info")?.obj("playurl")?.arr("stream") ?: return null
        val hls = streams.filterIsInstance<JsonObject>()
            .firstOrNull { it.str("protocol_name") == "http_hls" } ?: return null
        val ts = hls.arr("format")?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.str("format_name") == "ts" } ?: return null
        val codecs = ts.arr("codec")?.filterIsInstance<JsonObject>() ?: return null
        val codec = codecs.firstOrNull { it.str("codec_name") == "avc" } ?: codecs.firstOrNull() ?: return null
        return codec.int("current_qn")
    }

    /** 当前协议（http_hls → format ts → codec 优先 avc）的 accept_qn，按数值从大到小 */
    private fun pickAcceptQn(data: JsonObject): List<Int> {
        val streams = data.obj("playurl_info")?.obj("playurl")?.arr("stream") ?: return emptyList()
        val hls = streams.filterIsInstance<JsonObject>()
            .firstOrNull { it.str("protocol_name") == "http_hls" } ?: return emptyList()
        val ts = hls.arr("format")?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.str("format_name") == "ts" } ?: return emptyList()
        val codecs = ts.arr("codec")?.filterIsInstance<JsonObject>() ?: return emptyList()
        val codec = codecs.firstOrNull { it.str("codec_name") == "avc" }
            ?: codecs.firstOrNull() ?: return emptyList()
        return (codec.arr("accept_qn") ?: return emptyList())
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull() }
            .sortedDescending()
    }

    /** g_qn_desc[]（{qn, desc}）→ qn 到中文名的映射 */
    private fun gQnDescMap(data: JsonObject): Map<Int, String> {
        val arr = data.obj("playurl_info")?.obj("playurl")?.arr("g_qn_desc") ?: return emptyMap()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val qn = o.int("qn") ?: return@mapNotNull null
            val desc = o.str("desc")
            if (desc.isBlank()) null else qn to desc
        }.toMap()
    }

    /** label：优先用 g_qn_desc 里的 desc，取不到按 qn 兜底 */
    private fun qnLabel(qn: Int, descMap: Map<Int, String>): String =
        descMap[qn] ?: when (qn) {
            QN_ORIGINAL -> "原画"
            400 -> "蓝光"
            250 -> "超清"
            150 -> "高清"
            80 -> "流畅"
            else -> "qn=$qn"
        }

    /**
     * 从 playurl_info 里挑 HLS 地址（匿名 qn 会被限制在 250，但拿到地址即可播）：
     * protocol_name == "http_hls" → format_name == "ts" → codec[0]（优先 avc）→ url_info[0]，
     * 拼成 {host}{base_url}{extra}。
     */
    private fun pickHlsUrl(data: JsonObject): String {
        val streams = data.obj("playurl_info")?.obj("playurl")?.arr("stream") ?: return ""
        val hls = streams.filterIsInstance<JsonObject>()
            .firstOrNull { it.str("protocol_name") == "http_hls" } ?: return ""
        val formats = hls.arr("format") ?: return ""
        val ts = formats.filterIsInstance<JsonObject>()
            .firstOrNull { it.str("format_name") == "ts" } ?: return ""
        val codecs = ts.arr("codec")?.filterIsInstance<JsonObject>() ?: return ""
        val codec = codecs.firstOrNull { it.str("codec_name") == "avc" } ?: codecs.firstOrNull() ?: return ""
        val baseUrl = codec.str("base_url")
        if (baseUrl.isBlank()) return ""
        val urlInfo = codec.arr("url_info")?.filterIsInstance<JsonObject>()?.firstOrNull() ?: return ""
        val host = urlInfo.str("host")
        if (host.isBlank()) return ""
        return host + baseUrl + urlInfo.str("extra")
    }

    // ---------- 登录态校验 ----------

    /**
     * 服务端校验 B 站登录态（登录页与账号管理共用）。
     * - 明确已登录（code 0 且 data.isLogin = true）→ true
     * - 明确未登录（code -101 或 isLogin = false）→ false
     * - 风控 / 通道不通 / 解析不出来 → null（⚠️ 无法判断：既不当成"已失效"，也不挡住重新登录入口）
     */
    suspend fun sessionOk(): Boolean? = withContext(Dispatchers.IO) {
        if (SettingsStore.biliCookie.isBlank()) return@withContext false
        runCatching {
            val body = httpGet("https://api.bilibili.com/x/web-interface/nav")
                ?: return@runCatching null
            val root = json.parseToJsonElement(body).jsonObject
            val code = root.int("code")
            val isLogin = root.obj("data")?.str("isLogin")
            when {
                code == 0 && isLogin == "true" -> true
                code == -101 -> false
                code == 0 && isLogin == "false" -> false
                else -> null
            }
        }.getOrNull()
    }

    // ---------- 容错解析辅助 ----------

    /** 取子对象：非对象或缺失返回 null */
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    /** 取子数组：非数组或缺失返回 null */
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    /** 字段转字符串（数字/字符串都兼容），缺失或类型不符返回空串 */
    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""

    /** 字段转 Int，失败返回 null */
    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
}
