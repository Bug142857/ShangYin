package com.shangyin.app.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 虎牙直播客户端（www.huya.com）。
 *
 * UA 决定拿到哪套页面（2026-09 实测，这是本文件最关键的一条）：
 *  - 手机 UA 下 `/g` 页面里 `data-gid` 数量为 **0**（分区被前端换成另一套渲染），房间页里也没有
 *    `gameStreamInfoList`；换桌面 UA 后 `data-gid` 有 **757** 个、播放配置才内联在页面里。
 *    所以分区列表、房间列表、HTML 兜底全部必须用桌面 UA。
 *  - 播放地址改走移动端 JSON 接口 mp.huya.com/.../profileRoom：不依赖页面结构，桌面/手机 UA 都能拿。
 *  - 分区 URL 里的英文别名（如 /g/lol）不能当 gid，必须用 data-gid 的数字。
 *  - 房间列表 `cache.php?m=LiveList` 的响应头是 text/html，但响应体其实是 JSON，不能依赖 content-type。
 *  - HLS 直连实测 403，因此只取 FLV（http 直链保持原样，App 已开 cleartext）。
 */
object HuyaClient {

    const val PLATFORM = LivePlatforms.HUYA

    private const val REFERER = "https://www.huya.com/"

    /** 桌面 UA：分区数据与房间页内联配置只在桌面版页面里存在 */
    internal const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val URL_CATEGORY = "https://www.huya.com/g"
    private const val URL_ROOM_LIST = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage"
    private const val URL_PROFILE_ROOM = "https://mp.huya.com/cache.php?m=Live&do=profileRoom&roomid="

    /** 搜索接口（搜索页 JS 自己用的那个；末尾拼关键词，需 URL 编码） */
    private const val URL_SEARCH =
        "https://search.cdn.huya.com/?m=Search&do=getSearchContent&uid=0&v=4&typ=-5&livestate=0&rows=16&q="
    private const val URL_ROOM_PAGE = "https://www.huya.com/"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    // 播放配置字段（窗口内已把字面 \" 还原成 "）
    private val reStreamName = Regex("\"sStreamName\":\"([^\"]+)\"")
    private val reFlvUrl = Regex("\"sFlvUrl\":\"([^\"]+)\"")
    private val reFlvSuffix = Regex("\"sFlvUrlSuffix\":\"([^\"]+)\"")
    private val reFlvAnti = Regex("\"sFlvAntiCode\":\"([^\"]+)\"")

    // ---------- 请求 ----------

    /** 带桌面 UA + Referer 的 GET，失败返回 null（不抛） */
    private fun httpGet(url: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    // ---------- 分区 ----------

    /** 拉取分区表：Jsoup 取 [data-gid]，按出现顺序去重（同 gid 只留第一次） */
    suspend fun categories(): List<LiveCategory> = withContext(Dispatchers.IO) {
        val html = httpGet(URL_CATEGORY) ?: return@withContext emptyList()
        runCatching {
            val seen = HashSet<String>()
            val out = ArrayList<LiveCategory>()
            Jsoup.parse(html, URL_CATEGORY).select("[data-gid]").forEach { el ->
                val gid = el.attr("data-gid").trim()
                val name = el.text().trim()
                if (gid.isEmpty() || name.isEmpty()) return@forEach
                if (seen.add(gid)) out.add(LiveCategory(id = gid, name = name))
            }
            out
        }.getOrDefault(emptyList())
    }

    // ---------- 房间列表 ----------

    /**
     * 拉取分区下的直播间（page 从 1 开始）。
     * 响应体形如 {"status":200,"data":{"datas":[...]}}；无更多数据/解析失败 → emptyList()。
     */
    suspend fun rooms(categoryId: String, page: Int): List<LiveRoom> =
        withContext(Dispatchers.IO) {
            val url = "$URL_ROOM_LIST&gameId=$categoryId&tagAll=0&page=$page"
            // content-type 是 text/html 但 body 是 JSON，直接按 JSON 解析
            val body = httpGet(url) ?: return@withContext emptyList()
            runCatching {
                val datas = json.parseToJsonElement(body)
                    .jsonObject["data"]?.jsonObject
                    ?.get("datas") as? JsonArray
                    ?: return@runCatching emptyList()
                datas.mapNotNull { it as? JsonObject }
                    .map { obj ->
                        val roomName = obj.str("roomName")
                        LiveRoom(
                            platform = PLATFORM,
                            roomId = obj.str("profileRoom"),
                            // roomName 实测可能为空串，退用房间简介
                            title = if (roomName.isNotEmpty()) roomName else obj.str("introduction"),
                            streamer = obj.str("nick"),
                            cover = obj.str("screenshot"),
                            hot = formatLiveHot(obj.str("totalCount")),
                            categoryName = obj.str("gameFullName"),
                            isLive = true
                        )
                    }
                    .filter { it.roomId.isNotEmpty() }
            }.getOrDefault(emptyList())
        }

    // ---------- 搜索 ----------

    /**
     * 搜索直播间。
     *
     * ⚠️ 实测踩坑：搜索页 `search.php?hsk=xx` 的结果是 **JS 渲染**的 —— 裸 HTTP 抓到的 HTML 里只有
     * 「无结果 → 推荐直播」模板（`<script type="text/html" id="js-emptyDom">`），照它解析会拿到**推荐位**而不是搜索结果。
     * 页面 JS 真正请求的是 `search.cdn.huya.com` 的 `getSearchContent`（返回 JSON）：
     *   `{"response":{"1":{"docs":[{...,"game_nick":"主播名","gameLiveOn":true,"room_id":660000,"live_intro":"简介",...}]}}}`
     * 所以直接打这个接口，只取带 `room_id` 的条目（按 room_id 去重），在播的排前面。
     */
    suspend fun search(keyword: String, page: Int = 1): List<LiveRoom> = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        if (kw.isEmpty() || page > 1) return@withContext emptyList()
        val body = httpGet(URL_SEARCH + URLEncoder.encode(kw, "UTF-8"))
            ?: return@withContext emptyList()
        runCatching {
            val response = json.parseToJsonElement(body).jsonObject["response"]?.jsonObject
                ?: return@runCatching emptyList<LiveRoom>()
            val seen = HashSet<String>()
            val out = ArrayList<LiveRoom>()
            response.values.forEach { group ->
                val docs = (group as? JsonObject)?.get("docs") as? JsonArray ?: return@forEach
                docs.forEach { el ->
                    val doc = el as? JsonObject ?: return@forEach
                    val rid = doc.str("room_id")
                    if (rid.isBlank() || !seen.add(rid)) return@forEach
                    out.add(
                        LiveRoom(
                            platform = PLATFORM,
                            roomId = rid,
                            // 房间简介才是"房间名"，取不到退主播名
                            title = doc.str("live_intro").ifBlank { doc.str("game_nick") },
                            streamer = doc.str("game_nick"),
                            cover = doc.str("game_avatarUrl180"),
                            hot = "",
                            categoryName = doc.str("game_name"),
                            isLive = doc["gameLiveOn"]?.jsonPrimitive?.booleanOrNull ?: true
                        )
                    )
                }
            }
            out.sortedByDescending { it.isLive }
        }.getOrDefault(emptyList())
    }

    /** 封面可能是 `//host/…` 协议相对地址，补成 https（否则图片加载不出来） */
    private fun fixCoverUrl(raw: String?): String {
        val u = raw?.trim().orEmpty()
        return if (u.startsWith("//")) "https:$u" else u
    }

    // ---------- 播放地址 ----------

    /**
     * 4 档画质（iBitRate → 兜底中文名），顺序即画质菜单顺序：蓝光8M → 蓝光4M → 超清 → 流畅。
     * profileRoom 的 rateArray 里会给 sDisplayName，取得到就用它（见 [rateLabelMap]）。
     */
    private val RATE_LABELS = linkedMapOf(8000 to "蓝光8M", 4000 to "蓝光4M", 2000 to "超清", 500 to "流畅")

    /** 默认档：超清(2000)——比蓝光省流量、比流畅清晰 */
    private const val DEFAULT_RATE = 2000

    /** 默认档中文名（4 档全失败退回单档时用的 label，与旧版一致） */
    private const val DEFAULT_LABEL = "超清"

    /**
     * 解析播放地址，两级策略：
     *  1) 移动端 JSON 接口 mp.huya.com/.../profileRoom：结构稳定，先拿基础档确认在播，
     *     再按 [RATE_LABELS] 并发补 4 档（见 [resolveQualities]）；
     *  2) 接口拿不到 / 结构对不上时，兜底用桌面 UA 抓房间页 HTML 的内联 gameStreamInfoList（单档）。
     *
     * 实测（2026-09-22，桌面 UA + Referer www.huya.com）：
     *  - HLS 一律 403，所以只用 FLV；
     *  - 直接给播放地址加 `ratio=` 只有 ratio=2000（超清）能 200，ratio=500/4000/8000 全 403；
     *  - 但在 profileRoom 请求上加 `&iBitRate={8000|4000|2000|500}`，服务端会返回对应的
     *    antiCode，用该响应的 sFlvUrl/sStreamName/sFlvAntiCode 拼出的 FLV 地址（**不加 ratio**）
     *    实测可播（HEAD 200 / 同一 room 4 档都能拿到地址），所以多档走 iBitRate 这条路。
     *  - 注意 antiCode 是服务端按 (流, 档位) 短时缓存的，部分房间 rateArray 实测只有
     *    「超清(iBitRate=0)/流畅(500)」两项，因此 label 以 rateArray 为准、取不到才用兜底表。
     */
    suspend fun resolve(roomId: String): LiveResolveResult = withContext(Dispatchers.IO) {
        val id = roomId.trim()
        if (id.isEmpty()) return@withContext LiveResolveResult(error = "房间号为空")

        val body = httpGet(URL_PROFILE_ROOM + id)
        if (body != null) {
            // 非 null = 明确结论（成功 / 未开播）；只有 null（结构对不上）才继续走兜底
            runCatching { parseProfileRoom(body) }.getOrNull()?.let { base ->
                if (base.info != null) {
                    val qualities = resolveQualities(id, body)
                    if (qualities.isNotEmpty()) {
                        // 默认档选「超清」(2000)，它没成功才退列表第一项
                        val def = qualities.firstOrNull { it.first == DEFAULT_RATE }?.second
                            ?: qualities.first().second
                        return@withContext LiveResolveResult(
                            info = LivePlayInfo(url = def.url, isHls = false, referer = REFERER),
                            qualities = qualities.map { it.second }
                        )
                    }
                }
                // 4 档全失败（或未开播）→ 保留原有单档逻辑，错误文案不变
                return@withContext base.withFallbackQuality(DEFAULT_LABEL)
            }
        }

        val html = httpGet(URL_ROOM_PAGE + id)
        val info = html?.let { parseStream(it) }
        return@withContext when {
            info != null -> LiveResolveResult(info = info).withFallbackQuality(DEFAULT_LABEL)
            body == null && html == null -> LiveResolveResult(error = "网络请求失败，请重试")
            else -> LiveResolveResult(error = "该房间未开播或暂时拿不到直播地址")
        }
    }

    /**
     * 并发请求 4 档：在 profileRoom 上追加 `&iBitRate={rate}`，取该响应里的
     * sFlvUrl / sStreamName / sFlvAntiCode 拼 FLV 地址（isHls = false，label 用 rateArray 的
     * sDisplayName，取不到用 [RATE_LABELS] 兜底）。**某一档失败只跳过该档，绝不拖垮整体**；
     * 返回 (iBitRate, LiveQuality)，顺序与 [RATE_LABELS] 一致（即画质菜单顺序）。
     */
    private suspend fun resolveQualities(
        roomId: String,
        profileBody: String
    ): List<Pair<Int, LiveQuality>> = coroutineScope {
        val labels = rateLabelMap(profileBody)
        RATE_LABELS.keys
            .map { rate ->
                async {
                    rate to fetchQualityByRate(roomId, rate, labels[rate] ?: RATE_LABELS[rate].orEmpty())
                }
            }
            .awaitAll()
            .mapNotNull { (rate, q) -> q?.let { rate to it } }
    }

    /** 单档请求：换 iBitRate 拿对应 antiCode 并拼地址；失败 / 结构对不上返回 null（跳过该档） */
    private fun fetchQualityByRate(roomId: String, rate: Int, label: String): LiveQuality? = runCatching {
        val body = httpGet("$URL_PROFILE_ROOM$roomId&iBitRate=$rate") ?: return@runCatching null
        val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
            ?: return@runCatching null
        val info = pickStream(data) ?: return@runCatching null
        LiveQuality(label = label, url = info.url, isHls = false)
    }.getOrNull()

    /**
     * rateArray[] → iBitRate 到中文名的映射。实测 rateArray 同时出现在 `data.stream.rateArray`
     * 与 `data.stream.flv.rateArray`（内容一致），两处都看；同一 iBitRate 只留先出现的。
     * 例：{"sDisplayName":"超清","iBitRate":0,…}, {"sDisplayName":"流畅","iBitRate":500,…}。
     */
    private fun rateLabelMap(profileBody: String): Map<Int, String> {
        val root = runCatching { json.parseToJsonElement(profileBody) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        val stream = (root["data"] as? JsonObject)?.get("stream") as? JsonObject ?: return emptyMap()
        val arrays = listOfNotNull(
            stream["rateArray"] as? JsonArray,
            (stream["flv"] as? JsonObject)?.get("rateArray") as? JsonArray
        )
        val out = LinkedHashMap<Int, String>()
        arrays.forEach { arr ->
            arr.filterIsInstance<JsonObject>().forEach inner@{ o ->
                val rate = o.intOrNull("iBitRate") ?: return@inner
                val name = o.strOrNull("sDisplayName")
                if (name.isNullOrBlank()) return@inner
                if (!out.containsKey(rate)) out[rate] = name
            }
        }
        return out
    }

    /**
     * 解析 mp profileRoom 的 JSON。
     * 返回 null = 结构对不上（交给 HTML 兜底）；非 null = 成功或明确的失败原因。
     */
    private fun parseProfileRoom(body: String): LiveResolveResult? {
        val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
            ?: return null
        // liveStatus = "ON" 才在播；OFF / REPLAY 等一律按未开播处理
        if (data.strOrNull("liveStatus") != "ON") {
            return LiveResolveResult(error = "主播未开播")
        }
        val info = pickStream(data)
            ?: return LiveResolveResult(error = "该房间未开播或暂时拿不到直播地址")
        return LiveResolveResult(info = info)
    }

    /** 取 baseSteamInfoList[0]；没有就退回 data.stream.data[0].gameStreamInfoList[0] */
    private fun pickStream(data: JsonObject): LivePlayInfo? {
        val stream = data["stream"] as? JsonObject ?: return null
        val node = (stream["baseSteamInfoList"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()?.firstOrNull()
            ?: ((stream["data"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull()
                    ?.get("gameStreamInfoList") as? JsonArray)
                ?.filterIsInstance<JsonObject>()?.firstOrNull()
            ?: return null

        val name = node.strOrNull("sStreamName") ?: return null
        val flvUrl = node.strOrNull("sFlvUrl") ?: return null
        val suffix = node.strOrNull("sFlvUrlSuffix").orEmpty().ifBlank { "flv" }
        val anti = node.strOrNull("sFlvAntiCode").orEmpty()
        // 拼法：{sFlvUrl}/{sStreamName}.{sFlvUrlSuffix}?{sFlvAntiCode}，http 保持原样
        val url = buildString {
            append(flvUrl)
            append('/')
            append(name)
            append('.')
            append(suffix)
            if (anti.isNotEmpty()) append('?').append(anti)
        }
        return LivePlayInfo(url = url, isHls = false, referer = REFERER)
    }

    /**
     * 兜底：从房间页 HTML 里定位 gameStreamInfoList，取其后约 8000 字符窗口，
     * 把窗口里的字面 `\"` 还原为 `"`、`\/` 还原为 `/` 后再正则取各字段。
     */
    private fun parseStream(html: String): LivePlayInfo? {
        val idx = html.indexOf("gameStreamInfoList")
        if (idx < 0) return null
        val end = minOf(html.length, idx + 8000)
        val window = html.substring(idx, end).replace("\\\"", "\"").replace("\\/", "/")
        val name = reStreamName.find(window)?.groupValues?.get(1) ?: return null
        val flvUrl = reFlvUrl.find(window)?.groupValues?.get(1) ?: return null
        val suffix = reFlvSuffix.find(window)?.groupValues?.get(1) ?: "flv"
        val anti = reFlvAnti.find(window)?.groupValues?.get(1).orEmpty()
        // 拼法：{sFlvUrl}/{sStreamName}.{sFlvUrlSuffix}?{sFlvAntiCode}
        // 保持 http:// 原样（AndroidManifest 已开启 usesCleartextTraffic；HLS 直连实测 403）
        val url = buildString {
            append(flvUrl)
            append('/')
            append(name)
            append('.')
            append(suffix)
            if (anti.isNotEmpty()) append('?').append(anti)
        }
        return LivePlayInfo(url = url, isHls = false, referer = REFERER)
    }

    /** 取 JSON 字符串字段，缺失/非字符串/JsonNull → 空串 */
    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** 取 JSON 字符串字段；顺便把被转义的 `\/` 还原成 `/`（实测接口把 URL 写成 http:\/\/…） */
    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.replace("\\/", "/")

    /** 取 JSON 整数字段（数字 / 数字字符串都能解析），失败返回 null */
    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
}
