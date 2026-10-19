package com.shangyin.app.data.melon

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 吃瓜图床图片解密器（v0.242）。
 *
 * 风控真相（0.241 黑匣子 + zzz.js 逆向定案）：图床 CDN 对**所有客户端**下发
 * AES-128-CBC **密文**（不是垃圾数据！）——浏览器也只是靠 zzz.js 在前端解密后显示。
 * 因此根本不需要 WebView：okhttp 拉密文 + 本地解密即可，零等待、可缓存。
 *
 * 算法自 zzz.js（混淆 JS）逆向提取：`CryptoJS.AES.decrypt(b64, Utf8.parse(KEY),
 * {iv: Utf8.parse(IV), mode: CBC, padding: Pkcs7})`，KEY/IV 是码点序列混淆串
 * （"57_55_98_..." → parseInt → charCode → "97b60394abc2fbe1"）。
 * JS 端把二进制转 base64 只是 CryptoJS API 要求，Kotlin 直接对二进制解密。
 */
object MelonImageCipher {

    /** zzz.js 当前版本提取的密钥（站点轮换时走 [fetchKeyIv] 动态提取） */
    private const val defKey = "97b60394abc2fbe1"
    private const val defIv = "f5d965df75336270"

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
     * 动态从镜像站 zzz.js 提取 KEY/IV（防站点轮换密钥）。
     * zzz.js 静态资源不被风控拦截（PC 实测 50KB 完整可达）。
     * 混淆表里 KEY 在前 IV 在后，两个均为 16 段十进制码点序列。
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
            // 解密函数里先 KEY 后 IV；过滤后应剩 2 条 16 字节串
            if (seqs.size >= 2) seqs[0] to seqs[1] else null to null
        } ?: (null to null)
    }.getOrDefault(null to null)
}
