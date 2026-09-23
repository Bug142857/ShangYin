package com.shangyin.app.data.music

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 音乐下载：把 24bit 的播放直链存到手机里。
 *
 * - Android 10+（Q）：写公共 `Download/老郑分享/音乐/`（MediaStore，无需存储权限）
 * - Android 8/9：写应用外部私有目录 `Android/data/<包名>/files/Music/`（免权限，路径会提示给用户）
 */
object MusicDownloader {

    /** 文件名非法字符统一换成下划线，避免个别系统写入失败 */
    private val illegal = Regex("""[\\/:*?"<>|]""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 下载并保存。[onProgress] 回传 0..100（拿不到总长度时不回调）。
     * 返回保存位置的人话描述；失败抛异常（消息直接展示给用户）。
     */
    suspend fun download(
        context: Context,
        song: MusicSong,
        onProgress: (Int) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val info = MusicRepo.resolvePlay(song)
        val ext = extensionOf(info.url)
        val fileName = "${sanitize(song.name)} - ${sanitize(song.artists.ifBlank { "未知歌手" })}.$ext"
        val mime = if (ext == "flac") "audio/flac" else "audio/mpeg"

        val request = Request.Builder().url(info.url).apply {
            info.headers.forEach { (k, v) -> header(k, v) }
        }.build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("下载失败：响应为空")
            val total = body.contentLength()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToPublicDownloads(context, fileName, mime, total, body.byteStream(), onProgress)
                "Download/老郑分享/音乐/$fileName"
            } else {
                saveToAppDir(context, fileName, total, body.byteStream(), onProgress)
            }
        }
    }

    /** Android 10+：MediaStore 公共下载目录（写入期间 IS_PENDING=1，写完再置 0，避免半截文件被扫描到） */
    private fun saveToPublicDownloads(
        context: Context,
        fileName: String,
        mime: String,
        total: Long,
        input: java.io.InputStream,
        onProgress: (Int) -> Unit
    ) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // 同名文件先删掉，避免出现 (1)(2) 副本
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(fileName, "Download/老郑分享/音乐/")
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/老郑分享/音乐/")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("下载失败：无法创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                copyWithProgress(input, out, total, onProgress)
            } ?: throw IllegalStateException("下载失败：无法写入文件")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    /** Android 8/9：写应用外部私有目录（免权限） */
    private fun saveToAppDir(
        context: Context,
        fileName: String,
        total: Long,
        input: java.io.InputStream,
        onProgress: (Int) -> Unit
    ): String {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
            "音乐"
        ).apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { out -> copyWithProgress(input, out, total, onProgress) }
        return file.absolutePath
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        total: Long,
        onProgress: (Int) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var lastPercent = -1
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
            copied += n
            if (total > 0) {
                val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }
        out.flush()
    }

    /** 从直链推断扩展名（24bit 的直链以 .mp3 / .flac 结尾） */
    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".flac") -> "flac"
            path.endsWith(".m4a") -> "m4a"
            path.endsWith(".wav") -> "wav"
            path.endsWith(".ogg") -> "ogg"
            else -> "mp3"
        }
    }

    private fun sanitize(text: String): String =
        illegal.replace(text, "_").trim().take(60).ifBlank { "未命名" }
}
