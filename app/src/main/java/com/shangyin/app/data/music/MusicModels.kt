package com.shangyin.app.data.music

/**
 * 音乐来源。目前只有 [BIT24]（24bit 无损，https://www.24bit.net）：
 * 搜索、播放直链、歌词全部由内置接口提供（免登录、不需要任何插件）。
 *
 * 历史说明：v0.206 之前还支持"洛雪音源脚本"与网易云/QQ/酷我/酷狗/咪咕五个平台，按用户要求已全部移除。
 */
enum class MusicPlatform(val key: String, val label: String) {
    BIT24("bit24", "24bit无损");

    companion object {
        fun of(key: String): MusicPlatform? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 一首歌（搜索结果 / 清单里收藏的歌都归一化成它）。
 *
 * [raw] 是采集时留下的原始字段（24bit 目前存 id/name/player/album），
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
