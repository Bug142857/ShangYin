package com.shangyin.app.ui.search

import android.content.Context
import android.widget.Toast
import androidx.navigation.NavHostController
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.data.vod.VodItem
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.player.PlayerSession
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore

/**
 * 外网片源点击播放公共流程：
 * 补全详情（无播放地址时）→ 解析线路（parsePlayGroups 内部默认只留 3u8）→
 * 按播放地址定位断点续播（itemId 用 0）→ 跳播放页。
 * popCurrent=true 时进播放器前先弹出当前页（收藏条目详情页自动跳播场景），
 * 这样播放器按返回键直接回到来源列表，不会落回过渡残页。
 * 返回 true 表示已发起播放（导航离开），false 表示无地址等失败。
 */
suspend fun openVodAndPlay(
    nav: NavHostController,
    context: Context,
    src: VodSource,
    item: VodItem,
    popCurrent: Boolean = false
): Boolean {
    val full = if (item.vod_play_url.isBlank()) {
        runCatching { VodClient.fetchDetail(src, item.vod_id) }.getOrDefault(item)
    } else item
    val groups = VodClient.parsePlayGroups(full.vod_play_from, full.vod_play_url)
    if (groups.isEmpty()) {
        Toast.makeText(context, "「${full.vod_name}」暂无可用播放地址", Toast.LENGTH_SHORT).show()
        return false
    }
    // 断点续播：进度按播放地址记忆（itemId 用 0，地址本身全局唯一）
    var idx = 0
    var pos = 0L
    var bestTs = -1L
    groups[0].episodes.forEachIndexed { i, ep ->
        val p = SettingsStore.getVodProgress(SettingsStore.vodProgressKey(0L, ep.url))
            ?: return@forEachIndexed
        if (p.third > bestTs) {
            bestTs = p.third
            idx = i
            pos = p.first
        }
    }
    PlayerSession.itemId = 0L
    PlayerSession.title = full.vod_name
    PlayerSession.groups = groups
    PlayerSession.groupIndex = 0
    PlayerSession.startIndex = idx
    PlayerSession.startPosMs = pos
    if (popCurrent) nav.safePopBackStack()
    nav.safeNavigate("player")
    return true
}
