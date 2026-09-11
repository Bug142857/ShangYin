package com.shangyin.app.ui.music

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 离屏直链/歌词抓取器：后台加载音乐站（WebView Cookie 系统级共享，人机验证过一次即可直接进），
 * 自动获取目标歌曲直链，嗅探到的新直链与歌词回调给调用方。
 * 用途：①直链过期自动续播；②播放时后台抓歌词。
 * muteAudio=true 时点播后把页面音频元素静音（抓歌词场景避免与正在播放的声音重叠）。
 *
 * 直链获取按可靠性排序：
 *  1. API 重放：重放学习到的站内搜索/播放接口（关键词替换，页面上下文 fetch，免 DOM）；
 *  2. DOM 自动搜索 + 自动点播：填搜索框触发搜索，再按歌名模拟点击；
 *  3. 点播窗口兜底：点播后 20 秒内的音频流请求直接认作目标直链。
 *
 * 挂载后约 45 秒内未捕获则回调 onTimeout。捕获或超时后由调用方卸载本组件。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MusicRefresher(
    songName: String,
    artist: String,
    muteAudio: Boolean = false,
    onCaptured: (url: String, lyrics: String?) -> Unit,
    onTimeout: () -> Unit,
) {
    // 线程安全捕获槽（JS 桥在 WebView 后台线程回调）
    val found = remember { AtomicReference<String?>(null) }
    val lyricsFound = remember { AtomicReference<String?>(null) }
    var delivered by remember { mutableStateOf(false) }
    // 点播窗口：点播发出后 20 秒内的音频流请求，即使 URL/解析器匹配不上歌名也认作目标直链
    // （防站点改版导致解析器失效 / 歌手字段写法不一致）
    val playWindowAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    // 首页加载后先获取目标歌数据；MPA 搜索跳新页后 searchDone 已置位 → 走点播排程
    val searchDone = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    // 播放接口重放只发一轮（防重复风暴）
    val phase2Done = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val webRef = remember { AtomicReference<WebView?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    /** 在页面上下文里 fetch 一批 URL，响应自动回流到 onApi 走通用解析 */
    fun replay(urls: List<String>) {
        if (urls.isEmpty()) return
        val arr = org.json.JSONArray(urls).toString()
        mainHandler.post {
            val w = webRef.get() ?: return@post
            runCatching { w.evaluateJavascript("window.__replayFetch && window.__replayFetch($arr)", null) }
        }
    }

    val sniffer = remember {
        object {
            @JavascriptInterface
            fun onApi(url: String, body: String) {
                // 目标歌的歌词：refresher 只为一首歌服务，响应体含 LRC 时间轴即认定属于它
                if (lyricsFound.get() == null && body.length in 40..120_000 && LRC_REGEX.containsMatchIn(body)) {
                    lyricsFound.compareAndSet(null, body.trim())
                }
                val parsed = runCatching { SnifferParser.parse(body) }.getOrNull().orEmpty()
                // 刷新器抓到的接口同样入库学习（搜索变体重放成功后，下次刷新更快）
                MusicApiLearn.record(url, parsed)
                // 二段重放：搜索响应里有目标歌但没带直链 → 用它的 id/歌名重放播放接口
                if (found.get() == null && phase2Done.compareAndSet(false, true)) {
                    val hit = parsed.firstOrNull { s ->
                        s.playUrl.isNullOrBlank() &&
                            (s.name == songName || s.name.replace(" ", "") == songName.replace(" ", ""))
                    }
                    if (hit != null) {
                        val cands = MusicApiLearn.extractCandidates(body, songName)
                        replay(MusicApiLearn.playVariants(cands))
                    } else {
                        phase2Done.set(false) // 本响应不含目标歌，留给下一次响应
                    }
                }
                parsed.forEach { s ->
                    if (found.get() != null) return@forEach
                    if (s.playUrl.isNullOrBlank()) return@forEach
                    // 歌名匹配即可：refresher 一次只服务一首歌（歌手字段写法差异不再作为硬条件）
                    val nameOk = s.name == songName || s.name.replace(" ", "") == songName.replace(" ", "")
                    if (nameOk) found.compareAndSet(null, s.playUrl)
                }
            }

            @JavascriptInterface
            fun onAudio(url: String) {
                if (!url.startsWith("http") || found.get() != null) return
                val probe = SniffedSong(songName, artist, null, null)
                if (audioUrlMatchesSong(probe, url)) {
                    found.compareAndSet(null, url)
                    return
                }
                // 点播窗口兜底：窗口内出现的音频流 = 刚被点播的那首（网络层直接采信）
                val inWindow = System.currentTimeMillis() - playWindowAt.get() in 0..20_000
                if (inWindow && (AUDIO_URL_REGEX.containsMatchIn(url) || isAudioStreamUrl(url))) {
                    found.compareAndSet(null, url)
                }
            }
        }
    }

    // 轮询捕获结果（500ms 一次，45 秒超时）。
    // 直链与歌词都到 → 立即回调；直链先到 → 再等最多 3 秒歌词；45 秒仍无直链 → 超时
    LaunchedEffect(songName, artist) {
        var urlAt = -1L
        repeat(90) {
            delay(500)
            if (!delivered) {
                val u = found.get()
                val l = lyricsFound.get()
                if (u != null) {
                    val now = System.currentTimeMillis()
                    if (urlAt < 0) urlAt = now
                    if (l != null || now - urlAt > 3000) {
                        delivered = true
                        onCaptured(u, l)
                        return@LaunchedEffect
                    }
                }
            }
        }
        if (!delivered) onTimeout()
    }

    // 组件卸载时销毁 WebView，防泄漏；排程里的 evaluateJavascript 靠 webRef 判空跳过
    DisposableEffect(Unit) {
        onDispose {
            mainHandler.post {
                webRef.getAndSet(null)?.let { w -> runCatching { w.destroy() } }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.mediaPlaybackRequiresUserGesture = false // 允许 JS 自动触发播放
                settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
                addJavascriptInterface(sniffer, "MusicSniffer")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView, request: WebResourceRequest
                    ): WebResourceResponse? {
                        val u = request.url.toString()
                        val hasRange = request.requestHeaders.keys.any {
                            it.equals("Range", ignoreCase = true)
                        }
                        if (AUDIO_URL_REGEX.containsMatchIn(u) || (hasRange && isAudioStreamUrl(u))) {
                            sniffer.onAudio(u)
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        webRef.set(view)
                        view?.evaluateJavascript(SNIFFER_JS, null)
                        val n = JSONObject.quote(songName)
                        val a = JSONObject.quote(artist)
                        val k = JSONObject.quote(songName)
                        // 页面已就绪：1 秒后重放学到的搜索接口（免 DOM，最快路径）
                        view?.postDelayed({
                            webRef.get() ?: return@postDelayed
                            replay(MusicApiLearn.searchVariants(songName))
                        }, 1000)
                        if (searchDone.compareAndSet(false, true)) {
                            // 首页：3 秒后自动搜索目标歌（MPA 会跳到搜索结果页 / SPA 原地渲染）
                            view?.postDelayed({
                                val w = webRef.get() ?: return@postDelayed
                                runCatching {
                                    w.evaluateJavascript("window.__searchPlay && window.__searchPlay($k)", null)
                                }
                            }, 3000)
                            // 搜索完成后点播（SPA 原地渲染 / MPA 结果页均可）
                            listOf(8000L, 14000L).forEach { at ->
                                view?.postDelayed({
                                    val w = webRef.get() ?: return@postDelayed
                                    runCatching {
                                        w.evaluateJavascript(
                                            "window.__playByName && window.__playByName($n, $a, 1)", null
                                        )
                                    }
                                    playWindowAt.set(System.currentTimeMillis())
                                }, at)
                            }
                            view?.postDelayed({
                                val w = webRef.get() ?: return@postDelayed
                                runCatching {
                                    w.evaluateJavascript(
                                        "window.__playByName && window.__playByName($n, $a, 2)", null
                                    )
                                }
                                playWindowAt.set(System.currentTimeMillis())
                            }, 20000)
                        } else {
                            // 已搜索过（结果页/跳转页）：直接排点播
                            listOf(3000L, 9000L).forEach { at ->
                                view?.postDelayed({
                                    val w = webRef.get() ?: return@postDelayed
                                    runCatching {
                                        w.evaluateJavascript(
                                            "window.__playByName && window.__playByName($n, $a, 1)", null
                                        )
                                    }
                                    playWindowAt.set(System.currentTimeMillis())
                                }, at)
                            }
                            view?.postDelayed({
                                val w = webRef.get() ?: return@postDelayed
                                runCatching {
                                    w.evaluateJavascript(
                                        "window.__playByName && window.__playByName($n, $a, 2)", null
                                    )
                                }
                                playWindowAt.set(System.currentTimeMillis())
                            }, 15000)
                        }
                        // 抓歌词场景：点播后把页面音频元素静音（直链/歌词请求照发，只是不出声）
                        if (muteAudio && view != null) {
                            val muteJs = "document.querySelectorAll('audio,video').forEach(function(m){m.muted=true;m.volume=0})"
                            listOf(2500L, 4500L, 8000L, 12000L).forEach { delayMs ->
                                view.postDelayed({
                                    val w = webRef.get() ?: return@postDelayed
                                    runCatching { w.evaluateJavascript(muteJs, null) }
                                }, delayMs)
                            }
                        }
                    }
                }
                loadUrl(MUSIC_SITE)
            }
        },
        update = { },
        modifier = Modifier.width(1.dp).height(1.dp)
    )
}
