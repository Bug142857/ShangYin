package com.shangyin.app.data.zlib

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 电子书文件下载：把站点交给 WebView 的真实文件地址用 OkHttp 拉下来，
 * 直接保存到**固定目录**（不再弹系统「另存为」）：
 * - Android 10+：公共 `Download/老郑分享/书籍/`（MediaStore，无需存储权限）
 * - Android 8/9：应用外部私有目录 `Android/data/<包名>/files/书籍/`（免权限，路径提示给用户）
 *
 * ⚠️ Cookie 要按**文件地址所属域**取：文件可能落在 CDN 域，
 * 也可能仍在站点域且需要 `__diamwall` 反爬票据（所以这里从 CookieManager 取值，而不是自己拼）。
 */
object BookDownload {

    /** 书籍公共目录（Android 10+，MediaStore 的 RELATIVE_PATH） */
    const val PUBLIC_DIR = "Download/老郑分享/书籍/"

    /** 书籍在 Android 8/9 的私有目录名 */
    const val APP_DIR_NAME = "书籍"

    /** 下载结果：文件名 + 实际保存位置（人话，直接 Toast 给用户） */
    data class Result(val name: String, val where: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 下载到固定的书籍目录。写入前先确认响应确实是文件（不是额度用尽/未登录的 HTML 页面）。
     */
    suspend fun saveTo(
        context: Context,
        target: ZlibWeb.DownloadTarget,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        val ck = CookieManager.getInstance().getCookie(target.fileUrl)
        val req = Request.Builder().url(target.fileUrl).get()
            .header("User-Agent", ZlibClient.UA)
            .header("Referer", target.pageUrl)
            .header("Accept", "*/*")
            .apply { if (!ck.isNullOrBlank()) header("Cookie", ck) }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("下载失败：站点返回 HTTP ${resp.code}")
            val mime = resp.header("Content-Type").orEmpty()
            if (mime.contains("text/html", true)) {
                throw Exception(htmlReason(resp.body?.string().orEmpty()))
            }
            val name = suggestName(target, "book")
            val declared = resp.body?.contentLength() ?: -1L
            val input = resp.body?.byteStream() ?: throw Exception("下载失败：响应为空")
            val where = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToPublic(context, name, mime, input, declared, onProgress)
                PUBLIC_DIR.trimEnd('/') + "/" + name
            } else {
                saveToAppDir(context, name, input, declared, onProgress)
            }
            Result(name = name, where = where)
        }
    }

    /** Android 10+：MediaStore 公共书籍目录（写入期间 IS_PENDING=1，写完再置 0，避免半截文件被扫描到） */
    private fun saveToPublic(
        context: Context,
        fileName: String,
        mime: String,
        input: InputStream,
        declared: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // 同名文件先删掉，避免出现 (1)(2) 副本
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(fileName, PUBLIC_DIR)
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(fileName, mime))
            put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_DIR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw Exception("下载失败：无法创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                copyWithProgress(input, out, declared, onProgress)
            } ?: throw Exception("下载失败：无法写入文件")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    /** Android 8/9：写应用外部私有目录（免权限），返回实际绝对路径 */
    private fun saveToAppDir(
        context: Context,
        fileName: String,
        input: InputStream,
        declared: Long,
        onProgress: (Long, Long) -> Unit
    ): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, APP_DIR_NAME).apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { out -> copyWithProgress(input, out, declared, onProgress) }
        return file.absolutePath
    }

    private fun copyWithProgress(
        input: InputStream,
        out: java.io.OutputStream,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var n = input.read(buf)
        while (n > 0) {
            out.write(buf, 0, n)
            done += n
            onProgress(done, total)
            n = input.read(buf)
        }
        out.flush()
    }

    /** 建议文件名：优先站点给的 Content-Disposition，其次 URL 末段，最后用书名 + 站点给的扩展名 */
    fun suggestName(target: ZlibWeb.DownloadTarget, fallback: String): String {
        val fromUrl = runCatching {
            val p = java.net.URI(target.fileUrl).path.orEmpty()
            p.substringAfterLast('/').trim().takeIf { it.isNotBlank() && it.contains('.') }
        }.getOrNull()
        val raw = nameFromDisposition(target.fileName) ?: fromUrl ?: fallback
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(120).ifBlank { "book" }
        if (clean.contains('.')) return clean
        val ext = extFromMime(target.mimeType.orEmpty())
        return if (ext.isNullOrBlank()) clean else "$clean.$ext"
    }

    /** 给 MediaStore 用的 MIME：站点给的可用就用，否则按扩展名推断 */
    private fun mimeFor(fileName: String, fromServer: String): String {
        val base = fromServer.substringBefore(';').trim()
        if (base.isNotBlank() && base != "application/octet-stream" && !base.contains("text/html", true)) {
            return base
        }
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            "mobi" -> "application/x-mobipocket-ebook"
            "azw3" -> "application/vnd.amazon.ebook"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    /** Content-Disposition → 文件名（兼容 filename*=UTF-8''xxx 与 filename="xxx" 两种写法） */
    private fun nameFromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        val star = Regex("""filename\*\s*=\s*[^']*''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)
        val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)
        val raw = (star ?: plain)?.trim()?.trim('"') ?: return null
        if (raw.isBlank()) return null
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    private fun extFromMime(mime: String): String? = when {
        mime.contains("epub", true) -> "epub"
        mime.contains("pdf", true) -> "pdf"
        mime.contains("mobipocket", true) || mime.contains("mobi", true) -> "mobi"
        mime.contains("azw", true) -> "azw3"
        mime.contains("text/plain", true) -> "txt"
        mime.contains("zip", true) -> "zip"
        else -> null
    }

    /** 拿到的是 HTML 页面而不是文件（额度用尽 / 未登录 / 反爬），把页面文字翻成可操作提示 */
    private fun htmlReason(html: String): String {
        val text = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<\\s*br\\s*/?\\s*>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            text.contains("限额") || text.contains("limit", true) ->
                "Z-Library 提示：每日下载额度已用完（站点按账号/IP 限制），额度恢复后再试"
            text.contains("登录") || text.contains("log in", true) ->
                "需要登录 Z-Library 才能下载，请到 设置 → 账号管理 → Z-Library 登录"
            text.contains("未找到页面") || text.contains("not found", true) ->
                "站点返回 404：下载入口已失效，请返回重新搜索这本书"
            text.isBlank() -> "站点返回的不是文件（可能被反爬拦截），请重试"
            else -> "站点返回的不是文件：" + text.take(160)
        }
    }
}
