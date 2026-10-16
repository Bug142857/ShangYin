package com.shangyin.app.data.melon

import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 吃瓜网络诊断（v0.227）：设备端实测图床/切片域名的连通性，打破「本地全绿、设备全挂」
 * 的盲修循环（0.221-0.226 已盲修五轮图片）。
 *
 * 对每个目标域名输出五层证据，能直接区分三种根因：
 * - 系统DNS/DoH 都失败 → 解析层问题
 * - DNS 正常但 okhttp 与 HttpsURLConnection 全挂、WebView 正常 → **TLS 指纹/SNI 针对性拦截**
 *   （okhttp 与 Chromium 的 TLS ClientHello 指纹不同，风控按指纹放行浏览器、拦杀 App）
 * - 三栈全挂 → 域名/IP 级阻断（换域名或需要代理中转）
 *
 * WebView 探测只在主线程跑（图床主候选 + 切片域名各一次），结果以主页错误回调为准。
 */
object MelonDiag {

    /** 诊断目标：host → 是否图床（图床带 Referer） */
    private fun targets(): List<Pair<String, Boolean>> {
        val hosts = LinkedHashMap<String, Boolean>()
        MelonClient.activePicHost?.let { hosts[it] = true }
        hosts["pic.jtzatly.cn"] = true
        hosts["pic.ndhixj.cn"] = true
        MelonClient.fallbackPicHosts("").forEach { hosts.putIfAbsent(it, true) }
        hosts["mts.hhjd.mobi"] = false // 吃瓜视频切片域名（v0.225 实测）
        return hosts.entries.filter { it.key.isNotBlank() }.map { it.key to it.value }.take(4)
    }

    /** 跑全部诊断，返回可直接展示/复制的多行文本 */
    suspend fun run(context: Context): String = coroutineScope {
        val out = StringBuilder()
        out.appendLine("===== 吃瓜网络诊断 ${SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())} =====")
        val ts = targets()
        ts.forEachIndexed { idx, (host, isPic) ->
            out.appendLine("――― $host${if (isPic) "（图床）" else ""} ―――")
            val referer = if (isPic) MelonClient.lastBase.trimEnd('/') + "/" else null
            // WebView 先行（idx<2）：主框架访问会执行挑战页 JS 并把通过票据写入
            // CookieManager——之后的「okhttp+Cookie」探针才能验证 Cookie 直通是否有效
            if (idx < 2) out.appendLine("WebView: ${probeWebView(context, "https://$host/")}")

            // DNS + HTTP 栈并行跑，压时长（最坏 4s/项）
            val sysDns = async(Dispatchers.IO) {
                val r = runCatching { Dns.SYSTEM.lookup(host) }
                val ips: List<InetAddress> = r.getOrDefault(emptyList())
                when {
                    ips.isNotEmpty() -> ips.joinToString(",") { ip -> ip.hostAddress ?: "?" }
                    r.exceptionOrNull() != null -> "失败:${r.exceptionOrNull()!!.javaClass.simpleName}"
                    else -> "空"
                }
            }
            val doh = async(Dispatchers.IO) { DohResolver.endpointsStatus(host) }
            val okPlain = async(Dispatchers.IO) { probeOkhttp(host, referer, withCookie = false) }
            val okCookie = async(Dispatchers.IO) { probeOkhttp(host, referer, withCookie = true) }
            val urlConnRes = async(Dispatchers.IO) { probeUrlConn(host, referer) }

            out.appendLine("系统DNS: ${sysDns.await().ifEmpty { "空" }}")
            out.appendLine("DoH: ${doh.await()}")
            out.appendLine("okhttp: ${okPlain.await()}")
            out.appendLine("okhttp+Cookie: ${okCookie.await()}")
            out.appendLine("HttpConn: ${urlConnRes.await()}")
            if (isPic) out.appendLine("真实图片: ${async(Dispatchers.IO) { imageProbe() }.await()}")
        }
        out.appendLine("镜像: ${MelonClient.lastBase}  图床池: ${MelonClient.fallbackPicHosts("")}")
        out.appendLine("判读: 真实图片octet-stream=正常(CDN就这样标注); 真实图片text/html=风控/劫持; 403(/)=CDN对裸路径正常行为")

        // ---- v0.232 App 实况：此前探针测「网络通不通」，这段测「App 实际用的 URL/数据」——
        // 列表封面=Coil 拿到的真实 URL；当前视频=PlayerSession 真实播放地址；下载=任务真实进度值
        out.appendLine("")
        out.appendLine("――― App 实况 ―――")
        kotlinx.coroutines.coroutineScope {
            val dl = com.shangyin.app.data.download.VideoDownloadManager.active.value
            if (dl.isEmpty()) out.appendLine("下载任务: 无进行中")
            else dl.forEach { (_, t) ->
                out.appendLine(
                    "下载任务: ${t.title.take(14)} progress=${"%.3f".format(t.progress)} err=${t.error ?: "无"}"
                )
            }
            out.appendLine("已下载记录: ${com.shangyin.app.data.download.VideoDownloadManager.library.value.size} 条")

            val ep = runCatching {
                val g = com.shangyin.app.ui.player.PlayerSession.groups[com.shangyin.app.ui.player.PlayerSession.groupIndex]
                g.episodes[com.shangyin.app.ui.player.PlayerSession.startIndex].url
            }.getOrNull()
            if (ep.isNullOrBlank()) out.appendLine("当前视频: 无会话（先进播放页再诊断）")
            else {
                out.appendLine("当前视频: ${ep.take(90)}")
                out.appendLine("视频实测: ${async(Dispatchers.IO) { probeVideo(ep) }.await()}")
            }

            // 列表封面实测：现场走一遍 MelonClient.home()（App 解析路径本身）——
            // 解析失败/封面为空也是关键信息
            val homeRes = runCatching { MelonClient.home() }
            val covers = homeRes.getOrNull()?.items
                ?.mapNotNull { it.cover }?.filter { it.startsWith("http") }?.distinct()?.take(3)
                ?: emptyList()
            if (homeRes.isFailure) out.appendLine("列表解析: 失败 ${homeRes.exceptionOrNull()?.message?.take(60)}")
            if (covers.isEmpty() && homeRes.isSuccess) out.appendLine("列表解析: 成功但无封面URL")
            var n = 1
            for (c in covers) {
                val r = probeExact(c)
                out.appendLine("封面$n: $r (${c.take(70)})")
                n++
            }
        }
        out.toString()
    }

    /** 精确探测 App 内真实使用的 URL（不带缓存、UA/Referer 与图床路径一致） */
    private fun probeExact(url: String): String = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", MelonClient.UA)
            .header("Referer", "${MelonClient.lastBase.trimEnd('/')}/").build()
        probeClient.newCall(req).execute().use { r ->
            val size = r.body?.bytes()?.size ?: 0
            "HTTP ${r.code} ${r.header("Content-Type")} ${size}B"
        }
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message?.take(60) ?: ""}"
    }

    /** 播放地址探测：m3u8 输出清单头（KEY/切片行一眼可辨），其余输出状态与类型 */
    private fun probeVideo(url: String): String = try {
        val req = Request.Builder().url(url).header("User-Agent", MelonClient.UA).build()
        probeClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) "HTTP ${r.code} ${r.header("Content-Type")}"
            else {
                val body = r.body?.string().orEmpty()
                if (body.contains("#EXTM3U")) {
                    val segs = body.lines().count { it.isNotBlank() && !it.startsWith("#") }
                    val keyLine = body.lines().firstOrNull { it.contains("EXT-X-KEY") }?.take(90)
                    "m3u8 OK: $segs 行切片 KEY=${keyLine ?: "无"}"
                } else "HTTP 200 ${r.header("Content-Type")} 非清单(直链?)"
            }
        }
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message?.take(60) ?: ""}"
    }

    /** 共用裸探测 client（系统 DNS，与 App 内图床路径一致） */
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 真实图片探测（v0.229）：根路径 / 的空 HTML 是误导（边缘节点对 / 就是 200 空页），
     * 必须测真图。从最近成功镜像的首页抓第一个图床 URL，实测 Content-Type + 字节数——
     * 0.229 前所有误判的教训：只看 Content-Type 定生死，而 CDN 对图片实际返回
     * binary/octet-stream。
     */
    private fun imageProbe(): String {
        val base = MelonClient.lastBase.trimEnd('/')
        if (base.isBlank()) return "跳过（先在吃瓜里打开一篇文章再来诊断）"
        return try {
            val home = probeClient.newCall(
                Request.Builder().url("$base/").header("User-Agent", MelonClient.UA).build()
            ).execute().use { it.body?.string().orEmpty() }
            val img = Regex("""pic\.[a-z0-9]+\.cn/[^\s"'<>]+?\.(?:jpg|jpeg|png|webp)""")
                .find(home)?.value ?: return "首页未找到图床URL"
            probeClient.newCall(
                Request.Builder().url("https://$img")
                    .header("User-Agent", MelonClient.UA)
                    .header("Referer", "$base/").build()
            ).execute().use { r ->
                val size = r.body?.bytes()?.size ?: 0
                "HTTP ${r.code} ${r.header("Content-Type")} ${size}B (${img.take(60)})"
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message?.take(50) ?: ""}"
        }
    }

    private fun probeOkhttp(host: String, referer: String?, withCookie: Boolean): String {
        val b = Request.Builder().url("https://$host/").header("User-Agent", MelonClient.UA)
        referer?.let { b.header("Referer", it) }
        if (withCookie) {
            val c = runCatching {
                android.webkit.CookieManager.getInstance().getCookie("https://$host/")
            }.getOrNull()
            if (c.isNullOrBlank()) return "无Cookie可带"
            b.header("Cookie", c)
        }
        return try {
            probeClient.newCall(b.build()).execute().use { resp ->
                val ct = resp.header("Content-Type") ?: ""
                if (resp.isSuccessful && ct.startsWith("text/html")) {
                    // 抓 HTML 片段定性：JS 挑战页（script+setTimeout） / 运营商劫持（广告） / CDN 默认页
                    val snippet = resp.body?.string().orEmpty()
                        .replace(Regex("\\s+"), " ").trim().take(150)
                    "HTTP 200 html「$snippet」"
                } else "HTTP ${resp.code} $ct"
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message?.take(60) ?: ""}"
        }
    }

    private fun probeUrlConn(host: String, referer: String?): String {
        return try {
            val conn = URL("https://$host/").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", MelonClient.UA)
            referer?.let { conn.setRequestProperty("Referer", it) }
            val code = conn.responseCode
            runCatching { conn.disconnect() }
            "HTTP $code"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message?.take(60) ?: ""}"
        }
    }

    /** WebView（Chromium 栈）直接 loadUrl 目标，主页收到错误回调即记为失败 */
    private suspend fun probeWebView(context: Context, url: String): String {
        var web: WebView? = null
        try {
            return withContext(Dispatchers.Main) {
                withTimeoutOrNull(6000) {
                    suspendCancellableCoroutine { cont ->
                        web = WebView(context).apply {
                            var err: String? = null
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView, request: WebResourceRequest, error: WebResourceError
                                ) {
                                    if (request.isForMainFrame && err == null) err = "ERR(${error.errorCode})"
                                }

                                override fun onReceivedHttpError(
                                    view: WebView, request: WebResourceRequest, response: WebResourceResponse
                                ) {
                                    if (request.isForMainFrame && err == null) err = "HTTP ${response.statusCode}"
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    if (cont.isActive) cont.resume(err ?: "OK")
                                }
                            }
                            loadUrl(url)
                        }
                    }
                } ?: "超时"
            }
        } catch (e: Exception) {
            return "异常:${e.javaClass.simpleName}"
        } finally {
            withContext(Dispatchers.Main) { runCatching { web?.destroy() } }
        }
    }
}
