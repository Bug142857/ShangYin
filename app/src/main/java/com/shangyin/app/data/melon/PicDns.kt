package com.shangyin.app.data.melon

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * DoH 解析器（v0.226 从 PicDns 提取公共逻辑）：阿里公共 DNS（223.5.5.5）DoH 优先，
 * 系统解析仅作 DoH 失败时的回退。多 IP 交给 okhttp 依次尝试连接。
 *
 * v0.224 的教训：DoH 只在「系统解析抛异常」时触发不够——设备实测场景是运营商 DNS
 * **解析成功却返回污染/不可达 IP**（不抛异常），兜底永远不触发。所以必须 DoH 优先。
 * 成功结果缓存 10 分钟，避免每个请求都打一次 DoH。
 */
object DohResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    private const val CACHE_MS = 10 * 60 * 1000L
    private const val DOH = "https://223.5.5.5/resolve?name=%s&type=A"

    /** 解析域名：DoH 优先、系统回退。返回空列表表示全部失败（调用方决定抛异常还是兜底）。 */
    fun resolve(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { (t, ips) -> if (now - t < CACHE_MS && ips.isNotEmpty()) return ips }
        val ips = dohLookup(hostname).ifEmpty {
            runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        }.filterNot { it.isSiteLocalAddress }
        if (ips.isNotEmpty()) cache[hostname] = now to ips
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

/**
 * 吃瓜图床专用 DNS（v0.225）：仅对 pic.{词}.cn 域名生效，DoH 优先（见 [DohResolver]）。
 */
object PicDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.matches(MELON_PIC_HOST)) return Dns.SYSTEM.lookup(hostname)
        val ips = DohResolver.resolve(hostname)
        if (ips.isEmpty()) throw java.net.UnknownHostException("图床域名解析失败: $hostname")
        return ips
    }
}

/**
 * 通用 DNS（v0.226）：所有域名 DoH 优先、系统回退（见 [DohResolver]）。
 *
 * 用于视频下载：吃瓜视频切片域名（mts.hhjd.mobi 等）与图床同源遭遇运营商 DNS 污染，
 * 下载器此前直连系统 DNS → 解析到不可达 IP → 「下载一直失败」。播放器侧媒体3走自己的
 * 数据源不受此处影响；此处只替换 VideoDownloadManager 的 OkHttp client DNS。
 */
object GeneralDohDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val ips = DohResolver.resolve(hostname)
        if (ips.isEmpty()) throw java.net.UnknownHostException("域名解析失败: $hostname")
        return ips
    }
}
