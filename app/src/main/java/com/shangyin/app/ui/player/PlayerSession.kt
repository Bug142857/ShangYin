package com.shangyin.app.ui.player

import com.shangyin.app.data.vod.VodPlayGroup

/**
 * 播放页参数传递（仿 ItemDetailScreen 的 DetailCache 先例）：
 * 剧集数据量大，不走导航路由参数；进播放页前填充，退出后清空。
 */
object PlayerSession {
    var itemId: Long = 0L
    var title: String = ""
    var sourceName: String = ""

    /** 播放组（线路）列表，每组含剧集列表 */
    var groups: List<VodPlayGroup> = emptyList()

    /** 默认线路下标 */
    var groupIndex: Int = 0

    /** 起始集下标 */
    var startIndex: Int = 0

    /** 起始集内的续播位置（毫秒） */
    var startPosMs: Long = 0L

    /** 直播模式：不记忆进度、不显示选集/倍速，播放器用 streamHeaders 里的请求头 */
    var isLive: Boolean = false

    /** 二级信息（直播：来源 · 频道副标题 / 在线观影为空） */
    var subTitle: String = ""

    /** 播放请求头（直播防盗链：Referer 等；电视源通常为空） */
    var streamHeaders: Map<String, String> = emptyMap()

    /** 直播可选清晰度/线路（>1 项时播放器显示「画质」菜单；电视源同名频道的多条地址走这里） */
    var liveQualities: List<com.shangyin.app.data.live.LiveQuality> = emptyList()

    /** 当前频道：播放页的「刷新」与断流自动重连要用它重新解析（流会断开） */
    var liveRoom: com.shangyin.app.data.live.LiveRoom? = null

    fun clear() {
        itemId = 0L
        title = ""
        sourceName = ""
        groups = emptyList()
        groupIndex = 0
        startIndex = 0
        startPosMs = 0L
        isLive = false
        subTitle = ""
        streamHeaders = emptyMap()
        liveQualities = emptyList()
        liveRoom = null
    }
}
