package com.shangyin.app.ui

import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
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
import com.shangyin.app.ui.lists.ListManagerScreen
import com.shangyin.app.ui.player.PlayerScreen
import com.shangyin.app.ui.search.BikaComicDetailScreen
import com.shangyin.app.ui.search.H1SearchScreen
import com.shangyin.app.ui.search.H2SearchScreen
import com.shangyin.app.ui.search.SearchScreen
import com.shangyin.app.ui.search.SourceBrowseScreen
import com.shangyin.app.ui.settings.CloudSyncScreen
import com.shangyin.app.ui.settings.DataManageScreen
import com.shangyin.app.ui.settings.SettingsScreen
import com.shangyin.app.ui.settings.VodSourceScreen
import kotlinx.coroutines.flow.collect

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
            composable(
                route = "search?cat={cat}&kw={kw}",
                arguments = listOf(
                    navArgument("cat") { type = NavType.StringType; defaultValue = "影视" },
                    navArgument("kw") { type = NavType.StringType; defaultValue = "" }
                )
            ) { entry ->
                SearchScreen(
                    nav,
                    initialCat = entry.arguments?.getString("cat").orEmpty(),
                    initialKw = entry.arguments?.getString("kw").orEmpty()
                )
            }
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
            composable("account") { com.shangyin.app.ui.settings.AccountScreen(nav) }
            composable("cloudsync") { CloudSyncScreen(nav) }
            composable("dataManage") { DataManageScreen(nav) }
            composable("vodSources") { VodSourceScreen(nav) }
            // 片源管理（总入口：影视源配置 + 电视源配置）
            composable("sourceHub") { com.shangyin.app.ui.live.SourceHubScreen(nav) }
            composable("player") { PlayerScreen(nav) }
            composable(
                route = "h1search?kw={kw}",
                arguments = listOf(navArgument("kw") {
                    type = NavType.StringType
                    defaultValue = ""
                })
            ) { entry ->
                H1SearchScreen(nav, entry.arguments?.getString("kw").orEmpty())
            }
            composable(
                route = "h1source/{srcId}",
                arguments = listOf(navArgument("srcId") { type = NavType.StringType })
            ) { entry ->
                SourceBrowseScreen(nav, entry.arguments?.getString("srcId").orEmpty())
            }
            // H2 = 哔咔漫画（数据源来自 haka_comic 项目内置的哔咔 API）
            composable("h2search") { H2SearchScreen(nav) }
            // 哔咔漫画详情 + 阅读
            composable(
                route = "bikaComic/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                BikaComicDetailScreen(nav, entry.arguments?.getString("id").orEmpty())
            }
            // 漫画 = Komiic（里世界第三入口）
            composable("comicHome") { com.shangyin.app.ui.comic.ComicHomeScreen(nav) }
            composable(
                route = "comicDetail/{comicId}",
                arguments = listOf(navArgument("comicId") { type = NavType.StringType })
            ) { entry ->
                com.shangyin.app.ui.comic.ComicDetailScreen(
                    nav,
                    entry.arguments?.getString("comicId").orEmpty()
                )
            }
            // 游戏 = 无忧游戏库（wygamer.com，里世界第四入口）
            composable("gameHome") { com.shangyin.app.ui.wygamer.GameHomeScreen(nav) }
            composable(
                route = "gameDetail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                com.shangyin.app.ui.wygamer.GameDetailScreen(
                    nav,
                    entry.arguments?.getString("id").orEmpty()
                )
            }
            // 图书 = Z-Library（里世界第五入口）
            composable("bookHome") { com.shangyin.app.ui.zlib.BookHomeScreen(nav) }
            composable(
                route = "bookDetail/{id}/{hash}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("hash") { type = NavType.StringType }
                )
            ) { entry ->
                com.shangyin.app.ui.zlib.BookDetailScreen(
                    nav,
                    entry.arguments?.getString("id").orEmpty(),
                    entry.arguments?.getString("hash").orEmpty()
                )
            }
            // 电视 = 自定义 M3U / M3U8 源（里世界第六入口；页内搜索，返回键逐级返回）
            composable("tvHome") { com.shangyin.app.ui.live.LiveHomeScreen(nav) }
            composable("liveSources") { com.shangyin.app.ui.live.LiveSourcesScreen(nav) }
            // 音乐 = 24bit 无损（里世界第七入口；搜索/直链/歌词均由内置接口提供）
            composable("musicHome") { com.shangyin.app.ui.music.MusicHomeScreen(nav) }
            composable("musicPlayer") { com.shangyin.app.ui.music.MusicPlayerScreen(nav) }
            // 吃瓜 = 51爆料（里世界第八入口；原生解析镜像站，广告天然屏蔽，线路自动发现/切换）
            composable("melonHome") { com.shangyin.app.ui.melon.MelonHomeScreen(nav) }
            composable(
                route = "melonDetail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                com.shangyin.app.ui.melon.MelonDetailScreen(
                    nav,
                    entry.arguments?.getString("id").orEmpty()
                )
            }
            composable(
                route = "list/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                ListDetailScreen(nav, entry.arguments?.getLong("id") ?: 0L)
            }
            composable("listManager") { ListManagerScreen(nav) }
            // 我的下载（漫画 / 本子离线阅读）
            composable("downloads") { com.shangyin.app.ui.download.DownloadScreen(nav) }
        }

        // Z-Library 接口用的常驻隐藏 WebView（DiamWall 是 JS 挑战，接口必须走浏览器环境）
        com.shangyin.app.ui.zlib.ZlibWebHost()
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

    // ---------- 点「播放通知」回来时恢复上次页面（App 被系统回收/Activity 被销毁的场景） ----------
    val ctx = LocalContext.current

    // 关键：这里必须在任何写入之前**同步**读一次（用 remember，不是 LaunchedEffect），
    // 否则会被下面监听器刚发出的 home 覆盖掉。只有「本次启动来自播放通知」才返回目标路由，
    // 读的同时就清掉了标记 → 只跳一次。
    val pendingRestoreRoute = remember { NavRestore.consumePendingRestoreRoute(ctx) }

    // 持续记录当前路由：Activity 被回收后 Compose 导航栈不复存在，冷启动只能靠它回到原页面。
    // 写入的是「可再次 navigate 的完整路由串」（模板里的 {arg} 用实参填回去，见 restorableRoute）。
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            entry.restorableRoute()?.let { NavRestore.saveRoute(ctx, it) }
        }
    }

    // 真正跳一次：系统若已把任务栈恢复回来（当前就是目标页）就不重复跳；
    // safeNavigate 自带 runCatching 兜底，路由不存在 / 参数非法时不会崩，停在 home。
    LaunchedEffect(nav) {
        val target = pendingRestoreRoute ?: return@LaunchedEffect
        if (nav.currentBackStackEntry?.restorableRoute() == target) return@LaunchedEffect
        nav.safeNavigate(target)
    }
}

/** 路由模板里的参数占位符，如 `item/{id}` 中的 `{id}` */
private val ARG_PLACEHOLDER = Regex("\\{([^{}]+)\\}")

/**
 * 把导航栈条目还原成可以再次 navigate 的完整路由字符串。
 *
 * NavController 只暴露路由**模板**（`item/{id}`），要跳回去必须把实参填回占位符；
 * 字符串参数做 URL 编码（与项目各处 navigate 前的 encode 保持一致），
 * 否则参数里的 `/`、`?`、中文会破坏路径分段，导致恢复跳转失败。
 * 还原不出来的（缺参数）返回 null，调用方直接跳过，不会影响正常启动。
 */
private fun NavBackStackEntry.restorableRoute(): String? {
    val pattern = destination.route?.takeIf { it.isNotBlank() } ?: return null
    if (!pattern.contains('{')) return pattern
    val args = arguments ?: return null
    val route = ARG_PLACEHOLDER.replace(pattern) { m ->
        val name = m.groupValues[1]
        val value = args.get(name)
        when {
            value == null -> m.value
            value is String -> Uri.encode(value)
            else -> value.toString()
        }
    }
    // 还有没填上的占位符（参数缺失）→ 这条路由不能拿来恢复，交给调用方跳过
    return route.takeIf { !it.contains('{') }
}
