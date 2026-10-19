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
 * 吃瓜图床专用 Coil Fetcher（v0.244）：okhttp 拉真密文 → 本地 AES 解密 → WebView 兜底。
 *
 * 0.244 定案：okhttp 拿到的就是**真 AES-128-CBC 密文**（16B 对齐、同 URL PC curl 与
 * 设备逐字节一致），0.235~0.243 十轮「TLS 指纹/WebView 管线」盲修的根因是 v0.242 把
 * zzz.js 的 KEY/IV **装反**导致解密必败、密文被误判为指纹垃圾（详见 [MelonImageCipher]
 * 类注释）。本地解密成功后 WebView 仅作站点改版兜底，正常路径零等待。
 *
 * 数据流：缓存 → okhttp（直接是明文则用）→ [MelonImageCipher.decryptAuto]（硬编码 KEY
 * 正反双向）→ 动态提取 zzz.js KEY 再试 → [MelonWebImage.fetchViaWeb]（最后兜底）。
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
            (cacheFile.readBytes().takeIf { MelonImageCipher.looksLikeImage(it) } ?: run {
                MelonFlightLog.log("FETCH", "磁盘缓存垃圾拒收 size=${cacheFile.length()} 已删")
                runCatching { cacheFile.delete() }
                null
            })
        } else null
        if (bytes != null) MelonFlightLog.log("FETCH", "磁盘缓存命中 ${bytes.size}B")
        val fresh = bytes ?: run {
            // v0.244 数据流：okhttp 拉真密文 → 本地 AES 解密（硬编码 KEY 正反双向）→
            // 动态提取 zzz.js KEY 重试 → WebView 原始拉取仅作站点改版兜底。
            val cipher = fetchCipher(url)
            if (MelonImageCipher.looksLikeImage(cipher)) {
                MelonFlightLog.log("FETCH", "okhttp明文 ${cipher.size}B（CDN未加密此请求）")
                return@run finish(cipher, cacheFile)
            }
            MelonFlightLog.log(
                "FETCH",
                "okhttp密文 ${cipher.size}B head=${cipher.take(4).joinToString(" ") { "%02X".format(it) }} → 本地解密"
            )
            val b = MelonImageCipher.decryptAuto(cipher)
            if (b != null) {
                MelonFlightLog.log("FETCH", "解密成功 ${b.size}B")
                return@run finish(b, cacheFile)
            }
            // 硬编码密钥可能被站点轮换 → 动态拉 zzz.js 重新提取后双向重试
            MelonFlightLog.log("FETCH", "硬编码双向解密失败(${cipher.size}B/okhttp) → 动态提取密钥重试")
            val (k1, k2) = MelonImageCipher.fetchKeyIv(MelonClient.lastBase.ifBlank { "https://boy.fugkjqjmg.cc" })
            if (k1 != null && k2 != null) {
                val b2 = MelonImageCipher.decryptAuto(cipher, k1, k2)
                if (b2 != null) {
                    MelonFlightLog.log("FETCH", "动态密钥解密成功 ${b2.size}B")
                    return@run finish(b2, cacheFile)
                }
                MelonFlightLog.log("FETCH", "动态密钥双向解密仍失败 → WebView兜底")
            } else {
                MelonFlightLog.log("FETCH", "动态提取密钥失败(zzz.js 不可达) → WebView兜底")
            }
            // 兜底：Chromium 指纹原始拉取（站点改版/CDN 行为变化时的最后手段）
            val web = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(40_000) { MelonWebImage.fetchViaWeb(options.context, url) }
            }.getOrNull()
            if (web != null && MelonImageCipher.looksLikeImage(web)) {
                MelonFlightLog.log("FETCH", "WebView明文 ${web.size}B ✓")
                return@run finish(web, cacheFile)
            }
            val src = web ?: cipher
            val via = if (web != null) "WebView" else "okhttp"
            val fallbackPlain = MelonImageCipher.decryptAuto(src)
                ?: (if (k1 != null && k2 != null) MelonImageCipher.decryptAuto(src, k1, k2) else null)
            if (fallbackPlain == null) {
                MelonFlightLog.log(
                    "FETCH",
                    "WebView兜底全失败(${src.size}B/$via) head=${src.take(4).joinToString(" ") { "%02X".format(it) }}"
                )
                throw IOException("图床解密失败")
            }
            MelonFlightLog.log("FETCH", "WebView兜底解密成功 ${fallbackPlain.size}B")
            return@run finish(fallbackPlain, cacheFile)
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
        if (!MelonImageCipher.looksLikeImage(plain)) {
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

    // 图片魔数校验统一走 MelonImageCipher.looksLikeImage（v0.244 收编共用，勿在本地复制）

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
