package com.shangyin.app.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 抖音直播客户端（live.douyin.com）。
 *
 * 实测（2026-09）：整条链路纯 HTTP + 正则就能跑通，**不需要隐藏 WebView**（这一点和斗鱼不同）。
 *  - 分区：首页 HTML 里的 `href="/categorynew/{id}"` 锚点，实测 8 个顶级分类（id 形如 4_103）。
 *  - 房间：分区页的房间数据在 SSR 内联 JSON 里；该页 `<img>` 数量为 0，封面只能在 JSON 的 url_list 里取。
 *    SSR 页面没有分页参数 → page > 1 直接返回空列表（UI 会因此不显示「加载更多」）。
 *  - 播放：房间页里的地址是 JS 字符串字面量转义过的（`\"` = 引号，`\u0026` = `&`），
 *    **必须先 unescape 再正则**，否则 URL 里的 & 会把正则截断。
 *  - 桌面 UA：手机 UA 下页面不给 SSR 内联数据，全部用桌面 UA。
 */
object DouyinClient {

    const val PLATFORM = LivePlatforms.DOUYIN

    private const val REFERER = "https://live.douyin.com/"

    /** 桌面 UA：手机 UA 下首页 / 分区页拿不到 SSR 内联数据 */
    private const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val URL_HOME = "https://live.douyin.com/"
    private const val URL_CATEGORY_PREFIX = "https://live.douyin.com/categorynew/"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 首页分区：/categorynew/4_103 → 分类 id "4_103"，锚文本是分类名（如 游戏） */
    private val RE_CATEGORY = Regex("href=\"/categorynew/([\\d_]+)\"[^>]*>([^<]{1,12})<")

    /** SSR 内联 JSON 里的房间号（真机实测一页 16 条） */
    private val RE_WEB_RID = Regex("\"web_rid\":\"(\\d+)\"")

    /** 房间标题 / 主播名：在房间号附近窗口里取**最后一个**（同一个 web_rid 前最近的那个才是本房间的） */
    private val RE_TITLE = Regex("\"title\":\"([^\"]{1,80})\"")
    private val RE_NICK = Regex("\"nickname\":\"([^\"]{1,40})\"")

    /** 封面：url_list 数组里的第一个地址（封面不从 <img> 出，只能在 JSON 里取） */
    private val RE_COVER = Regex("\"url_list\":\\[\"(https://[^\"]+)\"")

    /** 热度：优先带单位的 user_count_str，退回 total_user */
    private val RE_USER_COUNT = Regex("\"user_count_str\":\"([^\"]+)\"")
    private val RE_TOTAL_USER = Regex("\"total_user\":(\\d+)")

    /** 播放地址：FLV（渐进式）/ HLS */
    private val FLV_REGEX = Regex("""https?://[^"'\s<>]*?\.flv\?[^"'\s<>]*""")
    private val M3U8_REGEX = Regex("""https?://[^"'\s<>]*?\.m3u8[^"'\s<>]*""")

    /** 已结束标记："status_code":4（后面不能紧跟数字，避免误伤 4xx 之类） */
    private val RE_STATUS_END = Regex("\"status_code\"\\s*:\\s*4(?!\\d)")

    /**
     * 在播标记：实测（2026-09）房间 JSON 窗口里「在播」的房间**一定**含 `"streamSrc":"http`
     * （15/15 全部命中）；窗口里没有它就按未开播处理（房间本身仍保留在列表里）。
     */
    private const val LIVE_STREAM_SRC = "\"streamSrc\":\"http"

    // ---------- 请求 ----------

    /** 统一 GET：非 200 / 异常都返回 null（异常不外抛） */
    private fun httpGet(url: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", UA_DESKTOP)
                .header("Referer", REFERER)
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    // ---------- 分区 ----------

    /**
     * 拉取顶级分区：正则抠出 /categorynew/{id} 链接，按页面顺序去重。
     * 实测 8 个：聊天 4_101 / 音乐 4_102 / 游戏 4_103 / 二次元 4_104 / 舞蹈 4_105 / 文化 4_106 / 生活 4_107 / 运动 4_108。
     */
    suspend fun categories(): List<LiveCategory> = withContext(Dispatchers.IO) {
        val html = httpGet(URL_HOME) ?: return@withContext emptyList()
        runCatching {
            val seen = HashSet<String>()
            val out = ArrayList<LiveCategory>()
            RE_CATEGORY.findAll(html).forEach { m ->
                val id = m.groupValues[1]
                val name = m.groupValues[2].trim()
                if (id.isEmpty() || name.isEmpty()) return@forEach
                if (seen.add(id)) out.add(LiveCategory(id = id, name = name))
            }
            out
        }.getOrDefault(emptyList())
    }

    // ---------- 房间列表 ----------

    /**
     * 拉取分区下的房间。SSR 页面没有分页参数，page > 1 直接返回空列表。
     * 单条房间的标题/封面缺失不影响该房间出现在列表里（只留空串）。
     * 返回前把未开播的房间排到后面（sortedByDescending 是稳定排序，在播之间保持原有相对顺序）。
     */
    suspend fun rooms(categoryId: String, page: Int): List<LiveRoom> = withContext(Dispatchers.IO) {
        if (page > 1) return@withContext emptyList()
        val cid = categoryId.trim()
        if (cid.isEmpty()) return@withContext emptyList()
        val html = httpGet(URL_CATEGORY_PREFIX + cid) ?: return@withContext emptyList()
        runCatching { parseRooms(html) }.getOrDefault(emptyList())
    }

    /** 先 unescape 再按 web_rid 切窗口解析（同一页 15~16 条房间） */
    private fun parseRooms(html: String): List<LiveRoom> {
        val text = unescape(html)
        val rids = LinkedHashSet<String>()
        RE_WEB_RID.findAll(text).forEach { rids.add(it.groupValues[1]) }
        return rids.mapNotNull { rid -> buildRoom(text, rid) }
            // 未开播的房间不要排在前面（稳定排序，在播之间保持原有相对顺序）
            .sortedByDescending { it.isLive }
    }

    /**
     * 单个房间：以 `"web_rid":"xxx"` 为锚点取「前 4000 字符 + 后 400 字符」窗口，
     * 在窗口里取最后一个 title / nickname / url_list（最近的才是这个房间自己的）。
     * 在播判定见 [LIVE_STREAM_SRC]；未开播的房间**不丢弃**，只是 isLive = false。
     */
    private fun buildRoom(text: String, rid: String): LiveRoom? {
        val idx = text.indexOf("\"web_rid\":\"$rid\"")
        if (idx < 0) return null
        val window = text.substring((idx - 4000).coerceAtLeast(0), (idx + 400).coerceAtMost(text.length))

        val title = RE_TITLE.findAll(window).lastOrNull()?.groupValues?.get(1).orEmpty()
        val streamer = RE_NICK.findAll(window).lastOrNull()?.groupValues?.get(1).orEmpty()
        val hot = formatLiveHot(
            RE_USER_COUNT.find(window)?.groupValues?.get(1)
                ?: RE_TOTAL_USER.find(window)?.groupValues?.get(1)
        )
        return LiveRoom(
            platform = LivePlatforms.DOUYIN,
            roomId = rid,
            title = title,
            streamer = streamer,
            cover = pickCover(window),
            hot = hot,
            categoryName = "",
            isLive = window.contains(LIVE_STREAM_SRC)
        )
    }

    /** 选封面：url_list 里最后一个「像图片」的地址（排除头像 / 静态资源等） */
    private fun pickCover(window: String): String =
        RE_COVER.findAll(window)
            .map { it.groupValues[1] }
            .lastOrNull { u ->
                u.contains("douyinpic") || u.contains("~tplv") ||
                    u.contains(".jpeg") || u.contains(".jpg") || u.contains(".webp")
            }
            .orEmpty()

    // ---------- 播放地址 ----------

    /**
     * 解析房间播放地址：先 unescape（`\"`→`"`、`\u0026`→`&`）再正则取全部 .flv 并按清晰度分档；
     * 一个 .flv 都没有才退回 m3u8（单档，isHls = true）。地址保持 http 原样（App 已开 cleartext）。
     *
     * 实测（2026-09）：房间页 HTML unescape 后有 **117 个 .flv、11 个不同流名**（同一档在多个 CDN
     * 域名下重复），后缀即清晰度：_or4 原画 / _uhd 超高清 / _hd 高清 / _sd 标清 / _ld 流畅 /
     * _md 中等 / _hiqhd5·_hiquhd5·_hiqsd5 是 H.265 系（兼容性差，菜单里放最后）/ 无后缀「默认」/
     * 其它未知后缀标成「其他（后缀）」；映射后若仍撞名再追加序号（见 [qualityLabel]、[dedupeLabels]）。
     *
     * 默认档刻意不选原画：原画实测 8~15Mbps，手机放会卡（用户反馈「直播特别卡」的主因之一），
     * 优先高清 → 标清 → 超高清 → 原画 → 列表第一项。
     */
    suspend fun resolve(roomId: String): LiveResolveResult = withContext(Dispatchers.IO) {
        val id = roomId.trim()
        if (id.isEmpty()) return@withContext LiveResolveResult(error = "房间号为空")

        val html = httpGet(URL_HOME + id)
            ?: return@withContext LiveResolveResult(error = "网络请求失败，请重试")
        val text = unescape(html)

        val qualities = parseFlvQualities(text)
        if (qualities.isNotEmpty()) {
            val def = pickDefaultQuality(qualities)
            return@withContext LiveResolveResult(
                info = LivePlayInfo(url = def.url, isHls = false, referer = REFERER),
                qualities = qualities
            )
        }
        M3U8_REGEX.find(text)?.value?.let { m3u8 ->
            return@withContext LiveResolveResult(
                info = LivePlayInfo(url = m3u8, isHls = true, referer = REFERER)
            )
        }
        // 页面里 status_code = 4 表示已结束，比笼统的"拿不到地址"更准确
        if (RE_STATUS_END.containsMatchIn(text)) {
            return@withContext LiveResolveResult(error = "主播未开播")
        }
        LiveResolveResult(error = "该房间未开播或暂时拿不到直播地址")
    }

    // ---------- 清晰度分档 ----------

    /**
     * 从页面里解析全部 FLV 清晰度档：按「清晰度后缀」去重（同一档只留页面顺序里的第一条，
     * 因为同一档会在多个 CDN 域名下重复），按画质菜单顺序排列，最后再兜一层 label 去重。
     */
    private fun parseFlvQualities(text: String): List<LiveQuality> {
        val bySuffix = LinkedHashMap<String, LiveQuality>()
        FLV_REGEX.findAll(text).forEach { m ->
            val url = m.value
            val suffix = flvSuffix(url)
            if (bySuffix.containsKey(suffix)) return@forEach
            bySuffix[suffix] = LiveQuality(label = qualityLabel(suffix), url = url, isHls = false)
        }
        // sortedBy 是稳定排序，同 rank 的 H.265 系之间保持页面顺序
        return dedupeLabels(bySuffix.values.sortedBy { qualityRank(it.label) })
    }

    /** 取 flv 地址的清晰度后缀：文件名最后一个 `_` 之后的部分；没有 `_`（形如 stream-xxx.flv）返回空串 */
    private fun flvSuffix(url: String): String {
        val name = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        return if ('_' in name) name.substringAfterLast('_') else ""
    }

    /**
     * 后缀 → 中文 label。
     * 实测（2026-09）房间页 unescape 后共有 11 个不同流名，后缀与其中文名对应关系如下表；
     * **未知后缀不再一律叫「默认」**，而是标出原文（`其他（abc）`），免得用户看到好几个「默认」。
     */
    private fun qualityLabel(suffix: String): String = when (suffix) {
        "or4" -> "原画"
        "uhd" -> "超高清"
        "hd" -> "高清"
        "sd" -> "标清"
        "ld" -> "流畅"
        "md" -> "中等"
        "hiqhd5" -> "H.265 高清"
        "hiquhd5" -> "H.265 超高清"
        "hiqsd5" -> "H.265 标清"
        "" -> "默认"
        else -> "其他（$suffix）"
    }

    /** 兜底去重：映射后万一还是撞名，第 2 个及以后追加序号（如「高清 2」），保证菜单里不出现重名 */
    private fun dedupeLabels(list: List<LiveQuality>): List<LiveQuality> {
        val counter = HashMap<String, Int>()
        return list.map { q ->
            val n = (counter[q.label] ?: 0) + 1
            counter[q.label] = n
            if (n == 1) q else q.copy(label = "${q.label} $n")
        }
    }

    /** 画质菜单顺序：原画 → 超高清 → 高清 → 标清 → 流畅 → 中等 → 默认 → H.265 系（放最后） */
    private fun qualityRank(label: String): Int = when (label) {
        "原画" -> 0
        "超高清" -> 1
        "高清" -> 2
        "标清" -> 3
        "流畅" -> 4
        "中等" -> 5
        "默认" -> 6
        else -> 7
    }

    /** 默认档：高清 → 标清 → 超高清 → 原画 → 第一项（原画码率太高，手机放会卡，不作为首选） */
    private fun pickDefaultQuality(list: List<LiveQuality>): LiveQuality =
        list.firstOrNull { it.label == "高清" }
            ?: list.firstOrNull { it.label == "标清" }
            ?: list.firstOrNull { it.label == "超高清" }
            ?: list.firstOrNull { it.label == "原画" }
            ?: list.first()

    /**
     * 还原 SSR 里的 JS 字符串转义：`\"` → `"`、`\u0026` → `&`。
     * 不还原的话 URL 里的 & 会被当成字符串结束，正则直接断在第一个参数上。
     */
    private fun unescape(s: String): String =
        s.replace("\\\"", "\"").replace("\\u0026", "&")
}
