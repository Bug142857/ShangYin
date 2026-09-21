package com.shangyin.app.data.zlib

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 电子书文件下载：把站点交给 WebView 的真实文件地址用 OkHttp 拉下来，存进系统「下载」目录。
 *
 * ⚠️ Cookie 要按**文件地址所属域**取：文件可能落在 CDN 域，
 * 也可能仍在站点域且需要 `__diamwall` 反爬票据（所以这里从 CookieManager 取值，而不是自己拼）。
 */
object BookDownload {

    data class Result(val name: String, val where: String, val uri: Uri?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun save(
        context: Context,
        target: ZlibWeb.DownloadTarget,
        fallbackName: String,
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
            val text = if (mime.contains("text/html", true)) resp.body?.string().orEmpty() else null
            if (text != null) throw Exception(htmlReason(text))
            val name = pickName(target, fallbackName, mime)
            val sink = openSink(context, name, mime)
            val declared = resp.body?.contentLength() ?: -1L
            try {
                resp.body?.byteStream()?.use { input ->
                    sink.stream.use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var n = input.read(buf)
                        while (n > 0) {
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, declared)
                            n = input.read(buf)
                        }
                    }
                }
            } catch (e: Exception) {
                sink.abort()
                throw e
            }
            sink.commit()
            Result(name, sink.where, sink.uri)
        }
    }

    /** 下载到「系统下载目录」：Android 10+ 走 MediaStore（无需权限），低版本写公共目录，再兜底应用目录 */
    private class Sink(
        val uri: Uri?,
        val stream: OutputStream,
        val where: String,
        private val onAbort: () -> Unit,
        private val onCommit: () -> Unit
    ) {
        fun commit() = onCommit()
        fun abort() = runCatching { onAbort() }
    }

    private fun openSink(context: Context, name: String, mime: String): Sink {
        val type = mime.substringBefore(';').trim().ifBlank { guessMime(name) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, type)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("insert 返回空")
                val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("openOutputStream 返回空")
                Sink(
                    uri = uri,
                    stream = out,
                    where = "下载/$name",
                    onAbort = { resolver.delete(uri, null, null) },
                    onCommit = {
                        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        runCatching { resolver.update(uri, done, null, null) }
                    }
                )
            }.getOrNull()?.let { return it }
        }
        // 兼容旧系统 / MediaStore 失败：写公共下载目录，再兜底应用专属目录
        val dir = runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }.getOrNull()
        if (dir != null && (dir.exists() || dir.mkdirs()) && canWrite(dir)) {
            val f = File(dir, name)
            return Sink(null, f.outputStream(), "下载/${f.name}", onAbort = { f.delete() }, onCommit = {})
        }
        val app = File(context.getExternalFilesDir(null) ?: context.filesDir, "books").apply { mkdirs() }
        val f = File(app, name)
        return Sink(null, f.outputStream(), f.absolutePath, onAbort = { f.delete() }, onCommit = {})
    }

    private fun canWrite(dir: File): Boolean = runCatching {
        val probe = File(dir, ".sy_write_test")
        val ok = probe.createNewFile() || probe.exists()
        probe.delete()
        ok
    }.getOrDefault(false)

    /** 文件名：优先站点给的 Content-Disposition，其次 URL 末段，最后用书名 + 站点给的扩展名 */
    private fun pickName(target: ZlibWeb.DownloadTarget, fallback: String, mime: String): String {
        val fromUrl = runCatching {
            val p = java.net.URI(target.fileUrl).path.orEmpty()
            p.substringAfterLast('/').trim().takeIf { it.isNotBlank() && it.contains('.') }
        }.getOrNull()
        val raw = nameFromDisposition(target.fileName) ?: fromUrl ?: fallback
        val clean = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(120).ifBlank { "book" }
        // 没有扩展名时按 MIME 补一个，方便系统用它选打开方式
        if (clean.contains('.')) return clean
        val ext = extFromMime(target.mimeType.orEmpty()) ?: extFromMime(mime)
        return if (ext.isNullOrBlank()) clean else "$clean.$ext"
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

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "mobi", "azw", "azw3" -> "application/x-mobipocket-ebook"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
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
