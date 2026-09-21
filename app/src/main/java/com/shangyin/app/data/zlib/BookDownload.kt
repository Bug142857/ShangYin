package com.shangyin.app.data.zlib

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 电子书文件下载：把站点交给 WebView 的真实文件地址用 OkHttp 拉下来，
 * 写到用户通过系统「保存到…」（SAF）选定的位置。
 *
 * ⚠️ Cookie 要按**文件地址所属域**取：文件可能落在 CDN 域，
 * 也可能仍在站点域且需要 `__diamwall` 反爬票据（所以这里从 CookieManager 取值，而不是自己拼）。
 */
object BookDownload {

    data class Result(val name: String, val where: String, val uri: Uri)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 下载到用户选定的目标（`ACTION_CREATE_DOCUMENT` 返回的 URI）。
     * 写入前先确认响应确实是文件（不是额度用尽/未登录的 HTML 页面）。
     */
    suspend fun saveTo(
        context: Context,
        targetUri: Uri,
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
            val name = fileNameFromUri(targetUri.toString())
                ?: suggestName(target, "book")
            val declared = resp.body?.contentLength() ?: -1L
            val out = context.contentResolver.openOutputStream(targetUri)
                ?: throw Exception("无法写入所选位置（请换一个目录）")
            try {
                resp.body?.byteStream()?.use { input ->
                    out.use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var n = input.read(buf)
                        while (n > 0) {
                            o.write(buf, 0, n)
                            done += n
                            onProgress(done, declared)
                            n = input.read(buf)
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { context.contentResolver.delete(targetUri, null, null) }
                throw e
            }
            Result(name = name, where = "所选位置/$name", uri = targetUri)
        }
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

    /** 从 SAF 返回的 URI 里取文件名（形如 `primary:Download/肠子.epub`） */
    private fun fileNameFromUri(uri: String): String? =
        java.net.URLDecoder.decode(uri, "UTF-8")
            .substringAfterLast('/')
            .substringAfter(':')
            .trim()
            .takeIf { it.isNotBlank() && it.contains('.') }

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
