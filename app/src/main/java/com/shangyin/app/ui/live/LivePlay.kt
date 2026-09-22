package com.shangyin.app.ui.live

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavHostController
import com.shangyin.app.data.live.LivePlatforms
import com.shangyin.app.data.live.LivePlayInfo
import com.shangyin.app.data.live.LiveResolveResult
import com.shangyin.app.data.live.LiveRoom
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodEpisode
import com.shangyin.app.data.vod.VodPlayGroup
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate

/**
 * 只做"解析"，不导航：播放页的「刷新」按钮与断流自动重连都复用它。
 *
 * 电视源的播放地址由用户自己导入（M3U 里的频道地址），不像平台直播那样短时效过期；
 * 但 M3U 里的直播流同样会断开，所以「刷新 / 重连」仍然必须保留（重连时重新解析一次地址）。
 */
suspend fun resolveLive(room: LiveRoom): LiveResolveResult = when (room.platform) {
    LivePlatforms.CUSTOM -> {
        // 电视源：roomId 就是频道播放地址，直接播
        val isHls = room.roomId.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        LiveResolveResult(info = LivePlayInfo(url = room.roomId, isHls = isHls, referer = ""))
    }
    else -> LiveResolveResult(error = "不支持的直播平台")
}

/**
 * 电视 → 播放页的公共流程：
 * 解析频道地址 → 填充 PlayerSession（直播模式，带请求头）→ 进播放页。
 *
 * popCurrent=true 时用单次原子导航（navigate + popUpTo 当前页），
 * 用于从清单里的电视频道条目（中间过渡页）直接进播放器。
 *
 * 返回 true = 已发起播放（导航离开）；false = 解析失败（已 Toast 说明原因）。
 */
suspend fun openLiveAndPlay(
    nav: NavHostController,
    context: Context,
    room: LiveRoom,
    popCurrent: Boolean = false,
    /** 调用方已知的备用线路/清晰度（如电视源里同名频道的多条地址），非空时优先用它 */
    extraQualities: List<com.shangyin.app.data.live.LiveQuality> = emptyList()
): Boolean {
    val result: LiveResolveResult = resolveLive(room)

    val info = result.info
    if (info == null) {
        Toast.makeText(context, result.error ?: "获取直播地址失败，请重试", Toast.LENGTH_LONG).show()
        return false
    }
    // 可选清晰度/线路：调用方给的优先（电视源同名频道多条地址），否则用解析结果；
    // 只有一档时播放器不显示画质菜单
    val qualities = (if (extraQualities.isNotEmpty()) extraQualities else result.qualities).ifEmpty {
        listOf(com.shangyin.app.data.live.LiveQuality("默认", info.url, info.isHls))
    }

    // 来源信息（清单里收藏时用的是同一口径）
    val subTitle = buildString {
        append(LivePlatforms.label(room.platform))
        val who = info.streamer.ifBlank { room.streamer }
        if (who.isNotBlank()) append(" · ").append(who)
    }

    PlayerSession.clear()
    PlayerSession.itemId = 0L // 直播不记进度，itemId 无意义
    PlayerSession.title = info.title.ifBlank { room.title }
    PlayerSession.subTitle = subTitle
    PlayerSession.sourceName = LivePlatforms.label(room.platform)
    PlayerSession.groups = listOf(
        VodPlayGroup(
            name = LivePlatforms.label(room.platform),
            episodes = listOf(VodEpisode(name = "频道", url = info.url))
        )
    )
    PlayerSession.groupIndex = 0
    PlayerSession.startIndex = 0
    PlayerSession.startPosMs = 0L
    PlayerSession.isLive = true
    // 电视的多线路（同名频道的多条地址）由调用方从这里传进来，播放页换线路直接换地址
    PlayerSession.liveQualities = qualities
    // 播放页要用它做「刷新」与断流自动重连（流会断开，必须能重新解析）
    PlayerSession.liveRoom = room
    // 请求头：电视源通常不需要 Referer；统一带上浏览器 UA，解析结果给了 referer 就一并带上
    PlayerSession.streamHeaders = buildMap {
        put("User-Agent", VodClient.UA)
        if (info.referer.isNotBlank()) put("Referer", info.referer)
    }

    if (popCurrent) {
        val curId = nav.currentBackStackEntry?.destination?.id
        runCatching {
            nav.navigate("player") {
                curId?.let { popUpTo(it) { inclusive = true } }
                launchSingleTop = true
            }
        }
    } else {
        nav.safeNavigate("player")
    }
    return true
}

/**
 * 清单里收藏电视频道时用的 doubanId："{platform}|{roomId}"
 * （platform 固定为 custom，roomId 就是频道播放地址）
 */
fun liveRoomFromCollect(doubanId: String, title: String, subTitle: String, cover: String?): LiveRoom? {
    val parts = doubanId.split("|")
    if (parts.size != 2) return null
    val platform = parts[0]
    val roomId = parts[1]
    if (platform.isBlank() || roomId.isBlank()) return null
    return LiveRoom(
        platform = platform,
        roomId = roomId,
        title = title,
        streamer = subTitle.substringAfter("·", "").trim(),
        cover = cover.orEmpty()
    )
}
