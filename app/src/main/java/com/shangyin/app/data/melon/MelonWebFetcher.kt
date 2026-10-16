package com.shangyin.app.data.melon

import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * 吃瓜图床专用 Coil Fetcher（v0.235）：走 [MelonWebImage] 的 WebView 解密管线。
 *
 * 图床服务器只对 Chromium TLS 指纹下发真密文（详见 MelonWebImage 类注释），普通
 * okhttp 路径拿到的是 200 + 混淆假字节——所以吃瓜封面/文章图必须经 WebView 取解密后
 * 的明文。本 Fetcher 只接管 `pic.{词}.cn` 域名，其余 URL 返回 null 交回 Coil 默认
 * String Fetcher。
 *
 * Coil 2.7 的 ImageSource 构造器 protected，无法给自定义 Fetcher 交付字节走系统缓存，
 * 因此自建一层磁盘缓存（cacheDir/melon_web_img，MD5 键）——WebView 解密一张 1~2s，
 * 没缓存列表滚动体验无法接受。明文 Bitmap 经 DrawableResult 交付（内存缓存照常生效，
 * 磁盘由本层负责；设置页「清缓存」会一并清理本目录）。
 */
class MelonWebFetcher(private val url: String, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val cacheFile = cacheFile(options.context, url)
        val bytes = if (cacheFile.isFile && cacheFile.length() > 0) {
            cacheFile.readBytes()
        } else {
            val ok = ensureReady(options.context)
            if (!ok) throw IOException("WebView 管线未就绪")
            val b = MelonWebImage.fetchDecrypted(url) ?: throw IOException("图床解密失败")
            runCatching {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeBytes(b)
            }
            b
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("明文解码失败")
        return DrawableResult(
            drawable = android.graphics.drawable.BitmapDrawable(options.context.resources, bmp),
            isSampled = false,
            dataSource = DataSource.NETWORK
        )
    }

    private suspend fun ensureReady(context: android.content.Context): Boolean =
        suspendCancellableCoroutine { c ->
            MelonWebImage.ensure(context, MelonClient.lastBase.ifBlank { "https://boy.fugkjqjmg.cc" }) { ok ->
                if (c.isActive) c.resume(ok)
            }
        }

    class Factory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            val host = runCatching { data.toHttpUrl().host }.getOrNull() ?: return null
            return if (host.matches(MELON_PIC_HOST)) MelonWebFetcher(data, options) else null
        }
    }

    companion object {
        /** 解密明文的磁盘缓存文件（MD5(url)），设置页清缓存一并清理 */
        fun cacheDir(context: android.content.Context): File =
            File(context.cacheDir, "melon_web_img")

        private fun cacheFile(context: android.content.Context, url: String): File {
            val md5 = MessageDigest.getInstance("MD5").digest(url.toByteArray())
            return File(cacheDir(context), md5.joinToString("") { "%02x".format(it) })
        }
    }
}
