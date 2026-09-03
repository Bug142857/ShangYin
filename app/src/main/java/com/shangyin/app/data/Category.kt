package com.shangyin.app.data

/**
 * 豆瓣搜索分类（对应豆瓣不同条目接口）。
 * 注意：主页分类标签由用户自定义，此枚举仅用于豆瓣搜索/详情抓取的接口映射。
 */
enum class Category(val label: String) {
    MOVIE("电影"),
    TV("剧集"),
    BOOK("图书"),
    GAME("游戏"),
    MUSIC("音乐")
}
