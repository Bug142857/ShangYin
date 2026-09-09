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

    const val THEME_FOLLOW = "follow"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

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
}
