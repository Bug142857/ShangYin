package com.shangyin.app.data.music

/**
 * 音乐来源。
 *
 * 现在只有 [BIT24]（用户指定：只保留 24bit 无损，其它来源不再使用）。
 * 其余 5 个"平台"取值仅为**兼容旧收藏**而保留：老收藏的 doubanId 里写着 `wy|xxx` 这类前缀，
 * 删掉枚举值会让这些条目解析不出歌曲（点开变砖），所以保留其取值 + 内置直连（见 [MusicNativeResolve]）。
 */
enum class MusicPlatform(val key: String, val label: String) {
    WY("wy", "网易云"),
    TX("tx", "QQ音乐"),
    KW("kw", "酷我"),
    KG("kg", "酷狗"),
    MG("mg", "咪咕"),

    /** 24bit 无损（https://www.24bit.net）：搜索、播放直链、歌词全部由内置接口提供 */
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

/** 解析失败原因（消息直接展示给用户，不静默吞） */
class MusicResolveException(message: String) : Exception(message)

/**
 * 播放请求头：[BIT24] 的直链来自网易云 CDN，站点自己用 referrerpolicy=no-referrer，实测不带 Referer 可下。
 * 其余取值只为兼容旧收藏（旧的网易云/酷我歌仍能播）。
 */
object MusicPlayHeaders {
    fun forPlatform(platform: MusicPlatform): Map<String, String> {
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        return when (platform) {
            MusicPlatform.WY -> mapOf(
                "User-Agent" to ua, "Referer" to "https://music.163.com/"
            )
            MusicPlatform.KW -> mapOf(
                "User-Agent" to ua, "Referer" to "http://www.kuwo.cn/"
            )
            MusicPlatform.BIT24 -> mapOf("User-Agent" to ua)
            else -> mapOf("User-Agent" to ua)
        }
    }
}
