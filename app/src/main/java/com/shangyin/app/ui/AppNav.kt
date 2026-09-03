package com.shangyin.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shangyin.app.ui.celebrity.CelebrityScreen
import com.shangyin.app.ui.home.HomeScreen
import com.shangyin.app.ui.item.ItemDetailScreen
import com.shangyin.app.ui.lists.ListDetailScreen
import com.shangyin.app.ui.search.SearchScreen
import com.shangyin.app.ui.settings.SettingsScreen

@Composable
fun AppNav(onThemeChanged: () -> Unit = {}) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(nav) }
        composable("search") { SearchScreen(nav) }
        composable(
            route = "item/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            ItemDetailScreen(nav, entry.arguments?.getLong("id") ?: 0L)
        }
        composable(
            route = "celebrity/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) { entry ->
            CelebrityScreen(nav, entry.arguments?.getString("id").orEmpty())
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
