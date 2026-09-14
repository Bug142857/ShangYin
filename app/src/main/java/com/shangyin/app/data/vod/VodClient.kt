package com.shangyin.app.data.vod

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 苹果CMS V10 采集站客户端。
 * - 搜索：GET {base}?ac=videolist&wd=关键词（大多直接带详情+剧集）
 * - 详情：GET {base}?ac=videolist&ids=vod_id（vod_play_url 为空时补拉）
 * - 剧集：vod_play_url 按 $$$ 分组、# 分集、第一个 $ 切名称/URL
 */
object VodClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // ---------- URL 构造 ----------

    /** 修正 baseUrl：去尾斜杠、XML 源替换为 JSON 源 */
    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/').let {
        if (it.endsWith("/at/xml")) it.removeSuffix("/at/xml") + "/api.php/provide/vod" else it
    }

    /** base 已含 ? 用 & 拼，否则用 ? */
    private fun buildUrl(base: String, params: String): String =
        normalizeBaseUrl(base) + (if (base.contains('?')) "&" else "?") + params

    // ---------- 请求 ----------

    private fun httpGet(url: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    // ---------- 搜索 ----------

    /**
     * 多源并行搜索，单源失败/慢不影响其他源（OkHttp 自带 5s 连接 / 10s 读取超时）。
     * 返回按匹配分排序后的条目（带 source 归属）。
     */
    suspend fun searchAll(
        sources: List<VodSource>,
        keyword: String,
        keywordYear: String = ""
    ): List<Pair<VodSource, List<VodItem>>> = coroutineScope {
        val kw = keyword.trim()
        if (kw.isEmpty() || sources.isEmpty()) return@coroutineScope emptyList()
        sources.filter { it.enabled }.map { src ->
            async(Dispatchers.IO) {
                val items = searchSource(src, kw)
                val scored = items
                    .map { it to matchScore(it.vod_name, kw, it.vod_year, keywordYear) }
                    .filter { it.second >= 2 }
                    .sortedByDescending { it.second }
                    .map { it.first }
                src to scored
            }
        }.awaitAll()
    }

    /** 单源搜索；code!=1 / 返回 HTML / 解析失败 → 空列表（公开供 UI 渐进式展示） */
    suspend fun searchSource(src: VodSource, wd: String): List<VodItem> = withContext(Dispatchers.IO) {
        val body = httpGet(buildUrl(src.baseUrl, "ac=videolist&wd=" + URLEncoder.encode(wd, "UTF-8")))
            ?: return@withContext emptyList()
        val resp = parseResp(body) ?: return@withContext emptyList()
        if (resp.code != 1) return@withContext emptyList()
        // 个别源搜索结果不带剧集，逐条补拉详情（最多前5条，避免慢）
        resp.list.take(5).map { item ->
            if (item.vod_play_url.isBlank()) fetchDetail(src, item.vod_id) else item
        }
    }

    /** 按 ids 拉详情 */
    private suspend fun fetchDetail(src: VodSource, vodId: Long): VodItem = withContext(Dispatchers.IO) {
        val body = httpGet(buildUrl(src.baseUrl, "ac=videolist&ids=$vodId"))
            ?: return@withContext VodItem(vod_id = vodId)
        val resp = parseResp(body) ?: return@withContext VodItem(vod_id = vodId)
        resp.list.firstOrNull() ?: VodItem(vod_id = vodId)
    }

    // ---------- 链接测试 ----------

    /**
     * 测试单个采集源是否可用，返回带结果的新 VodSource。
     * 结果分类：
     * - ok：HTTP 200 且返回合法 JSON、code=1，总量>0 → "可用 · 共 N 部"；量=0 也判可用（"可用 · 0 部"）
     * - proxy：连接超时/被拒（可解析但连不上）→ 需外网或已墙，提示红色
     * - dead：解析失败 / HTTP 非 200 / 返回 HTML（域名在但没有正确接口，可能改版/失效）→ 已失效
     */
    suspend fun testSource(src: VodSource): VodSource = withContext(Dispatchers.IO) {
        val url = buildUrl(src.baseUrl, "ac=videolist&wd=" + URLEncoder.encode("a", "UTF-8") + "&pg=1")
        val now = System.currentTimeMillis()
        val base = src.copy(testAt = now)
        val result = runCatching {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", UA).build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@use "dead" to "请求失败 HTTP ${resp.code}"
                val body = resp.body?.string() ?: return@use "dead" to "返回为空"
                if (!body.trimStart().startsWith("{")) return@use "dead" to "返回异常（非 JSON）"
                val r = json.decodeFromString<VodResp>(body)
                if (r.code != 1) return@use "dead" to "接口错误：${r.msg?.take(40) ?: "code=${r.code}"}"
                "ok" to "可用 · 共 ${r.total} 部"
            }
        }.getOrElse { e ->
            val msg = e.message.orEmpty()
            when {
                msg.contains("timeout", true) ||
                    msg.contains("connect", true) ||
                    msg.contains("refused", true) ||
                    msg.contains("unreachable", true) ||
                    msg.contains("failed to connect", true) ->
                    "proxy" to "需外网 · 直连超时/被拒"
                else -> "dead" to "已失效 · 连接失败"
            }
        }
        base.copy(testStatus = result.first, testMsg = result.second)
    }

    private fun parseResp(body: String): VodResp? = runCatching {
        // 有的源会返回 HTML（被墙/域名失效），快速识别直接放弃
        if (!body.trimStart().startsWith("{")) return@runCatching null
        json.decodeFromString<VodResp>(body)
    }.getOrNull()

    // ---------- 剧集解析 ----------

    /**
     * 解析 vod_play_from / vod_play_url 为播放组列表。
     * 格式：组1$$$组2；每组内 "第01集$url#第02集$url"，名称与 URL 用第一个 $ 切（URL 内可能含 $）。
     */
    fun parsePlayGroups(from: String, playUrl: String): List<VodPlayGroup> {
        if (playUrl.isBlank()) return emptyList()
        val groupNames = from.split("$$$").map { it.trim() }
        val groupBodies = playUrl.split("$$$")
        val groups = mutableListOf<VodPlayGroup>()
        groupBodies.forEachIndexed { gi, body ->
            val episodes = body.split('#').mapNotNull { ep ->
                val s = ep.trim()
                if (s.isBlank()) return@mapNotNull null
                val idx = s.indexOf('$')
                if (idx <= 0) return@mapNotNull null
                val name = s.substring(0, idx).trim()
                val url = s.substring(idx + 1).trim()
                if (url.isBlank() || !url.startsWith("http")) return@mapNotNull null
                VodEpisode(name, url)
            }
            if (episodes.isNotEmpty()) {
                val gName = groupNames.getOrNull(gi)?.takeIf { it.isNotBlank() } ?: "线路${gi + 1}"
                groups.add(VodPlayGroup(gName, episodes))
            }
        }
        return groups
    }

    // ---------- 标题匹配 ----------

    /** 归一化：去空白/标点、小写 */
    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 匹配评分：精确相等=3，互相包含=2，年份相同+1。
     * <2 分的结果不展示（避免搜"流浪地球"出来一堆无关片）。
     */
    fun matchScore(name: String, keyword: String, itemYear: String, keywordYear: String): Int {
        val a = normalize(name)
        val b = normalize(keyword)
        if (a.isEmpty() || b.isEmpty()) return 0
        var score = when {
            a == b -> 3
            a.contains(b) || b.contains(a) -> 2
            else -> 0
        }
        if (score > 0 && itemYear.isNotBlank() && keywordYear.isNotBlank() &&
            itemYear.take(4) == keywordYear.take(4)
        ) score += 1
        return score
    }

    // ---------- 订阅导入 ----------

    /**
     * 解析订阅内容：支持 KVideo 订阅 JSON 数组、单对象、或一行一个 URL 的纯文本。
     * 返回 (成功列表, 失败原因)；host 去重由调用方处理。
     */
    fun parseImport(text: String): Pair<List<VodSource>, String?> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList<VodSource>() to "内容为空"
        // JSON 数组 / 单对象（KVideo 订阅格式：id/name/baseUrl）
        if (trimmed.startsWith("[")) {
            return runCatching {
                json.decodeFromString<List<VodSourceSub>>(trimmed)
            }.getOrElse { return emptyList<VodSource>() to "JSON 解析失败" }
                .map { it.toVodSource() } to null
        }
        if (trimmed.startsWith("{")) {
            return runCatching {
                listOf(json.decodeFromString<VodSourceSub>(trimmed))
            }.getOrElse { return emptyList<VodSource>() to "JSON 解析失败" }
                .map { it.toVodSource() } to null
        }
        // 纯文本：一行一个 URL（或 名称|URL / 名称,URL）
        val out = mutableListOf<VodSource>()
        trimmed.lines().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('|', ',', '，')
            if (parts.size >= 2 && parts[1].trim().startsWith("http")) {
                out.add(VodSource(id = genId(parts[1]), name = parts[0].trim(), baseUrl = parts[1].trim()))
            } else if (line.startsWith("http")) {
                out.add(VodSource(id = genId(line), name = hostOf(line), baseUrl = line))
            }
        }
        if (out.isEmpty()) return emptyList<VodSource>() to "未识别到有效源（支持 JSON 或一行一个 URL）"
        return out to null
    }

    /** 从订阅链接下载 JSON 并解析 */
    fun fetchSubscription(url: String): Pair<List<VodSource>, String?> {
        val body = httpGet(url) ?: return emptyList<VodSource>() to "订阅链接下载失败"
        return parseImport(body)
    }

    @Serializable
    private data class VodSourceSub(
        val id: String = "",
        val name: String = "",
        val baseUrl: String = "",
        val url: String = "",
        val enabled: Boolean = true
    ) {
        fun toVodSource(): VodSource {
            val b = baseUrl.ifBlank { url }
            return VodSource(
                id = id.ifBlank { genId(b) },
                name = name.ifBlank { hostOf(b) },
                baseUrl = b,
                enabled = enabled
            )
        }
    }

    private fun genId(url: String): String =
        hostOf(url).replace(".", "_") + "_" + (url.hashCode().toLong() and 0x7FFFFFFF)

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url.trim()).host ?: url
    }.getOrDefault(url)
}
