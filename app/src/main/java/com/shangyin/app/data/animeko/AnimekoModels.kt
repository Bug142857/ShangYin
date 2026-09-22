package com.shangyin.app.data.animeko

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * animeko 媒体源（open-ani/animeko）导出格式。
 *
 * 订阅文件形如：
 * ```json
 * { "exportedMediaSourceDataList": { "mediaSources": [
 *   { "factoryId": "web-selector", "version": 2, "arguments": { ... } }
 * ] } }
 * ```
 * `factoryId`：
 * - `web-selector`：网页抓取源（本项目支持）—— 用 CSS 选择器 + 正则从站点页面里找片名/剧集/播放地址
 * - `rss`：BT/磁力源（nyaa、动漫花园、蜜柑计划等）—— 需要 BT 下载引擎，本项目**不支持**，导入时直接跳过
 */
@Serializable
data class AnimekoExport(
    val exportedMediaSourceDataList: AnimekoExportList = AnimekoExportList()
)

@Serializable
data class AnimekoExportList(
    val mediaSources: List<AnimekoEntry> = emptyList()
)

@Serializable
data class AnimekoEntry(
    val factoryId: String = "",
    val version: Int = 1,
    val arguments: AnimekoArguments = AnimekoArguments()
)

@Serializable
data class AnimekoArguments(
    val name: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val searchConfig: AnimekoSearchConfig = AnimekoSearchConfig(),
    /** 频道（线路）分级，键=频道名，值=级别；本实现只做展示，不参与排序 */
    val channelTiers: Map<String, Int> = emptyMap(),
    val tier: Int = 0
)

/** 网页源的核心配置（animeko 的 SelectorSearchConfig） */
@Serializable
data class AnimekoSearchConfig(
    /** 搜索地址模板，含 {keyword} 占位符 */
    val searchUrl: String = "",
    /** 只用关键词的第一个词搜索 */
    val searchUseOnlyFirstWord: Boolean = false,
    /** 去掉关键词里的特殊符号再搜 */
    val searchRemoveSpecial: Boolean = false,
    /** 用作片名的主体名数量（0=不处理） */
    val searchUseSubjectNamesCount: Int = 0,
    /** 部分源用它覆盖请求根地址 */
    val rawBaseUrl: String = "",
    /** 同源请求最小间隔（ms），0=不限 */
    val requestInterval: Int = 0,
    /** a / indexed / json-path-indexed */
    val subjectFormatId: String = "a",
    val selectorSubjectFormatA: AnimekoSelectorA? = null,
    val selectorSubjectFormatIndexed: AnimekoSelectorIndexed? = null,
    val selectorSubjectFormatJsonPathIndexed: AnimekoSelectorJsonPath? = null,
    /** index-grouped / no-channel */
    val channelFormatId: String = "no-channel",
    val selectorChannelFormatFlattened: AnimekoSelectorChannelFlattened? = null,
    val selectorChannelFormatNoChannel: AnimekoSelectorChannelNoChannel? = null,
    val defaultResolution: String = "",
    val defaultSubtitleLanguage: String = "",
    val onlySupportsPlayers: List<String> = emptyList(),
    val filterByEpisodeSort: Boolean = true,
    val filterBySubjectName: Boolean = true,
    val selectMedia: AnimekoSelectMedia? = null,
    val matchVideo: AnimekoMatchVideo? = null
)

@Serializable
data class AnimekoSelectorA(
    /** 搜索结果里的条目选择器（每个元素/其后代 a 的 href 即详情页） */
    val selectLists: String = "",
    val preferShorterName: Boolean = false
)

@Serializable
data class AnimekoSelectorIndexed(
    val selectNames: String = "",
    val selectLinks: String = "",
    val preferShorterName: Boolean = false
)

@Serializable
data class AnimekoSelectorJsonPath(
    val selectLinks: String = "",
    val selectNames: String = "",
    val preferShorterName: Boolean = false
)

@Serializable
data class AnimekoSelectorChannelFlattened(
    /** 线路名（tab）选择器 */
    val selectChannelNames: String = "",
    /** 从线路名里取频道名的正则，需含命名组 ch */
    val matchChannelName: String = "",
    /** 各线路的剧集列表容器（与线路名按顺序对应） */
    val selectEpisodeLists: String = "",
    /** 列表容器内的剧集元素 */
    val selectEpisodesFromList: String = "",
    /** 剧集链接取子元素时用（为空则用元素自身 href） */
    val selectEpisodeLinksFromList: String = "",
    /** 从剧集名里取集数排序键的正则，需含命名组 ep */
    val matchEpisodeSortFromName: String = ""
)

@Serializable
data class AnimekoSelectorChannelNoChannel(
    val selectEpisodes: String = "",
    val selectEpisodeLinks: String = "",
    val matchEpisodeSortFromName: String = ""
)

@Serializable
data class AnimekoSelectMedia(
    val distinguishSubjectName: Boolean = false,
    val distinguishChannelName: Boolean = false
)

@Serializable
data class AnimekoMatchVideo(
    /** 是否先跟着"嵌套播放页"再找视频地址（多数站点是 iframe/跳转的解析页） */
    val enableNestedUrl: Boolean = false,
    /** 匹配嵌套播放页地址的正则 */
    val matchNestedUrl: String = "",
    /** 匹配真实播放地址的正则；含命名组 v 时取该组 */
    val matchVideoUrl: String = "",
    /** 请求视频/播放页时附带的 Cookie */
    val cookies: String = "",
    /** 请求播放页时附带的请求头 */
    val addHeadersToVideo: AnimekoAddHeaders? = null,
    /** 扫描页面 DOM 里的媒体地址（本项目作为通用兜底一并启用） */
    val scanDomMediaUrls: Boolean = false,
    /** 扫描内联 script 里的媒体地址 */
    val scanInlineScriptUrls: Boolean = false
)

@Serializable
data class AnimekoAddHeaders(
    val referer: String = "",
    @SerialName("userAgent") val userAgent: String = ""
)
