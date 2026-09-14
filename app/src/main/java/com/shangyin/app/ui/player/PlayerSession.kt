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

    fun clear() {
        itemId = 0L
        title = ""
        sourceName = ""
        groups = emptyList()
        groupIndex = 0
        startIndex = 0
        startPosMs = 0L
    }
}
