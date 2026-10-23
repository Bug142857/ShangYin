package com.shangyin.app.data.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
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

    /** 视频公共目录（v0.225：与音乐/书籍统一） */
    const val VIDEO_RELATIVE = "Download/老郑分享/视频/"

    /** 单个文件。[uri] 可直接交给播放器（content:// 或 file://），为空表示拿不到 */
    data class Entry(
        val name: String,
        val size: Long,
        val modified: Long,
        val uri: String? = null
    )

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

    /** 视频目录摘要（v0.225；Android 8/9 私有目录与 VideoDownloadManager.videosDir 一致） */
    fun videoSummary(context: Context): Summary =
        summary(context, VIDEO_RELATIVE, File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "视频"))

    /** Android 8/9 音乐落盘目录（与 MusicDownloader 一致） */
    fun musicAppDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "音乐")

    /** Android 8/9 书籍落盘目录（与 BookDownload 一致） */
    fun bookAppDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "书籍")

    /**
     * 删除一个下载文件（下载管理「编辑→选择删除」用）。
     * Q+ 按 RELATIVE_PATH + DISPLAY_NAME 找 MediaStore 记录删；8/9 直接删私有目录文件。
     */
    fun deleteEntry(context: Context, relative: String, name: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relative, name),
                null
            )?.use { c ->
                var deleted = false
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                    )
                    if (resolver.delete(uri, null, null) > 0) deleted = true
                }
                deleted
            } ?: false
        } else {
            File(appDirFor(relative, context), name).delete()
        }
    }.getOrDefault(false)

    /**
     * v0.302：按枚举出的 Entry 直接删除（优先用 entry 自带 URI，命中更准）。
     * 修复：按名称匹配在某些机型（DISPLAY_NAME 被系统改名/后缀大小写不一致）找不到记录，
     * 导致书籍等文件在手机里没被真正删除。
     */
    fun deleteEntry(context: Context, entry: Entry): Boolean = runCatching {
        val uri = entry.uri
        when {
            uri == null -> false
            uri.startsWith("content:") ->
                context.contentResolver.delete(android.net.Uri.parse(uri), null, null) > 0
            uri.startsWith("file:") ->
                File(uri.removePrefix("file://")).delete()
            else -> File(uri).delete()
        }
    }.getOrDefault(false)

    private fun appDirFor(relative: String, context: Context): File = when (relative) {
        MUSIC_RELATIVE -> musicAppDir(context)
        BOOK_RELATIVE -> bookAppDir(context)
        else -> File(context.getExternalFilesDir(null) ?: context.filesDir, "下载")
    }

    /** 视频元信息：时长（毫秒）+ 封面帧 */
    data class VideoMeta(val durationMs: Long = 0L, val thumbnail: Bitmap? = null)

    /**
     * 读取本地视频的时长 + 封面帧（下载管理网格用）。
     * location 支持 content://（Q+ 公共目录）与文件绝对路径（8/9）。
     */
    fun loadVideoMeta(context: Context, location: String): VideoMeta = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (location.startsWith("content:")) {
                retriever.setDataSource(context, android.net.Uri.parse(location))
            } else {
                retriever.setDataSource(location)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val thumb = loadThumb(context, location, retriever)
            VideoMeta(duration, thumb)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(VideoMeta())

    private fun loadThumb(context: Context, location: String, retriever: MediaMetadataRetriever): Bitmap? =
        runCatching {
            if (location.startsWith("content:") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(
                    android.net.Uri.parse(location), Size(320, 180), CancellationSignal()
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }.getOrNull()

    /** 音乐元信息：时长（毫秒）+ 内嵌封面图 + 歌手（v0.302 增加歌手，下载列表与音乐收藏对齐用） */
    data class MusicMeta(val durationMs: Long = 0L, val cover: Bitmap? = null, val artist: String = "")

    fun loadMusicMeta(context: Context, entry: Entry): MusicMeta = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (entry.uri?.startsWith("content:") == true) {
                retriever.setDataSource(context, android.net.Uri.parse(entry.uri))
            } else {
                retriever.setDataSource(entry.uri ?: entry.name)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val cover = retriever.embeddedPicture?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: ""
            MusicMeta(duration, cover, artist)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(MusicMeta())

    /**
     * 书籍封面（下载管理用）：PDF 取首页渲染，EPUB 从压缩包里找 cover 图片，
     * 其余格式返回 null（UI 用文件名占位）。
     */
    fun loadBookCover(context: Context, entry: Entry): Bitmap? {
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> pdfFirstPage(context, entry)
            "epub" -> epubCover(context, entry)
            else -> null
        }
    }

    private fun pdfFirstPage(context: Context, entry: Entry): Bitmap? = runCatching {
        val pfd = if (entry.uri?.startsWith("content:") == true) {
            context.contentResolver.openFileDescriptor(android.net.Uri.parse(entry.uri), "r")
        } else {
            val f = File(entry.uri ?: entry.name)
            if (f.exists()) android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            else null
        } ?: return@runCatching null
        pfd.use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val w = 240
                    val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    private fun epubCover(context: Context, entry: Entry): Bitmap? = runCatching {
        val bytes = if (entry.uri?.startsWith("content:") == true) {
            context.contentResolver.openInputStream(android.net.Uri.parse(entry.uri))?.use { it.readBytes() }
        } else {
            File(entry.uri ?: entry.name).takeIf { it.exists() }?.readBytes()
        } ?: return@runCatching null
        val zip = java.util.zip.ZipInputStream(bytes.inputStream())
        var cover: ByteArray? = null
        val coverRegex = Regex("(?i).*/?cover[^/]*\\.(jpe?g|png)")
        zip.forEachEntry { e ->
            if (cover == null && coverRegex.matches(e.name)) {
                cover = zip.readBytes()
            }
        }
        cover?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }.getOrNull()

    private inline fun java.util.zip.ZipInputStream.forEachEntry(action: (java.util.zip.ZipEntry) -> Unit) {
        while (true) {
            val e = nextEntry ?: break
            action(e)
            closeEntry()
        }
        close()
    }

    private fun summary(context: Context, relative: String, appDir: File): Summary = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val entries = queryPublic(context, relative)
            Summary(relative.trimEnd('/'), true, entries.size, entries.sumOf { it.size }, entries)
        } else {
            val entries = listDir(appDir)
            Summary(appDir.absolutePath, false, entries.size, entries.sumOf { it.size }, entries)
        }
    }.getOrDefault(EMPTY)

    /** Android 10+：MediaStore 按 RELATIVE_PATH 查询（按修改时间倒序），带可播放的 content:// URI */
    private fun queryPublic(context: Context, relative: String): List<Entry> {
        val out = mutableListOf<Entry>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
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
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val timeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx).orEmpty()
                    if (name.isBlank()) continue
                    // DATE_MODIFIED 单位是秒，统一乘 1000 变毫秒
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idIdx)
                    )
                    out += Entry(name, c.getLong(sizeIdx), c.getLong(timeIdx) * 1000L, uri.toString())
                }
            }
        }
        return out
    }

    /** Android 8/9：直接枚举私有目录（按修改时间倒序），URI 用 file:// 绝对路径 */
    private fun listDir(dir: File): List<Entry> =
        runCatching { dir.listFiles() }.getOrNull().orEmpty()
            .filter { it.isFile }
            .map { Entry(it.name, it.length(), it.lastModified(), "file://${it.absolutePath}") }
            .sortedByDescending { it.modified }
}
