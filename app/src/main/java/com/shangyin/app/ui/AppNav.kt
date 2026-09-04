package com.shangyin.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shangyin.app.ui.celebrity.CelebrityScreen
import com.shangyin.app.ui.home.HomeScreen
import com.shangyin.app.ui.item.ItemDetailScreen
import com.shangyin.app.ui.lists.ListDetailScreen
import com.shangyin.app.ui.search.SearchScreen
import com.shangyin.app.ui.settings.SettingsScreen

/**
 * 导航动画配置：切换动画极短（120ms），保证操作省时丝滑。
 * 所有路由使用淡入淡出 + 轻微水平位移组合，避免渐慢渐变。
 */
private const val ANIM_MS = 120

private fun AnimatedContentTransitionScope<*>.quickEnter(): EnterTransition =
    fadeIn(tween(ANIM_MS))

private fun AnimatedContentTransitionScope<*>.quickExit(): ExitTransition =
    fadeOut(tween(ANIM_MS))

private fun AnimatedContentTransitionScope<*>.quickPopEnter(): EnterTransition =
    fadeIn(tween(ANIM_MS))

private fun AnimatedContentTransitionScope<*>.quickPopExit(): ExitTransition =
    fadeOut(tween(ANIM_MS))

/**
 * 根导航：
 * - 全局背景（避免 NavHost 重组/空栈瞬间白屏）
 * - 根路径 BackHandler 防止快速按返回导致 Activity 异常 finish 留白
 * - currentBackStackEntry 监控：为空时强制回到 home
 */
@Composable
fun AppNav(onThemeChanged: () -> Unit = {}) {
    val nav = rememberNavController()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        NavHost(
            navController = nav,
            startDestination = "home",
            enterTransition = { quickEnter() },
            exitTransition = { quickExit() },
            popEnterTransition = { quickPopEnter() },
            popExitTransition = { quickPopExit() }
        ) {
            composable("home") {
                // 根页：BackHandler 吞噬多余返回（避免快速连点退出 activity 造成的白屏）
                BackHandler(enabled = true) { /* ignore - 用户只能用系统退出/桌面键关App */ }
                HomeScreen(nav)
            }
            composable("search") { SearchScreen(nav) }
            composable(
                route = "item/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                ItemDetailScreen(nav, entry.arguments?.getLong("id") ?: 0L)
            }
            composable(
                route = "celebrity/{id}/{fromCategory}/{name}/{avatar}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("fromCategory") {
                        type = NavType.StringType; defaultValue = ""
                    },
                    navArgument("name") {
                        type = NavType.StringType; defaultValue = ""
                    },
                    navArgument("avatar") {
                        type = NavType.StringType; defaultValue = ""
                    }
                )
            ) { entry ->
                CelebrityScreen(
                    nav = nav,
                    celebrityId = entry.arguments?.getString("id").orEmpty(),
                    fromCategory = entry.arguments?.getString("fromCategory").orEmpty(),
                    passedName = entry.arguments?.getString("name").orEmpty(),
                    passedAvatar = entry.arguments?.getString("avatar").orEmpty()
                )
            }
            // 兼容搜索页点人物结果（不传name/avatar）的简单路由
            composable(
                route = "celebrity/{id}/{fromCategory}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("fromCategory") { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                CelebrityScreen(
                    nav = nav,
                    celebrityId = entry.arguments?.getString("id").orEmpty(),
                    fromCategory = entry.arguments?.getString("fromCategory").orEmpty()
                )
            }
            composable("settings") { SettingsScreen(nav, onThemeChanged) }
            composable(
                route = "list/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                ListDetailScreen(nav, entry.arguments?.getLong("id") ?: 0L)
            }
        }
    }

    // 导航栈安全网：NavHost 空栈或快速返回异常时强制回首页
    val currentEntry by nav.currentBackStackEntryAsState()
    var lastRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentEntry) {
        val route = currentEntry?.destination?.route
        if (route == null) {
            runCatching { nav.safeNavigate("home") }
        } else {
            lastRoute = route
        }
    }
}
