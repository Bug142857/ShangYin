package com.shangyin.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.shangyin.app.ui.settings.SettingsStore
import org.json.JSONObject

/**
 * 关键配置的自动备份/恢复（豆瓣 Cookie、坚果云 WebDAV、片源配置、主题）。
 *
 * 背景：用户侧更新 App 时若被卸载重装（应用商店/文件管理器行为），应用私有目录会被清空，
 * 导致豆瓣登录态和坚果云配置丢失、每次更新都要重新登录。
 * 方案：这些配置同步备份到公共目录 Download/老郑分享/config_backup.json，
 * 启动时若应用内缺失对应配置则自动从备份恢复（只补空缺，不覆盖新值）。
 * 注意：备份文件含 Cookie 和应用密码明文，仅适用于个人设备。
 */
object ConfigBackup {

    private const val DIR = "老郑分享"
    private const val FILE = "config_backup.json"

    /** 支持备份/恢复的配置 key（与 SettingsStore 的 SP key 一致） */
    val KEYS = listOf(
        "douban_cookie",
        "douban_ck",
        "webdav_url",
        "webdav_user",
        "webdav_pass",
        "vod_sources_json",
        "anime_sources_json",
        "bika_token",
        "wygamer_cookie",
        "zlib_cookie",
        "zlib_host",
        "theme"
    )

    /** 备份当前配置到公共 Download 目录（API 29+ 用 MediaStore，旧版本跳过） */
    fun backup(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val json = JSONObject(SettingsStore.exportConfig()).toString()
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // 删除旧备份（避免出现 (1)(2) 副本）
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(FILE, "Download/$DIR/"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    runCatching {
                        resolver.delete(ContentUris.withAppendedId(collection, c.getLong(0)), null, null)
                    }
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$DIR/")
            }
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: return false
            true
        }.getOrDefault(false)
    }

    /** 启动时恢复：仅当应用内缺失对应 key 且备份里有非空值时补上（不覆盖现有配置） */
    fun restoreIfNeeded(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return runCatching {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            var uri: android.net.Uri? = null
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(FILE, "Download/$DIR/"),
                null
            )?.use { c ->
                if (c.moveToFirst()) uri = ContentUris.withAppendedId(collection, c.getLong(0))
            }
            val target = uri ?: return 0
            val json = resolver.openInputStream(target)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return 0
            val obj = JSONObject(json)
            val restored = mutableMapOf<String, String>()
            for (key in KEYS) {
                if (obj.has(key)) {
                    val v = obj.optString(key, "")
                    if (v.isNotBlank()) restored[key] = v
                }
            }
            SettingsStore.importConfigIfMissing(restored)
        }.getOrDefault(0)
    }

    /** 公共备份文件的绝对路径描述（设置页展示用） */
    fun backupPath(): String =
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath}/$DIR/$FILE"
}
