package com.shangyin.app.data.music

/**
 * 音乐来源。搜索页可以按来源切换（chips），每个来源的歌曲 key 里带自己的 key，
 * 所以同名歌曲在不同来源下是两条收藏记录。
 *
 * - [S33VE] 闪闪音乐网（https://www.33ve.com）：搜索、播放直链、歌词、封面全都免登录免验证、无限频（详见 [Site33]）。
 * - [JOOX] / [NETEASE] 走聚合接口 gdstudio（详见 [GdStudio]）。实测 JOOX 只有搜索/歌词/封面可用，
 *   直链接口返回空，因此播放时由 [MusicRepo.resolvePlay] 回退到 33ve 找同名歌曲；网易云搜索+直链都可用。
 *
 * 历史：曾用过 24bit（www.24bit.net）与洛雪音源脚本，因限额/稳定性问题已按用户要求全部移除，
 * 旧收藏里的 `bit24|…` 条目因此不再能播放（用户已确认接受）。
 */
enum class MusicPlatform(val key: String, val label: String) {
    S33VE("33ve", "音乐"),
    JOOX("joox", "JOOX"),
    NETEASE("netease", "网易云");

    companion object {
        fun of(key: String): MusicPlatform? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 一首歌（搜索结果 / 清单里收藏的歌都归一化成它）。
 *
 * [raw] 是采集时留下的原始字段（33ve 存 id/name/singer；gdstudio 来源存 id/name/singer），
 * 供后续重新解析直链时使用；收藏进清单时它会一起写进 info 字段。
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
