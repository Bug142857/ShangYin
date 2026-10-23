package com.shangyin.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.graphics.vector.ImageVector

/** 里世界模块定义：主页入口、设置 → 模块管理、收藏页共用同一份 key */
data class InnerModuleEntry(
    val key: String,        // 模块开关 / 收藏 module 标识
    val title: String,
    val icon: ImageVector,
    val route: String       // 主页入口导航路由
)

val INNER_MODULE_ENTRIES: List<InnerModuleEntry> = listOf(
    InnerModuleEntry("h1", "番号", Icons.Rounded.Movie, "h1search"),
    InnerModuleEntry("h2", "本子", Icons.Rounded.Book, "h2search"),
    InnerModuleEntry("comic", "漫画", Icons.AutoMirrored.Rounded.Chat, "comicHome"),
    InnerModuleEntry("game", "游戏", Icons.Rounded.SportsEsports, "gameHome"),
    InnerModuleEntry("book", "图书", Icons.Rounded.MenuBook, "bookHome"),
    InnerModuleEntry("tv", "电视", Icons.Rounded.Tv, "tvHome"),
    InnerModuleEntry("music", "音乐", Icons.Rounded.MusicNote, "musicHome"),
    InnerModuleEntry("melon", "吃瓜", Icons.Rounded.EmojiEmotions, "melonHome"),
    InnerModuleEntry("xsite", "X站", Icons.Rounded.Cancel, "xsiteHome"),
    InnerModuleEntry("psite", "P站", Icons.Rounded.PlayCircle, "psiteHome"),
)
