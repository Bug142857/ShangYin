package com.shangyin.app.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
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

    // ---------- 播放地址 ----------

    /**
     * 解析播放地址，两级策略：
     *  1) 移动端 JSON 接口 mp.huya.com/.../profileRoom：结构稳定，直接取 baseSteamInfoList[0]；
     *  2) 接口拿不到 / 结构对不上时，兜底用桌面 UA 抓房间页 HTML 的内联 gameStreamInfoList。
     *
     * 清晰度**只有一档（超清）**，实测依据：虎牙 HLS 一律 403（带 Referer 也一样，加 ratio 也一样）
     * → 只能用 FLV；FLV 只有 ratio=2000（超清）返回 200，ratio=500/4000/8000 全部 403，
     * 不写 ratio 也 200。profileRoom 里虽有 data.stream.rateArray（蓝光8M 8000 / 蓝光4M 4000 /
     * 超清 2000 / 流畅 500）与 flv.multiLine[]，但只有超清档能播，所以不据此生成多档，只补一项「超清」。
     */
    suspend fun resolve(roomId: String): LiveResolveResult = withContext(Dispatchers.IO) {
        val id = roomId.trim()
        if (id.isEmpty()) return@withContext LiveResolveResult(error = "房间号为空")

        val body = httpGet(URL_PROFILE_ROOM + id)
        if (body != null) {
            // 非 null = 明确结论（成功 / 未开播）；只有 null（结构对不上）才继续走兜底
            runCatching { parseProfileRoom(body) }.getOrNull()
                ?.let { return@withContext it.withFallbackQuality("超清") }
        }

        val html = httpGet(URL_ROOM_PAGE + id)
        val info = html?.let { parseStream(it) }
        return@withContext when {
            info != null -> LiveResolveResult(info = info).withFallbackQuality("超清")
            body == null && html == null -> LiveResolveResult(error = "网络请求失败，请重试")
            else -> LiveResolveResult(error = "该房间未开播或暂时拿不到直播地址")
        }
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
}
