package com.shangyin.app.data.live

import kotlinx.serialization.Serializable

/**
 * 直播平台标识。
 * 收藏到里世界清单时：category = "直播"，doubanId = "{platform}|{roomId}"（roomId 对自定义源就是播放地址）。
 */
object LivePlatforms {
    const val HUYA = "huya"
    const val DOUYU = "douyu"
    const val BILI = "bili"
    const val DOUYIN = "douyin"
    /** 自定义 M3U / M3U8 直播源（导入的电视频道），UI 里叫「电视」 */
    const val CUSTOM = "custom"

    /** 收藏条目的分类名（与番号 / 本子 / 漫画 / 游戏 / 图书 同级） */
    const val CATEGORY = "直播"

    fun label(key: String): String = when (key) {
        HUYA -> "虎牙"
        DOUYU -> "斗鱼"
        BILI -> "B站"
        DOUYIN -> "抖音"
        CUSTOM -> "电视"
        else -> key
    }

    /** 支持切换的平台（顺序即 UI 顺序） */
    val ALL = listOf(HUYA, DOUYU, BILI, DOUYIN, CUSTOM)
}

/** 平台分区（虎牙 gid / 斗鱼 cid1_cid2 / B站 parentId_areaId / 自定义源为「源 id」） */
data class LiveCategory(val id: String, val name: String)

/** 直播间（或自定义源里的频道）条目 */
data class LiveRoom(
    val platform: String,
    val roomId: String,
    val title: String,
    val streamer: String = "",
    val cover: String = "",
    /** 人气（已格式化，如 "12.3万"；拿不到就空串） */
    val hot: String = "",
    val categoryName: String = "",
    /** 是否在播（部分平台列表只返回在播房间，恒为 true） */
    val isLive: Boolean = true
) {
    /** 收藏唯一键：platform|roomId */
    val collectId: String get() = "$platform|$roomId"
}

/**
 * 解析出来的可直接播放地址。
 * referer 是平台的防盗链要求（ExoPlayer 请求头里必须带上）。
 */
data class LivePlayInfo(
    val url: String,
    /** true = HLS（m3u8，交给 media3 HLS 模块）；false = 渐进式（flv，media3 内置 FLV 解析） */
    val isHls: Boolean,
    val referer: String,
    /** 播放页标题（房间名），为空时用列表里的标题 */
    val title: String = "",
    /** 主播名 */
    val streamer: String = ""
)

/**
 * 一档清晰度（播放器的「画质」菜单用它）：
 * label 给用户看（原画 / 超高清 / 高清 / 标清 / 流畅…），url 是可直接播放的地址。
 * 一个平台拿不到多档时只有一项，播放器就不显示画质菜单。
 */
data class LiveQuality(val label: String, val url: String, val isHls: Boolean)

/**
 * 直播地址解析结果：
 * - info 非空 = 成功
 * - error 非空 = 失败原因（要给用户看：未开播 / 需要登录 / 网络失败…）——不许把失败说成"没有内容"
 * - qualities = 可选清晰度（含 info 对应的那一档；<=1 项时播放器不显示画质菜单）
 */
data class LiveResolveResult(
    val info: LivePlayInfo? = null,
    val error: String? = null,
    val qualities: List<LiveQuality> = emptyList()
) {
    /** 把单档地址补成清晰度列表（老调用方只给 info 时用） */
    fun withFallbackQuality(label: String = "默认"): LiveResolveResult =
        if (info == null || qualities.isNotEmpty()) this
        else copy(qualities = listOf(LiveQuality(label, info.url, info.isHls)))
}

/** 人气数值格式化：1984762 → "198.5万"；拿不到就空串 */
fun formatLiveHot(raw: String?): String {
    val n = raw?.trim()?.toLongOrNull() ?: return ""
    return when {
        n >= 100_000_000 -> String.format("%.1f亿", n / 100_000_000.0)
        n >= 10_000 -> String.format("%.1f万", n / 10_000.0)
        n <= 0 -> ""
        else -> n.toString()
    }
}

/** 自定义直播源（M3U / M3U8 频道列表）：网络地址导入，或本地文件导入（内容存 content） */
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
