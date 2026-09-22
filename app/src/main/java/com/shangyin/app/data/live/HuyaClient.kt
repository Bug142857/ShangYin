package com.shangyin.app.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

/**
 * 虎牙直播客户端（www.huya.com）。
 *
 * 实测（2026-09）：
 *  - 分区：`/g` 页面里带 `data-gid` 的元素有 749 个，同一 gid 重复出现，按出现顺序去重即可；
 *    分区 URL 里的英文别名（如 /g/lol）不能当 gid，必须用 data-gid 的数字。
 *  - 房间列表：`/cache.php?m=LiveList&do=getLiveListByPage` 的响应头是 text/html，
 *    但响应体其实是 JSON，所以用 parseToJsonElement 解析，不能依赖 content-type。
 *  - 播放地址：房间页 HTML 的内联 script 里直接含 gameStreamInfoList（FLV 地址 + antiCode），
 *    不用再发 XHR。HLS 直连实测 403，因此只取 FLV（http 直链保持原样）。
 */
object HuyaClient {

    const val PLATFORM = LivePlatforms.HUYA

    private const val REFERER = "https://www.huya.com/"

    /** 桌面 UA */
    internal const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

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
        val html = httpGet("https://www.huya.com/g") ?: return@withContext emptyList()
        runCatching {
            val seen = HashSet<String>()
            val out = ArrayList<LiveCategory>()
            Jsoup.parse(html, "https://www.huya.com/g").select("[data-gid]").forEach { el ->
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
            val url = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage" +
                "&gameId=$categoryId&tagAll=0&page=$page"
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

    /** 解析房间页内联 script 里的 gameStreamInfoList，拼出 FLV 播放地址 */
    suspend fun resolve(roomId: String): LiveResolveResult = withContext(Dispatchers.IO) {
        // httpGet 内部已吞异常：null = 请求失败；非 null 但解析不出配置 = 未开播/结构变化
        val html = httpGet("https://www.huya.com/$roomId")
            ?: return@withContext LiveResolveResult(error = "网络请求失败，请重试")
        val info = parseStream(html)
            ?: return@withContext LiveResolveResult(error = "该房间未开播或页面结构有变，暂时拿不到直播地址")
        LiveResolveResult(info = info)
    }

    /**
     * 从 HTML 里定位 gameStreamInfoList，取其后约 8000 字符窗口，
     * 把窗口里的字面 `\"` 还原为 `"` 后再用正则取各字段。
     */
    private fun parseStream(html: String): LivePlayInfo? {
        val idx = html.indexOf("gameStreamInfoList")
        if (idx < 0) return null
        val end = minOf(html.length, idx + 8000)
        val window = html.substring(idx, end).replace("\\\"", "\"")
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
}
