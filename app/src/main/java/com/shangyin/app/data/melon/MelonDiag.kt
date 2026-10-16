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

            // DNS 两路 + 两个 HTTP 栈并行跑，压时长（最坏 4s 内/项）
            val sysDns = async(Dispatchers.IO) {
                val r = runCatching { Dns.SYSTEM.lookup(host) }
                val ips: List<InetAddress> = r.getOrDefault(emptyList())
                when {
                    ips.isNotEmpty() -> ips.joinToString(",") { ip -> ip.hostAddress ?: "?" }
                    r.exceptionOrNull() != null -> "失败:${r.exceptionOrNull()!!.javaClass.simpleName}"
                    else -> "空"
                }
            }
            val dohDns = async(Dispatchers.IO) {
                DohResolver.dohLookup(host).joinToString(",") { it.hostAddress ?: "?" }.ifEmpty { "无结果" }
            }
            val okhttpRes = async(Dispatchers.IO) { probeOkhttp(host, referer) }
            val urlConnRes = async(Dispatchers.IO) { probeUrlConn(host, referer) }

            out.appendLine("系统DNS: ${sysDns.await().ifEmpty { "空" }}")
            out.appendLine("DoH阿里: ${dohDns.await()}")
            out.appendLine("okhttp: ${okhttpRes.await()}")
            out.appendLine("HttpConn: ${urlConnRes.await()}")
            // WebView 只测前两个（图床主候选 + 切片），压总时长
            if (idx < 2) out.appendLine("WebView: ${probeWebView(context, "https://$host/")}")
        }
        out.appendLine("镜像: ${MelonClient.lastBase}  图床池: ${MelonClient.fallbackPicHosts("")}")
        out.appendLine("判读: okhttp+HttpConn 全挂而 WebView 正常 = TLS指纹拦截; 三栈全挂 = 域名/IP阻断; DNS失败 = 解析问题")
        out.toString()
    }

    private fun probeOkhttp(host: String, referer: String?): String {
        val client = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build() // 故意裸 client（系统 DNS），与 0.221-0.224 时代的图床路径一致
        val req = Request.Builder().url("https://$host/").header("User-Agent", MelonClient.UA)
            .apply { referer?.let { header("Referer", it) } }.build()
        return try {
            client.newCall(req).execute().use { "HTTP ${it.code} ${it.header("Content-Type") ?: ""}" }
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
