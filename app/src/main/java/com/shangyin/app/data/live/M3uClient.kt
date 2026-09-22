package com.shangyin.app.data.live

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 自定义直播源（M3U / M3U8 频道列表）客户端。
 * - 网络源：GET 列表地址拿纯文本；本地导入：文本存在 LiveSource.content 里
 * - 解析标准 M3U：`#EXTINF:-1 tvg-name="xx" group-title="yy",频道名` + 下一行播放地址
 * - 频道在 UI 里按 group-title 分组展示（没有分组的归到「未分组」）
 */
object M3uClient {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val reGroup = Regex("""group-title="([^"]*)"""")
    private val reTvgName = Regex("""tvg-name="([^"]*)"""")

    /**
     * 解析 M3U 文本。
     * 兼容常见写法：地址行前面可能有 `#EXTVLCOPT:` 等以 # 开头的行（忽略），
     * 同一地址重复出现只保留第一条（公开源里重复很常见）。
     */
    fun parse(text: String): List<LiveChannel> {
        val out = LinkedHashMap<String, LiveChannel>()
        var name: String? = null
        var group = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                group = reGroup.find(line)?.groupValues?.getOrNull(1).orEmpty().trim()
                val tvg = reTvgName.find(line)?.groupValues?.getOrNull(1).orEmpty().trim()
                val display = line.substringAfterLast(',', "").trim()
                name = display.ifBlank { tvg }
            } else if (!line.startsWith("#")) {
                // 播放地址行：上面的 #EXTINF 没给名字就用地址末段兜底
                val channelName = name?.takeIf { it.isNotBlank() }
                    ?: line.substringAfterLast('/').substringBefore('?').ifBlank { "未命名频道" }
                out.putIfAbsent(
                    line,
                    LiveChannel(group = group.ifBlank { "未分组" }, name = channelName, url = line)
                )
                name = null
            }
        }
        return out.values.toList()
    }

    /** 加载某个自定义源的频道列表（网络失败/为空都带原因返回，供 UI 区分「没有频道」与「加载失败」） */
    suspend fun loadChannels(src: LiveSource): LiveChannelResult = withContext(Dispatchers.IO) {
        if (src.isLocal) {
            val channels = runCatching { parse(src.content) }.getOrDefault(emptyList())
            return@withContext if (channels.isEmpty()) {
                LiveChannelResult(src, emptyList(), "该源没有解析到频道，可重新导入")
            } else LiveChannelResult(src, channels)
        }
        val body = runCatching {
            client.newCall(
                Request.Builder()
                    .url(src.url)
                    .header("User-Agent", UA)
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                resp.body?.string()
            }
        }.getOrNull() ?: return@withContext LiveChannelResult(src, emptyList(), "网络加载失败，可点重试")

        val channels = runCatching { parse(body) }.getOrDefault(emptyList())
        if (channels.isEmpty()) LiveChannelResult(src, emptyList(), "该源没有解析到频道（可能不是 M3U 列表）")
        else LiveChannelResult(src, channels)
    }

    /** 多源并行加载（单源失败不影响其它源） */
    suspend fun loadAll(sources: List<LiveSource>): List<LiveChannelResult> {
        val enabled = sources.filter { it.enabled }
        if (enabled.isEmpty()) return emptyList()
        return enabled.map { loadChannels(it) }
    }
}
