package com.shangyin.app.data.melon

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 吃瓜图床图片解密器（v0.244 换正 KEY/IV）。
 *
 * 风控真相（0.244 会话 PC 实测定案）：图床 CDN 对**所有客户端**下发 AES-128-CBC
 * **真密文**（字节数全部 16B 对齐、同 URL PC curl 与设备 okhttp 逐字节一致）——
 * 浏览器也只是靠 zzz.js 在前端解密后显示。okhttp 拉密文 + 本地解密即可，WebView
 * 只是站点改版时的兜底，不是必需路径。
 *
 * ## 0.242~0.243 十轮盲修的根因：KEY/IV 装反
 * zzz.js `decryptImage` 混淆字符串数组经轮换，静态出现顺序**不等于**使用顺序：
 * 实际 KEY=Utf8.parse("f5d965df75336270")、IV=Utf8.parse("97b60394abc2fbe1")，
 * 0.242 恰好装反 → JVM BadPaddingException → 密文被误判为「指纹随机垃圾」→
 * 七轮 WebView 管线盲修（AppContext 瘫痪 / Activity context / stub 页…全是歧途）。
 * 0.244 用 PC curl 同款密文 + 标准实现复现：换正后 FF D8 FF DB 一发命中。
 *
 * 算法自 zzz.js（混淆 JS）逆向提取：`CryptoJS.AES.decrypt(b64, Utf8.parse(KEY),
 * {iv: Utf8.parse(IV), mode: CBC, padding: Pkcs7})`。JS 端把二进制转 base64 只是
 * CryptoJS API 要求，Kotlin 直接对二进制解密。
 */
object MelonImageCipher {

    /** zzz.js 当前版本提取的密钥（v0.244 换正指派；站点轮换时走 [fetchKeyIv] 动态提取） */
    private const val defKey = "f5d965df75336270"
    private const val defIv = "97b60394abc2fbe1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** AES-128-CBC-PKCS7 解密；任何异常（密钥错/块长不对）返回 null */
    fun decrypt(cipherBytes: ByteArray, key: String = defKey, iv: String = defIv): ByteArray? =
        runCatching {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
            )
            c.doFinal(cipherBytes)
        }.getOrNull()

    /**
     * 解密 + 图片魔数校验：KEY/IV **正反两种指派都试**（zzz.js 混淆轮换可能翻转提取序），
     * 命中图片魔数者胜出——密钥指派顺序从此不再是单点故障。全败返回 null。
     * 动态密钥场景直接传 (k1, k2)（fetchKeyIv 的文件序），反转指派内部自动尝试。
     */
    fun decryptAuto(cipherBytes: ByteArray, key: String = defKey, iv: String = defIv): ByteArray? {
        decrypt(cipherBytes, key, iv)?.takeIf { looksLikeImage(it) }?.let { return it }
        return decrypt(cipherBytes, iv, key)?.takeIf { looksLikeImage(it) }
    }

    /**
     * 动态从镜像站 zzz.js 提取 KEY/IV（防站点轮换密钥）。
     * zzz.js 静态资源不被风控拦截（PC 实测 50KB 完整可达）。
     * 返回按文件序的两条 16 字节串（[first, second]）——**指派顺序不可信**（混淆轮换），
     * 调用方必须经 [decryptAuto] 正反双向尝试，不得直接按 (KEY, IV) 消费。
     */
    fun fetchKeyIv(base: String): Pair<String?, String?> = runCatching {
        val req = Request.Builder().url("$base/usr/plugins/tbxw/js/zzz.js")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null to null
            val js = resp.body?.string() ?: return@runCatching null to null
            val seqs = Regex("""(?:\d+_){15,30}\d+""").findAll(js)
                .map { m ->
                    m.value.split('_').mapNotNull { it.toIntOrNull() }
                        .takeIf { it.size == 16 && it.all { n -> n in 32..126 } }
                        ?.let { list -> String(list.map { it.toChar() }.toCharArray()) }
                }
                .filterNotNull()
                .distinct()
                .toList()
            // 解密函数里两个序列（KEY/IV 指派随混淆轮换，双向尝试兜底）
            if (seqs.size >= 2) seqs[0] to seqs[1] else null to null
        } ?: (null to null)
    }.getOrDefault(null to null)

    /** 图片魔数校验：JPEG(FFD8)/PNG(8950)/GIF(4749)/WEBP(RIFF....WEBP)，垃圾字节一律拒收（v0.244 自 Fetcher 收编共用） */
    internal fun looksLikeImage(b: ByteArray): Boolean {
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
}
