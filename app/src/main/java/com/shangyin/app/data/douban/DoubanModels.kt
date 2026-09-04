package com.shangyin.app.data.douban

import com.shangyin.app.data.Category

/** 豆瓣搜索结果（统一归一化后的条目） */
data class DoubanResult(
    val category: Category,
    val doubanId: String,
    val title: String,
    val subTitle: String = "",
    val year: String = "",
    val coverUrl: String? = null,
    val url: String? = null,
    val rating: Float? = null,
    val intro: String = ""   // 搜索页附带的简介（游戏等详情接口缺失时兜底）
)

/** 演职员（条目详情页横滑列表项） */
data class DoubanCelebrity(
    val id: String,
    val name: String,
    val latinName: String = "",
    val role: String = "",      // 导演 / 饰 角色名
    val avatarUrl: String? = null
)

/** 预告片/视频 */
data class DoubanVideo(
    val id: String,
    val title: String,
    val typeName: String = "预告片",
    val coverUrl: String? = null,
    val videoUrl: String,
    val runtime: String = ""    // "01:40"
)

/** 剧照 */
data class DoubanPhoto(
    val id: String,
    val largeUrl: String?,
    val normalUrl: String?
)

/** 网友短评 */
data class DoubanInterest(
    val userName: String,
    val avatarUrl: String? = null,
    val rating: Float? = null,
    val comment: String,
    val date: String = "",
    val location: String = "",
    val votes: Int = 0
)

/** 影人详情页 */
data class DoubanCelebrityDetail(
    val name: String,
    val latinName: String = "",
    val avatarUrl: String? = null,
    val shortInfo: String = "",             // 职业 / 代表作品
    val infoPairs: List<Pair<String, String>> = emptyList(), // [出生日期, …] 等信息
    val url: String = "",
    val works: List<CelebrityWork> = emptyList()
)

/** 影人相关作品 */
data class CelebrityWork(
    val id: String,
    val title: String,
    val year: String,
    val type: String,    // movie / tv / book / music / game
    val roles: String,   // 演员 (饰 xxx)
    val rating: Float? = null,
    val coverUrl: String? = null
)

/** 从豆瓣条目页解析出的详情 */
data class DoubanDetail(
    val title: String? = null,
    val rating: Float? = null,
    val coverUrl: String? = null,
    val summary: String? = null,
    val info: String? = null,
    val directors: String? = null,
    val casts: String? = null,
    val genres: String? = null,
    val videos: List<DoubanVideo> = emptyList()
) {
    val isEmpty: Boolean
        get() = title == null && rating == null && coverUrl == null &&
            summary == null && info == null && directors == null && casts == null
}
