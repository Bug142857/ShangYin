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
    // v0.304：番号按片源拆成四个入口（番1~番4，各自只看对应片源，避免混在一起太杂）
    InnerModuleEntry("h1_1", "番1", Icons.Rounded.Movie, "h1search?idx=1"),
    InnerModuleEntry("h1_2", "番2", Icons.Rounded.Movie, "h1search?idx=2"),
    InnerModuleEntry("h1_3", "番3", Icons.Rounded.Movie, "h1search?idx=3"),
    InnerModuleEntry("h1_4", "番4", Icons.Rounded.Movie, "h1search?idx=4"),
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
