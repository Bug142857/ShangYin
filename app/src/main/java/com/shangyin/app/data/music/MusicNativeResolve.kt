package com.shangyin.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 平台内置直连（不依赖 LX 音源脚本的兜底通道）。
 *
 * 为什么必须要有它：LX 音源脚本只负责"换直链"，而**各家聚合音源的后端服务器极不稳定**
 * （实测 Huibq 的 onrender 返回 503、ikun 域名不可达），一旦后端挂了用户就完全听不了。
 * 平台自己有几条**公开直链**通道，实测可用，作为兜底最稳。
 *
 * 实测结论（2026-09-23，curl 取证）：
 * - 网易云 `outer/url?id={id}.mp3` → 302 跳 `m70x.music.126.net`，带 Referer 下载得到
 *   `200 audio/mpeg`（4 分钟歌约 3.8MB）→ **可用**（VIP/无版权歌不给链接，会走失败分支）
 * - 酷我 `antiserver.kuwo.cn/anti.s` → 直接返回 mp3 直链文本，下载 `206 audio/mpeg` → **可用**
 * - 酷狗 `trackercdn` 需要付费票据（返回 `status=2` 无 url）、咪咕 `listenSong` 参数校验苛刻、
 *   QQ 需要 vkey 签名 → 这三家仍只能靠音源脚本
 */
object MusicNativeResolve {

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 不自动跟随跳转：网易云的 302 Location 就是真实直链，需要自己取出来 */
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 解析结果：[url] 非空即成功；否则 [error] 说明该平台为什么给不了直链 */
    data class NativeResult(val url: String? = null, val error: String? = null)

    suspend fun resolve(song: MusicSong): NativeResult = withContext(Dispatchers.IO) {
        runCatching {
            when (song.platform) {
                MusicPlatform.WY -> wy(song)
                MusicPlatform.KW -> kw(song)
                else -> NativeResult(error = "${song.platform.label}没有内置直连")
            }
        }.getOrElse { NativeResult(error = "内置直连请求失败：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun wy(song: MusicSong): NativeResult {
        val id = song.id
        val request = Request.Builder()
            .url("https://music.163.com/song/media/outer/url?id=$id.mp3")
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .build()
        client.newCall(request).execute().use { resp ->
            val location = resp.header("Location").orEmpty()
            return if (resp.code in 300..399 && location.contains("music.126.net")) {
                NativeResult(url = location)
            } else {
                NativeResult(error = "网易云未提供试听链接（可能需 VIP 或无版权）")
            }
        }
    }

    private fun kw(song: MusicSong): NativeResult {
        val rid = song.raw["rid"]?.takeIf { it.isNotBlank() } ?: song.id
        val request = Request.Builder()
            .url("http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_$rid")
            .header("User-Agent", UA)
            .header("Referer", "http://www.kuwo.cn/")
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty().trim()
            return if (body.startsWith("http")) NativeResult(url = body)
            else NativeResult(error = "酷我未返回直链（可能需 VIP 或歌曲已下架）")
        }
    }
}
