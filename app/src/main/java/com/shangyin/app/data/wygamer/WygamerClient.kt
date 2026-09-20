package com.shangyin.app.data.wygamer

import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 列表卡片（游戏/资源条目） */
@kotlinx.serialization.Serializable
data class GameItem(
    val id: String,
    val title: String,
    val url: String,
    val cover: String?,
    val categories: List<String>,
    val badge: String?
)

/** 详情页属性行（游戏大小 / 游戏版本 / 更新日期…） */
data class GameAttr(val key: String, val value: String)

/** 详情页下载按钮（迅雷下载 / 百度网盘…），url 为站内 pay-download 中转链接 */
data class GameDownload(val label: String, val url: String)

/** 正文段落（isHeading=true 为小标题） */
data class GameBlock(val isHeading: Boolean, val text: String)

data class GameDetail(
    val id: String,
    val title: String,
    val cover: String?,
    val categories: List<String>,
    val attrs: List<GameAttr>,
    val downloads: List<GameDownload>,
    val screenshots: List<String>,
    val blocks: List<GameBlock>,
    val unzipPassword: String?
)

data class GameCategory(val name: String, val url: String, val count: Int)

/**
 * 无忧游戏库（www.wygamer.com，WordPress + Zibll 主题）客户端。
 *
 * 为什么抓 HTML 而不是用 WP REST：
 *  - `/wp-json/wp/v2/posts` 的 featured_media 恒为 0、meta 里没有下载信息，
 *    封面和下载框都拿不到，因此列表/详情必须解析 HTML（Jsoup）。
 *  - 分类表例外：`/wp-json/wp/v2/categories` 返回干净且带条数，用它做分类 chips。
 *
 * 实测（2026-09-18）：
 *  - 列表页 `posts.posts-item`，封面 `img[data-src]`（src 是占位图），标题 `h2.item-heading a`
 *  - 翻页：首页 `/page/N`，分类 `/category/{slug}/page/N`，搜索 `/page/N?s=关键词`
 *  - 详情页：`.single-cover img`、`.article-title`、`.pay-attr`、`.article-content`（正文+截图）
 *  - 下载：`a[href*=/pay-download/]` → 跟随 302 跳到网盘分享页（迅雷/百度等，非直链）
 *  - 全站统一解压密码在 `.pay-box` 的 `data-clipboard-text` 里
 *
 * 登录：Zibll 的 user_signin 表单带 slider 滑块验证码，程序化提交不可靠，
 * 因此走 WebView 登录后取 Cookie（WygamerLoginActivity），此处只负责带上 Cookie 请求。
 */
object WygamerClient {

    const val BASE = "https://www.wygamer.com"

    /** 与登录页 WebView 实际发出的 UA 保持一致（取 WebView 真实默认 UA，不伪造） */
    val UA: String
        get() = com.shangyin.app.App.webViewUa.ifBlank { FALLBACK_UA }

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 封面占位图文件名，出现即代表该 img 没有真实图 */
    private const val PLACEHOLDER = "thumbnail-lg.svg"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun newRequest(url: String): Request {
        val cookie = SettingsStore.wygamerCookie
        val b = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", "$BASE/")
        if (cookie.isNotBlank()) b.header("Cookie", cookie)
        return b.build()
    }

    private suspend fun doc(url: String): Document = withContext(Dispatchers.IO) {
        client.newCall(newRequest(url)).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("请求失败 HTTP ${resp.code}")
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) throw Exception("站点返回空内容")
            Jsoup.parse(html, url)
        }
    }

    /** 取 img 的真实地址：data-src 优先（lazyload），src 是占位图时忽略 */
    private fun realImgSrc(img: org.jsoup.nodes.Element?, base: String): String? {
        if (img == null) return null
        val dataSrc = img.attr("data-src").trim()
        if (dataSrc.isNotBlank() && !dataSrc.contains(PLACEHOLDER)) {
            return if (dataSrc.startsWith("http")) dataSrc else "$base$dataSrc"
        }
        val src = img.attr("src").trim()
        if (src.isNotBlank() && !src.contains(PLACEHOLDER)) {
            return if (src.startsWith("http")) src else "$base$src"
        }
        return null
    }

    // ---------------- 列表 ----------------

    private fun parseList(d: Document): List<GameItem> {
        val out = ArrayList<GameItem>()
        for (card in d.select("posts.posts-item")) {
            val link = card.selectFirst("h2.item-heading a[href]")
                ?: card.selectFirst(".item-thumbnail a[href]")
                ?: continue
            val url = link.attr("abs:href")
            val id = url.trimEnd('/').substringAfterLast('/')
            if (id.isBlank()) continue
            val title = card.selectFirst("h2.item-heading a")?.text()?.trim()
                ?: card.selectFirst(".item-thumbnail img")?.attr("alt")?.trim().orEmpty()
            val cover = realImgSrc(card.selectFirst(".item-thumbnail img"), BASE)
            val categories = card.select(".item-tags a.but.c-blue, .item-tags a.but.c-green, .item-tags a.but.c-yellow")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && it != "免费资源" }
            val badge = card.selectFirst(".item-tags a.meta-pay")?.text()?.trim()
                ?: card.selectFirst("badge.img-badge")?.text()?.trim()
            out += GameItem(id, title, url, cover, categories.distinct().take(3), badge)
        }
        return out
    }

    /** 首页最新列表（page 从 1 开始） */
    suspend fun home(page: Int): List<GameItem> =
        parseList(doc(if (page <= 1) "$BASE/" else "$BASE/page/$page"))

    /** 关键词搜索 */
    suspend fun search(keyword: String, page: Int): List<GameItem> {
        val e = URLEncoder.encode(keyword, "UTF-8")
        return parseList(doc(if (page <= 1) "$BASE/?s=$e" else "$BASE/page/$page?s=$e"))
    }

    /** 分类列表（category.url 为分类首页地址） */
    suspend fun category(categoryUrl: String, page: Int): List<GameItem> =
        parseList(doc(if (page <= 1) categoryUrl else "${categoryUrl.trimEnd('/')}/page/$page"))

    /**
     * 分类表：用 WP REST（返回干净、带条数，按条数降序 = 热门分类在前）。
     * 失败时返回空列表（UI 只显示"最新"）。
     */
    suspend fun categories(): List<GameCategory> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE/wp-json/wp/v2/categories?per_page=40&orderby=count&order=desc"
            client.newCall(newRequest(url).newBuilder().header("Accept", "application/json").build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList<GameCategory>()
                    val arr = org.json.JSONArray(resp.body?.string().orEmpty())
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val name = o.optString("name").trim()
                        val slug = o.optString("slug").trim()
                        if (name.isBlank() || slug.isBlank()) return@mapNotNull null
                        GameCategory(name, "$BASE/category/$slug/", o.optInt("count"))
                    }
                }
        }.getOrDefault(emptyList())
    }

    // ---------------- 详情 ----------------

    suspend fun detail(id: String): GameDetail {
        val url = "$BASE/${id.trim().trimStart('/')}"
        val d = doc(url)
        val title = d.selectFirst("h1.article-title")?.text()?.trim()
            ?: d.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        if (title.isBlank()) throw Exception("页面解析失败（可能已被删除）")

        val cover = realImgSrc(d.selectFirst(".single-cover img"), BASE)
            ?: d.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        // 面包屑里的分类（去掉"首页"和"正文"）
        val categories = d.select("ul.breadcrumb li a").map { it.text().trim() }
            .filter { it.isNotBlank() && it != "首页" && it != "正文" }

        val attrs = d.select(".pay-attr > div").mapNotNull { row ->
            val k = row.selectFirst(".attr-key")?.text()?.trim()?.trimEnd('：', ':') ?: return@mapNotNull null
            val v = row.selectFirst(".attr-value")?.text()?.trim().orEmpty()
            if (k.isBlank()) null else GameAttr(k, v)
        }.filter { it.value.isNotBlank() }

        val downloads = d.select(".pay-box a[href*=/pay-download/]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            val label = a.text().trim().ifBlank { "下载" }
            if (href.isBlank()) null else GameDownload(label, href)
        }

        val screenshots = d.select(".article-content .wp-block-image img")
            .mapNotNull { realImgSrc(it, BASE) }
            .distinct()

        val blocks = d.select(".article-content p, .article-content h1, .article-content h2, .article-content h3")
            .mapNotNull { el ->
                val t = el.text().trim()
                if (t.isBlank()) null else GameBlock(el.tagName().startsWith("h"), t)
            }

        val unzipPassword = d.select("[data-clipboard-text]")
            .firstOrNull { it.attr("data-clipboard-tag").contains("密码") }
            ?.attr("data-clipboard-text")?.trim()?.takeIf { it.isNotBlank() }

        return GameDetail(
            id = id, title = title, cover = cover, categories = categories.distinct(),
            attrs = attrs, downloads = downloads.distinctBy { it.url },
            screenshots = screenshots, blocks = blocks, unzipPassword = unzipPassword
        )
    }

    /**
     * 解析站内 pay-download 中转链接 → 网盘分享页地址（实测为 302 跳转，如 pan.xunlei.com）。
     * 若站点改为 HTML 中转，则兜底解析 meta refresh / 脚本跳转。
     */
    suspend fun resolveDownload(payUrl: String): String = withContext(Dispatchers.IO) {
        client.newCall(newRequest(payUrl)).execute().use { resp ->
            if (!resp.isSuccessful && resp.code !in 300..399) throw Exception("解析失败 HTTP ${resp.code}")
            val finalUrl = resp.request.url.toString()
            if (!finalUrl.contains("wygamer.com")) return@use finalUrl
            val html = resp.body?.string().orEmpty()
            val d = Jsoup.parse(html, payUrl)
            val meta = d.selectFirst("meta[http-equiv=refresh]")?.attr("content").orEmpty()
            val fromMeta = Regex("""url=(.+)""", RegexOption.IGNORE_CASE).find(meta)?.groupValues?.get(1)?.trim()
            val fromScript = Regex("""(?:location\.href|location\.replace\()\s*=?\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1)
            (fromMeta ?: fromScript)?.takeIf { it.isNotBlank() }?.let { return@use it }
            throw Exception("未能解析出网盘链接，请用浏览器打开")
        }
    }
}