package com.shangyin.app.data.live

import kotlinx.serialization.Serializable

/**
 * 电视（自定义 M3U / M3U8 源）标识。
 * 收藏到里世界清单时：category = "电视"，doubanId = "{platform}|{roomId}"（平台固定为 custom，roomId 就是频道播放地址）。
 */
object LivePlatforms {
    /** 自定义 M3U / M3U8 直播源（导入的电视频道），UI 里叫「电视」 */
    const val CUSTOM = "custom"

    /** 收藏条目的分类名（与番号 / 本子 / 漫画 / 游戏 / 图书 同级） */
    const val CATEGORY = "电视"

    fun label(key: String): String = when (key) {
        CUSTOM -> "电视"
        else -> key
    }
}

/** 电视频道（自定义 M3U 源里的一个频道）条目 */
data class LiveRoom(
    val platform: String,
    val roomId: String,
    val title: String,
    val streamer: String = "",
    val cover: String = "",
    /** 人气（已格式化，如 "12.3万"；拿不到就空串） */
    val hot: String = "",
    val categoryName: String = "",
    /** 是否在播（列表里恒为 true） */
    val isLive: Boolean = true
) {
    /** 收藏唯一键：platform|roomId */
    val collectId: String get() = "$platform|$roomId"
}

/**
 * 解析出来的可直接播放地址。
 * referer 是防盗链要求（个别源需要，ExoPlayer 请求头里必须带上；电视源通常为空串）。
 */
data class LivePlayInfo(
    val url: String,
    /** true = HLS（m3u8，交给 media3 HLS 模块）；false = 渐进式（flv，media3 内置 FLV 解析） */
    val isHls: Boolean,
    val referer: String,
    /** 播放页标题（频道名），为空时用列表里的标题 */
    val title: String = "",
    /** 频道副标题 / 来源 */
    val streamer: String = ""
)

/**
 * 一档清晰度/线路（播放器的「画质」菜单用它）：
 * label 给用户看（电视源里同名频道的多条地址就是多档），url 是可直接播放的地址。
 * 只有一个档位时播放器就不显示画质菜单。
 */
data class LiveQuality(val label: String, val url: String, val isHls: Boolean)

/**
 * 播放地址解析结果（电视源：roomId 就是地址，直接成功）：
 * - info 非空 = 成功
 * - error 非空 = 失败原因（要给用户看）
 * - qualities = 可选线路（同名频道多条地址；<=1 项时播放器不显示菜单）
 */
data class LiveResolveResult(
    val info: LivePlayInfo? = null,
    val error: String? = null,
    val qualities: List<LiveQuality> = emptyList()
)

/** 电视源（M3U / M3U8 频道列表）：网络地址导入，或本地文件导入（内容存 content） */
@Serializable
data class LiveSource(
    val id: String,
    val name: String,
    /** 网络地址；本地导入时为 "local://文件名"（内容在 content 里） */
    val url: String,
    /** 本地导入的 m3u 文本（url 以 local:// 开头时使用） */
    val content: String = "",
    val enabled: Boolean = true
) {
    val isLocal: Boolean get() = url.startsWith(LOCAL_PREFIX)

    companion object {
        const val LOCAL_PREFIX = "local://"
    }
}

/** M3U 里的一个频道 */
data class LiveChannel(
    val group: String,
    val name: String,
    val url: String
)

/** 多源并行加载结果（单源失败不影响其它源，UI 显示"该源无频道/失败"） */
data class LiveChannelResult(
    val source: LiveSource,
    val channels: List<LiveChannel>,
    val error: String? = null
)
