package com.shangyin.app.ui.settings

import android.content.Context
import android.content.SharedPreferences

/** 应用设置存储（SharedPreferences，简单够用） */
object SettingsStore {

    private const val NAME = "app_settings"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_THEME = "theme"
    private const val KEY_AVATAR_URI = "avatar_uri"

    const val THEME_FOLLOW = "follow"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
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
}
