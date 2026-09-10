package com.shangyin.app.ui.music

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.view.ViewGroup
import android.webkit.WebSettings
import androidx.compose.ui.viewinterop.AndroidView
import com.shangyin.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 音乐搜索（泡椒音源 flac.music.hi.cn）：
 * WebView 自适应嗅探方案，公共逻辑见 MusicSniffer.kt。
 * 收藏流程：点 + → 已有直链直接落库；没有则挂起等待，用户在网页里点播这首歌时自动完成收藏。
 */
/** 收藏中等待直链捕获的条目（仅在主线程访问） */
private class PendingSave(
    val song: SniffedSong,
    var cont: kotlinx.coroutines.CancellableContinuation<String>? = null
)

/** 歌名匹配（规范化后相等或互相包含）——用 API 解析结果与待收藏歌曲配对 */
private fun songNameMatches(a: String, b: String): Boolean {
    val x = a.lowercase().replace(" ", "")
    val y = b.lowercase().replace(" ", "")
    if (x.length < 2 || y.length < 2) return false
    return x == y || x.contains(y) || y.contains(x)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSearchScreen(nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val songs = remember { mutableStateListOf<SniffedSong>() }
    // WebView 播放音频时真实发出的音频流请求（最新在前）——比解析 API 字段更可靠的直链来源
    val audioStreamUrls = remember { mutableListOf<String>() }
    val seenUrls = remember { mutableStateListOf<String>() }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var captured by remember { mutableIntStateOf(0) }
    val savingKeys = remember { mutableStateListOf<String>() }
    val savedKeys = remember { mutableStateListOf<String>() }
    var parsing by remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    // 待捕获的收藏：自动点播失败后挂起，用户在网页里手动点播这首歌时自动完成
    val pendingSaves = remember { mutableListOf<PendingSave>() }

    /** 音频流入池（主线程），并检查是否有等待直链的收藏 */
    fun onAudioCaptured(url: String) {
        audioStreamUrls.removeAll { it == url }
        audioStreamUrls.add(0, url)
        if (audioStreamUrls.size > 20) audioStreamUrls.removeAt(audioStreamUrls.size - 1)
        val pending = pendingSaves.firstOrNull { audioUrlMatchesSong(it.song, url) } ?: return
        pendingSaves.remove(pending)
        pending.cont?.let { c ->
            if (c.isActive) c.resume(url)
            pending.cont = null
        }
    }

    val sniffer = remember {
        object {
            @JavascriptInterface
            fun onApi(url: String, body: String) {
                // 播放接口的 URL 本身若是音频流（fetch/XHR 发出的播放请求），当作音频捕获
                if (isAudioStreamUrl(url)) {
                    mainHandler.post { onAudioCaptured(url) }
                }
                val parsed = SnifferParser.parse(body)
                // 挂起中的收藏优先匹配：播放 API 响应里带歌名 + 直链，不依赖直链 URL 是否含歌名。
                // 无视 seenUrls 去重——同一首歌二次点播时 API URL 相同，会被去重挡住导致永不匹配
                if (pendingSaves.isNotEmpty() && parsed.isNotEmpty()) {
                    mainHandler.post {
                        parsed.forEach { p ->
                            val pending = pendingSaves.firstOrNull {
                                songNameMatches(it.song.name, p.name) &&
                                    (it.song.artist.isBlank() || p.artist.isBlank() ||
                                        it.song.artist.contains(p.artist) ||
                                        p.artist.contains(it.song.artist))
                            } ?: return@forEach
                            if (p.playUrl.isNullOrBlank()) return@forEach
                            pendingSaves.remove(pending)
                            // 回填列表里这首歌的直链，下次收藏直接可用
                            val idx = songs.indexOfFirst {
                                songNameMatches(it.name, p.name)
                            }
                            if (idx >= 0 && songs[idx].playUrl != p.playUrl) {
                                songs[idx] = songs[idx].copy(playUrl = p.playUrl)
                            }
                            pending.cont?.let { c ->
                                if (c.isActive) c.resume(p.playUrl)
                                pending.cont = null
                            }
                        }
                    }
                }
                if (url in seenUrls) return
                seenUrls.add(url)
                if (seenUrls.size > 200) seenUrls.clear()
                if (parsed.isEmpty()) return
                parsing = true
                val fresh = parsed.filter { p -> songs.none { it.name == p.name && it.artist == p.artist } }
                if (fresh.isNotEmpty()) {
                    songs.addAll(0, fresh)
                    captured += fresh.size
                }
                // 同一首歌再次出现时用新直链覆盖（网页里重新播放会拿到新链接，旧的可能已过期）
                parsed.forEach { p ->
                    val idx = songs.indexOfFirst { it.name == p.name && it.artist == p.artist }
                    if (idx >= 0 && !p.playUrl.isNullOrBlank() && p.playUrl != songs[idx].playUrl) {
                        songs[idx] = songs[idx].copy(playUrl = p.playUrl)
                    }
                }
                parsing = false
            }

            @JavascriptInterface
            fun onAudio(url: String) {
                // 网页播放器 <audio>/<video> 标签的 src（http 开头才收，blob: 无法播）
                if (!url.startsWith("http")) return
                mainHandler.post { onAudioCaptured(url) }
            }
        }
    }

    /** 解析这首歌的可播直链：API 字段 → 池内歌名匹配（不做模拟点播，没有就交给挂起等待） */
    suspend fun resolvePlayUrl(song: SniffedSong): String? {
        song.playUrl?.takeIf { it.startsWith("http") }?.let { return it }
        // 池里只认"歌名匹配"的直链——池里可能混着别的歌/误捕获的链接，不能随便兜底
        audioStreamUrls.firstOrNull { audioUrlMatchesSong(song, it) }?.let { return it }
        return null
    }

    /** 挂起等待用户在网页里点播这首歌，捕获到歌名匹配的直链即返回；超时/取消返回 null */
    suspend fun waitCapture(song: SniffedSong, timeoutMs: Long): String? {
        val pending = PendingSave(song)
        pendingSaves.add(pending)
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.suspendCancellableCoroutine { c ->
                pending.cont = c
                c.invokeOnCancellation { pendingSaves.remove(pending) }
            }
        }
    }

    /** 收藏一首歌（含自动试听获取直链）。开始前按 key 重新取最新解析结果——播放接口回填的直链立即生效 */
    suspend fun saveSongNow(key: String, onDone: (Boolean, String?) -> Unit) {
        savingKeys.add(key)
        val song = songs.firstOrNull { "${it.name}::${it.artist}" == key } ?: run {
            savingKeys.remove(key)
            onDone(false, "歌曲已不在列表中")
            return
        }
        var url = runCatching { resolvePlayUrl(song) }.getOrNull()
        if (url == null) {
            // 挂起等待用户在网页里点一下这首歌（60 秒内捕获即自动完成）
            android.widget.Toast.makeText(
                context,
                "请在网页里点一下这首歌试听，捕获直链后自动完成收藏",
                android.widget.Toast.LENGTH_LONG
            ).show()
            url = runCatching { waitCapture(song, 60_000) }.getOrNull()
        }
        val id = withContext(Dispatchers.IO) {
            runCatching {
                Repo.saveMusic(name = song.name, artist = song.artist, coverUrl = song.cover, playUrl = url)
            }.getOrDefault(-1L)
        }
        savingKeys.remove(key)
        val ok = id != -1L
        if (ok && !savedKeys.contains(key)) savedKeys.add(key)
        val msg = when {
            !ok -> "收藏失败，请重试"
            song.playUrl?.startsWith("http") == true -> "已收藏到主页「音乐」清单"
            url != null -> "已收藏（捕获到试听直链）"
            else -> "已收藏，但未获取直链——可在网页试听后重新收藏"
        }
        onDone(ok, msg)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音乐搜索") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (parsing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        if (captured > 0) "捕获 $captured 首" else "等待网页数据…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 14.dp)
                    )
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 使用说明
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "在下方网页中搜索歌曲，点 + 收藏。若收藏提示等待直链，请在网页里点播这首歌，系统会自动完成收藏；收藏后到主页「音乐」清单点歌即播。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }

            // WebView
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.userAgentString = settings.userAgentString
                                .replace("; wv)", ")")
                            addJavascriptInterface(sniffer, "MusicSniffer")
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?, request: WebResourceRequest?
                                ): Boolean = false // 全部站内跳转留在 WebView

                                override fun shouldInterceptRequest(
                                    view: WebView, request: WebResourceRequest
                                ): WebResourceResponse? {
                                    // 用户在网页里点播放时，WebView 会真实发出音频流请求。
                                    // 这个 URL 就是当前能播的直链（WebView 已过 WAF，Cookie 全局共享），
                                    // 抓下来存池，收藏时填充——不依赖站内 API 字段格式。
                                    val u = request.url.toString()
                                    val hasRange = request.requestHeaders.keys.any {
                                        it.equals("Range", ignoreCase = true)
                                    }
                                    // 纯音频扩展名不要求 Range 头；无扩展名时须带 Range（流式播放特征）+ 播放接口特征
                                    val byExt = AUDIO_URL_REGEX.containsMatchIn(u)
                                    if (byExt || (hasRange && isAudioStreamUrl(u))) {
                                        view.post { onAudioCaptured(u) }
                                    }
                                    return null
                                }

                                override fun onPageFinished(v: WebView?, url: String?) {
                                    v?.evaluateJavascript(SNIFFER_JS, null)
                                }
                            }
                            loadUrl(MUSIC_SITE)
                        }
                    },
                    update = { webViewRef.value = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 捕获结果（原生列表）
            if (songs.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "捕获到 ${songs.size} 首歌曲",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                if (expanded) Icons.Rounded.KeyboardArrowDown
                                else Icons.Rounded.KeyboardArrowUp,
                                contentDescription = null
                            )
                        }
                        if (expanded) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                            ) {
                                items(songs) { song ->
                                    val key = "${song.name}::${song.artist}"
                                    val saved = key in savedKeys
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                song.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (song.artist.isNotBlank()) {
                                                Text(
                                                    song.artist,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        when {
                                            key in savingKeys -> CircularProgressIndicator(
                                                Modifier.size(18.dp), strokeWidth = 2.dp
                                            )
                                            saved -> Icon(
                                                Icons.Rounded.Check,
                                                contentDescription = "已收藏",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            else -> Icon(
                                                Icons.Rounded.Add,
                                                contentDescription = "收藏",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(22.dp).clickable {
                                                    if (!saved) {
                                                        // 点收藏即自动获取直链（API 已有则直接用；没有则自动点播试听）
                                                        scope.launch {
                                                            saveSongNow(key) { _, msg ->
                                                                android.widget.Toast.makeText(
                                                                    context, msg, android.widget.Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        // 逐首自动获取直链 + 收藏（串行：避免同时点播多首互相干扰）
                                        songs.forEach { s ->
                                            val k = "${s.name}::${s.artist}"
                                            if (k !in savedKeys) {
                                                saveSongNow(k) { _, _ -> }
                                            }
                                        }
                                        android.widget.Toast.makeText(
                                            context, "全部收藏完成", android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("全部收藏") }
                        }
                    }
                }
            }
        }
    }
}

