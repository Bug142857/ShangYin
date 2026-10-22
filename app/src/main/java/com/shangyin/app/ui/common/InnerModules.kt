package com.shangyin.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OndemandVideo
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.ui.graphics.vector.ImageVector

/** 里世界模块定义：主页入口、设置 → 模块管理、收藏页共用同一份 key */
data class InnerModuleEntry(
    val key: String,        // 模块开关 / 收藏 module 标识
    val title: String,
    val icon: ImageVector,
    val route: String       // 主页入口导航路由
)

val INNER_MODULE_ENTRIES: List<InnerModuleEntry> = listOf(
    InnerModuleEntry("h1", "番号", Icons.Rounded.OndemandVideo, "h1search"),
    InnerModuleEntry("h2", "本子", Icons.Rounded.MenuBook, "h2search"),
    InnerModuleEntry("comic", "漫画", Icons.Rounded.AutoStories, "comicHome"),
    InnerModuleEntry("game", "游戏", Icons.Rounded.SportsEsports, "gameHome"),
    InnerModuleEntry("book", "图书", Icons.Rounded.Book, "bookHome"),
    InnerModuleEntry("tv", "电视", Icons.Rounded.LiveTv, "tvHome"),
    InnerModuleEntry("music", "音乐", Icons.Rounded.MusicNote, "musicHome"),
    InnerModuleEntry("melon", "吃瓜", Icons.Rounded.TheaterComedy, "melonHome"),
    InnerModuleEntry("xsite", "X站", Icons.Rounded.Tag, "xsiteHome"),
    InnerModuleEntry("psite", "P站", Icons.Rounded.SmartDisplay, "psiteHome"),
)
