package com.shangyin.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 五大平台的原生数据接口：搜索 / 歌词。
 *
 * 全部接口在 2026-09-23 用 curl 实测通过，接口来源与坑写在对应函数上方；
 * 少数接口确实不可用（需要签名等）的地方，函数返回空并在注释里写明原因。
 *
 * 这里只负责"给数据"：播放直链由 LX 音源脚本解析，所以搜索出来的每首歌
 * 必须把平台自己的 ID 字段塞进 [MusicSong.raw]（脚本里字段名是写死的），
 * 否则脚本取不到 ID 就解析不出版本直链。
 */
object MusicApis {

    /** 手机 UA：各平台对 PC UA 的老接口/防盗链策略不同，统一用移动端 UA 最稳 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** 本文件自带的客户端（不依赖 App 里的私有 client），超时 12s，避免某平台卡死拖慢整个页面 */
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
                MusicPlatform.WY -> wySearch(keyword, page, limit)
                MusicPlatform.TX -> txSearch(keyword, page, limit)
                MusicPlatform.KW -> kwSearch(keyword, page, limit)
                MusicPlatform.KG -> kgSearch(keyword, page, limit)
                MusicPlatform.MG -> mgSearch(keyword, page, limit)
            }
        }

    /** 歌词：拿不到就返回空歌词（不抛异常），避免歌词缺失把播放界面搞崩 */
    suspend fun lyric(song: MusicSong): MusicLyric = try {
        when (song.platform) {
            MusicPlatform.WY -> wyLyric(song.id)
            MusicPlatform.TX -> txLyric(song.id)
            MusicPlatform.KW -> kwLyric(song.id)
            MusicPlatform.KG -> kgLyric(song)
            MusicPlatform.MG -> mgLyric(song)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        MusicLyric()
    }

    // ==================== 网易云 wy ====================
    // 搜索用 /api/cloudsearch/pc（POST 表单）：老接口 /api/search/get/web 不返回封面，这个返回 al.picUrl
    // 注意：这类老 api 必须带 Referer: https://music.163.com/，实测不带会 403

    private val WY_HEADERS = mapOf("Referer" to "https://music.163.com/")

    private suspend fun wySearch(keyword: String, page: Int, limit: Int): List<MusicSong> {
        val text = postForm(
            "https://music.163.com/api/cloudsearch/pc",
            mapOf(
                "s" to keyword,
                "type" to "1",
                "limit" to limit.toString(),
                "offset" to ((page - 1) * limit).toString()
            ),
            WY_HEADERS
        )
        val songs = root(text).obj("result").arr("songs")
        if (songs.isEmpty()) throw IllegalStateException("接口返回空")
        return songs.mapNotNull { e -> wySong(e.asObject(), null) }
    }

    /** 歌词：/api/song/lyric 一次拿原词 + 翻译（tlyric） */
    private suspend fun wyLyric(songId: String): MusicLyric {
        val text = getText(
            url("https://music.163.com/api/song/lyric", "id" to songId, "lv" to "1", "tv" to "1"),
            WY_HEADERS
        )
        val o = root(text)
        return MusicLyric(o.obj("lrc").str("lyric"), o.obj("tlyric").str("lyric"))
    }

    /**
     * 网易云两种返回形态：搜索是"新字段"（ar/al/dt），老歌单接口是"老字段"（artists/album/duration）。
     * 老版没有 albumMid（网易云 web 系接口根本不给专辑 mid），所以只填 albumId。
     */
    private fun wySong(o: JsonObject?, from: String?): MusicSong? {
        val obj = o ?: return null
        val id = obj.str("id")
        if (id.isBlank()) return null
        val album = obj.obj("al") ?: obj.obj("album")
        val artists = obj.arr("ar").ifEmpty { obj.arr("artists") }
            .joinToString("/") { it.asObject().str("name") }
        val name = obj.str("name")
        val pic = https(album.str("picUrl"))
        // dt/ms 都是毫秒
        val duration = obj.long("dt") ?: obj.long("duration") ?: 0L
        return MusicSong(
            platform = MusicPlatform.WY,
            id = id,
            name = name,
            artists = artists,
            album = album.str("name"),
            cover = if (pic.isBlank()) "" else "$pic?param=300y300",
            durationMs = duration,
            from = from.orEmpty(),
            raw = mapOf(
                "source" to "wy",
                "id" to id,
                "songmid" to id,
                "name" to name,
                "singer" to artists,
                "albumName" to album.str("name"),
                "albumId" to album.str("id"),
                "picUrl" to pic,
                "duration" to if (duration > 0) duration.toString() else ""
            )
        )
    }

    // ==================== QQ音乐 tx ====================
    // 搜索：c.y.qq.com/soso/fcgi-bin/client_search_cp（new_json=1 才有 singer/album 结构化字段）
    // 歌词：c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg（nobase64=1 直接返回明文 LRC，必须换 player.html 当 Referer）

    private val TX_HEADERS = mapOf("Referer" to "https://y.qq.com/")
    private val TX_LYRIC_HEADERS = mapOf("Referer" to "https://y.qq.com/portal/player.html")

    private suspend fun txSearch(keyword: String, page: Int, limit: Int): List<MusicSong> {
        val text = getText(
            url(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp",
                "p" to page.toString(),
                "n" to limit.toString(),
                "w" to keyword,
                "format" to "json",
                "new_json" to "1",
                "aggr" to "1",
                "lossless" to "1",
                "platform" to "yqq.json"
            ),
            TX_HEADERS
        )
        val list = root(text).obj("data").obj("song").arr("list")
        if (list.isEmpty()) throw IllegalStateException("接口返回空")
        return list.mapNotNull { e -> txSong(e.asObject(), null) }
    }

    private suspend fun txLyric(songMid: String): MusicLyric {
        val text = getText(
            url(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
                "songmid" to songMid,
                "format" to "json",
                "nobase64" to "1",
                "g_tk" to "5381"
            ),
            TX_LYRIC_HEADERS
        )
        val o = root(text)
        return MusicLyric(o.str("lyric"), o.str("trans"))
    }

    private fun txSong(d: JsonObject?, from: String?): MusicSong? {
        val obj = d ?: return null
        val mid = obj.str("songmid").ifBlank { obj.str("mid") }
        if (mid.isBlank()) return null
        // 搜索走 file.strMediaMid，榜单走 strMediaMid（脚本读 songId 时更可能命中播放资源）
        val mediaMid = obj.obj("file").str("strMediaMid").ifBlank { obj.str("strMediaMid") }
        val album = obj.obj("album")
        val albumMid = obj.str("albummid").ifBlank { album.str("mid") }
        val albumName = obj.str("albumname").ifBlank { album.str("name") }
        val albumId = obj.str("albumid").ifBlank { album.str("id") }
        val artists = obj.arr("singer").joinToString("/") { it.asObject().str("name") }
        val name = obj.str("songname").ifBlank { obj.str("name") }
        // interval 是秒
        val duration = (obj.long("interval") ?: 0L) * 1000
        val pic = if (albumMid.isBlank()) "" else "https://y.qq.com/music/photo_new/T002R300x300M000$albumMid.jpg"
        return MusicSong(
            platform = MusicPlatform.TX,
            id = mid,
            name = name,
            artists = artists,
            album = albumName,
            cover = pic,
            durationMs = duration,
            from = from.orEmpty(),
            raw = mapOf(
                "source" to "tx",
                "songmid" to mid,
                "songId" to mediaMid.ifBlank { mid },
                "albumMid" to albumMid,
                "albumId" to albumId,
                "mid" to mid,
                "media_mid" to mediaMid,
                "name" to name,
                "singer" to artists,
                "albumName" to albumName,
                "picUrl" to pic,
                "duration" to if (duration > 0) duration.toString() else ""
            )
        )
    }

    // ==================== 酷我 kw ====================
    // 搜索：官方 www 接口 /api/www/search/searchMusicBykeyWord 需要 JS 计算的 Secret 头（用官方算法精确复现也返回
    //       "The request is illegal!"），故退回老 PC 接口 search.kuwo.cn/r.s（实测可用）
    //       坑：老接口返回的是单引号 JS 对象字面量，不是合法 JSON，得先替换引号；歌名里还有 &nbsp; 实体
    // 歌词：m.kuwo.cn/newh5/singles/songinfoandlrc（返回逐行 lrclist，需要自己拼成 LRC）

    private val KW_HEADERS = mapOf("Referer" to "https://www.kuwo.cn/")
    private val KW_M_HEADERS = mapOf("Referer" to "https://m.kuwo.cn/")

    private suspend fun kwSearch(keyword: String, page: Int, limit: Int): List<MusicSong> {
        val text = getText(
            url(
                "https://search.kuwo.cn/r.s",
                "all" to keyword,
                "ft" to "music",
                "itemset" to "web_2013",
                "client" to "kt",
                "pn" to (page - 1).toString(),
                "rn" to limit.toString(),
                "rformat" to "json",
                "encoding" to "utf8"
            ),
            KW_HEADERS
        )
        // 单引号 -> 双引号（实测接口把值里的撇号转义成了 &apos;，所以直接替换不会破坏数据）
        val list = root(text.replace("'", "\"")).arr("abslist")
        if (list.isEmpty()) throw IllegalStateException("接口返回空")
        return list.mapNotNull { e -> kwSong(e.asObject(), null) }
    }

    private suspend fun kwLyric(rid: String): MusicLyric {
        val text = getText(
            url("https://m.kuwo.cn/newh5/singles/songinfoandlrc", "musicId" to rid),
            KW_M_HEADERS
        )
        val lines = root(text).obj("data").arr("lrclist")
        if (lines.isEmpty()) return MusicLyric()
        val lrc = lines.joinToString("\n") { e ->
            val o = e.asObject()
            kwLrcTime(o.str("time")) + o.str("lineLyric")
        }
        return MusicLyric(lrc)
    }

    /**
     * 搜索与榜单两种字段名：搜索是 NAME/ARTIST/DC_TARGETID（大写），榜单是小写。
     * DURATION 单位秒；榜单里 duration 可能是试听片段长度，优先用 song_duration。
     */
    private fun kwSong(o: JsonObject?, from: String?): MusicSong? {
        val obj = o ?: return null
        val rid = obj.str("DC_TARGETID").ifBlank { obj.str("id") }
        if (rid.isBlank()) return null
        val musicRid = obj.str("MUSICRID").ifBlank { "MUSIC_$rid" }
        val name = kwText(obj.str("NAME").ifBlank { obj.str("name") })
        val artists = kwText(obj.str("ARTIST").ifBlank { obj.str("artist") })
        val album = kwText(obj.str("ALBUM").ifBlank { obj.str("album") })
        val albumId = obj.str("ALBUMID").ifBlank { obj.str("albumid") }
        // 榜单封面字段是 web_albumpic_short（形如 120/s3s94/93/211513640.jpg），拿到就是现成可用的图
        val picShort = obj.str("web_albumpic_short")
        val pic = if (picShort.isBlank()) "" else "https://img1.kuwo.cn/star/albumcover/$picShort"
        val seconds = obj.str("song_duration").ifBlank { obj.str("DURATION").ifBlank { obj.str("duration") } }
        val duration = (seconds.toLongOrNull() ?: 0L) * 1000
        return MusicSong(
            platform = MusicPlatform.KW,
            id = rid,
            name = name,
            artists = artists,
            album = album,
            cover = pic,
            durationMs = duration,
            from = from.orEmpty(),
            raw = mapOf(
                "source" to "kw",
                "rid" to rid,
                "musicrid" to musicRid,
                "songmid" to rid,
                "albumId" to albumId,
                "name" to name,
                "singer" to artists,
                "albumName" to album,
                "picUrl" to pic,
                "duration" to if (duration > 0) duration.toString() else ""
            )
        )
    }

    /** 酷我歌词返回的是"秒数（字符串）+ 一行文本"，拼成标准 LRC 时间戳 */
    private fun kwLrcTime(seconds: String): String {
        val total = seconds.toDoubleOrNull() ?: 0.0
        val minute = (total / 60).toInt()
        val sec = total - minute * 60
        return String.format(Locale.US, "[%02d:%05.2f]", minute, sec)
    }

    /** 酷我老接口把空格/撇号等转成了 HTML 实体（&nbsp; / &apos;），取值后统一还原 */
    private fun kwText(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    // ==================== 酷狗 kg ====================
    // 搜索：mobilecdn.kugou.com 的 api/v3 接口。⚠️ 该域名证书与主机名不匹配（curl 报 SEC_E_WRONG_PRINCIPAL），
    //       https 会校验失败，只能走 http（App 的 Manifest 已开 usesCleartextTraffic）
    // 歌词：krcs.kugou.com/search 拿 id+accesskey，再 lyrics.kugou.com/download?fmt=lrc 取 base64 明文 LRC
    //       （fmt=krc 是加密二进制，翻译歌词在里面，解析成本高，这里不接，故 translated 恒为空）

    private val KG_HEADERS = mapOf("Referer" to "https://www.kugou.com/")
    private const val KG_API = "http://mobilecdn.kugou.com/api/v3"

    private suspend fun kgSearch(keyword: String, page: Int, limit: Int): List<MusicSong> {
        val text = getText(
            url(
                "$KG_API/search/song",
                "format" to "json",
                "keyword" to keyword,
                "page" to page.toString(),
                "pagesize" to limit.toString(),
                "showtype" to "1"
            ),
            KG_HEADERS
        )
        val list = root(text).obj("data").arr("info")
        if (list.isEmpty()) throw IllegalStateException("接口返回空")
        return list.mapNotNull { e -> kgSong(e.asObject(), null) }
    }

    private suspend fun kgLyric(song: MusicSong): MusicLyric {
        // 酷狗按文件 hash 找歌词；hash 拿不到就没法查
        val hash = song.raw["hash"].orEmpty()
        if (hash.isBlank()) return MusicLyric()
        val search = getText(
            url(
                "https://krcs.kugou.com/search",
                "ver" to "1",
                "man" to "yes",
                "client" to "mobi",
                "keyword" to "${song.name} ${song.artists}".trim(),
                "hash" to hash
            ),
            KG_HEADERS
        )
        val candidate = root(search).arr("candidates").firstOrNull()?.asObject() ?: return MusicLyric()
        val id = candidate.str("id")
        val accessKey = candidate.str("accesskey")
        if (id.isBlank() || accessKey.isBlank()) return MusicLyric()
        val download = getText(
            url(
                "https://lyrics.kugou.com/download",
                "ver" to "1",
                "client" to "pc",
                "id" to id,
                "accesskey" to accessKey,
                "fmt" to "lrc",
                "charset" to "utf8"
            ),
            KG_HEADERS
        )
        val content = root(download).str("content")
        if (content.isBlank()) return MusicLyric()
        // 用 MIME 解码器：base64 里可能夹换行
        val lrc = String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8)
        return MusicLyric(lrc)
    }

    /** 搜索用 singername + union_cover；榜单歌曲用 authors[].author_name + album_sizable_cover */
    private fun kgSong(o: JsonObject?, from: String?): MusicSong? {
        val obj = o ?: return null
        val hash = obj.str("hash")
        if (hash.isBlank()) return null
        val name = obj.str("songname")
        val artists = obj.str("singername").ifBlank {
            obj.arr("authors").joinToString("/") { it.asObject().str("author_name") }
        }
        val albumId = obj.str("album_id")
        val albumAudioId = obj.str("album_audio_id")
        val audioId = obj.str("audio_id")
        val albumName = obj.str("album_name").ifBlank { obj.str("remark") }
        // 搜索结果没有封面字段，只有 trans_param.union_cover（带 {size} 占位符，实测换成 300 可用）
        val pic = obj.obj("trans_param").str("union_cover")
            .ifBlank { obj.str("album_sizable_cover") }
            .replace("{size}", "300")
        // duration 单位秒
        val duration = (obj.long("duration") ?: 0L) * 1000
        return MusicSong(
            platform = MusicPlatform.KG,
            id = hash,
            name = name,
            artists = artists,
            album = albumName,
            cover = https(pic),
            durationMs = duration,
            from = from.orEmpty(),
            raw = mapOf(
                "source" to "kg",
                "hash" to hash,
                "songmid" to hash,
                "album_id" to albumId,
                "album_audio_id" to albumAudioId,
                "audio_id" to audioId,
                "albumId" to albumId,
                "name" to name,
                "singer" to artists,
                "albumName" to albumName,
                "picUrl" to https(pic),
                "duration" to if (duration > 0) duration.toString() else ""
            )
        )
    }

    // ==================== 咪咕 mg ====================
    // 搜索：app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do（返回 copyrightId + contentId + lyricUrl）
    //       ⚠️ pageSize 实测无效（恒定 20 条/页），只有 pageNo 生效，所以这里取一页后再截断到 limit
    // 歌词：搜索接口直接给了 lyricUrl / trcUrl（纯文本 LRC）；raw 里没有歌词地址时，用 contentId 调
    //       MIGUM3.0/resource/song/by-contentids/v2.0 回查 lrcUrl
    // 已废弃：m.music.migu.cn/migu/remoting/* 与 music.migu.cn/v3/api/* 现在一律返回 v5 的 HTML 页面

    private val MG_HEADERS = mapOf("Referer" to "https://music.migu.cn/v5/")
    private const val MG_SEARCH = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do"
    private const val MG_SONG_BY_CONTENT = "https://app.c.nf.migu.cn/MIGUM3.0/resource/song/by-contentids/v2.0"
    private const val MG_SEARCH_SWITCH = "{\"song\":1}"

    private suspend fun mgSearch(keyword: String, page: Int, limit: Int): List<MusicSong> {
        val text = getText(
            url(
                MG_SEARCH,
                "ua" to "Android_migu",
                "version" to "5.0.1",
                "text" to keyword,
                "pageNo" to page.toString(),
                "pageSize" to limit.toString(),
                "searchSwitch" to MG_SEARCH_SWITCH
            ),
            MG_HEADERS
        )
        val list = root(text).obj("songResultData").arr("result")
        if (list.isEmpty()) throw IllegalStateException("接口返回空")
        return list.take(limit).mapNotNull { e -> mgSearchSong(e.asObject()) }
    }

    private suspend fun mgLyric(song: MusicSong): MusicLyric {
        var lrcUrl = song.raw["lrcUrl"].orEmpty()
        val trcUrl = song.raw["trcUrl"].orEmpty()
        // 榜单歌曲的 raw 没有歌词地址，用 contentId 回查一次
        if (lrcUrl.isBlank()) {
            val contentId = song.raw["contentId"].orEmpty()
            if (contentId.isNotBlank()) {
                val detail = getText(url(MG_SONG_BY_CONTENT, "contentId" to contentId), MG_HEADERS)
                lrcUrl = root(detail).arr("data").firstOrNull()?.asObject().str("lrcUrl")
            }
        }
        if (lrcUrl.isBlank()) return MusicLyric()
        val lrc = getText(lrcUrl, MG_HEADERS)
        // 翻译歌词（trcUrl）失败不影响主歌词
        val trc = if (trcUrl.isBlank()) "" else try {
            getText(trcUrl, MG_HEADERS)
        } catch (e: Exception) {
            ""
        }
        return MusicLyric(lrc, trc)
    }

    /** 搜索结果：id 是曲库 songId，copyrightId 才是音源脚本要的 ID */
    private fun mgSearchSong(o: JsonObject?): MusicSong? {
        val obj = o ?: return null
        val copyrightId = obj.str("copyrightId")
        if (copyrightId.isBlank()) return null
        val name = obj.str("name")
        val artists = obj.arr("singers").joinToString("/") { it.asObject().str("name") }
        val album = obj.arr("albums").firstOrNull()?.asObject()
        // imgItems 按尺寸分 01/02/03，取最大的 03（没有就退第一个）
        val items = obj.arr("imgItems")
        val pic = items.firstOrNull { it.asObject().str("imgSizeType") == "03" }?.asObject().str("img")
            .ifBlank { items.firstOrNull()?.asObject().str("img") }
        val lrcUrl = obj.str("lyricUrl")
        return MusicSong(
            platform = MusicPlatform.MG,
            id = copyrightId,
            name = name,
            artists = artists,
            album = album.str("name"),
            cover = https(pic),
            durationMs = 0L, // 搜索接口不返回时长，留 0（不编造）
            raw = mapOf(
                "source" to "mg",
                "copyrightId" to copyrightId,
                "id" to copyrightId,
                "songmid" to copyrightId,
                "contentId" to obj.str("contentId"),
                "songId" to obj.str("id"),
                "albumId" to album.str("id"),
                "name" to name,
                "singer" to artists,
                "albumName" to album.str("name"),
                "picUrl" to https(pic),
                "duration" to "",
                "lrcUrl" to lrcUrl,
                "trcUrl" to obj.str("trcUrl")
            )
        )
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

    private suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val builder = Request.Builder().url(url).post(body).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IllegalStateException("响应为空")
        }
    }

    /** 拼 query（值统一 URL 编码，中文/花括号都能安全带上） */
    private fun url(base: String, vararg params: Pair<String, String>): String =
        base + "?" + params.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, StandardCharsets.UTF_8.name())
        }

    private fun root(text: String): JsonObject =
        json.parseToJsonElement(text) as? JsonObject ?: throw IllegalStateException("返回的不是 JSON 对象")

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key).asObject()

    private fun JsonObject?.arr(key: String): List<JsonElement> = (this?.get(key) as? JsonArray) ?: emptyList()

    /** 取字符串；数字/布尔原样转字符串（各平台有的字段是数字有的带引号），缺失或 null 返回 "" */
    private fun JsonObject?.str(key: String): String = when (val e = this?.get(key)) {
        null, JsonNull -> ""
        is JsonPrimitive -> e.content
        else -> ""
    }

    private fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()

    /** 封面统一走 https（多数平台返回 http 链接） */
    private fun https(url: String): String =
        if (url.startsWith("http://")) "https://" + url.substring(7) else url

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
