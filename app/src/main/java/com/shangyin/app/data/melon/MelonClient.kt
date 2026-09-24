package com.shangyin.app.data.melon

import android.util.Base64
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 列表条目（吃瓜帖子卡片） */
data class MelonPost(
    val id: String,
    val title: String,
    val cover: String?,
    val author: String,
    val date: String,
    val categories: List<String>
)

/** 一页列表：items + 下一页路径（null = 没有下一页；以站点 page-nav 的 next 链接为准） */
data class MelonPage(val items: List<MelonPost>, val nextPath: String?)

/** 正文内容块 */
sealed class MelonBlock {
    data class Text(val text: String) : MelonBlock()
    data class Heading(val text: String) : MelonBlock()
    data class Image(val url: String) : MelonBlock()
    data class Video(val title: String, val url: String) : MelonBlock()
}

/** 详情页 */
data class MelonDetail(
    val id: String,
    val title: String,
    val cover: String?,
    val author: String,
    val date: String,
    val categories: List<String>,
    val blocks: List<MelonBlock>,
    /** 相关推荐（站内其它帖子） */
    val related: List<MelonPost>
)

/** 分类（path 为站点相对路径，如 "category/bljp/"） */
data class MelonCategory(val name: String, val path: String)

/** 会话级缓存：首页「上次看到」续看入口（退出模块再进仍在） */
object MelonCache {
    @Volatile
    var last: MelonPost? = null
}

/**
 * 吃瓜（51爆料）客户端：Jsoup 直接解析镜像站 HTML，全程原生渲染，无 WebView。
 *
 * 站点结构（Mirages 主题 / Typecho，实测 2026-09-24）：
 *  - 列表页：`article` 内 `div.post-card[id^=post-card-]`；**广告是 `article.ad-item` / `#ad-card-*`，
 *    解析时直接跳过 → 广告从源头消失**；封面图在卡片内脚本的
 *    `loadBannerDirect('https://…jpeg', …)` 调用里（img.src 是占位图）
 *  - 翻页：`.page-navigator li.next a` 指向下一页（首页 `/page/N/`、分类 `/category/{slug}/N/`、
 *    搜索 `/search/{kw}/N/`），直接跟随该链接，天然免疫各版块翻页格式差异
 *  - 详情页：`h1.post-title` + `ul.post-meta` + `[itemprop=articleBody]`；
 *    正文图片真实地址在 img 的 `data-xkrkllgl` 等属性里（src 是占位广告图）；
 *    视频是 `div.dplayer[data-config]`，JSON 里 `video.url` 为 HLS m3u8 直链
 *    （auth_key 有时效，必须每次进详情现取现用）；`.txt-apps` 按钮墙、开头 blockquote
 *    （最新地址/APP推广）、底部分类表均为广告/导航，全部剥离
 *
 * 线路：着陆页 `www.ipqegzvg.cc` 是 Base64+document.write 混淆页，解码后含当前线路列表；
 * 镜像域名会不定期轮换，因此启动时「着陆页解码发现 + 逐个探活（页面须含 post-card 特征）」，
 * 选中的镜像持久化（SettingsStore.melonBase）；请求失败自动重新选线重试一次。
 */
object MelonClient {

    /** 着陆页（线路发布页） */
    private const val LANDING = "https://www.ipqegzvg.cc/"

    /** 硬编码兜底线路（着陆页打不开时使用；2026-09-24 实测可用） */
    private val FALLBACK_BASES = listOf(
        "https://branch.upsqllhj.cc",
        "https://abuse.upsqllhj.cc",
        "https://already.upsqllhj.cc",
        "https://d2hriolbecggrv.cloudfront.net"
    )

    /** 站内分类 chips（反差吃瓜真实分类是 /category/fccg/；/51dh.html 是导航页非列表） */
    val CATEGORIES = listOf(
        MelonCategory("最新", ""),
        MelonCategory("学生校园", "category/xsxy/"),
        MelonCategory("明星黑料", "category/mxhl/"),
        MelonCategory("反差吃瓜", "category/fccg/"),
        MelonCategory("必撸精品", "category/bljp/"),
        MelonCategory("每日大赛", "category/mrds/"),
        MelonCategory("网黄专区", "category/whzq/"),
        MelonCategory("吃瓜猎奇", "category/cgxw/"),
        MelonCategory("热门混剪", "category/dyfjx/"),
        MelonCategory("寸止挑战", "category/cztz/"),
        MelonCategory("无码AV", "category/wmav/"),
        MelonCategory("探花精选", "category/thjp/")
    )

    /** 播放器请求头要用，公开 */
    val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 最近一次成功使用的镜像（播放器 Referer 用；详情加载后即为有效值） */
    @Volatile
    var lastBase: String = FALLBACK_BASES.first()
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 真实图片地址特征：绝对 URL + 图片扩展名（占位图 /usr/plugins/ 下的相对路径会被滤掉） */
    private val IMG_URL = Regex("""\.(jpe?g|png|gif|webp)([?#].*)?$""", RegexOption.IGNORE_CASE)

    /** 非正文站点返回的 HTTP 错误（如 404 帖子被删），换线也无法解决，直接抛给 UI */
    private class HttpError(val code: Int) : IOException("HTTP $code")

    @Volatile
    private var cachedBase: String? = null

    // ---------------- 线路管理 ----------------

    /** 当前线路：内存缓存 → 持久化 → 重新发现探活 */
    private suspend fun currentBase(): String {
        cachedBase?.let { return it }
        SettingsStore.melonBase.takeIf { it.isNotBlank() }?.let {
            cachedBase = it
            lastBase = it
            return it
        }
        val base = discoverBase()
        cachedBase = base
        lastBase = base
        SettingsStore.melonBase = base
        return base
    }

    /** 作废当前线路（请求失败时），下次 currentBase() 重新选线 */
    private fun invalidateBase() {
        cachedBase = null
        SettingsStore.melonBase = ""
    }

    /** 着陆页解码发现线路 + 兜底列表，逐个探活，返回第一个真实可用的镜像 */
    private suspend fun discoverBase(): String = withContext(Dispatchers.IO) {
        val candidates = LinkedHashSet<String>()
        runCatching { discoverFromLanding() }.onSuccess { candidates.addAll(it) }
        candidates.addAll(FALLBACK_BASES)
        for (c in candidates) {
            if (probe(c)) return@withContext c
        }
        throw IOException("所有线路均无法访问，请稍后重试")
    }

    /**
     * 解析着陆页：HTML 是 `document.write(Base64.decode('…'))` 混淆，
     * 解码后的内容里才有当前线路列表 → 提取全部 http(s) 域名作为候选。
     */
    private fun discoverFromLanding(): List<String> {
        val html = httpGet(LANDING)
        val blob = Regex("""Base64\.decode\('([A-Za-z0-9+/=]+)'\)""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val decoded = String(Base64.decode(blob, Base64.DEFAULT))
        return Regex("""https?://[A-Za-z0-9.-]+""")
            .findAll(decoded)
            .map { it.value.trimEnd('.') }
            .filter { host ->
                // 排除已知非站点域名：邮箱自动回复 / APP下载 / 官方导航页
                val h = host.removePrefix("https://").removePrefix("http://")
                h.isNotBlank() && !h.contains("51bl") && !h.contains("baoliao") &&
                    !h.contains("szwfphzc") && !h.contains("telegram") && !h.contains("pm.me")
            }
            .distinct()
            .toList()
    }

    /** 探活：首页必须真的渲染出帖子卡片（排除导航页/挑战页） */
    private fun probe(base: String): Boolean = runCatching {
        httpGet("$base/").contains("post-card")
    }.getOrDefault(false)

    // ---------------- HTTP 基础 ----------------

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(resp.code)
            return resp.body?.string().orEmpty()
        }
    }

    /**
     * 拉取并解析 `base + path`：
     *  - 网络异常 / 403 / 5xx → 大概率线路挂了，作废当前镜像重新选线再试一次
     *  - 404 等 → 内容问题，直接抛出（换线没用）
     */
    private suspend fun doc(path: String): Document {
        repeat(2) { attempt ->
            val base = currentBase()
            try {
                // path 通常为站内相对路径；翻页链路里若残留绝对地址（换线场景）也直接可用
                val url = if (path.startsWith("http")) path else "$base$path"
                val html = httpGet(url)
                if (html.isBlank()) throw IOException("站点返回空内容")
                return Jsoup.parse(html, url)
            } catch (e: HttpError) {
                if (e.code != 403 && e.code !in 500..599) throw e
                if (attempt == 1) throw e
            } catch (e: Exception) {
                if (attempt == 1) throw e
            }
            invalidateBase()
        }
        throw IOException("加载失败")
    }

    // ---------------- 列表 ----------------

    private fun parseList(d: Document): MelonPage {
        // base 以本次响应的实际地址为准（请求内部可能已自动换线）
        val base = d.baseUri().trimEnd('/')
        val items = ArrayList<MelonPost>()
        for (art in d.select("article")) {
            if (art.hasClass("ad-item")) continue // 广告条目：整条跳过
            val card = art.selectFirst("div.post-card[id^=post-card-]") ?: continue
            val id = card.id().removePrefix("post-card-")
            val title = card.selectFirst("h2.post-card-title")?.text()?.trim().orEmpty()
            if (id.isBlank() || title.isBlank()) continue
            // 封面在卡片内联脚本的 loadBannerDirect('…') 里
            val cover = Regex("""loadBannerDirect\('([^']+)'""").find(card.html())
                ?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
            val info = card.selectFirst(".post-card-info")
            val author = info?.selectFirst("span[itemprop=author]")?.text()
                ?.trim()?.trimEnd('•', ' ')?.trim().orEmpty()
            val date = info?.selectFirst("span[itemprop=datePublished]")?.text()
                ?.trim()?.trimEnd('•', ' ')?.trim().orEmpty()
            val cats = info?.select("span")
                ?.lastOrNull { !it.hasAttr("itemprop") }?.text()
                ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
                .orEmpty()
            items += MelonPost(id, title, cover, author, date, cats)
        }
        // 下一页：跟随站点自己的 next 链接（转成相对路径，换线后仍走统一的线路管理）
        val next = d.selectFirst(".page-navigator li.next a[href]")?.attr("abs:href")
            ?.takeIf { it.isNotBlank() }
            ?.let { it.substringAfter(base, it).ifBlank { null } }
        return MelonPage(items.distinctBy { it.id }, next)
    }

    /** 加载任意列表页（firstPath 为首页/分类/搜索的第一页路径；nextPath 直接透传翻页） */
    private suspend fun list(firstPath: String, nextPath: String? = null): MelonPage =
        parseList(doc(nextPath ?: firstPath))

    /** 最新列表第一页 */
    suspend fun home(): MelonPage = list("/")

    /** 分类列表第一页（category.path，如 "category/bljp/"） */
    suspend fun category(path: String): MelonPage = list("/$path")

    /** 搜索第一页 */
    suspend fun search(keyword: String): MelonPage =
        list("/search/${URLEncoder.encode(keyword, "UTF-8")}/")

    /** 翻页：nextPath 是上一页 page-nav 给出的相对路径 */
    suspend fun nextPage(nextPath: String): MelonPage = list("", nextPath)

    // ---------------- 详情 ----------------

    suspend fun detail(id: String): MelonDetail {
        val base = currentBase()
        val d = doc("/archives/${id.trim()}/")
        val title = d.selectFirst("h1.post-title")?.text()?.trim().orEmpty()
        if (title.isBlank()) throw Exception("帖子不存在或已被删除")
        val cover = d.selectFirst("meta[itemprop=image]")?.attr("content")?.takeIf { it.startsWith("http") }
        val meta = d.selectFirst("ul.post-meta")
        val author = meta?.select("li a")?.firstOrNull()?.text()?.trim().orEmpty()
        val date = meta?.select("time")?.firstOrNull()?.text()?.trim().orEmpty()
        val cats = meta?.select("li a")?.drop(1)?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }.orEmpty()

        val body = d.selectFirst("[itemprop=articleBody]")
            ?: throw Exception("正文解析失败")

        val blocks = ArrayList<MelonBlock>()
        for (el in body.children()) {
            when {
                // 跳过：广告按钮墙 / 站点导航表 / 内联脚本 / 锚点占位 / 横幅广告容器
                el.hasClass("txt-apps") || el.tagName() == "table" ||
                    el.tagName() == "script" || el.tagName() == "blockquote" ||
                    el.hasClass("horizontal-banner") -> continue
                // 视频播放器：data-config JSON 里的 video.url（HLS m3u8）
                el.hasClass("dplayer") -> {
                    parseVideo(el)?.let { blocks += it }
                }
                el.tagName() == "p" -> {
                    // 段内图片（懒加载：真实地址在 data-* 属性里）
                    val imgs = el.select("img").mapNotNull { realImg(it) }
                    blocks += imgs.map { MelonBlock.Image(it) }
                    val text = el.ownText().trim()
                    if (text.isNotBlank()) blocks += MelonBlock.Text(text)
                }
                el.tagName() in setOf("h1", "h2", "h3", "h4", "h5") -> {
                    val text = el.text().trim()
                    if (text.isNotBlank()) blocks += MelonBlock.Heading(text)
                }
            }
        }

        // 相关推荐：正文里的站内文章直链按钮
        val related = body.select("a[href*=archives]")
            .mapNotNull { a ->
                val href = a.attr("abs:href")
                val rid = Regex("""/archives/(\d+)/""").find(href)?.groupValues?.get(1)
                val rtitle = a.text().trim()
                if (rid.isNullOrBlank() || rtitle.isBlank() || rid == id) null
                else MelonPost(rid, rtitle, null, "", "", emptyList())
            }
            .distinctBy { it.id }
            .take(6)

        return MelonDetail(id, title, cover, author, date, cats, blocks, related)
    }

    /** 从 dplayer 的 data-config 里取 HLS 直链（video.url 优先，其次 video_h265.url） */
    private fun parseVideo(el: Element): MelonBlock? {
        val cfg = el.attr("data-config").takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val json = JSONObject(cfg)
            val url = json.optJSONObject("video")?.optString("url")?.takeIf { it.startsWith("http") }
                ?: json.optJSONObject("video_h265")?.optString("url")?.takeIf { it.startsWith("http") }
            url?.let { MelonBlock.Video(el.attr("data-video_title").trim().ifBlank { "视频" }, it) }
        }.getOrNull()
    }

    /** 图片真实地址：任意属性值是带图片扩展名的绝对 URL 即可（src 是占位图不可信） */
    private fun realImg(img: Element): String? {
        for (attr in img.attributes()) {
            val v = attr.value.trim()
            if (v.startsWith("http") && IMG_URL.containsMatchIn(v) && !v.contains("/usr/plugins/")) {
                return v
            }
        }
        return null
    }
}
