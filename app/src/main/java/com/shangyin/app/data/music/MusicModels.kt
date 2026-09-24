package com.shangyin.app.data.music

/**
 * 音乐来源。搜索页可以按来源切换（chips），每个来源的歌曲 key 里带自己的 key，
 * 所以同名歌曲在不同来源下是两条收藏记录。
 *
 * - [S33VE] 闪闪音乐网（https://www.33ve.com）：搜索、播放直链、歌词、封面全都免登录免验证、无限频（详见 [Site33]）。
 * - [MVMMP3] 无名音乐网（http://www.mvmp3.com）：与 33ve 同一套建站系统，接口形态一致（详见 [MvMp3]），
 *   搜索结果自带封面，直链走酷我 CDN，实测稳定可用。
 * - [JOOX] / [NETEASE]：历史来源，原先走 gdstudio 聚合接口，该接口已按用户要求整体下线（被 mvmp3 替代）。
 *   枚举值仅为兼容旧收藏条目（key 解析不崩）：这两个来源**不再出现在搜索 chips**，
 *   旧条目点播时由 [MusicRepo.resolvePlay] 自动按"歌名 + 歌手"去 mvmp3 / 33ve 找同名歌播放。
 *
 * 历史：曾用过 24bit（www.24bit.net）与洛雪音源脚本，因限额/稳定性问题已按用户要求全部移除，
 * 旧收藏里的 `bit24|…` 条目因此不再能播放（用户已确认接受）。
 */
enum class MusicPlatform(val key: String, val label: String, val inSearch: Boolean = true) {
    S33VE("33ve", "音乐"),
    MVMMP3("mvmp3", "mvmp3"),
    JOOX("joox", "JOOX", inSearch = false),
    NETEASE("netease", "网易云", inSearch = false);

    companion object {
        fun of(key: String): MusicPlatform? = entries.firstOrNull { it.key == key }

        /** 搜索页 chips 要显示的来源（历史上线过又下线的来源不显示） */
        val searchable: List<MusicPlatform> get() = entries.filter { it.inSearch }
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
