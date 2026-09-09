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
}
