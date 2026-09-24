package com.shangyin.app.ui

import android.content.Context

/**
 * 「点播放通知回到最小化前的那一页」所需的两个持久化标记。
 *
 * 为什么单独开一个 SharedPreferences：这份数据只服务于导航恢复，和应用设置无关，
 * 与 [com.shangyin.app.ui.settings.SettingsStore] 分开存，互不污染。
 *
 * 只存两样东西：
 *  - [KEY_ROUTE]：最后一次可恢复的路由字符串（Activity 被回收/重建后靠它跳回去）
 *  - [KEY_FROM_NOTIFICATION]：本次启动是否来自播放通知（只有它才允许跳，普通启动不干预用户）
 */
object NavRestore {

    /** 播放通知的 sessionActivity Intent 携带的标记（由 `MusicPlaybackService` 写入） */
    const val EXTRA_FROM_NOTIFICATION = "from_media_notification"

    private const val NAME = "nav_restore"
    private const val KEY_ROUTE = "last_route"
    private const val KEY_FROM_NOTIFICATION = "from_media_notification"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 记录当前路由（在 [AppNav] 里随导航栈变化调用） */
    fun saveRoute(ctx: Context, route: String) {
        if (!isSavable(route)) return
        sp(ctx).edit().putString(KEY_ROUTE, route).apply()
    }

    /** 打上「本次启动来自播放通知」的标记（MainActivity.onCreate 调用） */
    fun markFromNotification(ctx: Context) {
        sp(ctx).edit().putBoolean(KEY_FROM_NOTIFICATION, true).apply()
    }

    /** 清掉「来自播放通知」标记（onNewIntent / 冷启动消费后调用） */
    fun clearFromNotification(ctx: Context) {
        sp(ctx).edit().remove(KEY_FROM_NOTIFICATION).apply()
    }

    /**
     * 一次性取出「要恢复的路由」：仅当本次启动来自播放通知时返回上次的路由，
     * 并顺手清掉标记（保证只跳一次）。普通启动（桌面/最近任务图标进入）返回 null，不干预。
     */
    fun consumePendingRestoreRoute(ctx: Context): String? {
        val prefs = sp(ctx)
        if (!prefs.getBoolean(KEY_FROM_NOTIFICATION, false)) return null
        // 先清标记再取路由：即使后续跳转失败，也不会在下次启动重复触发
        prefs.edit().remove(KEY_FROM_NOTIFICATION).apply()
        return prefs.getString(KEY_ROUTE, null)?.takeIf { isRestorable(it) }
    }

    /**
     * 是否值得写进 SP。
     * 排除登录等**临时页**：登录完成后应回首页，恢复登录页没意义（且常缺参数）。
     * home 也要写 —— 用来表示「用户最后就停在首页」，否则会错误地恢复到更早的深层页面。
     */
    private fun isSavable(route: String): Boolean =
        route.isNotBlank() && !route.startsWith("login")

    /** 是否可作为恢复目标：home 本身就是 NavHost 的起点，恢复它等于没恢复 */
    private fun isRestorable(route: String): Boolean =
        isSavable(route) && route != "home"
}
