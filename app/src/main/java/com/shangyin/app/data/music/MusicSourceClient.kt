package com.shangyin.app.data.music

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 在线试听音源：网易云音乐公开 Web 接口（手机 UA 可直接调用，无需登录）。
 *
 * 说明：flac.music.hi.cn 等无损站均有 WAF 反爬拦截（程序化请求返回验证页），
 * 因此试听音源用网易云公开接口实现同样的"搜索歌曲 → 在线播放"形态；
 * 部分版权/VIP 歌曲拿不到播放直链（url 为 null），UI 会提示无试听源。
 */
object MusicSourceClient {

    /** 搜索结果里的单曲 */
    data class Song(
        val id: String,
        val name: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** 按关键词搜歌（歌名/歌手/专辑），返回最多 limit 条 */
    fun searchSongs(keyword: String, limit: Int = 30): List<Song> {
        val url = "https://music.163.com/api/search/get/web?s=" +
            java.net.URLEncoder.encode(keyword, "UTF-8") +
            "&type=1&offset=0&limit=$limit"
        val req = Request.Builder().url(url).get()
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val root = org.json.JSONObject(body)
            val songs = root.optJSONObject("result")?.optJSONArray("songs")
                ?: return emptyList()
            val out = mutableListOf<Song>()
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val id = s.optLong("id").takeIf { it > 0 }?.toString() ?: continue
                val name = s.optString("name").trim()
                if (name.isBlank()) continue
                val artistsArr = s.optJSONArray("artists")
                val artistList = mutableListOf<String>()
                if (artistsArr != null) {
                    for (j in 0 until artistsArr.length()) {
                        val a = artistsArr.optJSONObject(j)?.optString("name").orEmpty()
                        if (a.isNotBlank()) artistList.add(a)
                    }
                }
                val album = s.optJSONObject("album")?.optString("name").orEmpty()
                val dur = s.optLong("duration")
                out.add(Song(id, name, artistList.joinToString("/"), album, dur))
            }
            return out
        }
    }

    /** 取播放直链（试听码率）；无版权/VIP 歌返回 null */
    fun songUrl(songId: String): String? {
        val url = "https://music.163.com/api/song/enhance/player/url?id=$songId&br=192000"
        val req = Request.Builder().url(url).get()
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            val data = runCatching {
                org.json.JSONObject(body).optJSONArray("data")?.optJSONObject(0)
            }.getOrNull() ?: return null
            val playUrl = data.optString("url").trim()
            return playUrl.ifBlank { null }
        }
    }
}
