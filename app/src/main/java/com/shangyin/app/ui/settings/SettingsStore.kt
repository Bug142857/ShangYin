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

    const val THEME_FOLLOW = "follow"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** Z-Library 默认线路域名 */
    const val DEFAULT_ZLIB_HOST = "z-library.sk"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
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

    // ---------- 配置备份/恢复（配合 ConfigBackup 写公共目录，防卸载重装丢登录态） ----------

    /** 导出关键配置快照（key 与 SP key 一致） */
    fun exportConfig(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (key in com.shangyin.app.data.ConfigBackup.KEYS) {
            out[key] = sp.getString(key, "").orEmpty()
        }
        return out
    }

    /** 恢复配置：仅补上当前 SP 缺失的 key，不覆盖已有值；返回恢复条数 */
    fun importConfigIfMissing(data: Map<String, String>): Int {
        var count = 0
        val editor = sp.edit()
        for ((key, value) in data) {
            if (value.isNotBlank() && !sp.contains(key)) {
                editor.putString(key, value)
                count++
            }
        }
        if (count > 0) editor.apply()
        return count
    }

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

    /** 哔咔漫画登录 token（JWT，为空 = 未登录） */
    var bikaToken: String
        get() = sp.getString(KEY_BIKA_TOKEN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_BIKA_TOKEN, v).apply()

    fun clearBikaToken() {
        sp.edit().remove(KEY_BIKA_TOKEN).apply()
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
