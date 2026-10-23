package com.shangyin.app.data.psite

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * P站（里世界第十入口）：pornhub.com 原生解析，无 WebView。
 *
 * 站点无公开 API，走网页解析（2026-10-21 实测，桌面/移动 UA 同构）：
 *  - 列表：GET /video?o={sort}&c={categoryId}&page=N；搜索：/video/search?search=kw&page=N
 *    （实测搜索接口不接受 o= 参数，会 404 —— 搜索态忽略排序档位）
 *      → <li data-video-vkey="..."> 每项含 img.js-videoThumb(src=签名缩略图 alt=标题)、
 *        .duration .time(时长)、.underThumb .title(标题)、.uploaderLink(频道)、.views(观看文本)
 *      → 分页直接 ?page=N，总数未知（页码条走「未知末页」模式）
 *  - 详情：GET /view_video.php?viewkey=... → flashvars 里内嵌 mediaDefinitions JSON 数组
 *      → 各清晰度 HLS master.m3u8（em-h.phncdn.com）+ 一个签名 get_media 中转 mp4；
 *        取最高码率 HLS 交给 ExoPlayer（站内 HLS 直连可播，实测无需 Referer，UA 即可）
 *
 * 列表/详情都需要年龄验证 cookie（age_verified 等），缺失时部分地区会跳验证页。
 * 站点对部分 IP 段有地区封锁，需外网环境（与番号/本子模块一致）。
 */
object PsiteClient {

    const val BASE = "https://www.pornhub.com"

    /** PlayerSession.sourceName 标识 */
    const val SOURCE_NAME = "P站"

    /** 播放器/下载请求头要用，公开 */
    const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 年龄验证 cookie（部分地区无此 cookie 会出验证页而不是视频列表） */
    const val COOKIE = "accessAgeDisclaimerPH=1; age_verified=1; accessPH=1"

    /**
     * 排序档位：code 直接作 o= 参数；"" = 站点首页默认（Recently Featured，即「最新」）；
     * "recommended" 走站点 /recommended 路径（v0.303）
     */
    val SORTS = listOf(
        "推荐" to "recommended",
        "最新" to "",
        "最热" to "ht",
        "人气" to "mv",
        "高分" to "tr"
    )

    /** 默认排序：推荐（/recommended） */
    const val DEFAULT_SORT = "recommended"

    // ---------------- 数据模型 ----------------

    /** 列表条目（vkey = 视频唯一码，详情/播放/进度 key 都用它） */
    data class PVideo(
        val vkey: String,
        val title: String,
        val thumbnail: String?,
        val durationSec: Int,
        val views: String,      // 站点原样文本，如 "9.2K Views"
        val uploader: String    // 频道/作者名，可能为空
    )

    /** 解析出的播放地址（HLS master.m3u8 或直链 mp4） */
    data class PMedia(
        val url: String,
        val quality: String,    // "720"/"1080"/"best"…
        val isHls: Boolean
    )

    /** 分类（c= 数字 id 为站点现行参数；label 为中文显示名） */
    data class PCategory(val id: Int, val label: String)

    // ---------------- 分类表（2026-10-21 自 /categories 实抓 id→名称，人工译名） ----------------

    val CATEGORIES: List<PCategory> = listOf(
        PCategory(3, "业余"),
        PCategory(138, "认证素人"),
        PCategory(1, "亚洲"),
        PCategory(103, "韩国"),
        PCategory(101, "印度"),
        PCategory(98, "阿拉伯"),
        PCategory(102, "巴西"),
        PCategory(26, "拉丁"),
        PCategory(99, "俄罗斯"),
        PCategory(96, "英国"),
        PCategory(100, "捷克"),
        PCategory(94, "法国"),
        PCategory(95, "德国"),
        PCategory(97, "意大利"),
        PCategory(4, "美臀"),
        PCategory(8, "美乳"),
        PCategory(59, "小胸"),
        PCategory(7, "巨根"),
        PCategory(9, "金发"),
        PCategory(11, "深发"),
        PCategory(42, "红发"),
        PCategory(13, "口交"),
        PCategory(20, "手交"),
        PCategory(131, "舔舐"),
        PCategory(15, "中出"),
        PCategory(16, "颜射"),
        PCategory(14, "爆射"),
        PCategory(69, "潮吹"),
        PCategory(80, "群交"),
        PCategory(2, "狂欢"),
        PCategory(72, "双管齐下"),
        PCategory(21, "硬核"),
        PCategory(67, "激烈"),
        PCategory(41, "第一视角"),
        PCategory(22, "自慰"),
        PCategory(502, "女性高潮"),
        PCategory(592, "指法"),
        PCategory(23, "玩具"),
        PCategory(10, "捆绑"),
        PCategory(19, "拳交"),
        PCategory(542, "绑带"),
        PCategory(18, "恋物"),
        PCategory(93, "玉足"),
        PCategory(211, "圣水"),
        PCategory(78, "按摩"),
        PCategory(24, "户外"),
        PCategory(31, "真实"),
        PCategory(90, "试镜"),
        PCategory(88, "校园"),
        PCategory(89, "保姆"),
        PCategory(444, "家庭幻想"),
        PCategory(181, "老少配"),
        PCategory(242, "绿帽"),
        PCategory(25, "跨种族"),
        PCategory(76, "双性男"),
        PCategory(92, "男Solo"),
        PCategory(492, "女Solo"),
        PCategory(61, "直播"),
        PCategory(86, "动漫"),
        PCategory(241, "角色扮演"),
        PCategory(81, "剧情"),
        PCategory(33, "脱衣舞"),
        PCategory(57, "合集"),
        PCategory(522, "浪漫"),
        PCategory(562, "纹身"),
        PCategory(6, "大码"),
        PCategory(32, "搞笑")
    )

    // ---------------- 列表 ----------------

    /**
     * 拉取视频列表。
     * @param sortCode SORTS 的 code（"" = 首页默认）
     * @param catId 分类 id；0 = 全部
     * @param query 关键词；非空走搜索接口（分类/排序仍可叠加）
     */
    suspend fun list(
        sortCode: String,
        catId: Int,
        query: String,
        page: Int
    ): List<PVideo> = withContext(Dispatchers.IO) {
        val p = page.coerceAtLeast(1)
        // 实测搜索接口不接受 o= 参数（404 Page Not Found），搜索态忽略排序档位
        val path: String = if (query.isNotBlank()) {
            "$BASE/video/search?search=${URLEncoder.encode(query.trim(), "UTF-8")}&page=$p"
        } else if (sortCode == "recommended") {
            // v0.303：推荐 = 站点 /recommended 页
            buildString {
                append("$BASE/recommended?")
                val q = ArrayList<String>()
                if (catId > 0) q.add("c=$catId")
                q.add("page=$p")
                append(q.joinToString("&"))
            }
        } else {
            buildString {
                append("$BASE/video?")
                val q = ArrayList<String>()
                if (sortCode.isNotBlank()) q.add("o=$sortCode")
                if (catId > 0) q.add("c=$catId")
                q.add("page=$p")
                append(q.joinToString("&"))
            }
        }
        parseList(httpGet(path))
    }

    /** 从列表页 HTML 解析视频条目（列表/搜索同构标记） */
    internal fun parseList(html: String): List<PVideo> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<PVideo>()
        val seen = HashSet<String>()
        for (li in doc.select("li[data-video-vkey]")) {
            val vkey = li.attr("data-video-vkey").trim()
            if (vkey.isEmpty() || !seen.add(vkey)) continue
            val img = li.selectFirst("img.js-videoThumb") ?: li.selectFirst("img.videoThumb") ?: continue
            val thumb = img.attr("src").takeIf { it.startsWith("http") }
                ?: img.attr("data-path").takeIf { it.startsWith("http") }
                ?: continue
            val title = li.selectFirst(".underThumb .title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: img.attr("alt").trim().takeIf { it.isNotBlank() }
                ?: continue
            val durText = li.selectFirst(".duration .time")?.text()?.trim().orEmpty()
            // 列表页是 .views（"9.2K Views"），搜索页是 .videoViews（"801"）
            val viewsText = li.selectFirst(".views")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: li.selectFirst(".videoViews")?.text()?.trim().orEmpty()
            out += PVideo(
                vkey = vkey,
                title = title,
                thumbnail = thumb,
                durationSec = parseDuration(durText),
                views = viewsText,
                uploader = li.selectFirst(".uploaderLink")?.text()?.trim().orEmpty()
            )
        }
        return out
    }

    // ---------------- 播放地址 ----------------

    /**
     * 解析视频页 → 可播地址。
     * 站点把 mediaDefinitions 直接内嵌在 flashvars JS 对象里：
     * "mediaDefinitions":[{format:"hls",quality:"720",videoUrl:"..."},...]
     * 优先取最高数值清晰度的 HLS；没有 HLS 再退任何 mp4 直链。
     *
     * 手机端常见失败态（列表能过但视频页被拦）都做了显式识别，报错直接说人话：
     * 年龄门（部分地区 EU 强制验证）/ 地区屏蔽（VPN 出口被拉黑）/ 视频删除 / Premium。
     */
    suspend fun resolve(vkey: String): PMedia = withContext(Dispatchers.IO) {
        val html = try {
            httpGet("$BASE/view_video.php?viewkey=${URLEncoder.encode(vkey, "UTF-8")}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("视频页请求失败：${e.message?.takeIf { it.isNotBlank() } ?: "网络异常"}（需外网，可换节点重试）", e)
        }
        val pageTitle = html.substringAfter("<title>", "").substringBefore("</title>")
            .substringBefore("<").trim()

        // 主路径：mediaDefinitions JSON 数组（括号配对扫描，避免截断转义）。
        // 先提取、失败后才做拦截态分类——正常页的 JS 模板里也混有 "age-verification"、
        // "has been removed" 等字样（实测 3/90 处），先检查标记会把每个正常视频误判成被拦。
        val marker = "mediaDefinitions"
        val i = html.indexOf(marker)
        if (i >= 0) {
            var start = -1
            for (k in i + marker.length until minOf(i + marker.length + 20, html.length)) {
                if (html[k] == '[') { start = k; break }
            }
            if (start >= 0) {
                var depth = 0
                var end = -1
                var inStr = false
                var esc = false
                for (k in start until html.length) {
                    val ch = html[k]
                    if (esc) { esc = false; continue }
                    when {
                        ch == '\\' && inStr -> esc = true
                        ch == '"' -> inStr = !inStr
                        inStr -> {}
                        ch == '[' -> depth++
                        ch == ']' -> { depth--; if (depth == 0) { end = k; break } }
                    }
                }
                if (end > 0) {
                    runCatching {
                        val arr = org.json.JSONArray(html.substring(start, end + 1))
                        data class Raw(val format: String, val quality: String, val url: String)
                        val defs = ArrayList<Raw>()
                        for (x in 0 until arr.length()) {
                            val o = arr.optJSONObject(x) ?: continue
                            val u = o.optString("videoUrl").takeIf { it.startsWith("http") } ?: continue
                            defs += Raw(o.optString("format"), o.optString("quality"), u)
                        }
                        defs
                    }.getOrNull()?.let { defs ->
                        // HLS 按数值清晰度降序取第一个
                        defs.filter { it.format.equals("hls", true) }
                            .maxByOrNull { it.quality.toIntOrNull() ?: -1 }
                            ?.let { return@withContext PMedia(it.url, it.quality, true) }
                        // 退 mp4 直链
                        defs.firstOrNull { !it.format.equals("hls", true) }
                            ?.let { return@withContext PMedia(it.url, "直链", false) }
                    }
                }
            }
        }
        // 兜底：页面变体/结构变化时全页正则扫 phncdn 的 m3u8（flashvars 里 \/ 转义先还原）
        val unescaped = html.replace("\\/", "/")
        val m3u8 = Regex("https://[a-z0-9-]+\\.phncdn\\.com/[^\"'\\s<>]+?master\\.m3u8[^\"'\\s<>]*")
            .findAll(unescaped).firstOrNull()?.value
        if (m3u8 != null) return@withContext PMedia(m3u8, "720", true)

        // 提取全失败 → 按标题/特征分类拦截态（此时才可信：真拦截页没有 mediaDefinitions）
        val blockedMsg = when {
            pageTitle.contains("age verification", true) -> "该地区需年龄验证，换个节点（美/亚出口）重试"
            pageTitle.contains("page not found", true) -> "视频不存在（404）"
            pageTitle.contains("unavailable", true) -> "视频在当前节点地区被屏蔽，换个节点重试"
            html.contains("unavailable in your country", true) -> "视频在当前节点地区被屏蔽，换个节点重试"
            else -> "页面无播放数据（可能被地区屏蔽，title=$pageTitle）"
        }
        throw IOException(blockedMsg)
    }

    // ---------------- HTTP ----------------

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", COOKIE)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    // ---------------- 工具 ----------------

    /** "10:00" / "1:05:30" → 秒 */
    fun parseDuration(text: String): Int {
        val parts = text.trim().split(":").map { it.trim().toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    /** 秒 → mm:ss / h:mm:ss */
    fun formatDuration(sec: Int): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
