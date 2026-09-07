package com.shangyin.app

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
 * 图片下载工具：用 OkHttp 抓取图片字节（带豆瓣防盗链 Referer 头），
 * 然后通过 MediaStore 写入 /Pictures/老郑分享/ 目录。
 * 对 Android 10+ 用分区存储（MediaStore），老版本直接写 File 后刷新 MediaScanner。
 */
object ImageDownloader {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 下载图片到相册，返回保存的文件名；失败抛异常 */
    suspend fun download(context: Context, url: String, suggestedName: String? = null): String =
        withContext(Dispatchers.IO) {
            require(url.isNotBlank()) { "图片地址为空" }

            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                        "Version/16.0 Mobile/15E148 Safari/604.1"
                )
                .build()

            // 豆瓣图片需要 Referer 绕过防盗链
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
            val finalReq = if (host.endsWith("doubanio.com") || host.endsWith("douban.com")) {
                req.newBuilder().header("Referer", "https://m.douban.com/").build()
            } else req

            val resp = client.newCall(finalReq).execute()
            if (!resp.isSuccessful) throw Exception("下载失败 HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw Exception("响应体为空")

            // 生成文件名
            val ext = when {
                url.endsWith(".jpg", true) || url.endsWith(".jpeg", true) -> "jpg"
                url.endsWith(".png", true) -> "png"
                url.endsWith(".webp", true) -> "webp"
                url.endsWith(".gif", true) -> "gif"
                else -> (resp.header("Content-Type") ?: "image/jpeg").substringAfterLast('/').substringBefore(';')
                    .ifBlank { "jpg" }
            }
            val safeName = (suggestedName ?: "img_${System.currentTimeMillis()}")
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .take(40)
            val fileName = "${safeName}_${System.currentTimeMillis()}.$ext"

            val albumDir = "老郑分享"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 分区存储
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/$ext")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$albumDir")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    contentValues
                ) ?: throw Exception("无法创建相册条目")
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(bytes)
                } ?: throw Exception("打开输出流失败")
            } else {
                // 老版本直接写文件
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    albumDir
                ).apply { mkdirs() }
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                // 通知系统刷新相册
                context.sendBroadcast(
                    android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                        .setData(android.net.Uri.fromFile(file))
                )
            }
            fileName
        }
}
