package com.shangyin.app.data.download

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 音乐 / 书籍下载目录的枚举与大小统计（只读，下载管理页展示用）。
 *
 * 目录约定（与 [com.shangyin.app.data.music.MusicDownloader] / [com.shangyin.app.data.zlib.BookDownload] 保持一致）：
 * - Android 10+（Q）：公共目录 `Download/老郑分享/音乐/`、`Download/老郑分享/书籍/`，
 *   用 MediaStore.Downloads 按 RELATIVE_PATH 查询；
 * - Android 8/9：应用外部私有目录，用 File.listFiles() 枚举。
 *
 * 所有 IO 都用 runCatching 包住，个别文件读不到不会整页崩。
 */
object MediaFileStore {

    /** 音乐公共目录（相对外部存储根，末尾带 / 与 MediaStore 的 RELATIVE_PATH 一致） */
    const val MUSIC_RELATIVE = "Download/老郑分享/音乐/"

    /** 书籍公共目录 */
    const val BOOK_RELATIVE = "Download/老郑分享/书籍/"

    /** 单个文件 */
    data class Entry(val name: String, val size: Long, val modified: Long)

    /**
     * 某目录的摘要。
     * @param path 展示给用户的真实路径（公共目录是相对路径，私有目录是绝对路径）
     * @param isPublic 是否公共目录（点击跳转方式不同）
     */
    data class Summary(
        val path: String,
        val isPublic: Boolean,
        val count: Int,
        val totalBytes: Long,
        val entries: List<Entry>
    )

    val EMPTY = Summary("", false, 0, 0L, emptyList())

    fun musicSummary(context: Context): Summary = summary(context, MUSIC_RELATIVE, musicAppDir(context))

    fun bookSummary(context: Context): Summary = summary(context, BOOK_RELATIVE, bookAppDir(context))

    /** Android 8/9 音乐落盘目录（与 MusicDownloader 一致） */
    fun musicAppDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "音乐")

    /** Android 8/9 书籍落盘目录（与 BookDownload 一致） */
    fun bookAppDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "书籍")

    private fun summary(context: Context, relative: String, appDir: File): Summary = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val entries = queryPublic(context, relative)
            Summary(relative.trimEnd('/'), true, entries.size, entries.sumOf { it.size }, entries)
        } else {
            val entries = listDir(appDir)
            Summary(appDir.absolutePath, false, entries.size, entries.sumOf { it.size }, entries)
        }
    }.getOrDefault(EMPTY)

    /** Android 10+：MediaStore 按 RELATIVE_PATH 查询（按修改时间倒序） */
    private fun queryPublic(context: Context, relative: String): List<Entry> {
        val out = mutableListOf<Entry>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(relative),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val timeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx).orEmpty()
                    if (name.isBlank()) continue
                    // DATE_MODIFIED 单位是秒，统一乘 1000 变毫秒
                    out += Entry(name, c.getLong(sizeIdx), c.getLong(timeIdx) * 1000L)
                }
            }
        }
        return out
    }

    /** Android 8/9：直接枚举私有目录（按修改时间倒序） */
    private fun listDir(dir: File): List<Entry> =
        runCatching { dir.listFiles() }.getOrNull().orEmpty()
            .filter { it.isFile }
            .map { Entry(it.name, it.length(), it.lastModified()) }
            .sortedByDescending { it.modified }
}
