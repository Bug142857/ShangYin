package com.shangyin.app.data.melon

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 吃瓜图床专用 DNS（v0.224）：设备网络对图床域名解析失败/被污染时，
 * 走阿里公共 DoH（223.5.5.5 JSON API）兜底解析真实 IP。
 * 仅对 pic.{词}.cn 域名生效（应用内其它域名一律走系统 DNS），成功结果缓存 10 分钟。
 */
object PicDns : Dns {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    private const val CACHE_MS = 10 * 60 * 1000L
    private const val DOH = "https://223.5.5.5/resolve?name=%s&type=A"

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.matches(MELON_PIC_HOST)) return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.let { (t, ips) -> if (now - t < CACHE_MS && ips.isNotEmpty()) return ips }
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }
            .getOrDefault(emptyList())
            .filterNot { it.isSiteLocalAddress }
        val ips = if (system.isNotEmpty()) system else dohLookup(hostname)
        if (ips.isEmpty()) throw java.net.UnknownHostException("图床域名解析失败: $hostname")
        cache[hostname] = now to ips
        return ips
    }

    /** 阿里 DoH JSON API：{"Status":0,"Answer":[{"type":1,"data":"1.2.3.4"},...]} */
    private fun dohLookup(hostname: String): List<InetAddress> {
        val url = DOH.format(hostname).toHttpUrl()
        val req = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            // 轻量正则解析（type=1 为 A 记录），避免引入 JSON 依赖
            return Regex("""\{\s*"name"\s*:\s*"[^"]*"\s*,\s*"type"\s*:\s*1\s*,[^}]*"data"\s*:\s*"(\d{1,3}(?:\.\d{1,3}){3})"\s*\}""")
                .findAll(body)
                .mapNotNull { runCatching { InetAddress.getByName(it.groupValues[1]) }.getOrNull() }
                .distinct()
                .toList()
        }
    }
}
