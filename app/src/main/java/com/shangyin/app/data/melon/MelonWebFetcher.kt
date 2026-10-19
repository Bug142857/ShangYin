package com.shangyin.app.data.melon

import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 吃瓜图床专用 Coil Fetcher（v0.243）：okhttp → WebView 原始拉取 → Kotlin 解密。
 *
 * 图床按 TLS 指纹放行 Chromium（详见 MelonWebImage 类注释）：okhttp 拿到 200+随机
 * 垃圾字节（每次头都不同），只有 WebView 能拿到真数据（可能为密文）。v0.235~0.242
 * 七轮管线未就绪的根因 = ApplicationContext 创建的 WebView Chromium 静默瘫痪
 * （v0.243 定案）——现改用 Activity context 创建，并在失败时销毁重建。
 *
 * 数据流：缓存 → okhttp（直接是明文则用）→ [MelonWebImage.fetchViaWeb]（非图魔数时）
 * → [MelonImageCipher.decrypt]（WebView 字节仍非图 = 密文，硬编码 KEY → 动态提取）。
 *
 * Coil 2.7 的 ImageSource 构造器 protected，无法给自定义 Fetcher 交付字节走系统缓存，
 * 因此自建一层磁盘缓存（cacheDir/melon_web_img，MD5 键）——明文 Bitmap 经
 * DrawableResult 交付（内存缓存照常生效，磁盘由本层负责；设置页「清缓存」会一并
 * 清理本目录）。
 */
class MelonWebFetcher(private val url: String, private val options: Options) : Fetcher {

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(): FetchResult {
        MelonFlightLog.log("FETCH", "入口 url=$url")
        val cacheFile = cacheFile(options.context, url)
        val bytes = if (cacheFile.isFile && cacheFile.length() > 0) {
            (cacheFile.readBytes().takeIf { looksLikeImage(it) } ?: run {
                MelonFlightLog.log("FETCH", "磁盘缓存垃圾拒收 size=${cacheFile.length()} 已删")
                runCatching { cacheFile.delete() }
                null
            })
        } else null
        if (bytes != null) MelonFlightLog.log("FETCH", "磁盘缓存命中 ${bytes.size}B")
        val fresh = bytes ?: run {
            // v0.243 数据流：okhttp 拉原始字节 → 非图魔数（风控指纹下发垃圾）→ WebView
            // （Activity context Chromium，v0.235~0.242 七轮未就绪的根因即 AppContext 创建
            // 瘫痪）同源原始拉取 → 仍非图魔数（真密文）→ Kotlin 侧 AES 解密（硬编码 KEY
            // → 动态提取 zzz.js KEY 重试）。
            val cipher = fetchCipher(url)
            if (looksLikeImage(cipher)) {
                MelonFlightLog.log("FETCH", "okhttp明文 ${cipher.size}B（CDN未加密此请求）")
                return@run finish(cipher, cacheFile)
            }
            MelonFlightLog.log(
                "FETCH",
                "okhttp非图 ${cipher.size}B head=${cipher.take(4).joinToString(" ") { "%02X".format(it) }} → WebView原始拉取"
            )
            val web = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(40_000) { MelonWebImage.fetchViaWeb(options.context, url) }
            }.getOrNull()
            if (web != null && looksLikeImage(web)) {
                MelonFlightLog.log("FETCH", "WebView明文 ${web.size}B ✓")
                return@run finish(web, cacheFile)
            }
            val src = web ?: cipher
            val via = if (web != null) "WebView" else "okhttp"
            val b = MelonImageCipher.decrypt(src)
            if (b == null) {
                // 硬编码密钥可能被站点轮换 → 动态拉 zzz.js 重新提取后重试一次
                MelonFlightLog.log("FETCH", "硬编码密钥解密失败(${src.size}B/$via) → 动态提取密钥重试")
                val (k, iv) = MelonImageCipher.fetchKeyIv(MelonClient.lastBase.ifBlank { "https://boy.fugkjqjmg.cc" })
                if (k == null || iv == null) {
                    MelonFlightLog.log("FETCH", "动态提取密钥失败(zzz.js 不可达)")
                    throw IOException("解密密钥不可用")
                }
                val b2 = MelonImageCipher.decrypt(src, k, iv)
                if (b2 == null) {
                    MelonFlightLog.log("FETCH", "动态密钥解密仍失败 head=${src.take(4).joinToString(" ") { "%02X".format(it) }}")
                    throw IOException("图床解密失败")
                }
                MelonFlightLog.log("FETCH", "动态密钥解密成功 ${b2.size}B key=$k")
                return@run finish(b2, cacheFile)
            }
            MelonFlightLog.log("FETCH", "解密成功 ${b.size}B")
            return@run finish(b, cacheFile)
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(fresh, 0, fresh.size)
        if (bmp == null) {
            MelonFlightLog.log("FETCH", "失败: 明文${fresh.size}B解码null")
            throw IOException("明文解码失败")
        }
        MelonFlightLog.log("FETCH", "成功 ${bmp.width}x${bmp.height}")
        return DrawableResult(
            drawable = android.graphics.drawable.BitmapDrawable(options.context.resources, bmp),
            isSampled = false,
            dataSource = DataSource.NETWORK
        )
    }

    /** 解密结果：魔数校验 → 写缓存 → 返回 */
    private suspend fun finish(plain: ByteArray, cacheFile: File): ByteArray {
        if (!looksLikeImage(plain)) {
            MelonFlightLog.log("FETCH", "失败: 明文${plain.size}B非图片魔数 head=${plain.take(4).joinToString(" ") { "%02X".format(it) }}")
            throw IOException("解密结果非图片")
        }
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(plain)
        }
        return plain
    }

    /** okhttp 拉原始密文（图床对 App 的 okhttp 完全放行——黑匣子 DOC/FETCH 证实） */
    private suspend fun fetchCipher(url: String): ByteArray =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Referer", MelonClient.lastBase.ifBlank { "https://boy.fugkjqjmg.cc" })
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("图床 HTTP ${resp.code}")
                resp.body?.bytes() ?: throw IOException("图床空响应")
            }
        }

    /** 图片魔数校验：JPEG(FFD8)/PNG(8950)/GIF(4749)/WEBP(RIFF....WEBP)，垃圾字节一律拒收 */
    private fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 16) return false
        return when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> true                                  // JPEG
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> true                                  // PNG
            b[0] == 0x47.toByte() && b[1] == 0x49.toByte() -> true                                  // GIF
            b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
                b[8] == 0x57.toByte() && b[9] == 0x45.toByte() -> true                              // RIFF+WEBP
            else -> false
        }
    }

    /**
     * Uri 版 Factory（v0.240 决定性修复）：Coil 2.x 对 String model 会先经 StringMapper
     * 映射为 [android.net.Uri] 再做 Fetcher 分派——v0.235~0.239 的 Factory<String> 从未
     * 命中过（诊断里 Coil 报「BitmapFactory null」= 内置 UriFetcher 拉到混淆假字节后
     * 解码失败，本 Fetcher 一次都没执行）。改为接管 Uri，图片加载路径才真正走进管线。
     */
    class Factory : Fetcher.Factory<android.net.Uri> {
        override fun create(data: android.net.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "http" && data.scheme != "https") return null
            val host = runCatching { data.toString().toHttpUrl().host }.getOrNull() ?: return null
            val hit = host.matches(MELON_PIC_HOST)
            MelonFlightLog.log("FACTORY", "Uri分派 host=$host 命中=$hit")
            return if (hit) MelonWebFetcher(data.toString(), options) else null
        }
    }

    /** String 版兜底：model 显式以 String/ByteArray 等形式传入时仍能命中（与 Uri 版同逻辑） */
    class StringFactory : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
            val host = runCatching { data.toHttpUrl().host }.getOrNull() ?: return null
            val hit = host.matches(MELON_PIC_HOST)
            MelonFlightLog.log("FACTORY", "String分派 host=$host 命中=$hit")
            return if (hit) MelonWebFetcher(data, options) else null
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
