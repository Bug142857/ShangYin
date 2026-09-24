package com.shangyin.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * gdstudio 聚合音乐接口（https://music-api.gdstudio.xyz/api.php）——JOOX / 网易云两个来源都走它。
 *
 * 接口形态（全部 GET，参数都在 query 上）：
 * - 搜索：`types=search&source={joox|netease}&name={关键词}&count={n}&pages={p}` → JSON 数组
 * - 直链：`types=url&source=&id=&br=320` → `{url, br, size}`
 * - 歌词：`types=lyric&source=&id=` → `{lyric, tlyric}`
 * - 封面：`types=pic&source=&id=&size=300` → `{url}`（数组/对象两种形态都遇到过，这里都兼容）
 *
 * ⚠️ **JOOX 直链是「时有时无」，不是拿不到**（2026-09-24 反复实测修正过一版错误结论）：
 * - 同一首《晴天》第一次测拿到 `206 audio/mpeg`（ID3 头，11MB，仅需 UA 就能播），几分钟后连测 8 次全空；
 * - 一批 8 首不同歌一次一测：3 首有、5 首空（空的偏热门原唱/大厂版权：陈奕迅、周杰伦、Beyond、王菲）；
 * - 上一批里《小城故事》3/3 有、《Shape of You》2/3 有、《Hello》3/3 有。
 * 结论：上游对**部分曲目/时段**不给流（版权或额度），单次失败不能当成"这首歌没有"。
 * 所以 [resolveUrl] 对 JOOX 做**多轮重试**（换码率 + 短退避），并把 33ve 兜底留在
 * [MusicRepo.resolvePlay]；`source=netease` 一直稳定可用（320k 实测可下）。
 */
object GdStudio {

    private const val BASE = "https://music-api.gdstudio.xyz/api.php"

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** gdstudio 返回的直链指向各家 CDN，实测只需 UA */
    val PLAY_HEADERS: Map<String, String> = mapOf("User-Agent" to UA)

    private val headers = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json",
        "Referer" to "https://music.gdstudio.xyz/"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 封面只在 pic 接口里给，逐首去取太慢：只为前若干首并发补一次（失败就留空） */
    private const val COVER_LIMIT = 12

    /** 补封面这步是可选的，超时就放弃，不让搜索卡住 */
    private const val COVER_TIMEOUT_MS = 8000L

    /** 取直链的重试轮数（JOOX 时有时无，见类注释）与尝试的码率 */
    private const val MAX_URL_ROUNDS = 3
    private val URL_BITRATES = listOf(320, 128)

    // ==================== 搜索 ====================

    /** 搜索（来源是 gdstudio 支持的平台；接口没有数据时按"空结果"抛异常，与 [Site33.search] 同口径） */
    suspend fun search(
        platform: MusicPlatform,
        keyword: String,
        page: Int = 1,
        limit: Int = 30
    ): List<MusicSong> {
        val text = get(
            mapOf(
                "types" to "search",
                "source" to platform.key,
                "name" to keyword,
                "count" to "$limit",
                "pages" to "$page"
            )
        )
        val arr = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
        val songs = arr.orEmpty().mapNotNull { parseSong(platform, it as? JsonObject ?: return@mapNotNull null) }
        if (songs.isEmpty()) throw IllegalStateException("接口返回空")
        return withTimeoutOrNull(COVER_TIMEOUT_MS) { fillCovers(platform, songs) } ?: songs
    }

    private fun parseSong(platform: MusicPlatform, o: JsonObject): MusicSong? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return null
        // artist 是数组（少数情况下是字符串），统一拼成"歌手1 / 歌手2"
        val artists = when (val a = o["artist"]) {
            is JsonArray -> a.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString(" / ")
            else -> a?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        return MusicSong(
            platform = platform,
            id = id,
            name = name,
            artists = artists,
            album = o["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            raw = mapOf("id" to id, "name" to name, "singer" to artists)
        )
    }

    /** 并发给前 [COVER_LIMIT] 首补封面；单首失败留空，不影响其它 */
    private suspend fun fillCovers(platform: MusicPlatform, songs: List<MusicSong>): List<MusicSong> =
        coroutineScope {
            val covers = songs.take(COVER_LIMIT)
                .map { s -> async { runCatching { pic(platform, s.id) }.getOrNull() } }
                .awaitAll()
            songs.mapIndexed { i, s ->
                val url = covers.getOrNull(i)
                if (url.isNullOrBlank()) s else s.copy(cover = url)
            }
        }

    private suspend fun pic(platform: MusicPlatform, id: String): String? {
        val text = get(
            mapOf("types" to "pic", "source" to platform.key, "id" to id, "size" to "300")
        )
        val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val obj = when (el) {
            is JsonArray -> el.firstOrNull() as? JsonObject
            is JsonObject -> el
            else -> null
        } ?: return null
        return obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
    }

    // ==================== 直链 / 歌词 ====================

    /**
     * 取播放直链；拿不到返回 null。
     *
     * ⚠️ JOOX 的直链**时有时无**（见类注释）：单次空返回只代表"这一刻上游没给"，不代表这首歌没有，
     * 所以这里做 3 轮 × 2 种码率的重试（轮间短退避）。netease 基本第一轮第一个码率就命中，
     * 多出来的轮次不会真的发出去。
     */
    suspend fun resolveUrl(platform: MusicPlatform, id: String): String? {
        repeat(MAX_URL_ROUNDS) { round ->
            for (br in URL_BITRATES) {
                val text = runCatching {
                    get(mapOf("types" to "url", "source" to platform.key, "id" to id, "br" to "$br"))
                }.getOrNull() ?: continue
                val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                if (!url.isNullOrBlank() && url.startsWith("http")) return url
            }
            if (round < MAX_URL_ROUNDS - 1) delay(400L * (round + 1))
        }
        return null
    }

    /** 歌词（歌词接口不给时返回空歌词，不抛异常——歌词缺失不该挡住播放） */
    suspend fun lyric(platform: MusicPlatform, id: String): MusicLyric {
        val text = runCatching {
            get(mapOf("types" to "lyric", "source" to platform.key, "id" to id))
        }.getOrNull() ?: return MusicLyric()
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return MusicLyric()
        return MusicLyric(
            lrc = obj["lyric"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            translated = obj["tlyric"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    // ==================== 底层 ====================

    private suspend fun get(params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") {
            "${it.key}=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val builder = Request.Builder().url("$BASE?$query")
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }
}
