package com.shangyin.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 24bit 无损（https://www.24bit.net）：音乐模块唯一的来源，搜索 / 播放直链 / 歌词全在这里。
 *
 * 接口与路由规则（2026-09-23 curl + 浏览器实测）：
 * - 搜索两个曲库：`POST /api/player/searchOnlineMusicOne`（24bit 曲库）与 `...Two`（16bit 曲库），
 *   JSON 体、值要 URL 编码、`Token` 头留空即可，免登录。两个都要查——只查 One 会漏掉 Two 独有的歌
 *   （例：搜「屋顶」，温岚&周杰伦·爱回温 只在 Two 里）。
 * - 详情页路径是 `/music/{档位}/{id}`，档位必须与来源对应：
 *   One 的 id → `a`(24bit 192kHz) 或 `c`(24bit 96kHz)；Two 的 id → `b`(16bit 44kHz)。
 *   ⚠️ 档位选错拿不到直链（页面里没有 itemMusic），所以 `raw["src"]` 必须跟着歌走。
 * - 详情页是 SSR，HTML 的 RSC 数据里内嵌 `itemMusic{url, cover, quality, format, size, lrc}`；
 *   `url` 是网易云 CDN 直链，**带时效签名**，所以既要现取、也要短时缓存。
 * - ⚠️⚠️ **详情页有每日访问限额**：超限后访问任何详情页都会返回 200 + 约 7~8KB 空壳页，
 *   正文写着「今日访问已达限额，可明日再来。如果您已注册过，可登录后访问」。
 *   这既是我们"取不到直链"的真实原因，也意味着**不能为每首歌随便抓详情页**：
 *   所以这里做了 (1) 详情缓存（含封面/音质/歌词，进 App 后只抓一次）、(2) 限额标记（本次会话不再重试）。
 */
object Bit24 {

    const val SITE = "https://www.24bit.net"

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 播放请求头：直链来自网易云 CDN，站点自己用 referrerpolicy=no-referrer，实测不带 Referer 也能下 */
    val PLAY_HEADERS: Map<String, String> = mapOf("User-Agent" to UA)

    private val headers = mapOf("Referer" to "$SITE/")

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 直链时效：站点每次打开详情页都换一个签名，这里只做短时复用，避免频繁消耗限额 */
    private const val URL_TTL_MS = 15 * 60 * 1000L

    /** 详情缓存：歌曲 id → 详情（封面/音质/格式/歌词 + 取到直链的时间） */
    private val detailCache = ConcurrentHashMap<String, Detail>()

    /** 检测到「今日访问已达限额」的时间戳（0 = 未命中）；命中后本次会话不再抓详情页 */
    private val quotaHitAt = AtomicLong(0L)

    /** 详情页里解析出来的东西 */
    data class Detail(
        val url: String? = null,
        val cover: String = "",
        val quality: String = "",
        val format: String = "",
        val size: String = "",
        val lrc: String = "",
        /** 直链取到的时间（0 = 没有直链） */
        val urlAt: Long = 0L
    ) {
        fun freshUrl(now: Long): String? = url?.takeIf { urlAt > 0 && now - urlAt < URL_TTL_MS }
    }

    /** 详情获取结果：[detail] 非空即成功；否则 [error] 说明原因（可直接给用户看） */
    data class DetailResult(val detail: Detail? = null, val error: String? = null)

    // ==================== 搜索 ====================

    /** 搜两个曲库并合并（One 在前、Two 在后，与站点首页的顺序一致） */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 30): List<MusicSong> {
        val one = searchEndpoint("searchOnlineMusicOne", keyword, page, "one")
        val two = runCatching { searchEndpoint("searchOnlineMusicTwo", keyword, page, "two") }
            .getOrDefault(emptyList())
        val merged = (one + two).distinctBy { it.id }
        if (merged.isEmpty()) throw IllegalStateException("接口返回空")
        return merged.take(limit)
    }

    private suspend fun searchEndpoint(api: String, keyword: String, page: Int, src: String): List<MusicSong> {
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val text = postJson("$SITE/api/player/$api", """{"keyword":"$encoded","page":$page}""")
        val arr: JsonArray = (root(text)["result"] as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { e -> song(e as? JsonObject ?: return@mapNotNull null, src) }
    }

    private fun song(o: JsonObject, src: String): MusicSong? {
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
            cover = "",
            raw = mapOf(
                "id" to id, "name" to name, "player" to player, "album" to album,
                // 来源决定详情页档位（a/c 还是 b），必须跟着歌一起存（收藏进清单也会写进 info）
                "src" to src
            )
        ).let { it.copy(cover = detailCache[id]?.cover.orEmpty()) }
    }

    // ==================== 详情 / 直链 / 歌词 ====================

    /** 取播放直链（命中缓存且未过期就直接返回，省一次限额） */
    suspend fun resolve(song: MusicSong): DetailResult {
        val now = System.currentTimeMillis()
        detailCache[song.id]?.freshUrl(now)?.let { return DetailResult(detail = detailCache[song.id]) }
        val result = ensureDetail(song)
        val detail = result.detail ?: return result
        return if (detail.url.isNullOrBlank()) {
            DetailResult(error = result.error ?: "24bit 没给这首歌直链（可能已下架）")
        } else DetailResult(detail = detail)
    }

    /** 歌词（优先用详情里的 lrc） */
    suspend fun lyric(song: MusicSong): MusicLyric {
        detailCache[song.id]?.let { if (it.lrc.isNotBlank()) return MusicLyric(lrc = it.lrc) }
        val result = ensureDetail(song)
        return MusicLyric(lrc = result.detail?.lrc.orEmpty())
    }

    /** 给列表补封面（只补前 [max] 首、且只补还没缓存的，控制限额消耗） */
    suspend fun fillCovers(songs: List<MusicSong>, max: Int = 8): List<MusicSong> {
        val targets = songs.filter { detailCache[it.id]?.cover.isNullOrBlank() }.take(max)
        if (targets.isEmpty()) return songs
        targets.forEach { ensureDetail(it) }
        if (detailCache.isEmpty()) return songs
        return songs.map { s -> detailCache[s.id]?.cover?.takeIf { it.isNotBlank() }?.let { s.copy(cover = it) } ?: s }
    }

    /** 缓存的详情（UI 想显示音质/格式时用） */
    fun cached(id: String): Detail? = detailCache[id]

    /** 是否已命中每日限额 */
    fun quotaExceeded(): Boolean = quotaHitAt.get() > 0L

    private suspend fun ensureDetail(song: MusicSong): DetailResult = withContext(Dispatchers.IO) {
        if (quotaExceeded()) {
            return@withContext DetailResult(
                error = "24bit 今日访问已达限额（站点限制），明天再来；或在 24bit 官网登录后访问可提升额度"
            )
        }
        val tiers = when (song.raw["src"]) {
            "two" -> listOf("b")
            else -> listOf("a", "c")
        }
        var lastError: String? = null
        for (tier in tiers) {
            // 注意：Kotlin 2.0 下内联 lambda 里不能 continue，所以先 getOrNull 再判空
            val req = runCatching { getText("$SITE/music/$tier/${song.id}") }
            val html = req.getOrNull()
            if (html == null) {
                lastError = "详情页请求失败：${req.exceptionOrNull()?.message ?: "网络异常"}"
                continue
            }
            // 顺序很重要：404（档位不对/已下架）要先于限额判断，否则会被误报成"限额"
            if (html.contains("This page could not be found")) {
                lastError = "24bit 上没有这首歌的「$tier」档位（可能已下架）"
                continue
            }
            if (isQuotaPage(html)) {
                quotaHitAt.set(System.currentTimeMillis())
                return@withContext DetailResult(
                    error = "24bit 今日访问已达限额（站点限制），明天再来；或在 24bit 官网登录后访问可提升额度"
                )
            }
            val detail = parseDetail(html)
            if (detail.url.isNullOrBlank()) {
                lastError = "24bit 页面里没取到直链（可能已下架）"
                continue
            }
            // 与缓存合并：歌词/封面来自哪次抓取都算数
            val merged = (detailCache[song.id] ?: Detail()).let { old ->
                detail.copy(
                    cover = detail.cover.ifBlank { old.cover },
                    lrc = detail.lrc.ifBlank { old.lrc }
                )
            }
            detailCache[song.id] = merged
            return@withContext DetailResult(detail = merged)
        }
        DetailResult(error = lastError ?: "24bit 没给这首歌直链")
    }

    /** 限额页特征：正文有「已达限额」且没有 itemMusic */
    private fun isQuotaPage(html: String): Boolean =
        html.contains("已达限额") || (html.length < 12_000 && !html.contains("itemMusic"))

    private fun parseDetail(html: String): Detail {
        val i = html.indexOf("itemMusic")
        if (i < 0) return Detail()
        // RSC 里 JSON 被 <script> 又转义一层：先还原引号，再按普通 JSON 片段取字段
        val seg = html.substring(i, minOf(html.length, i + 6000)).replace("\\\"", "\"")
        fun field(name: String): String =
            Regex("\"$name\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(seg)?.groupValues?.getOrNull(1).orEmpty()
        fun unescape(s: String): String = s.replace("\\n", "\n").replace("\\/", "/").replace("\\\"", "\"")
        return Detail(
            url = unescape(field("url")).takeIf { it.startsWith("http") },
            cover = unescape(field("cover")),
            quality = unescape(field("quality")),
            format = unescape(field("format")),
            size = unescape(field("size")),
            lrc = unescape(field("lrc")),
            urlAt = System.currentTimeMillis()
        )
    }

    // ==================== 底层网络与 JSON 工具 ====================

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IllegalStateException("响应为空")
        }
    }

    private suspend fun postJson(url: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(url).post(body).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IllegalStateException("响应为空")
        }
    }

    private fun root(text: String): JsonObject =
        runCatching { json.parseToJsonElement(text) as JsonObject }.getOrThrow()

    private fun JsonObject.str(key: String): String =
        ((this[key] as? JsonPrimitive)?.content ?: "").replace("\\n", "\n")
}
