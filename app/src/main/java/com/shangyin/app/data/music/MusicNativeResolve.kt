package com.shangyin.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 播放直链解析：从 24bit 无损（https://www.24bit.net）的歌曲详情页取出真实音频地址。
 *
 * 机制（2026-09-23 curl 实测）：
 * - 详情页 `https://www.24bit.net/music/a/{id}` 是服务端渲染，HTML 的 RSC 数据里内嵌
 *   `itemMusic{url, size, quality, format, lrc}`；
 * - `url` 是网易云 CDN 直链，**带时效签名**（同一个 id 每次打开都不同）→ 只能播放时现取，
 *   配合播放数据源的惰性解析，不会拿到过期链接；
 * - 实测直链 `206 audio/mpeg`，不带 Referer 也能下（站点自己用 referrerpolicy=no-referrer）。
 */
object MusicNativeResolve {

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 不自动跟随跳转：详情页是 HTML，直接拿源码自己解析 */
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * 播放请求头：24bit 的直链来自网易云 CDN，站点自己用 `referrerpolicy=no-referrer`，
     * 实测不带 Referer 也能下，所以只带 UA。
     */
    val PLAY_HEADERS: Map<String, String> = mapOf("User-Agent" to UA)

    /** 解析结果：[url] 非空即成功；否则 [error] 说明为什么给不了直链 */
    data class NativeResult(val url: String? = null, val error: String? = null)

    suspend fun resolve(song: MusicSong): NativeResult = withContext(Dispatchers.IO) {
        runCatching {
            when (song.platform) {
                MusicPlatform.BIT24 -> bit24(song)
            }
        }.getOrElse { NativeResult(error = "直连请求失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * 24bit 无损：详情页是 SSR，HTML 的 RSC 数据里内嵌 `itemMusic{url,...}`。
     * 直链带**时效签名**（同一个 id 每次打开 url 都不同），所以只能播放时现取（正合我们的惰性解析）。
     * 实测：返回的 mp3/flac 直链不带 Referer 也能下（站点自己用 referrerpolicy=no-referrer）。
     */
    private fun bit24(song: MusicSong): NativeResult {
        val request = Request.Builder()
            .url("https://www.24bit.net/music/a/${song.id}")
            .header("User-Agent", UA)
            .header("Referer", "https://www.24bit.net/")
            .build()
        client.newCall(request).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            // RSC 里 JSON 被转义：\"url\":\"https://...\"
            val url = Regex("""\\+"url\\+":\\+"(https?://[^"\\]+)""")
                .find(html)?.groupValues?.getOrNull(1)
            return if (!url.isNullOrBlank()) NativeResult(url = url)
            else NativeResult(error = "24bit 页面里没取到直链（歌曲可能已下架）")
        }
    }
}
