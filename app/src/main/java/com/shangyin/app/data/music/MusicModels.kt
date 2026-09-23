package com.shangyin.app.data.music

import kotlinx.serialization.Serializable

/**
 * 音乐平台。key 与 LX 自定义源（洛雪音源脚本）里的源 key 完全一致：
 * kw=酷我 / kg=酷狗 / tx=QQ音乐 / wy=网易云 / mg=咪咕。
 * 音源脚本初始化时返回的 sources 就是这个 key 的集合，两边必须对得上才能取到播放直链。
 */
enum class MusicPlatform(val key: String, val label: String) {
    WY("wy", "网易云"),
    TX("tx", "QQ音乐"),
    KW("kw", "酷我"),
    KG("kg", "酷狗"),
    MG("mg", "咪咕"),

    /**
     * 24bit 无损（https://www.24bit.net，聚合站，非"平台"但走同一套搜索/播放流程）。
     * 它的搜索与播放直链都免登录、由 [MusicNativeResolve] 直接取，不依赖音源脚本。
     */
    BIT24("bit24", "24bit无损");

    companion object {
        fun of(key: String): MusicPlatform? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 音质档位（与 LX 自定义源一致的取值）。
 * [level] 越大音质越高，音源不支持时按 level 往下逐级降级重试。
 */
enum class MusicQuality(val key: String, val label: String, val level: Int) {
    Q128("128k", "标准", 1),
    Q320("320k", "高品", 2),
    FLAC("flac", "无损", 3),
    FLAC24("flac24bit", "Hi-Res", 4);

    companion object {
        fun of(key: String): MusicQuality = entries.firstOrNull { it.key == key } ?: Q320

        /** 从 [want] 开始的降级顺序（含自身），用于音源不支持时逐级回退 */
        fun fallbacks(want: MusicQuality): List<MusicQuality> =
            entries.filter { it.level <= want.level }.sortedByDescending { it.level }
    }
}

/**
 * 一首歌（搜索结果 / 榜单歌曲 / 清单里收藏的歌都归一化成它）。
 *
 * [raw] 是传给 LX 音源脚本的 musicInfo 原始字段：各平台字段名不同且脚本里是写死的
 * （wy 用 id、kw 用 rid/musicrid、tx 用 songmid、kg 用 hash、mg 用 copyrightId），
 * 所以除了统一的 name/singer/albumName/picUrl 外，必须把平台自己的 id 字段也放进去，
 * 否则脚本取不到 ID 就解析不出直链。数字会被原样写成 JSON number（脚本里可能有数值比较）。
 */
data class MusicSong(
    val platform: MusicPlatform,
    val id: String,
    val name: String,
    val artists: String,
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0L,
    /** 来源榜单/歌单名（搜索结果为空） */
    val from: String = "",
    val raw: Map<String, String> = emptyMap()
) {
    /** 收藏唯一键（存进清单条目的 doubanId）：平台|歌曲ID */
    val key: String get() = "${platform.key}|$id"

    /** 列表副标题：歌手 · 专辑 */
    val subtitle: String get() = listOf(artists, album).filter { it.isNotBlank() }.joinToString(" · ")
}

/** 歌词（[translated] 为翻译歌词，没有则空串） */
data class MusicLyric(val lrc: String = "", val translated: String = "")

/**
 * 播放直链解析结果：
 * [url] 非空即成功；[headers] 是防盗链请求头（ExoPlayer 播放时必须带上）。
 */
data class MusicPlayInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

/** 解析失败原因（区分"音源没装/没启用"与"音源返回失败"，界面照实提示，不静默吞） */
class MusicResolveException(message: String) : Exception(message)

/** 各平台播放直链的默认请求头（音源脚本只返回 URL，防盗链头由 App 按平台补） */
object MusicPlayHeaders {
    fun forPlatform(platform: MusicPlatform): Map<String, String> {
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        return when (platform) {
            MusicPlatform.WY -> mapOf(
                "User-Agent" to ua, "Referer" to "https://music.163.com/"
            )
            MusicPlatform.TX -> mapOf(
                "User-Agent" to ua, "Referer" to "https://y.qq.com/"
            )
            MusicPlatform.KW -> mapOf(
                "User-Agent" to ua, "Referer" to "http://www.kuwo.cn/"
            )
            MusicPlatform.KG -> mapOf(
                "User-Agent" to ua, "Referer" to "https://www.kugou.com/"
            )
            MusicPlatform.MG -> mapOf(
                "User-Agent" to ua, "Referer" to "https://music.migu.cn/"
            )
            // 24bit 的直链来自网易云 CDN，站点自己用 referrerpolicy=no-referrer，实测不带 Referer 可下
            MusicPlatform.BIT24 -> mapOf("User-Agent" to ua)
        }
    }
}

/**
 * 已导入的 LX 自定义音源脚本（洛雪音源，见 https://github.com/pdone/lx-music-source）。
 * 脚本只负责解析播放直链（action=musicUrl），搜索/歌单/歌词数据由 App 内置接口提供。
 */
@Serializable
data class MusicSourceScript(
    val id: String,
    /** 脚本头部 @name */
    val name: String,
    val version: String = "",
    val author: String = "",
    val homepage: String = "",
    val description: String = "",
    /** 网络导入地址；本地导入时为 "local://文件名"（内容在 content 里） */
    val url: String = "",
    /** 脚本源码（导入时抓取并落盘，避免每次启动重新下载） */
    val content: String = "",
    val enabled: Boolean = true,
    /** 初始化后拿到的平台 key → 支持的音质列表（UI 展示 + 选源用） */
    val support: Map<String, List<String>> = emptyMap(),
    /** 最近一次初始化失败原因（非空时 UI 显示"脚本异常"） */
    val lastError: String? = null
) {
    val isLocal: Boolean get() = url.startsWith(LOCAL_PREFIX)

    /** 是否声明支持某平台 */
    fun supports(platform: MusicPlatform): Boolean =
        support.containsKey(platform.key) && support[platform.key]?.isNotEmpty() == true

    /** 某平台支持的音质档位（按高→低） */
    fun qualitiesOf(platform: MusicPlatform): List<MusicQuality> =
        support[platform.key].orEmpty().map { MusicQuality.of(it) }.sortedByDescending { it.level }

    companion object {
        const val LOCAL_PREFIX = "local://"
    }
}

/**
 * 推荐音源的试跑结果（「测试推荐音源」用）：
 * [support] 非空 = 这个源在你当前网络下可用；否则 [error] 是失败原因。
 */
data class SourceProbe(
    val name: String,
    val url: String,
    val support: Map<String, List<String>>? = null,
    val error: String? = null,
    /** 下载到的脚本原文（可用时直接用它导入，省一次下载） */
    val content: String = ""
) {
    val ok: Boolean get() = support != null
}
