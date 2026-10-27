package com.shangyin.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.LiveTv
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
    // v0.316：表世界并入「清单」模块（搜索 + 分类 + 清单），放在游戏前面
    InnerModuleEntry("lists", "清单", Icons.Rounded.ListAlt, "listsHome"),
    // v0.316：观影 = 清单详情里的「在线观看」独立成模块（关键词跨片源搜索）
    InnerModuleEntry("watch", "观影", Icons.Rounded.LiveTv, "watchHome"),
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

/** 收藏管理（设置 → 收藏管理）与各模块收藏页共用的模块清单（module key 与 InnerFavorites 一致） */
data class FavModuleEntry(
    val key: String,
    val title: String,
    val icon: ImageVector
)

val FAV_MODULE_ENTRIES: List<FavModuleEntry> = listOf(
    // 顺序与主页模块宫格保持一致（v0.317：音乐与游戏换位）
    FavModuleEntry("watch", "观影", Icons.Rounded.LiveTv),
    FavModuleEntry("music", "音乐", Icons.Rounded.MusicNote),
    FavModuleEntry("game", "游戏", Icons.Rounded.SportsEsports),
    FavModuleEntry("tv", "电视", Icons.Rounded.Tv),
    FavModuleEntry("melon", "吃瓜", Icons.Rounded.EmojiEmotions),
    FavModuleEntry("book", "图书", Icons.Rounded.MenuBook),
    FavModuleEntry("comic", "漫画", Icons.AutoMirrored.Rounded.Chat),
    FavModuleEntry("h2", "本子", Icons.Rounded.Book),
    FavModuleEntry("h1", "番号", Icons.Rounded.Movie),
    FavModuleEntry("psite", "P站", Icons.Rounded.PlayCircle),
    FavModuleEntry("xsite", "X站", Icons.Rounded.Cancel),
)
