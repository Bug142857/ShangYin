package com.shangyin.app.data.melon

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 吃瓜图床 WebView 解密管线（v0.235）——「内容级风控」的终极修复。
 *
 * ## 根因（0.233/0.234 两轮字节级诊断定案）
 * 图床服务器按 **TLS/JA3 指纹**下发不同数据：Chromium（WebView）→ 真密文（CryptoJS
 * OpenSSL 格式，可被站点前端 decryptImage 解开）；其余所有栈（curl/okhttp/Conscrypt/
 * node undici，PC 与设备实测一致）→ 200 + 固定头 `3E AA 70 8E` 的混淆假字节
 * （表面 200/octet-stream/正常大小，内容是垃圾）。假说排除史：DNS 污染（权威 DoH 对照）、
 * Cookie 挑战（无 Cookie 的 WebView 也能看）、UA/Referer（多栈同头全假）、Sec-Fetch 头
 * （PC curl 带全套仍假）、Conscrypt 兜底（设备实测同样假字节）。
 *
 * ## 方案
 * 唯一被白名单的客户端 = App 内 WebView（Chromium 网络栈）。站点页 zzz.js 提供了完整
 * 解密函数（`fetch → ab2b64 → decryptImage → 明文 base64`，密钥/IV 在混淆表）。
 * 所以：隐藏 WebView 加载一次镜像站点页，借页面上下文执行站点自己的解密管线，
 * 拿**明文 base64** 回传 App → BitmapFactory 直接解码。密钥轮换无需关心（JS 现场取）。
 *
 * 串行队列（单 WebView），整体超时 20s；任何环节失败返回 null（调用方自行兜底）。
 */
object MelonWebImage {

    private val scope: CoroutineScope = MainScope()
    private val mutex = Mutex()
    private var webView: WebView? = null
    private val starting = AtomicBoolean(false)
    @Volatile private var pageReady = false
    @Volatile private var pageUrl: String = ""

    /** 页面上下文是否就绪（站点 JS 已加载，decryptImage 可用） */
    val isReady: Boolean get() = pageReady

    /**
     * 确保隐藏 WebView 已加载站点页（主线程执行；重复调用幂等，页面变更时重载）。
     * onReady 在页面就绪（或失败）后回调一次。
     */
    fun ensure(context: Context, baseUrl: String, onReady: (Boolean) -> Unit) {
        val page = baseUrl.toHttpUrl().let { "${it.scheme}://${it.host}/" }
        if (pageReady && page == pageUrl) {
            onReady(true); return
        }
        if (!starting.compareAndSet(false, true)) {
            // 已在加载中：轮询等结果
            scope.launch {
                var waited = 0
                while (!pageReady && waited < 20_000) { delay(300); waited += 300 }
                onReady(pageReady)
            }
            return
        }
        pageUrl = page
        scope.launch(Dispatchers.Main) {
            try {
                @SuppressLint("SetJavaScriptEnabled")
                val wv = webView ?: WebView(context.applicationContext).also {
                    it.settings.javaScriptEnabled = true
                    it.settings.domStorageEnabled = true
                    it.settings.blockNetworkImage = true // 只借 JS/网络，不渲染图片（省流量）
                    it.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 检查解密函数是否真的可用（外链 JS 加载失败时页面照样 finish）
                            view?.evaluateJavascript(
                                "(typeof decryptImage==='function' && typeof ab2b64==='function')?'1':'0'"
                            ) { v ->
                                pageReady = v?.contains("1") == true
                                starting.set(false)
                                onReady(pageReady)
                            }
                        }
                    }
                    webView = it
                }
                if (wv.url != page) wv.loadUrl(page)
            } catch (t: Throwable) {
                starting.set(false)
                onReady(false)
            }
        }
    }

    /**
     * 经 WebView 执行站点解密管线取图：返回明文图片字节（JPEG/PNG/WebP），失败 null。
     * 内部串行（mutex）；单张整体 20s 超时。
     */
    suspend fun fetchDecrypted(url: String): ByteArray? = withContext(Dispatchers.Main) {
        val wv = webView ?: return@withContext null
        if (!pageReady) return@withContext null
        withTimeout(20_000) {
            mutex.withLock {
                suspendCancellableCoroutine { cont ->
                    // 借站点页上下文：fetch(密文) → ab2b64 → decryptImage → 明文 base64
                    // 结果挂 window.__mj_out；轮询取回后清空。异常 → __mj_err。
                    val js = """
                        (function(){
                          window.__mj_out=null; window.__mj_err=null;
                          fetch('$url')
                            .then(function(r){return r.arrayBuffer()})
                            .then(function(ab){return ab2b64(ab)})
                            .then(function(b64){return decryptImage(b64)})
                            .then(function(plain){window.__mj_out=plain||'ERR:EMPTY'})
                            .catch(function(e){window.__mj_err=''+e});
                        })();
                    """.trimIndent()
                    wv.evaluateJavascript(js, null)
                    // 轮询结果（fetch+解密一张典型 0.5~2s）
                    var waited = 0
                    fun poll() {
                        wv.evaluateJavascript(
                            "(window.__mj_err?('ERR:'+window.__mj_err):(window.__mj_out===null?null:window.__mj_out))"
                        ) { v ->
                            val s = v?.trim().orEmpty()
                            when {
                                s == "null" && waited < 15_000 -> {
                                    waited += 250
                                    wv.postDelayed({ poll() }, 250)
                                }
                                s.contains("\"ERR") -> if (cont.isActive) cont.resume(null)
                                s.length > 4 -> {
                                    // v 是 JS 字符串字面量："<base64>" → 去引号 + JS 转义还原
                                    val b64 = s.removeSurrounding("\"")
                                        .replace("\\\"", "\"").replace("\\\\", "\\")
                                    val bytes = runCatching {
                                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                    }.getOrNull()
                                    if (cont.isActive) cont.resume(bytes)
                                }
                                else -> if (cont.isActive) cont.resume(null)
                            }
                        }
                    }
                    wv.postDelayed({ poll() }, 300)
                }
            }
        }
    }

    /** 释放（低内存回调时调用） */
    fun destroy() {
        scope.launch(Dispatchers.Main) {
            runCatching { webView?.destroy() }
            webView = null
            pageReady = false
        }
    }
}
