package com.shangyin.app.data.melon

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.shangyin.app.App
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
 * 吃瓜图床 WebView 原始拉取管线（v0.243）。
 *
 * ## 风控真相（0.241 黑匣子 + 0.242/0.243 两轮对照实验定案）
 * 图床 CDN 按 **TLS/JA3 指纹**下发数据：Chromium（WebView）→ 真*密文*（站点 zzz.js
 * 前端解密）；其余栈（okhttp/Conscrypt/curl）→ 200 + 随机垃圾字节（每次头都不同：
 * 4F E8 / 3E AA / DD E2 / C2 8F——0.242 飞行日志实证，同 URL 多次拉取头均不同）。
 *
 * ## v0.235~0.242 七轮 ensure=false 的真正根因（v0.243 定案）
 * 管线用 `context.applicationContext` 创建常驻 WebView——**ApplicationContext 创建的
 * WebView 在该设备上 Chromium 静默瘫痪**（evaluateJavascript 回调一次都不执行，
 * 25s/60s 轮询全空）；而诊断探针用 Activity context 的 WebView 每次都 OK，同设备
 * 同网络对照实锤。修复：WebView 一律改用 [App.currentActivity] 创建。
 *
 * ## v0.243 管线设计
 * 1. stub 页**不再加载 zzz.js**（0.239 方案依赖镜像可达 + 慢加载）：极简空页，
 *    `loadDataWithBaseURL(图床origin)` 只借 origin——fetch 同源无 CORS 限制；
 * 2. 就绪判定 = `location.protocol==='https:'`（stub 已提交、origin 已建立），
 *    秒级达成；看门狗 20s 内 JS 回调一次都没触发 → 判定瘫痪 WebView → destroy
 *    重建（旧方案失败后继续用坏 WebView 轮询到 60s，永不自愈）；
 * 3. [fetchViaWeb] 页面内 `fetch → 分块 btoa → base64` 回传原始字节（可能是密文），
 *    Kotlin 侧做魔数判定与 AES 解密（[MelonImageCipher]，KEY/IV 自 zzz.js 逆向）。
 *
 * 串行队列（单 WebView）；任何环节失败返回 null（调用方自行兜底）。
 */
object MelonWebImage {

    private val scope: CoroutineScope = MainScope()
    private val mutex = Mutex()
    private var webView: WebView? = null
    private val starting = AtomicBoolean(false)
    @Volatile private var pageReady = false
    @Volatile private var pageUrl: String = ""

    /**
     * 确保隐藏 WebView 已加载 origin=图床的 stub 页（主线程执行；重复调用幂等）。
     * onReady 在页面就绪（或失败）后回调一次。
     */
    fun ensure(context: Context, targetUrl: String, onReady: (Boolean) -> Unit) {
        val page = targetUrl.toHttpUrl().let { "${it.scheme}://${it.host}/" }
        if (pageReady && page == pageUrl) {
            onReady(true); return
        }
        if (!starting.compareAndSet(false, true)) {
            // 已在加载中：轮询等结果（上限 30s，与下方看门狗同量级）
            scope.launch {
                var waited = 0
                while (!pageReady && waited < 30_000) { delay(500); waited += 500 }
                onReady(pageReady)
            }
            return
        }
        pageUrl = page
        scope.launch(Dispatchers.Main) {
            try {
                // 宿主 Activity 已销毁的旧 WebView 必须重建（Activity context 的生命周期）
                val existing = webView
                if (existing != null) {
                    val act = existing.context as? android.app.Activity
                    if (act?.isDestroyed == true || act?.isFinishing == true) {
                        MelonFlightLog.log("PIPE", "宿主Activity已销毁 → 重建WebView")
                        runCatching { existing.destroy() }
                        webView = null
                        pageReady = false
                    }
                }
                @SuppressLint("SetJavaScriptEnabled")
                val wv = webView ?: WebView(App.currentActivity ?: context.applicationContext).also {
                    it.settings.javaScriptEnabled = true
                    it.settings.domStorageEnabled = true
                    it.settings.blockNetworkImage = true // 只借 JS/网络，不渲染图片（省流量）
                    webView = it
                }
                // v0.243：stub 页无任何外部依赖（zzz.js 已移出，解密在 Kotlin 侧）——
                // baseUrl=图床 origin，页面内 fetch 目标图 = 同源请求（无 CORS 限制）。
                val stub = "<!DOCTYPE html><html><head><meta charset='utf-8'></head><body></body></html>"
                MelonFlightLog.log("PIPE", "stub加载 page=$page ctx=${wv.context.javaClass.simpleName}")
                wv.loadDataWithBaseURL(page, stub, "text/html", "utf-8", null)

                var waited = 0
                var settled = false
                fun settle(ok: Boolean) {
                    if (settled) return
                    settled = true
                    starting.set(false)
                    if (ok) {
                        pageReady = true
                        MelonFlightLog.log("PIPE", "就绪 waited=${waited}ms")
                    } else {
                        // v0.243：失败即销毁，下次请求从头重建（旧方案带病轮询永不自愈）
                        MelonFlightLog.log("PIPE", "未就绪 waited=${waited}ms → 销毁待重建")
                        runCatching { wv.destroy() }
                        if (webView === wv) { webView = null; pageReady = false }
                    }
                    onReady(ok)
                }
                // 看门狗：evaluateJavascript 回调一次都不执行 = Chromium 瘫痪实锤
                val watchdog = Runnable { settle(false) }
                wv.postDelayed(watchdog, 20_000)
                fun poll() {
                    if (settled) return
                    runCatching {
                        wv.evaluateJavascript("location.protocol==='https:'?'1':'0'") { v ->
                            when {
                                v?.contains("'1'") == true -> settle(true)
                                v != null && waited < 15_000 -> { waited += 250; wv.postDelayed({ poll() }, 250) }
                                v == null && waited < 15_000 -> { waited += 500; wv.postDelayed({ poll() }, 500) }
                                else -> settle(false)
                            }
                        }
                    }.onFailure { settle(false) }
                }
                poll()
            } catch (t: Throwable) {
                starting.set(false)
                onReady(false)
            }
        }
    }

    /**
     * v0.243 核心：经隐藏 WebView（Chromium 网络栈）拉取图片**原始字节**。
     * 图床风控指纹只放行 Chromium——okhttp 拿到的是 200+随机垃圾。stub 页 origin=
     * 图床自身，fetch 为同源请求（无 CORS 限制），Chromium 指纹下发的真密文原样
     * 回传；是明文还是密文由调用方魔数判定 + [MelonImageCipher] 解密。
     * 内部串行（mutex）；失败返回 null。
     */
    suspend fun fetchViaWeb(context: Context, url: String): ByteArray? {
        val ready = suspendCancellableCoroutine { cont ->
            ensure(context, url) { ok -> if (cont.isActive) cont.resume(ok) }
        }
        if (!ready) {
            MelonFlightLog.log("WEBFETCH", "管线未就绪 url=$url")
            return null
        }
        return withContext(Dispatchers.Main) {
            val wv = webView ?: return@withContext null
            withTimeout(30_000) {
                mutex.withLock {
                    suspendCancellableCoroutine { cont ->
                        // fetch → arrayBuffer → 分块转 base64（String.fromCharCode.apply
                        // 参数上限 ~64k，0x8000 分块安全）→ btoa → 挂 window.__mj_out
                        val js = """
                            (function(){
                              window.__mj_out=null; window.__mj_err=null;
                              fetch('$url').then(function(r){
                                if(!r.ok) throw new Error('HTTP '+r.status);
                                return r.arrayBuffer();
                              }).then(function(ab){
                                var u8=new Uint8Array(ab), s='', CH=0x8000;
                                for(var i=0;i<u8.length;i+=CH){
                                  s+=String.fromCharCode.apply(null,u8.subarray(i,Math.min(i+CH,u8.length)));
                                }
                                return btoa(s);
                              }).then(function(b64){ window.__mj_out=b64; })
                                .catch(function(e){ window.__mj_err=''+e; });
                            })();
                        """.trimIndent()
                        runCatching { wv.evaluateJavascript(js, null) }
                            .onFailure { if (cont.isActive) cont.resume(null) }
                        var waited = 0
                        var settled = false
                        fun settle(v: ByteArray?) {
                            if (settled) return
                            settled = true
                            if (cont.isActive) cont.resume(v)
                        }
                        val watchdog = Runnable { settle(null) }
                        wv.postDelayed(watchdog, 29_000)
                        fun poll() {
                            if (settled) return
                            wv.evaluateJavascript(
                                "(window.__mj_err===null&&window.__mj_out===null)?null:(window.__mj_err===null?window.__mj_out:('ERR:'+window.__mj_err))"
                            ) { v ->
                                val s = v?.trim().orEmpty()
                                when {
                                    s == "null" && waited < 25_000 -> {
                                        waited += 250
                                        wv.postDelayed({ poll() }, 250)
                                    }
                                    s.contains("\"ERR") -> {
                                        MelonFlightLog.log("WEBFETCH", "JS异常 waited=${waited}ms ${s.take(90)}")
                                        settle(null)
                                    }
                                    s.length > 4 -> {
                                        // v 是 JS 字符串字面量："<base64>"（base64 字母表无引号/反斜杠，直接去引号）
                                        val b64 = s.removeSurrounding("\"")
                                        val bytes = runCatching {
                                            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                        }.getOrNull()
                                        MelonFlightLog.log(
                                            "WEBFETCH", "完成 waited=${waited}ms b64=${b64.length}B 字节=${bytes?.size ?: -1}B"
                                        )
                                        settle(bytes)
                                    }
                                    else -> settle(null)
                                }
                            }
                        }
                        wv.postDelayed({ poll() }, 300)
                    }
                }
            }
        }
    }
}
