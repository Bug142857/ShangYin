package com.shangyin.app.data.animeko

import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodEpisode
import com.shangyin.app.data.vod.VodSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * animeko `web-selector` 网页源引擎（open-ani/animeko 的 SelectorMediaSource 等价实现）。
 *
 * 流程：搜索页（CSS/JSONPath 选片名 + 详情页地址）→ 详情页（线路 tab + 各线路剧集）→
 * 剧集页（必要时跟一层"嵌套播放页"）→ 用 `matchVideoUrl` 正则取出 m3u8/mp4。
 *
 * ⚠️ 与 animeko 的差异（本实现刻意保守，避免"静默空列表"）：
 * - 完全走 OkHttp + Jsoup，**不注入 JS**：需要浏览器挑战的站点（如 hanime1 的 403）会测试为「需外网」；
 * - `filterBySubjectName`（按主体名相似度过滤搜索结果）**不启用** —— 它会误杀同名不同季的条目；
 * - `filterByEpisodeSort` 只做「按集数去重 + 排序」，若过滤后为空则**回退为不过滤**（否则整条线路会莫名空掉）；
 * - `requestInterval`（同源请求间隔）不强制（会让长番剧解析慢到不可用），改为限制并发数。
 */
object AnimekoClient {

    /** 内置 animeko 订阅（用户 2026-09-22 提供，即"当时在 animeko 用的"那几份） */
    val BUILTIN_SUBSCRIPTIONS: List<Pair<String, String>> = listOf(
        "MajoSissi 源合集" to
            "https://ghfast.top/raw.githubusercontent.com/MajoSissi/animeko-source/main/dist/all.json",
        "creamycake CSS 源" to "https://sub.creamycake.org/v1/css1.json",
        "creamycake BT 源（不支持）" to "https://sub.creamycake.org/v1/bt1.json"
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 搜索结果条目（网页源只有名字 + 详情页地址，没有海报） */
    data class WebSubject(val name: String, val pageUrl: String)

    /** 线路（animeko 的频道）+ 剧集 */
    data class WebChannel(val name: String, val episodes: List<VodEpisode>)

    /** 导入结果 */
    data class ImportResult(
        val sources: List<VodSource>,
        /** 订阅里被跳过的 BT/磁力源数量（本项目不支持） */
        val skippedBt: Int,
        val error: String?
    )

    /** 已解析出的播放地址缓存（key = 源id|剧集页地址），避免重复解析同一个剧集 */
    private val resolveCache = ConcurrentHashMap<String, String>()

    // ---------- 配置读取 ----------

    fun config(src: VodSource): AnimekoSearchConfig? = runCatching {
        if (src.akConfig.isBlank()) null
        else json.decodeFromString<AnimekoSearchConfig>(src.akConfig)
    }.getOrNull()

    // ---------- 请求 ----------

    /** 返回 (状态码, 正文)；网络异常返回 null */
    private suspend fun httpGet(
        url: String,
        referer: String? = null,
        cookies: String? = null,
        userAgent: String? = null
    ): Pair<Int, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            val b = Request.Builder().url(url)
                .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: VodClient.UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) b.header("Referer", referer)
            if (!cookies.isNullOrBlank()) b.header("Cookie", cookies)
            client.newCall(b.build()).execute().use { resp ->
                resp.code to resp.body?.string()
            }
        }.getOrNull()
    }

    private fun origin(url: String): String = runCatching {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}" + if (u.port > 0) ":${u.port}/" else "/"
    }.getOrDefault(url)

    /** 相对地址转绝对地址 */
    private fun absolute(base: String, link: String): String? {
        if (link.isBlank()) return null
        val l = link.trim()
        if (l.startsWith("http://") || l.startsWith("https://")) return l
        if (l.startsWith("//")) return runCatching { java.net.URI(base).scheme + ":" + l }.getOrNull()
        return runCatching { java.net.URI(base).resolve(l).toString() }.getOrNull()
    }

    // ---------- 关键词处理 ----------

    private fun buildKeyword(cfg: AnimekoSearchConfig, raw: String): String {
        var k = raw.trim()
        if (cfg.searchRemoveSpecial) {
            // 去掉特殊符号（保留中英文数字与空格）
            k = k.filter { it.isLetterOrDigit() || it.isWhitespace() }
        }
        if (cfg.searchUseOnlyFirstWord) {
            k = k.split(' ', '\u3000', '\t').firstOrNull { it.isNotBlank() }.orEmpty()
        }
        return k.ifBlank { raw.trim() }
    }

    // ---------- 搜索 ----------

    /**
     * 搜索：返回 null = 请求失败（网络/HTTP 错误，界面应提示"源失败"而不是"没有"）；
     * 返回空列表 = 站点确实没结果。
     */
    suspend fun search(src: VodSource, keyword: String): List<WebSubject>? {
        val cfg = config(src) ?: return null
        if (cfg.searchUrl.isBlank()) return null
        val kw = buildKeyword(cfg, keyword)
        if (kw.isBlank()) return emptyList()
        val url = cfg.searchUrl.replace("{keyword}", URLEncoder.encode(kw, "UTF-8"))
        val resp = httpGet(url, referer = origin(url)) ?: return null
        val body = resp.second
        if (resp.first !in 200..399 || body.isNullOrBlank()) return null
        return runCatching { parseSubjects(cfg, body, url) }.getOrDefault(emptyList())
    }

    private fun parseSubjects(cfg: AnimekoSearchConfig, body: String, pageUrl: String): List<WebSubject> {
        val out: List<Pair<String, String>> = when (cfg.subjectFormatId) {
            "indexed" -> {
                val sel = cfg.selectorSubjectFormatIndexed
                if (sel == null) emptyList() else {
                    val doc = Jsoup.parse(body, pageUrl)
                    val names = doc.select(sel.selectNames)
                    val links = doc.select(sel.selectLinks)
                    links.mapIndexed { i, el ->
                        val name = names.getOrNull(i)?.let { nameOf(it, sel.preferShorterName) }.orEmpty()
                        name to (el.absUrl("href").ifBlank { el.attr("href") })
                    }
                }
            }

            "json-path-indexed" -> {
                val sel = cfg.selectorSubjectFormatJsonPathIndexed
                if (sel == null) emptyList() else parseJsonPathIndexed(body, sel)
            }

            else -> {
                val sel = cfg.selectorSubjectFormatA
                if (sel == null) emptyList() else {
                    val doc = Jsoup.parse(body, pageUrl)
                    doc.select(sel.selectLists).map { el ->
                        val linkEl = if (el.tagName() == "a") el else (el.selectFirst("a") ?: el)
                        val href = linkEl.absUrl("href").ifBlank { linkEl.attr("href") }
                        nameOf(el, sel.preferShorterName) to href
                    }
                }
            }
        }
        // 去重 + 补全绝对地址 + 丢掉空名/空地址
        return out.mapNotNull { (n, l) ->
            val link = absolute(pageUrl, l) ?: return@mapNotNull null
            val name = n.trim().ifBlank { return@mapNotNull null }
            WebSubject(name, link)
        }.distinctBy { it.pageUrl }
    }

    /** `$[*]['url','link']` 形态：根数组的每个元素里取第一个存在的键 */
    private fun parseJsonPathIndexed(body: String, sel: AnimekoSelectorJsonPath): List<Pair<String, String>> {
        val keys = Regex("'([^']+)'").findAll(sel.selectLinks.removePrefix("\$[*]"))
            .map { it.groupValues[1] }.toList()
        val nameKeys = Regex("'([^']+)'").findAll(sel.selectNames.removePrefix("\$[*]"))
            .map { it.groupValues[1] }.toList()
        val arr = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val link = keys.firstNotNullOfOrNull { k ->
                (obj[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            } ?: return@mapNotNull null
            val name = nameKeys.firstNotNullOfOrNull { k ->
                (obj[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            }.orEmpty()
            name to link
        }
    }

    private fun nameOf(el: Element, preferShorter: Boolean): String {
        val candidates = listOf(el.text(), el.attr("title"), el.attr("alt"))
            .map { it.trim() }.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return ""
        return if (preferShorter) candidates.minByOrNull { it.length } ?: "" else candidates.first()
    }

    // ---------- 详情页 → 线路 + 剧集 ----------

    /** null = 请求失败；空列表 = 页面结构没匹配到剧集 */
    suspend fun channels(src: VodSource, pageUrl: String): List<WebChannel>? {
        val cfg = config(src) ?: return null
        val ua = cfg.matchVideo?.addHeadersToVideo?.userAgent
        val resp = httpGet(pageUrl, referer = origin(pageUrl), userAgent = ua) ?: return null
        val body = resp.second
        if (resp.first !in 200..399 || body.isNullOrBlank()) return null
        return runCatching { parseChannels(cfg, body, pageUrl) }.getOrDefault(emptyList())
    }

    private fun parseChannels(cfg: AnimekoSearchConfig, body: String, pageUrl: String): List<WebChannel> {
        val doc = Jsoup.parse(body, pageUrl)
        return if (cfg.channelFormatId == "index-grouped") {
            val sel = cfg.selectorChannelFormatFlattened ?: return emptyList()
            val names = doc.select(sel.selectChannelNames)
            val lists = doc.select(sel.selectEpisodeLists)
            val out = mutableListOf<WebChannel>()
            for (i in 0 until maxOf(names.size, lists.size)) {
                val rawName = names.getOrNull(i)?.text().orEmpty()
                val chName = matchGroup(rawName, sel.matchChannelName, "ch")
                    ?: rawName.ifBlank { "线路${i + 1}" }
                val container = lists.getOrNull(i) ?: continue
                val eps = episodesOf(container, sel.selectEpisodesFromList, sel.selectEpisodeLinksFromList, pageUrl)
                if (eps.isNotEmpty()) {
                    out.add(WebChannel(chName.trim(), applySortFilter(cfg, eps, sel.matchEpisodeSortFromName)))
                }
            }
            out
        } else {
            val sel = cfg.selectorChannelFormatNoChannel ?: return emptyList()
            val eps = episodesOf(doc, sel.selectEpisodes, sel.selectEpisodeLinks, pageUrl)
            if (eps.isEmpty()) emptyList()
            else listOf(WebChannel("默认线路", applySortFilter(cfg, eps, sel.matchEpisodeSortFromName)))
        }
    }

    private fun matchGroup(raw: String, pattern: String, group: String): String? {
        if (raw.isBlank() || pattern.isBlank()) return null
        return runCatching { Regex(pattern).find(raw)?.groups?.get(group)?.value?.trim() }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun episodesOf(
        root: Element,
        selector: String,
        linkSelector: String,
        pageUrl: String
    ): List<VodEpisode> {
        if (selector.isBlank()) return emptyList()
        return root.select(selector).mapNotNull { el ->
            val linkEl = if (linkSelector.isBlank()) el else (el.selectFirst(linkSelector) ?: el)
            val href = linkEl.absUrl("href").ifBlank { linkEl.attr("href") }
            val url = absolute(pageUrl, href) ?: return@mapNotNull null
            val name = el.text().trim().ifBlank { linkEl.text().trim() }.ifBlank { "第?集" }
            VodEpisode(name = name, url = "", pageUrl = url)
        }.distinctBy { it.pageUrl }
    }

    /** 按集数去重 + 排序；过滤后为空则回退原列表（避免整条线路莫名空掉） */
    private fun applySortFilter(cfg: AnimekoSearchConfig, eps: List<VodEpisode>, pattern: String): List<VodEpisode> {
        if (!cfg.filterByEpisodeSort || pattern.isBlank()) return eps
        val re = runCatching { Regex(pattern) }.getOrNull() ?: return eps
        val scored = eps.mapNotNull { ep ->
            val m = re.find(ep.name) ?: return@mapNotNull null
            val raw = m.groups["ep"]?.value ?: return@mapNotNull null
            ep to Regex("\\d+(\\.\\d+)?").find(raw)?.value?.toDoubleOrNull()
        }
        if (scored.isEmpty()) return eps // 站点命名与配置不符：不筛
        return if (scored.all { it.second != null }) {
            scored.distinctBy { it.second }.sortedBy { it.second }.map { it.first }
        } else {
            scored.distinctBy { it.first.pageUrl }.map { it.first }
        }
    }

    // ---------- 剧集页 → 播放地址 ----------

    /** 解析剧集页得到 m3u8/mp4 地址；失败返回 null（带会话缓存） */
    suspend fun resolveVideo(src: VodSource, episodePageUrl: String): String? {
        val key = "${src.id}|$episodePageUrl"
        resolveCache[key]?.let { return it }
        val cfg = config(src) ?: return null
        val mv = cfg.matchVideo
        val headers = mv?.addHeadersToVideo
        var text = fetchText(episodePageUrl, headers?.referer, mv?.cookies, headers?.userAgent) ?: return null

        // 嵌套播放页（多数站点是 iframe / 解析跳转页）：跟一层
        if (mv != null && mv.enableNestedUrl && mv.matchNestedUrl.isNotBlank()) {
            val nested = findNestedUrl(text, mv.matchNestedUrl)
                ?.let { absolute(episodePageUrl, it.trim('"', '\'', ' ')) }
            if (nested != null && nested != episodePageUrl) {
                val nestedText = fetchText(nested, episodePageUrl, mv.cookies, headers?.userAgent)
                if (!nestedText.isNullOrBlank()) text = text + "\n" + nestedText
            }
        }

        // 地址挑选：先扫候选 URL，再用配置的 matchVideoUrl 优选
        // ⚠️ 不能"拿配置正则去跑整页文本"：实测秋之动漫那条
        //    `(^http(s)?:\/\/(?!.*http(s)?:\/\/).+((\.mp4)|(\.mkv)|(m3u8))...)` 在 300KB 页面上会
        //    灾难性回溯（ReDoS），整页匹配几分钟都出不来。扫候选 URL 是线性的，且等价够用。
        val picked = pickVideoUrl(unescape(text), mv?.matchVideoUrl.orEmpty())
        if (picked != null) resolveCache[key] = picked
        return picked
    }

    /**
     * 从页面文本里挑播放地址：
     * 1. 扫出候选 URL（.m3u8/.mp4/.mkv，以及已知的 CDN 域名）；
     * 2. 有配置 `matchVideoUrl` 时，取第一个命中该正则的候选（正则只跑在短 URL 串上，不会回溯爆炸）；
     * 3. 都没命中就取第一个候选（比返回 null 更符合用户预期 —— 站点里通常只有一条正片地址）。
     */
    private fun pickVideoUrl(text: String, matchVideoUrl: String): String? {
        val candidates = scanCandidates(text)
        if (candidates.isEmpty()) return null
        if (matchVideoUrl.isNotBlank()) {
            runCatching { Regex(matchVideoUrl) }.getOrNull()?.let { re ->
                candidates.firstOrNull { c ->
                    runCatching { re.containsMatchIn(c) }.getOrDefault(false)
                }?.let { return it }
            }
        }
        return candidates.first()
    }

    /** 扫出页面里的媒体候选地址（按出现顺序去重） */
    private fun scanCandidates(text: String): List<String> {
        val found = LinkedHashSet<String>()
        MEDIA_URL_RE.findAll(text).forEach { found.add(it.value.trim('"', '\'', ' ', ',', ';', ')')) }
        CDN_URL_RE.findAll(text).forEach { found.add(it.value.trim('"', '\'', ' ', ',', ';', ')')) }
        return found.filter { it.startsWith("http") }.toList()
    }

    /**
     * 找嵌套播放页地址：**逐行**匹配而不是整页匹配。
     * `matchNestedUrl` 里有的分支是未锚定的 `.+xxx.+`（如 `(.+player.cycanime.com/\?url=.+)`），
     * 在几百 KB 的单行文本上会 O(n²) 回溯；逐行匹配把回溯限制在单行长度内，结果等价（都是找 URL）。
     */
    private fun findNestedUrl(text: String, pattern: String): String? {
        val re = runCatching { Regex(pattern) }.getOrNull() ?: return null
        return unescape(text).lineSequence().firstNotNullOfOrNull { line ->
            runCatching { re.find(line)?.value }.getOrNull()
        }
    }

    private suspend fun fetchText(
        url: String,
        referer: String?,
        cookies: String?,
        userAgent: String?
    ): String? {
        val resp = httpGet(
            url,
            referer = referer?.takeIf { it.isNotBlank() } ?: origin(url),
            cookies = cookies,
            userAgent = userAgent
        ) ?: return null
        return if (resp.first in 200..399) resp.second else null
    }

    private fun unescape(s: String): String =
        s.replace("\\/", "/").replace("&amp;", "&").replace("\\u002F", "/")

    /** 直接以 .m3u8/.mp4/.mkv 结尾（或带查询串）的媒体地址 */
    private val MEDIA_URL_RE =
        Regex("""https?://[^"'\s\\<>]+?\.(?:m3u8|mp4|mkv)[^"'\s\\<>]*""", RegexOption.IGNORE_CASE)

    /** 无扩展名但属于已知视频 CDN 的地址（订阅配置里 matchVideoUrl 会命中这些域） */
    private val CDN_URL_RE =
        Regex("""https?://[^"'\s\\<>]*(?:bilivideo|akamaized)[^"'\s\\<>]*""", RegexOption.IGNORE_CASE)

    /** 视频播放需要的请求头（Referer/UA/Cookie），交给播放器 */
    fun videoHeaders(src: VodSource, episodePageUrl: String): Map<String, String> {
        val cfg = config(src) ?: return emptyMap()
        val mv = cfg.matchVideo ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        mv.addHeadersToVideo?.referer?.takeIf { it.startsWith("http") }?.let { out["Referer"] = it }
        mv.addHeadersToVideo?.userAgent?.takeIf { it.isNotBlank() }?.let { out["User-Agent"] = it }
        mv.cookies?.takeIf { it.isNotBlank() }?.let { out["Cookie"] = it }
        // 站点没写 referer 时补剧集页来源（多数站点按 Referer 防盗链）
        if (!out.containsKey("Referer")) out["Referer"] = origin(episodePageUrl)
        return out
    }

    /**
     * 预解析整条线路：返回 pageUrl → 播放地址（解析失败的条目不在表里）。
     * 预解析而不是"边播边解析"：播放器需要完整播放列表来自动连播，且解析要先于进入播放页。
     * [onProgress] 每解析成功一条回调一次（页面据此显示"正在解析 X/Y"并即时点亮已解析的集）。
     */
    suspend fun resolveAll(
        src: VodSource,
        episodes: List<VodEpisode>,
        concurrency: Int = 4,
        onProgress: ((pageUrl: String, videoUrl: String) -> Unit)? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val out = ConcurrentHashMap<String, String>()
        val sem = Semaphore(concurrency.coerceAtLeast(1))
        coroutineScope {
            episodes.map { ep ->
                launch {
                    sem.withPermit {
                        resolveVideo(src, ep.pageUrl)?.let {
                            out[ep.pageUrl] = it
                            onProgress?.invoke(ep.pageUrl, it)
                        }
                    }
                }
            }.joinAll()
        }
        out.toMap()
    }

    // ---------- 链接测试 ----------

    /** 测试网页源：能搜到结果 = 可用；请求失败 = 需外网/反爬拦直连；能打开但无结果 = 可能结构变化 */
    suspend fun testSource(src: VodSource): VodSource {
        val now = System.currentTimeMillis()
        val cfg = config(src)
        if (cfg == null || cfg.searchUrl.isBlank()) {
            return VodClient.withTestResult(src, "dead", "无法解析源配置", now)
        }
        val result = try {
            search(src, TEST_KEYWORD)
        } catch (e: Exception) {
            val (st, msg) = VodClient.networkErrorResult(e)
            return VodClient.withTestResult(src, st, msg, now)
        }
        return when {
            result == null ->
                VodClient.withTestResult(src, "proxy", "需外网 · 直连失败（或站点反爬拦直连）", now)
            result.isEmpty() ->
                VodClient.withTestResult(src, "dead", "搜索无结果（站点结构可能已变）", now)
            else ->
                VodClient.withTestResult(src, "ok", "可用 · 搜索到 ${result.size} 条", now)
        }
    }

    /** 测试用关键词（中文动漫站基本都有） */
    private const val TEST_KEYWORD = "海贼王"

    // ---------- 导入（animeko 订阅 JSON → 源列表） ----------

    /**
     * 解析 animeko 导出 JSON / 订阅文件。
     * 只收 `web-selector`；`rss`（BT/磁力）跳过并在结果里带上条数说明。
     */
    fun parseImport(text: String): ImportResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ImportResult(emptyList(), 0, "内容为空")

        val entries: List<AnimekoEntry> = runCatching {
            when {
                trimmed.startsWith("[") -> json.decodeFromString<List<AnimekoEntry>>(trimmed)
                trimmed.contains("exportedMediaSourceDataList") ->
                    json.decodeFromString<AnimekoExport>(trimmed).exportedMediaSourceDataList.mediaSources
                trimmed.startsWith("{") -> listOf(json.decodeFromString<AnimekoEntry>(trimmed))
                else -> emptyList()
            }
        }.getOrElse { return ImportResult(emptyList(), 0, "animeko 配置解析失败") }

        if (entries.isEmpty()) return ImportResult(emptyList(), 0, "订阅里没有媒体源")
        val web = entries.filter { it.factoryId == "web-selector" }
        val skipped = entries.size - web.size
        val sources = web.mapNotNull { toVodSource(it) }.distinctBy { it.name }
        if (sources.isEmpty()) {
            return ImportResult(
                emptyList(), skipped,
                if (skipped > 0) "该订阅只有 $skipped 个 BT/磁力源（需 BT 下载引擎，本项目不支持）"
                else "没有可用的网页源（web-selector）"
            )
        }
        return ImportResult(sources, skipped, null)
    }

    /** 单个 animeko 条目 → 本项目源（kind=animeko，配置原文存 akConfig） */
    fun toVodSource(entry: AnimekoEntry): VodSource? {
        val cfg = entry.arguments.searchConfig
        if (cfg.searchUrl.isBlank()) return null
        val name = entry.arguments.name.ifBlank { "animeko 源" }
        return VodSource(
            id = buildSourceId(name),
            name = name,
            baseUrl = origin(cfg.rawBaseUrl.ifBlank { cfg.searchUrl }).trimEnd('/'),
            kind = KIND_ANIMEKO,
            akConfig = json.encodeToString(AnimekoSearchConfig.serializer(), cfg)
        )
    }

    private fun buildSourceId(name: String): String =
        "ak_" + name.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5]"), "").take(10) +
            "_" + (name.hashCode().toLong() and 0x7FFFFFFF)
}

/** 源类型：animeko 网页源（其余视为苹果CMS 采集源） */
const val KIND_ANIMEKO = "animeko"
