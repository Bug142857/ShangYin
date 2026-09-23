package com.shangyin.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 只提供 24bit 无损（www.24bit.net）的搜索/歌词。
 *
 * 接口在 2026-09-23 用 curl 实测通过，接口来源与坑写在对应函数上方；
 * 播放直链由 [MusicNativeResolve] 从详情页现取（带时效签名，不能在这里缓存）。
 */
object MusicApis {

    /** 手机 UA：站点对 PC UA 的老接口/防盗链策略不同，统一用移动端 UA 最稳 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 本文件自带的客户端（不依赖 App 里的私有 client），超时 12s，避免接口卡死拖慢整个页面 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ==================== 对外 API ====================

    /** 搜索歌曲；失败直接抛异常（消息可直接展示给用户） */
    suspend fun search(platform: MusicPlatform, keyword: String, page: Int = 1, limit: Int = 30): List<MusicSong> =
        guard(platform, "搜索") {
            when (platform) {
                MusicPlatform.BIT24 -> bit24Search(keyword, page, limit)
                else -> throw IllegalStateException("${platform.label} 已不再支持搜索")
            }
        }

    /** 歌词：拿不到就返回空歌词（不抛异常），避免歌词缺失把播放界面搞崩 */
    suspend fun lyric(song: MusicSong): MusicLyric = try {
        when (song.platform) {
            MusicPlatform.BIT24 -> bit24Lyric(song.id)
            else -> MusicLyric()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        MusicLyric()
    }

    // ==================== 24bit 无损（https://www.24bit.net） ====================
    // 接口实测（2026-09-23，curl 免登录 200）：
    // - 搜索：POST /api/player/searchOnlineMusicOne，JSON 体且值要 URL 编码（{"keyword":"%E6%99%B4%E5%A4%A9","page":1}）
    //   ⚠️ 只有 searchOnlineMusicOne 的 id 能在详情页对上歌；searchOnlineMusicTwo 的 id 会串成**别的歌**（已弃用，
    //      "放错歌"比"搜不到"更糟，宁可结果少也不串）。
    // - 详情页 /music/a/{id} 是 SSR，HTML 的 RSC 数据里内嵌
    //   itemMusic{url(带时效签名的网易云 CDN 直链), size, quality, format, lrc(歌词)} —— 直链与歌词都从这来。

    private val BIT24_HEADERS = mapOf("Referer" to "https://www.24bit.net/")

    private suspend fun bit24Search(keyword: String, page: Int, limit: Int): List<MusicSong> {
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val body = """{"keyword":"$encoded","page":$page}"""
        val text = postJson("https://www.24bit.net/api/player/searchOnlineMusicOne", body, BIT24_HEADERS)
        val arr = root(text).arr("result")
        if (arr.isEmpty()) throw IllegalStateException("接口返回空")
        return arr.mapNotNull { e -> bit24Song(e.asObject()) }.take(limit)
    }

    /** 歌词：详情页的 itemMusic.lrc（24bit 自带歌词，比外部歌词接口更贴合它自己的曲库） */
    private suspend fun bit24Lyric(songId: String): MusicLyric {
        val html = getText("https://www.24bit.net/music/a/$songId", BIT24_HEADERS)
        return MusicLyric(lrc = bit24Lrc(html))
    }

    private fun bit24Song(o: JsonObject?): MusicSong? {
        val id = o.str("id").takeIf { it.isNotBlank() } ?: return null
        val name = o.str("name").takeIf { it.isNotBlank() } ?: return null
        val player = o.str("player")
        val album = o.str("album")
        return MusicSong(
            platform = MusicPlatform.BIT24,
            id = id,
            name = name,
            artists = player,
            album = album,
            cover = o.str("cover"),
            raw = mapOf("id" to id, "name" to name, "player" to player, "album" to album)
        )
    }

    /** 从 SSR 的 RSC 数据里取出 itemMusic.lrc（JSON 在 <script> 里又被 JS 转义了一层，反斜杠数量不定） */
    private fun bit24Lrc(html: String): String {
        val raw = Regex("""\\+"lrc\\+":\\+"(.*?)\\+"\s*[},]""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.getOrNull(1) ?: return ""
        // 反斜杠层数不固定（实测是两层），统一按"任意层"还原
        return raw.replace(Regex("\\\\+n"), "\n")
            .replace(Regex("\\\\+\""), "\"")
            .replace(Regex("\\\\+/"), "/")
    }

    // ==================== 底层网络与 JSON 工具 ====================

    private suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("User-Agent", UA)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.string() ?: throw IllegalStateException("响应为空")
            }
        }

    /** POST JSON 体（24bit 的接口要求 Content-Type: application/json，值内部自行 URL 编码） */
    private suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(url).post(body).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IllegalStateException("响应为空")
        }
    }

    private fun root(text: String): JsonObject =
        json.parseToJsonElement(text) as? JsonObject ?: throw IllegalStateException("返回的不是 JSON 对象")

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject?.arr(key: String): List<JsonElement> = (this?.get(key) as? JsonArray) ?: emptyList()

    /** 取字符串；数字/布尔原样转字符串（站点有的字段是数字有的带引号），缺失或 null 返回 "" */
    private fun JsonObject?.str(key: String): String = when (val e = this?.get(key)) {
        null, JsonNull -> ""
        is JsonPrimitive -> e.content
        else -> ""
    }

    /** 把底层异常（超时/HTTP 码/JSON 解析失败）包成能直接展示给用户的提示 */
    private inline fun <T> guard(platform: MusicPlatform, what: String, block: () -> T): T =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("${platform.label}${what}失败：${e.message ?: e.javaClass.simpleName}")
        }
}
