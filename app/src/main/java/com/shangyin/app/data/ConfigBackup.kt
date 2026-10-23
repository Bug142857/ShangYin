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
 * 关键配置的自动备份/恢复（登录态、坚果云 WebDAV、片源/电视源配置、主题、
 * 昵称/头像、下载存储位置、阅读器偏好、模块开关、下载任务记录）。
 *
 * 背景：用户侧更新 App 时若被卸载重装（应用商店/文件管理器行为），应用私有目录会被清空，
 * 导致豆瓣登录态和坚果云配置丢失、每次更新都要重新登录。
 * 方案：这些配置同步备份到公共目录 Download/老郑分享/config_backup.json，
 * 启动时若应用内缺失对应配置则自动从备份恢复（只补空缺，不覆盖新值）。
 * 注意：备份文件含 Cookie、应用密码与哔咔账密明文，仅适用于个人设备。
 */
object ConfigBackup {

    private const val DIR = "老郑分享"
    private const val FILE = "config_backup.json"

    /**
     * 备份范围：`app_settings` 里**所有**参与备份的键（判定见 `SettingsStore.isBackupKey`）。
     *
     * v0.308 起取消白名单：以前这里列 25 个 key，新增设置项忘了加就静默漏备份；
     * 现在任何新写入 app_settings 的配置（含以后新增的类型）自动进出备份文件。
     * 仅排除内部状态（last_cloud_sync / cache_cleared_version）与播放进度 vod_progress_*。
     */

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
            // v0.308：不再只认白名单，备份文件里所有非内部键都参与「补缺」
            for (key in obj.keys()) {
                if (!SettingsStore.isBackupKey(key)) continue
                val v = obj.optString(key, "")
                if (v.isNotBlank()) restored[key] = v
            }
            SettingsStore.importConfigIfMissing(restored)
        }.getOrDefault(0)
    }

    /** 公共备份文件的绝对路径描述（设置页展示用） */
    fun backupPath(): String =
        "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath}/$DIR/$FILE"
}
