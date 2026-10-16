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

    /** 阿里 DoH JSON API：{"Status":0,"Answer":[{"type":1,"data":"1.2.3.4"},...]}（公开供诊断使用） */
    fun dohLookup(hostname: String): List<InetAddress> {
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
 * 通用 DNS（v0.227）：**系统 DNS 优先**（保留运营商就近调度），系统解析失败才走 DoH。
 *
 * v0.226 教训：下载器曾用「DoH 优先」——阿里 DoH 返回的是权威/全局节点而非本运营商
 * 调度节点，设备实测连上即被 reset（「下载 Connection reset 而同域名播放器正常」，
 * 播放器走系统 DNS）。切片域名的系统解析从未发现污染（污染的是图床域名），所以
 * 这里与播放器对齐：系统优先；DoH 只做系统解析抛异常时的兜底。
 * 图床域名走 [PicDns]（DoH 优先，那边的系统解析污染是实锤），两者策略刻意不同。
 */
object GeneralDohDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val sys = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        val ips = sys.ifEmpty { DohResolver.resolve(hostname) }.filterNot { it.isSiteLocalAddress }
        if (ips.isEmpty()) throw java.net.UnknownHostException("域名解析失败: $hostname")
        return ips
    }
}
