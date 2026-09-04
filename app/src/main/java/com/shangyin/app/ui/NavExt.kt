package com.shangyin.app.ui

import androidx.navigation.NavController

/**
 * 全局导航防抖：导航过渡动画期间（约 500ms）忽略重复的导航/返回操作，
 * 避免快速连点返回或连点条目时 NavHost 状态错乱导致白屏。
 */
object NavGuard {
    @Volatile
    private var lastActionTime = 0L
    private const val INTERVAL = 500L

    /** 是否允许执行一次导航/返回（带节流） */
    fun allow(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionTime < INTERVAL) return false
        lastActionTime = now
        return true
    }
}

/** 安全跳转：过渡期间忽略重复调用 */
fun NavController.safeNavigate(route: String) {
    if (!NavGuard.allow()) return
    runCatching { navigate(route) }
}

/** 安全返回：过渡期间忽略重复调用 */
fun NavController.safePopBackStack(): Boolean {
    if (!NavGuard.allow()) return false
    return runCatching { popBackStack() }.getOrDefault(false)
}
