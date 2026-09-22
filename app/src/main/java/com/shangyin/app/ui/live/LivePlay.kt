package com.shangyin.app.ui.live

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavHostController
import com.shangyin.app.data.live.BiliLiveClient
import com.shangyin.app.data.live.DouyuClient
import com.shangyin.app.data.live.DouyinClient
import com.shangyin.app.data.live.HuyaClient
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
 * 直播 → 播放页的公共流程：
 * 解析房间真实流地址（虎牙/斗鱼/B站各不相同）→ 填充 PlayerSession（直播模式，带防盗链请求头）→ 进播放页。
 *
 * popCurrent=true 时用单次原子导航（navigate + popUpTo 当前页），
 * 用于从清单里的直播条目（中间过渡页）直接进播放器。
 *
 * 返回 true = 已发起播放（导航离开）；false = 解析失败（已 Toast 说明原因）。
 */
suspend fun openLiveAndPlay(
    nav: NavHostController,
    context: Context,
    room: LiveRoom,
    popCurrent: Boolean = false
): Boolean {
    val result: LiveResolveResult = when (room.platform) {
        LivePlatforms.HUYA -> HuyaClient.resolve(room.roomId)
        LivePlatforms.DOUYU -> DouyuClient.resolve(room.roomId)
        LivePlatforms.BILI -> BiliLiveClient.resolve(room.roomId)
        LivePlatforms.DOUYIN -> DouyinClient.resolve(room.roomId)
        LivePlatforms.CUSTOM -> {
            // 自定义源：roomId 就是频道播放地址，直接播
            val isHls = room.roomId.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            LiveResolveResult(info = LivePlayInfo(url = room.roomId, isHls = isHls, referer = ""))
        }
        else -> LiveResolveResult(error = "不支持的直播平台")
    }

    val info = result.info
    if (info == null) {
        Toast.makeText(context, result.error ?: "获取直播地址失败，请重试", Toast.LENGTH_LONG).show()
        return false
    }

    // 主播 + 平台信息（清单里收藏时用的是同一口径）
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
            episodes = listOf(VodEpisode(name = "直播", url = info.url))
        )
    )
    PlayerSession.groupIndex = 0
    PlayerSession.startIndex = 0
    PlayerSession.startPosMs = 0L
    PlayerSession.isLive = true
    // 防盗链：三个平台都校验 Referer（虎牙/斗鱼/B站实测必须带），UA 与站点脚本保持一致
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

/** 清单里收藏直播条目时用的 doubanId："{platform}|{roomId}" */
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
