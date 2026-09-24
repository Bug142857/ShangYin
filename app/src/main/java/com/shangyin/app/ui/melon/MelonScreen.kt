package com.shangyin.app.ui.melon

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.shangyin.app.ui.safePopBackStack

/**
 * 吃瓜（里世界第八入口）：51爆料 吃瓜站点，内嵌 WebView 浏览。
 *
 * 为什么用 WebView 而不是原生解析：该站首页是 JS 动态生成的（整页 Base64 混淆 +
 * document.write 输出），线路地址每次都由浏览器端随机生成并 ping 测速选线，
 * 镜像域名还会不定期轮换 —— 原生模拟这套逻辑极其脆弱，WebView 里站点自己的
 * JS 全部照常工作，等同于浏览器体验，站点改版也不受影响。
 *
 * 会话内记住最后浏览的地址（MelonCache）：退出再进不回首页，直接续看。
 */
private const val HOME_URL = "https://www.ipqegzvg.cc/"

/** 会话级缓存：退出吃瓜页再进时恢复上次浏览到的页面 */
object MelonCache {
    @Volatile
    var lastUrl: String? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelonScreen(nav: NavHostController) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    // 当前主框地址：错误页的「回首页」只在确实不在首页时显示
    var currentUrl by remember { mutableStateOf(MelonCache.lastUrl ?: HOME_URL) }

    fun openExternal(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            android.widget.Toast.makeText(context, "无法打开链接", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("吃瓜") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { webView?.reload() },
                        enabled = !loadError
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { pad ->
        Box(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            // 站内不少图片/视频走 http 直链，允许混载
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val url = request.url.toString()
                                val scheme = request.url.scheme?.lowercase()
                                // http/https 留在应用内浏览；其余协议（mailto / tg / intent 等）交给系统
                                if (scheme == "http" || scheme == "https") return false
                                openExternal(url)
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                currentUrl = url
                                loading = true
                                loadError = false
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                loading = false
                                currentUrl = url
                                // 记住浏览位置：下次进模块直接续看
                                if (!url.startsWith("about:")) MelonCache.lastUrl = url
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                super.onReceivedError(view, request, error)
                                // 只处理主框架错误；子资源挂了不影响页面展示
                                if (request.isForMainFrame) {
                                    loading = false
                                    loadError = true
                                }
                            }
                        }
                        // 站内「下载 App」等直链交给系统浏览器处理
                        setDownloadListener { url, _, _, _, _ -> openExternal(url) }
                        loadUrl(MelonCache.lastUrl ?: HOME_URL)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (loading && !loadError) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (loadError) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .align(Alignment.Center)
                ) {
                    Text(
                        "线路加载失败\n该站线路经常更换，试试回首页重新选线",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { webView?.reload() },
                        modifier = Modifier.padding(top = 16.dp)
                    ) { Text("重试") }
                    if (!currentUrl.startsWith(HOME_URL)) {
                        OutlinedButton(
                            onClick = { webView?.loadUrl(HOME_URL) },
                            modifier = Modifier.padding(top = 8.dp)
                        ) { Text("回首页") }
                    }
                }
            }
        }
    }

    // 返回键逐级返回：WebView 有历史先退页面历史，没有才退出模块
    BackHandler(enabled = true) {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else nav.safePopBackStack()
    }
}
