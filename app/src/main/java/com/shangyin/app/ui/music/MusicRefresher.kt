package com.shangyin.app.ui.music

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
 * 离屏直链刷新器：后台加载音乐站（WebView Cookie 系统级共享，人机验证过一次即可直接进），
 * 自动点播目标歌曲，嗅探到的新直链回调给调用方。
 * 用于"直链过期自动续播"：老直链失效时无需用户手动重新收藏。
 *
 * 挂载后约 45 秒内未捕获则回调 onTimeout。捕获或超时后由调用方卸载本组件。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MusicRefresher(
    songName: String,
    artist: String,
    onCaptured: (String) -> Unit,
    onTimeout: () -> Unit,
) {
    // 线程安全捕获槽（JS 桥在 WebView 后台线程回调）
    val found = remember { AtomicReference<String?>(null) }
    var delivered by remember { mutableStateOf(false) }

    val sniffer = remember {
        object {
            @JavascriptInterface
            fun onApi(url: String, body: String) {
                if (found.get() != null) return
                SnifferParser.parse(body).forEach { s ->
                    if (s.playUrl.isNullOrBlank()) return@forEach
                    val nameOk = s.name == songName || s.name.replace(" ", "") == songName.replace(" ", "")
                    val artistOk = artist.isBlank() ||
                        s.artist.contains(artist) || artist.contains(s.artist)
                    if (nameOk && artistOk) found.compareAndSet(null, s.playUrl)
                }
            }

            @JavascriptInterface
            fun onAudio(url: String) {
                if (!url.startsWith("http") || found.get() != null) return
                val probe = SniffedSong(songName, artist, null, null)
                if (audioUrlMatchesSong(probe, url)) found.compareAndSet(null, url)
            }
        }
    }

    // 轮询捕获结果（500ms 一次，45 秒超时）
    LaunchedEffect(songName, artist) {
        repeat(90) {
            delay(500)
            if (!delivered) {
                found.get()?.let { u ->
                    delivered = true
                    onCaptured(u)
                    return@LaunchedEffect
                }
            }
        }
        if (!delivered) onTimeout()
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
                        view?.evaluateJavascript(SNIFFER_JS, null)
                        // 页面数据渲染需要时间：3 秒后点歌名元素，9 秒后试行内播放按钮
                        val n = JSONObject.quote(songName)
                        val a = JSONObject.quote(artist)
                        view?.postDelayed({
                            view.evaluateJavascript(
                                "window.__playByName && window.__playByName($n, $a, 1)", null
                            )
                        }, 3000)
                        view?.postDelayed({
                            view.evaluateJavascript(
                                "window.__playByName && window.__playByName($n, $a, 2)", null
                            )
                        }, 9000)
                    }
                }
                loadUrl(MUSIC_SITE)
            }
        },
        update = { },
        modifier = Modifier.width(1.dp).height(1.dp)
    )
}
