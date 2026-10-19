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
                out.appendLine("视频实测: ${async(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(15_000) { probeVideo(ep) } ?: "超时(15s)" }.await()}")
            }

            // 列表封面实测：现场走一遍 MelonClient.home()（App 解析路径本身）——
            // 解析失败/封面为空也是关键信息。v0.237：全部环节限时，防串行叠加卡死
            // （0.236 实测最坏 140s 无反馈=用户以为卡死）
            val homeRes = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(20_000) { MelonClient.home() }
            }
            val covers = homeRes.getOrNull()?.items
                ?.mapNotNull { it.cover }?.filter { it.startsWith("http") }?.distinct()?.take(3)
                ?: emptyList()
            when {
                homeRes.isFailure -> out.appendLine("列表解析: 失败 ${homeRes.exceptionOrNull()?.message?.take(60)}")
                homeRes.getOrNull() == null -> out.appendLine("列表解析: 超时(20s)")
                covers.isEmpty() -> out.appendLine("列表解析: 成功但无封面URL")
            }
            var n = 1
            for (c in covers) {
                val r = async(Dispatchers.IO) {
                    // 裸 HTTP：输出响应体前 8 字节十六进制——FFD8=JPEG/8950=PNG/RIFF=WEBP，
                    // 全 00 或 3C3F(<?xml)/3C21(<!--)=HTML，字节层面定性拿到的是不是图
                    val bare = try {
                        val req = Request.Builder().url(c)
                            .header("User-Agent", MelonClient.UA)
                            .header("Referer", "${MelonClient.lastBase.trimEnd('/')}/").build()
                        probeClient.newCall(req).execute().use { resp ->
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            val head = bytes.take(8).joinToString(" ") { "%02X".format(it) }
                            "HTTP ${resp.code} ${resp.header("Content-Type")} ${bytes.size}B head=[$head]"
                        }
                    } catch (e: Exception) {
                        "${e.javaClass.simpleName}: ${e.message?.take(50)}"
                    }
                    // HttpConn（安卓原生 TLS/Conscrypt，WebView 同源栈）对照——
                    // 若它 head=FFD8（真图）而 okhttp=混淆字节，「TLS 指纹风控」实锤
                    val viaConn = if (n == 1)
                        httpConnBytes(c, "${MelonClient.lastBase.trimEnd('/')}/") else null
                    // Coil 真身：走 App 的 ImageLoader 完整路径（共享client+拦截器+缓存+
                    // v0.235 WebView Fetcher），与列表 UI 完全同路。v0.237：限时 12s——
                    // 它内部走 WebView 管线，未就绪时会等待，不限制就会拖死整个诊断
                    val viaCoil = if (n == 1) {
                        try {
                            val loader = (context.applicationContext as? coil.ImageLoaderFactory)?.newImageLoader()
                            if (loader == null) "Coil: loader不可用(非Factory ctx)"
                            else {
                                val req = coil.request.ImageRequest.Builder(context.applicationContext)
                                    .data(c).allowHardware(false).build()
                                val res = kotlinx.coroutines.withTimeoutOrNull(12_000) { loader.execute(req) }
                                if (res == null) "Coil: 超时(12s)"
                                else when (res) {
                                    is coil.request.SuccessResult ->
                                        "Coil:成功 ${res.drawable.intrinsicWidth}x${res.drawable.intrinsicHeight}"
                                    else -> "Coil:失败 " +
                                        ((res as? coil.request.ErrorResult)?.throwable
                                            ?.let { "${it.javaClass.simpleName}: ${it.message?.take(60)}" }
                                            ?: res.javaClass.simpleName)
                                }
                            }
                        } catch (e: Exception) {
                            "Coil异常: ${e.javaClass.simpleName}: ${e.message?.take(50)}"
                        }
                    } else null
                    listOfNotNull(bare, viaConn, viaCoil).joinToString("  ||  ")
                }.await()
                out.appendLine("封面$n: $r")
                n++
            }

            // v0.242 原生解密探针：okhttp 拉密文 → 本地 AES-128-CBC 解密 → 魔数。
            // 一行定案：密文头 + 解密结果（FFD8=成功，方案闭环；null=密钥错，看动态提取）
            val cipherTarget = covers.firstOrNull()
            if (cipherTarget != null) {
                val r = try {
                    val cipher = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val req = Request.Builder().url(cipherTarget)
                                .header("User-Agent", MelonClient.UA)
                                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()
                            probeClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) null else resp.body?.bytes()
                            }
                        }
                    }
                    when {
                        cipher == null -> "拉密文失败/超时(10s)"
                        cipher.isEmpty() -> "密文 0B（CDN 拦截）"
                        else -> {
                            val head = cipher.take(4).joinToString(" ") { "%02X".format(it) }
                            val plain = MelonImageCipher.decrypt(cipher)
                            val plainHead = plain?.take(4)?.joinToString(" ") { "%02X".format(it) }
                            if (plain != null) "密文${cipher.size}B[$head] → 解密${plain.size}B[$plainHead] ✓"
                            else {
                                val (k, iv) = MelonImageCipher.fetchKeyIv(MelonClient.lastBase)
                                val p2 = if (k != null && iv != null) MelonImageCipher.decrypt(cipher, k, iv) else null
                                if (p2 != null) "密文${cipher.size}B → 动态密钥解密${p2.size}B ✓（硬编码密钥已轮换）"
                                else "密文${cipher.size}B[$head] → 解密失败（密钥不可用）"
                            }
                        }
                    }
                } catch (e: Exception) {
                    "异常 ${e.javaClass.simpleName}: ${e.message?.take(50)}"
                }
                out.appendLine("原生解密: $r")
            } else out.appendLine("原生解密: 跳过（无封面URL）")

            // 首页 HTML 双栈对照：App 解析的封面 URL 是截断版 .jpe 而 PC 同镜像完整——
            // okhttp（FORCE_NETWORK）与 WebView 各拉一次首页，比较首条 loadBannerDirect
            // URL 完整性 → 一测定案「HTML 层内容混淆」是否存在及影响哪个栈
            try {
                val base = MelonClient.lastBase.trimEnd('/')
                val okHtml = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(8_000) {
                        val req = Request.Builder().url("$base/")
                            .header("User-Agent", MelonClient.UA)
                            .cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()
                        probeClient.newCall(req).execute().use { it.body?.string().orEmpty() }
                    }
                }.getOrNull() ?: ""
                val okUrl = Regex("""loadBannerDirect\('([^']{10,130})'""").find(okHtml)?.groupValues?.get(1)
                out.appendLine(
                    if (okHtml.isEmpty()) "首页okhttp: 超时(8s)"
                    else "首页okhttp: LEN=${okHtml.length} 首条=${okUrl ?: "无"}"
                )
            } catch (e: Exception) {
                out.appendLine("首页okhttp: ${e.javaClass.simpleName}: ${e.message?.take(40)}")
            }
            // v0.239：管线页已改为极简 stub（仅 zzz.js），首页 HTML 不再进 WebView——
            // 「首页WebView」对照行失去意义，删除。管线健康度看「WebView管线」raw 行。

            // v0.241 飞行黑匣子：管线每一步的实跑记录（Factory 分派是否命中/解密各环节
            // 结果/解析层统计），设备自己说出卡在哪一步
            val flight = MelonFlightLog.dump().take(28)
            out.appendLine("")
            out.appendLine("――― 飞行日志（最近${flight.size}条，新→旧） ―――")
            if (flight.isEmpty()) out.appendLine("（空：进列表/文章页后再跑诊断才有记录）")
            flight.forEach { out.appendLine(it) }
        }
        out.toString()
    }

    /**
     * HttpURLConnection 探针（v0.234 字节级终审）：Android 原生 TLS 栈（Conscrypt），与
     * WebView 的网络栈同源——若它拿到真图（FFD8）而 okhttp 拿到混淆字节（3E AA...），
     * 则「TLS 指纹风控」实锤，修复 = 图片/视频/下载全面换 HttpConn 栈。
     */
    private fun httpConnBytes(url: String, referer: String?): String = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("User-Agent", MelonClient.UA)
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
        }
        conn.connect()
        val code = conn.responseCode
        val head = ByteArray(8)
        val len = if (code in 200..299) {
            val ins = conn.inputStream
            var off = 0
            while (off < 8) {
                val r = ins.read(head, off, 8 - off)
                if (r < 0) break
                off += r
            }
            ins.close()
            off
        } else 0
        conn.disconnect()
        "HTTP $code head=[${head.take(len).joinToString(" ") { "%02X".format(it) }}]"
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message?.take(40)}"
    }

    /** 播放地址探测：m3u8 → KEY 实测 + 前 2 个切片实测（seek 卡死/下载失败的同链路定性） */
    private suspend fun probeVideo(url: String): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", MelonClient.UA).build()
            probeClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) "清单 HTTP ${r.code} ${r.header("Content-Type")}"
                else {
                    val body = r.body?.string().orEmpty()
                    if (!body.contains("#EXTM3U")) "HTTP 200 非清单(直链?)"
                    else {
                        val segs = body.lines().count { it.isNotBlank() && !it.startsWith("#") }
                        val sb = StringBuilder("m3u8 OK: $segs 片")
                        val base = url.substringBeforeLast('/')
                        // KEY 实测（AES-128 的密钥拉不到 = 播放黑屏/下载解不开）
                        body.lines().firstOrNull { it.contains("EXT-X-KEY") }?.let { kl ->
                            val ku = Regex("URI=\"([^\"]+)\"").find(kl)?.groupValues?.get(1)
                            if (ku != null) {
                                val abs = if (ku.startsWith("http")) ku else "$base/$ku"
                                try {
                                    probeClient.newCall(
                                        Request.Builder().url(abs).header("User-Agent", MelonClient.UA).build()
                                    ).execute().use { kr ->
                                        val kb = kr.body?.bytes() ?: ByteArray(0)
                                        sb.append(" | KEY:HTTP ${kr.code} ${kb.size}B" + if (kb.size == 16) "(正确16B)" else "")
                                    }
                                } catch (e: Exception) {
                                    sb.append(" | KEY:${e.javaClass.simpleName}")
                                }
                            }
                        }
                        // 前 2 个切片实测（卡 seek/下载 0% 多半是切片请求失败）
                        val segUrls = body.lines()
                            .filter { it.isNotBlank() && !it.startsWith("#") }
                            .take(2).map { s ->
                                if (s.startsWith("http")) s else "$base/$s"
                            }
                        segUrls.forEachIndexed { i, su ->
                            try {
                                probeClient.newCall(
                                    Request.Builder().url(su).header("User-Agent", MelonClient.UA).build()
                                ).execute().use { sr ->
                                    // TS 切片验证：每 188 字节首个应为 0x47；假切片（风控混淆
                                    // 字节）此处错乱 → ExoPlayer 解码卡死 / AES 解密失败下载挂
                                    val raw = sr.body?.bytes() ?: ByteArray(0)
                                    val head = raw.take(8).joinToString(" ") { "%02X".format(it) }
                                    val tsOk = raw.size >= 377 &&
                                        raw[0] == 0x47.toByte() && raw[188] == 0x47.toByte() &&
                                        raw[376] == 0x47.toByte()
                                    sb.append(" | 片$i:HTTP ${sr.code} ${raw.size}B head=[$head] TS对齐=${if (tsOk) "OK" else "错乱"}")
                                }
                            } catch (e: Exception) {
                                sb.append(" | 片$i:${e.javaClass.simpleName}: ${e.message?.take(40)}")
                            }
                        }
                        // 切片 HttpConn 对照（v0.234）：okhttp 假切片而 conn 真切片 → 换栈
                        segUrls.firstOrNull()?.let { su ->
                            sb.append(" | 片0conn:${httpConnBytes(su, null)}")
                        }
                        sb.toString()
                    }
                }
            }
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message?.take(60)}"
        }
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
