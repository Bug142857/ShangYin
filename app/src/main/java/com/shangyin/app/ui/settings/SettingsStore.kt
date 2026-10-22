package com.shangyin.app.ui.settings

import android.content.Context
import android.content.SharedPreferences

/** 应用设置存储（SharedPreferences，简单够用） */
object SettingsStore {

    private const val NAME = "app_settings"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_THEME = "theme"
    private const val KEY_AVATAR_URI = "avatar_uri"
    private const val KEY_DOUBAN_COOKIE = "douban_cookie"
    private const val KEY_DOUBAN_CK = "douban_ck"
    private const val KEY_WEBDAV_URL = "webdav_url"
    private const val KEY_WEBDAV_USER = "webdav_user"
    private const val KEY_WEBDAV_PASS = "webdav_pass"
    private const val KEY_LAST_CLOUD_SYNC = "last_cloud_sync"
    private const val KEY_DOWNLOAD_INTERNAL = "download_internal"
    private const val KEY_CACHE_CLEARED_VERSION = "cache_cleared_version"

    const val THEME_FOLLOW = "follow"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** Z-Library 默认线路域名 */
    const val DEFAULT_ZLIB_HOST = "z-library.sk"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        disabledInnerModules.value =
            sp.getStringSet(KEY_DISABLED_INNER_MODULES, emptySet())?.toSet() ?: emptySet()
    }

    // ---------- 里世界模块开关（设置 → 模块管理） ----------

    private const val KEY_DISABLED_INNER_MODULES = "disabled_inner_modules"

    /** 被关闭的里世界模块 key 集合（StateFlow：设置页改完主页立即生效） */
    val disabledInnerModules = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** 开关某个里世界模块入口（true=显示） */
    fun setInnerModuleEnabled(key: String, enabled: Boolean) {
        val cur = disabledInnerModules.value.toMutableSet()
        if (enabled) cur.remove(key) else cur.add(key)
        sp.edit().putStringSet(KEY_DISABLED_INNER_MODULES, cur).apply()
        disabledInnerModules.value = cur
    }

    /** 注册 SharedPreferences 变更监听（用于主题切换等） */
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var nickname: String
        get() = sp.getString(KEY_NICKNAME, "东北老郑").orEmpty()
        set(v) = sp.edit().putString(KEY_NICKNAME, v).apply()

    /** theme: follow(系统) / light / dark */
    var theme: String
        get() = sp.getString(KEY_THEME, THEME_FOLLOW) ?: THEME_FOLLOW
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    val isDark: Boolean?
        get() = when (theme) {
            THEME_DARK -> true
            THEME_LIGHT -> false
            else -> null
        }

    /** 头像 URI（由相册选择后保存） */
    var avatarUri: String
        get() = sp.getString(KEY_AVATAR_URI, "").orEmpty()
        set(v) = sp.edit().putString(KEY_AVATAR_URI, v).apply()

    /** 豆瓣 Cookie（完整字符串，用于请求头 Cookie） */
    var doubanCookie: String
        get() = sp.getString(KEY_DOUBAN_COOKIE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_DOUBAN_COOKIE, v).apply()

    /** 豆瓣 ck 值（登录态的关键 token，单独存储用于搜索 URL 参数） */
    var doubanCk: String
        get() = sp.getString(KEY_DOUBAN_CK, "").orEmpty()
        set(v) = sp.edit().putString(KEY_DOUBAN_CK, v).apply()

    /** 是否已登录豆瓣（有 dbcl cookie 才算登录态） */
    val isDoubanLoggedIn: Boolean
        get() = doubanCookie.contains("dbcl", ignoreCase = true) && doubanCk.isNotBlank()

    /** 清除豆瓣登录信息 */
    fun clearDoubanLogin() {
        sp.edit().remove(KEY_DOUBAN_COOKIE).remove(KEY_DOUBAN_CK).apply()
    }

    /** 下载存储位置：false=应用外部私有目录（默认，推荐，不占内部空间） true=应用内部私有目录 */
    var downloadInternal: Boolean
        get() = sp.getBoolean(KEY_DOWNLOAD_INTERNAL, false)
        set(v) = sp.edit().putBoolean(KEY_DOWNLOAD_INTERNAL, v).apply()

    /**
     * 已清过 Coil 磁盘缓存的应用版本号（versionCode）。启动时与当前版本比对，
     * 不一致则清一次图片磁盘缓存（v0.226：历史上污染的 200 垃圾响应被永久缓存）。
     */
    var cacheClearedVersion: Long
        get() = sp.getLong(KEY_CACHE_CLEARED_VERSION, 0L)
        set(v) = sp.edit().putLong(KEY_CACHE_CLEARED_VERSION, v).apply()

    private const val KEY_CHAPTER_SORT_DESC = "chapter_sort_desc"

    /** 漫画/本子章节列表倒序排列（全局记忆，跨重启保留） */
    var chapterSortDesc: Boolean
        get() = sp.getBoolean(KEY_CHAPTER_SORT_DESC, false)
        set(v) = sp.edit().putBoolean(KEY_CHAPTER_SORT_DESC, v).apply()

    private const val KEY_READER_VERTICAL = "reader_vertical"

    /** 阅读器滑动模式（全局记忆，跨章节/跨重启保留）：true=上下连续滑动 false=左右翻页 */
    var readerVertical: Boolean
        get() = sp.getBoolean(KEY_READER_VERTICAL, false)
        set(v) = sp.edit().putBoolean(KEY_READER_VERTICAL, v).apply()

    // ---------- 清单详情页：布局 / 音乐排序（按清单 ID 分别记忆，跨重启保留） ----------

    const val LAYOUT_GRID = "grid"
    const val LAYOUT_LIST = "list"
    const val SORT_ADDED = "added"
    const val SORT_ARTIST = "artist"

    /** 某清单的内容布局（key: list_layout_<id>）；从未设置过返回空串，由页面决定默认值 */
    fun listLayout(listId: Long): String = sp.getString("list_layout_$listId", "").orEmpty()

    fun setListLayout(listId: Long, value: String) {
        sp.edit().putString("list_layout_$listId", value).apply()
    }

    /** 某清单音乐条目的排序（key: list_sort_<id>）：added=按添加时间（默认）/ artist=按歌手名称 */
    fun listSort(listId: Long): String =
        sp.getString("list_sort_$listId", "").orEmpty().ifBlank { SORT_ADDED }

    fun setListSort(listId: Long, value: String) {
        sp.edit().putString("list_sort_$listId", value).apply()
    }

    // ---------- 配置备份/恢复（配合 ConfigBackup 写公共目录，防卸载重装丢登录态） ----------

    /** 导出关键配置快照（key 与 SP key 一致；StringSet 键转逗号串） */
    fun exportConfig(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (key in com.shangyin.app.data.ConfigBackup.KEYS) {
            out[key] = if (key == KEY_DISABLED_INNER_MODULES) {
                sp.getStringSet(key, emptySet())?.joinToString(",").orEmpty()
            } else {
                sp.getString(key, "").orEmpty()
            }
        }
        return out
    }

    /**
     * 把快照写回 SP。overwrite=false 只补本机缺失的 key（ConfigBackup 启动恢复用），
     * overwrite=true 非空值直接覆盖（主备份恢复用，恢复前有 auto_backup 兜底）。
     * 返回写入条数。
     */
    private fun applyConfig(data: Map<String, String>, overwrite: Boolean): Int {
        var count = 0
        val editor = sp.edit()
        for ((key, value) in data) {
            if (value.isBlank()) continue
            if (!overwrite && sp.contains(key)) continue
            when (key) {
                KEY_DISABLED_INNER_MODULES -> {
                    val set = value.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
                    editor.putStringSet(key, set)
                    // 同步 StateFlow，否则主页模块开关不刷新
                    disabledInnerModules.value = set
                }
                else -> editor.putString(key, value)
            }
            count++
        }
        if (count > 0) editor.apply()
        return count
    }

    /** 恢复配置：仅补上当前 SP 缺失的 key，不覆盖已有值；返回恢复条数 */
    fun importConfigIfMissing(data: Map<String, String>): Int = applyConfig(data, overwrite = false)

    /** 主备份恢复：备份里的非空配置直接覆盖本机值（含 StringSet 键还原 + StateFlow 同步） */
    fun importSnapshot(data: Map<String, String>): Int = applyConfig(data, overwrite = true)

    // ---------- WebDAV 云同步 ----------

    /** WebDAV 服务器地址，如 https://dav.jianguoyun.com/dav/ */
    var webdavUrl: String
        get() = sp.getString(KEY_WEBDAV_URL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WEBDAV_URL, v).apply()

    var webdavUser: String
        get() = sp.getString(KEY_WEBDAV_USER, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WEBDAV_USER, v).apply()

    /** WebDAV 应用密码（坚果云等用应用密码而非登录密码） */
    var webdavPass: String
        get() = sp.getString(KEY_WEBDAV_PASS, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WEBDAV_PASS, v).apply()

    /** 上次云同步成功时间戳（0 = 从未同步） */
    var lastCloudSync: Long
        get() = sp.getLong(KEY_LAST_CLOUD_SYNC, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_CLOUD_SYNC, v).apply()

    val isWebdavConfigured: Boolean
        get() = webdavUrl.isNotBlank() && webdavUser.isNotBlank() && webdavPass.isNotBlank()

    fun clearWebdavConfig() {
        sp.edit().remove(KEY_WEBDAV_URL).remove(KEY_WEBDAV_USER)
            .remove(KEY_WEBDAV_PASS).remove(KEY_LAST_CLOUD_SYNC).apply()
    }

    // ---------- 哔咔漫画 ----------

    private const val KEY_BIKA_TOKEN = "bika_token"
    private const val KEY_BIKA_ACCOUNT = "bika_account"
    private const val KEY_BIKA_PASSWORD = "bika_password"

    /** 哔咔漫画登录 token（JWT，为空 = 未登录） */
    var bikaToken: String
        get() = sp.getString(KEY_BIKA_TOKEN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_BIKA_TOKEN, v).apply()

    /** 哔咔账号（用户名/邮箱，用于 token 过期后静默重登） */
    var bikaAccount: String
        get() = sp.getString(KEY_BIKA_ACCOUNT, "").orEmpty()
        set(v) = sp.edit().putString(KEY_BIKA_ACCOUNT, v).apply()

    /** 哔咔密码（明文存本机，仅供 token 过期后自动重登） */
    var bikaPassword: String
        get() = sp.getString(KEY_BIKA_PASSWORD, "").orEmpty()
        set(v) = sp.edit().putString(KEY_BIKA_PASSWORD, v).apply()

    fun clearBikaToken() {
        sp.edit().remove(KEY_BIKA_TOKEN).apply()
    }

    /** 清除哔咔账号密码（登出时用） */
    fun clearBikaCredentials() {
        sp.edit().remove(KEY_BIKA_ACCOUNT).remove(KEY_BIKA_PASSWORD).apply()
    }

    // ---------- 无忧游戏库（www.wygamer.com） ----------

    private const val KEY_WYGAMER_COOKIE = "wygamer_cookie"

    /** 无忧游戏库 Cookie（WebView 登录后抓取，用于带登录态抓取页面） */
    var wygamerCookie: String
        get() = sp.getString(KEY_WYGAMER_COOKIE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_WYGAMER_COOKIE, v).apply()

    /** 是否已登录无忧游戏库（Zibll 登录票据） */
    val isWygamerLoggedIn: Boolean
        get() = wygamerCookie.contains("zibll", ignoreCase = true) ||
            wygamerCookie.contains("wordpress_logged_in", ignoreCase = true)

    fun clearWygamerLogin() {
        sp.edit().remove(KEY_WYGAMER_COOKIE).apply()
    }

    // ---------- Z-Library（图书） ----------

    private const val KEY_ZLIB_COOKIE = "zlib_cookie"
    private const val KEY_ZLIB_HOST = "zlib_host"

    /** Z-Library Cookie（含 remix_userid / remix_userkey + 反爬验证票据） */
    var zlibCookie: String
        get() = sp.getString(KEY_ZLIB_COOKIE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_ZLIB_COOKIE, v).apply()

    /** Z-Library 线路域名（可换，默认 z-library.sk），不带协议 */
    var zlibHost: String
        get() = sp.getString(KEY_ZLIB_HOST, DEFAULT_ZLIB_HOST).orEmpty().ifBlank { DEFAULT_ZLIB_HOST }
        set(v) = sp.edit().putString(KEY_ZLIB_HOST, v.trim().trimEnd('/').removePrefix("https://")).apply()

    fun clearZlibLogin() {
        sp.edit().remove(KEY_ZLIB_COOKIE).apply()
    }

    // ---------- 吃瓜（51爆料） ----------

    private const val KEY_MELON_BASE = "melon_base"

    /** 吃瓜站点当前可用镜像（自动发现+探活后持久化；失效后由客户端重新选线覆盖） */
    var melonBase: String
        get() = sp.getString(KEY_MELON_BASE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_MELON_BASE, v.trim().trimEnd('/')).apply()

    private const val KEY_VIDEO_DOWNLOADS = "video_downloads_json"

    /** 已下载视频记录（JSON 数组；文件本体在应用私有外存「视频/」目录，不随备份导出） */
    var videoDownloadsJson: String
        get() = sp.getString(KEY_VIDEO_DOWNLOADS, "[]").orEmpty()
        set(v) = sp.edit().putString(KEY_VIDEO_DOWNLOADS, v).apply()

    // ---------- 电视（自定义 M3U 源） ----------

    private const val KEY_LIVE_SOURCES = "live_sources_json"

    /** 自定义直播源列表（JSON 持久化；网络地址或本地导入的 m3u 内容） */
    var liveSourcesJson: String
        get() = sp.getString(KEY_LIVE_SOURCES, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LIVE_SOURCES, v).apply()

    fun getLiveSources(): List<com.shangyin.app.data.live.LiveSource> = runCatching {
        if (liveSourcesJson.isBlank()) emptyList()
        else vodJson.decodeFromString<List<com.shangyin.app.data.live.LiveSource>>(liveSourcesJson)
    }.getOrDefault(emptyList())

    fun setLiveSources(list: List<com.shangyin.app.data.live.LiveSource>) {
        liveSourcesJson = vodJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                com.shangyin.app.data.live.LiveSource.serializer()
            ),
            list
        )
    }

    /** 首次使用（或用户把电视源全删了）时播种内置默认电视源 */
    fun ensureDefaultLiveSourcesSeeded() {
        if (!sp.contains(KEY_LIVE_SOURCES) || getLiveSources().isEmpty()) {
            setLiveSources(com.shangyin.app.data.live.DEFAULT_LIVE_SOURCES)
        }
    }

    // ---------- 在线观影（片源管理 + 播放进度） ----------

    private const val KEY_VOD_SOURCES = "vod_sources_json"
    private const val KEY_VOD_PROGRESS_PREFIX = "vod_progress_"

    private val vodJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** 采集源列表（JSON 持久化） */
    var vodSourcesJson: String
        get() = sp.getString(KEY_VOD_SOURCES, "").orEmpty()
        set(v) = sp.edit().putString(KEY_VOD_SOURCES, v).apply()

    fun getVodSources(): List<com.shangyin.app.data.vod.VodSource> = runCatching {
        if (vodSourcesJson.isBlank()) emptyList()
        else vodJson.decodeFromString<List<com.shangyin.app.data.vod.VodSource>>(vodSourcesJson)
    }.getOrDefault(emptyList())

    fun setVodSources(list: List<com.shangyin.app.data.vod.VodSource>) {
        vodSourcesJson = vodJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.shangyin.app.data.vod.VodSource.serializer()),
            list
        )
    }

    /**
     * 首次使用时播种内置默认源（仅当从未配置过片源时执行一次；
     * 用户之后删光所有源也不会重新播种）。
     */
    fun ensureDefaultVodSourcesSeeded() {
        if (!sp.contains(KEY_VOD_SOURCES)) {
            setVodSources(com.shangyin.app.data.vod.DEFAULT_VOD_SOURCES)
        }
    }

    /** 播放进度 key：play_{itemId}_{episodeUrl.hashCode()} */
    fun vodProgressKey(itemId: Long, episodeUrl: String): String =
        "play_${itemId}_${episodeUrl.hashCode()}"

    /** 读取播放进度，返回 (positionMs, durationMs, timestampMs)；无记录返回 null */
    fun getVodProgress(key: String): Triple<Long, Long, Long>? {
        val raw = sp.getString(KEY_VOD_PROGRESS_PREFIX + key, null) ?: return null
        val parts = raw.split(";")
        val pos = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val dur = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val ts = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        return Triple(pos, dur, ts)
    }

    fun saveVodProgress(key: String, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0L) return
        sp.edit().putString(KEY_VOD_PROGRESS_PREFIX + key, "$positionMs;$durationMs;${System.currentTimeMillis()}").apply()
    }

    fun clearVodProgress(key: String) {
        sp.edit().remove(KEY_VOD_PROGRESS_PREFIX + key).apply()
    }
}
