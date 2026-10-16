package com.shangyin.app.data.melon

import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
        }
        out.appendLine("镜像: ${MelonClient.lastBase}  图床池: ${MelonClient.fallbackPicHosts("")}")
        out.appendLine("判读: HTML片段=挑战页/劫持页(看内容定性); 若okhttp+Cookie返回200 image=Cookie直通已解; 三栈全挂=域名阻断; 切片域名403属CDN对裸路径的正常行为")
        out.toString()
    }

    private fun probeOkhttp(host: String, referer: String?, withCookie: Boolean): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build() // 故意裸 client（系统 DNS），与 App 内图床路径一致
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
            client.newCall(b.build()).execute().use { resp ->
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
