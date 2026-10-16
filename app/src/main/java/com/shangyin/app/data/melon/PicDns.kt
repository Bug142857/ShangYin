package com.shangyin.app.data.melon

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 吃瓜图床专用 DNS（v0.225）：仅对 pic.{词}.cn 域名生效，**DoH 优先**。
 *
 * v0.224 的教训：DoH 只在「系统解析抛异常」时触发，但设备实测场景是
 * 运营商 DNS **解析成功却返回污染/不可达 IP**（不抛异常）——兜底永远不触发。
 * 所以改为强制 DoH 优先（阿里公共 DNS 223.5.5.5，返回腾讯 CDN 的真实公网 IP），
 * 系统解析只作 DoH 失败时的回退。多 IP 交给 okhttp 依次尝试连接。
 * 成功结果缓存 10 分钟，避免每个请求都打一次 DoH。
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
        // DoH 优先（绕过运营商污染/坏节点）；失败回退系统解析
        val ips = dohLookup(hostname).ifEmpty {
            runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        }.filterNot { it.isSiteLocalAddress }
        if (ips.isEmpty()) throw java.net.UnknownHostException("图床域名解析失败: $hostname")
        cache[hostname] = now to ips
        return ips
    }

    /** 阿里 DoH JSON API：{"Status":0,"Answer":[{"type":1,"data":"1.2.3.4"},...]} */
    private fun dohLookup(hostname: String): List<InetAddress> {
        val url = DOH.format(hostname).toHttpUrl()
        val req = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string() ?: return@runCatching emptyList()
                // 轻量正则解析（type=1 为 A 记录），避免引入 JSON 依赖
                Regex("""\{\s*"name"\s*:\s*"[^"]*"\s*,\s*"type"\s*:\s*1\s*,[^}]*"data"\s*:\s*"(\d{1,3}(?:\.\d{1,3}){3})"\s*\}""")
                    .findAll(body)
                    .mapNotNull { runCatching { InetAddress.getByName(it.groupValues[1]) }.getOrNull() }
                    .distinct()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }
}
