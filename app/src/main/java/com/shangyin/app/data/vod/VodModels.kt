package com.shangyin.app.data.vod

import kotlinx.serialization.Serializable

/**
 * 苹果CMS V10 采集源配置（用户在"片源管理"里维护）。
 * baseUrl 形如 https://jszyapi.com/api.php/provide/vod，搜索时拼 ?ac=videolist&wd=标题
 */
@Serializable
data class VodSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    /** 所属目录：cn=国内可访问（默认）/ proxy=需外网环境 */
    val region: String = "cn",
    /** 链接测试：null 未测 / ok 可用 / dead 已失效 / proxy 需外网 */
    val testStatus: String? = null,
    /** 测试结果描述，如"可用 · 共 123 部" */
    val testMsg: String? = null,
    /** 最近测试时间戳（ms） */
    val testAt: Long = 0L
)

/** 苹果CMS videolist 接口响应（字段全默认值，防个别源缺字段解析崩） */
@Serializable
data class VodResp(
    val code: Int = 0,
    val msg: String? = null,
    val page: Int = 1,
    val pagecount: Int = 1,
    val total: Int = 0,
    val list: List<VodItem> = emptyList()
)

/** 采集站影片条目（搜索/详情通用） */
@Serializable
data class VodItem(
    val vod_id: Long = 0,
    val vod_name: String = "",
    val vod_pic: String = "",
    val type_name: String = "",
    val vod_year: String = "",
    val vod_area: String = "",
    val vod_remarks: String = "",
    val vod_douban_id: Long = 0,
    /** 播放组名，如 "jsyun$$$jsm3u8" */
    val vod_play_from: String = "",
    /** 剧集数据：组$$$组#集$下标分隔，如 "第01集$url1#第02集$url2$$$第01集$url3" */
    val vod_play_url: String = ""
)

/** 一个播放组（对应 vod_play_from 的一项）及其剧集列表 */
data class VodPlayGroup(
    val name: String,
    val episodes: List<VodEpisode>
) {
    val isEmpty: Boolean get() = episodes.isEmpty()
}

data class VodEpisode(
    val name: String,
    val url: String
)

/** 单源搜索结果（含错误信息，UI 可显示"该源无结果/失败"） */
data class SourceResult(
    val source: VodSource,
    val items: List<VodItem>,
    val error: String? = null
)
