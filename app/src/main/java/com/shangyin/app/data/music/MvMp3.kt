package com.shangyin.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 无名音乐网（http://www.mvmp3.com）——第二音源。
 *
 * 与 [Site33]（33ve.com）**同一套建站系统（ilingku）**，接口形态完全一致：
 * - 搜索：`GET /so.php?wd={关键词}&page={n}`（HTML，服务端渲染，**自带酷狗封面**，不用额外抓）
 * - 直链：`POST /style/js/play.php`，表单 `id={32位hash}&type=dance` → JSON `{url, lrc, pic, name, singer}`
 * - 首页有人机验证墙，但**上面两个接口不需要**（实测无 Cookie 直接可用，须带 Accept/Accept-Language 头）
 *
 * 与 33ve 的差别（实测 2026-09-24）：
 * - 直链来自**酷我 CDN**（car-er.kuwo.cn，https，只需 UA 即可播，实测 206 audio/mpeg）；
 * - 搜索结果每条自带封面（imge.kugou.com）与文件大小；歌词 lrc 首行带站名广告（无碍渲染）。
 */
object MvMp3 {

    const val SITE = "http://www.mvmp3.com"

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 直链是酷我 CDN（https），实测只需 UA */
    val PLAY_HEADERS: Map<String, String> = mapOf("User-Agent" to UA)

    private val pageHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "$SITE/"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val metaCache = ConcurrentHashMap<String, Meta>()

    data class Meta(
        val name: String = "",
        val singer: String = "",
        val pic: String = "",
        val lrc: String = ""
    )

    data class PlayResult(val url: String? = null, val meta: Meta? = null, val error: String? = null)

    fun cachedMeta(id: String): Meta? = metaCache[id]

    // ==================== 搜索 ====================

    /** 搜索歌曲（解析服务端渲染的搜索结果页，结构同 [Site33.search]） */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): List<MusicSong> {
        val url = "$SITE/so.php?wd=" + java.net.URLEncoder.encode(keyword, "UTF-8") + "&page=$page"
        val html = getText(url)
        val songs = Regex("<li>([\\s\\S]*?)</li>").findAll(html).mapNotNull { m -> parseItem(m.groupValues[1]) }
            .take(limit)
            .toList()
        if (songs.isEmpty()) throw IllegalStateException("接口返回空")
        return songs
    }

    private fun parseItem(block: String): MusicSong? {
        val id = Regex("/mp3/([0-9a-f]{32})\\.html").find(block)?.groupValues?.getOrNull(1) ?: return null
        // 搜索结果页自带封面（酷狗图床），不用再调接口补
        val cover = Regex("<img src=\"([^\"]+)\"").find(block)?.groupValues?.getOrNull(1).orEmpty()
        // 标题形如「周杰伦 - <font color='red'>晴天</font>」（关键词高亮，要剥标签）
        val rawTitle = Regex("class=\"url\"[^>]*>([\\s\\S]*?)</a>").find(block)?.groupValues?.getOrNull(1)
            ?: return null
        val title = rawTitle.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()
        if (title.isBlank()) return null
        val artists = title.substringBefore(" - ", "").trim()
        val name = if (artists.isBlank()) title else title.substringAfter(" - ").trim()
        val duration = Regex("stime\"[^>]*>\\s*(\\d{1,2}:\\d{2})").find(block)?.groupValues?.getOrNull(1).orEmpty()
        val cached = metaCache[id]
        return MusicSong(
            platform = MusicPlatform.MVMMP3,
            id = id,
            name = name.ifBlank { title },
            artists = artists,
            album = "",
            cover = cover.ifBlank { cached?.pic.orEmpty() },
            durationMs = parseDuration(duration),
            raw = mapOf("id" to id, "name" to name, "singer" to artists)
        )
    }

    /** "04:29" → 毫秒 */
    private fun parseDuration(text: String): Long {
        val parts = text.split(":")
        if (parts.size != 2) return 0L
        val min = parts[0].toLongOrNull() ?: return 0L
        val sec = parts[1].toLongOrNull() ?: return 0L
        return (min * 60 + sec) * 1000
    }

    // ==================== 直链 / 歌词 ====================

    /** 取播放直链（type=dance；失败时降级试其它 type，与 33ve 同口径） */
    suspend fun resolve(song: MusicSong): PlayResult {
        val attempts = listOf("dance", "music", "mp3")
        var lastError: String? = null
        for (type in attempts) {
            // 注意：不能写成 runCatching{}.getOrElse{ continue }——Kotlin 不允许在内联 lambda 里 continue
            val attempt = runCatching { play(song.id, type) }
            val result = attempt.getOrNull()
            if (result == null) {
                lastError = "取直链失败：${attempt.exceptionOrNull()?.message ?: "网络异常"}"
                continue
            }
            val url = result.url?.takeIf { it.startsWith("http") }
            if (url != null) return PlayResult(url = url, meta = result.meta)
            lastError = if (result.meta?.name.isNullOrBlank()) "站点没有这首歌的可播放资源（可能已下架）" else "站点未返回直链"
        }
        return PlayResult(error = lastError ?: "取直链失败")
    }

    /** 歌词（先用缓存，没有就调一次 play.php 顺手拿到） */
    suspend fun lyric(song: MusicSong): MusicLyric {
        metaCache[song.id]?.let { if (it.lrc.isNotBlank()) return MusicLyric(lrc = it.lrc) }
        val result = runCatching { play(song.id, "dance") }.getOrNull() ?: return MusicLyric()
        return MusicLyric(lrc = result.meta?.lrc.orEmpty())
    }

    /** `POST /style/js/play.php`：返回 url/lrc/pic/name/singer（免验证免 Cookie，必须带 Accept 头） */
    private suspend fun play(id: String, type: String): PlayResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().add("id", id).add("type", type).build()
        val request = Request.Builder()
            .url("$SITE/style/js/play.php")
            .post(body)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "$SITE/")
            .build()
        val text = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return@withContext PlayResult(error = "站点返回异常")
        val meta = Meta(
            name = obj.str("name"),
            singer = obj.str("singer"),
            pic = obj.str("pic"),
            lrc = obj.str("lrc")
        )
        if (meta.lrc.isNotBlank() || meta.pic.isNotBlank()) {
            metaCache[id] = (metaCache[id] ?: Meta()).let { old ->
                meta.copy(
                    name = meta.name.ifBlank { old.name },
                    singer = meta.singer.ifBlank { old.singer },
                    pic = meta.pic.ifBlank { old.pic },
                    lrc = meta.lrc.ifBlank { old.lrc }
                )
            }
        }
        PlayResult(url = obj.str("url").takeIf { it.startsWith("http") }, meta = meta)
    }

    // ==================== 底层 ====================

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        pageHeaders.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            // 站点把请求劫持到人机验证页时长度也够，这里靠关键内容缺失兜底
            if (text.length < 100 || text.contains("verifyForm")) throw IllegalStateException("站点拒绝了请求")
            text
        }
    }

    private fun JsonObject.str(key: String): String =
        ((this[key] as? JsonPrimitive)?.content ?: "").replace("\\n", "\n").replace("\\/", "/")
}
