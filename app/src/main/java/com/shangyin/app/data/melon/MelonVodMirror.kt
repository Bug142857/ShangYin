package com.shangyin.app.data.melon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 吃瓜点播「本地镜像」修复（v0.254）。
 *
 * **根因**：站点 m3u8 缺 `#EXT-X-ENDLIST`（VOD 收尾标签），media3 将其当作直播流处理：
 * 时长被映射为天文数字（黑匣子实锤 dur=2836434455ms≈32 天且随真实时间增长），进度条
 * 全长≈无限 → seek 落点几乎必然在真实内容（353s）之外 → 永远缓冲不到 → 「点进度条必卡」。
 * 浏览器 hls.js 对缺标签的宽容策略掩盖了问题（0.249 用户实测浏览器正常）。
 *
 * **方案**：后台把整个播放列表镜像到本地——切片并行下载、密钥本地化、重写列表
 * 强制补上 ENDLIST，就绪后无缝替换当前播放（保持位置）。本地列表是纯 VOD：
 * 时长正确、进度条真实、seek 走本地文件。镜像失败/超时则保持原流（现状兜底）。
 */
object MelonVodMirror {

    private val mirrors = ConcurrentHashMap<String, File>()
    private val inFlight = ConcurrentHashMap<String, Boolean>()
    // 镜像真实总时长（EXTINF 求和，ms）——切换本地播放时钳制位置（审查发现：
    // 直播化时间轴上 seek 过的位置可达 28 亿 ms，直接带入本地 VOD 会越界立刻 ENDED）
    private val durations = ConcurrentHashMap<String, Long>()

    /** 镜像真实时长（ms；未就绪返回 0） */
    fun durationOf(url: String): Long = durations[url] ?: 0L

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .dns(GeneralDohDns)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { c ->
            c.proceed(
                c.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .build()
            )
        }
        .build()

    fun cached(url: String): File? = mirrors[url]?.takeIf { it.isDirectory }

    /** 后台准备本地镜像；已就绪立即返回，进行中则等待，失败返回 null（调用方保持原流） */
    suspend fun prepare(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        if (inFlight.putIfAbsent(url, true) == true) {
            // 已有任务在进行：轮询等待（上限 90s）
            repeat(90) {
                delay(1_000)
                cached(url)?.let { return@withContext it }
            }
            return@withContext null
        }
        val t0 = System.currentTimeMillis()
        try {
            val dir = File(context.cacheDir, "vodmirror/" + url.hashCode().toString(16)).apply { deleteRecursively(); mkdirs() }
            val c = client()
            val playlist = c.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("m3u8 HTTP ${r.code}")
                // v0.255：站点 HLS 地址无 .m3u8 后缀，镜像门放宽后可能拿到 mp4 等直链——
                // 先 peek 256B 验证 #EXTM3U 再读全量（否则 string() 会把整个视频读进内存）
                val head = r.peekBody(256L).string()
                if (!head.contains("#EXTM3U")) throw IOException("非m3u8响应 head=${head.take(48)}")
                r.body?.string() ?: throw IOException("m3u8 空响应体")
            }
            val base = URI(url)
            val lines = playlist.lines()
            val segUrls = lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { base.resolve(it.trim()).toString() }
            if (segUrls.isEmpty()) throw IOException("无切片")

            // 真实总时长 = EXTINF 标签求和（秒，可能带小数）
            val totalSec = lines.filter { it.startsWith("#EXTINF") }
                .sumOf { ln -> ln.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 }
            val totalMs = (totalSec * 1000).toLong()

            // 密钥本地化（保持 IV 语义不变，仅改 URI 指向本地文件）
            val keyUri = lines.firstOrNull { it.startsWith("#EXT-X-KEY") }
                ?.let { Regex("URI=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

            coroutineScope {
                // v0.256：并发 6→10（慢源高延迟链路下吞吐明显受益；实测 6 并发 174MB≈83s，
                // 与用户手动下载任务并存时镜像被抢带宽拖到 2 分钟+，用户等不及就放弃）
                val sem = Semaphore(10)
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                val quarter = (segUrls.size / 4).coerceAtLeast(1)
                if (keyUri != null) {
                    async {
                        sem.withPermit {
                            val kf = File(dir, "key.bin")
                            c.newCall(Request.Builder().url(base.resolve(keyUri).toString()).build())
                                .execute().use { r ->
                                    if (!r.isSuccessful) throw IOException("key HTTP ${r.code}")
                                    kf.writeBytes(r.body?.bytes() ?: throw IOException("key 空响应体"))
                                }
                        }
                    }
                }
                segUrls.mapIndexed { idx, su ->
                    async {
                        sem.withPermit {
                            val f = File(dir, "seg-%05d.ts".format(idx))
                            var last: Exception? = null
                            repeat(2) {
                                try {
                                    c.newCall(Request.Builder().url(su).build()).execute().use { r ->
                                        if (!r.isSuccessful) throw IOException("slice HTTP ${r.code}")
                                        f.writeBytes(r.body?.bytes() ?: throw IOException("slice 空响应体"))
                                    }
                                    // v0.256：进度打点（每 25% 一条）——0.255 装机镜像 115s 无任何
                                    // 中间日志，「还在下载」与「卡死」不可分辨，取证先于取证结论
                                    val n = done.incrementAndGet()
                                    if (n % quarter == 0) MelonFlightLog.log("MIRROR", "切片 $n/${segUrls.size}")
                                    return@withPermit
                                } catch (e: Exception) { last = e }
                            }
                            throw last ?: IOException("切片失败 $su")
                        }
                    }
                }.awaitAll()
            }

            // 重写列表：KEY 指向本地；切片改相对文件名；末尾强制 ENDLIST
            val out = StringBuilder()
            var segIdx = 0
            var hadEnd = false
            for (ln in lines) {
                when {
                    ln.startsWith("#EXT-X-ENDLIST") -> hadEnd = true // 原文位置去重，结尾统一补
                    ln.startsWith("#EXT-X-KEY") -> out.appendLine(ln.replace(Regex("URI=\"[^\"]+\""), "URI=\"key.bin\""))
                    ln.isNotBlank() && !ln.startsWith("#") -> {
                        out.appendLine("seg-%05d.ts".format(segIdx))
                        segIdx++
                    }
                    else -> out.appendLine(ln)
                }
            }
            if (!hadEnd) out.appendLine("#EXT-X-ENDLIST")
            File(dir, "index.m3u8").writeText(out.toString())

            MelonFlightLog.log(
                "MIRROR", "完成 ${segUrls.size}片 时长${totalMs / 1000}s 用时${(System.currentTimeMillis() - t0) / 1000}s → 可切换本地播放"
            )
            durations[url] = totalMs
            mirrors[url] = dir
            dir
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            MelonFlightLog.log(
                "MIRROR", "失败 ${e.javaClass.simpleName}: ${e.message?.take(60)} → 保持原流"
            )
            null
        } finally {
            inFlight.remove(url)
        }
    }
}
