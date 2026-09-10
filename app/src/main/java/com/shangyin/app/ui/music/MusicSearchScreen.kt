package com.shangyin.app.ui.music

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 音乐搜索（泡椒音源 flac.music.hi.cn）：
 *
 * 该站有 WAF（雷池）拦截一切程序化请求（HTTP 468 空响应），只能由真实浏览器访问。
 * 方案 = WebView 自适应嗅探：
 *  1. 用户在内嵌 WebView 里完成人机验证（首次），网站对 WebView 完全放行；
 *  2. 注入 JS Hook 包装 fetch/XHR，把页面自己发出的 API 响应回传给原生层；
 *  3. 原生层启发式解析出歌曲列表（歌名/歌手/封面/播放直链），用户点收藏即落库。
 * 不依赖该站的任何固定 API 路径，站内改版基本不受影响。
 */
private const val MUSIC_SITE = "https://flac.music.hi.cn/"

/** 嗅探到的单曲 */
data class SniffedSong(
    val name: String,
    val artist: String,
    val cover: String?,
    val playUrl: String?
)

/** 启发式解析 WebView 捕获的 API 响应体 */
object SnifferParser {

    private val AUDIO_URL_REGEX = Regex(
        """https?://[^\s"'<>\\]+?\.(?:mp3|flac|m4a|aac|wav|ogg|ape)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )

    fun parse(body: String): List<SniffedSong> {
        if (body.length > 3_000_000) return emptyList()
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        val root = try {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = LinkedHashMap<String, SniffedSong>()
        walk(root, out)
        return out.values.toList()
    }

    private fun keyOf(s: SniffedSong) = "${s.name}::${s.artist}"

    private fun walk(node: Any?, out: LinkedHashMap<String, SniffedSong>) {
        when (node) {
            is JSONObject -> {
                tryParseSongArray(node, out)
                node.keys().forEach { k -> walk(node.opt(k), out) }
            }
            is JSONArray -> {
                // 只有"对象数组且长度>=2"才尝试按歌曲列表解析
                if (node.length() >= 2) {
                    val parsed = parseSongArray(node)
                    parsed.forEach { out.putIfAbsent(keyOf(it), it) }
                }
                for (i in 0 until node.length()) walk(node.opt(i), out)
            }
        }
    }

    private fun tryParseSongArray(obj: JSONObject, out: LinkedHashMap<String, SniffedSong>) {
        for (key in listOf("songs", "list", "data", "result", "music", "items", "rows")) {
            val arr = obj.opt(key)
            if (arr is JSONArray && arr.length() >= 2) {
                parseSongArray(arr).forEach { out.putIfAbsent(keyOf(it), it) }
            }
        }
    }

    private fun parseSongArray(arr: JSONArray): List<SniffedSong> {
        val songs = mutableListOf<SniffedSong>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return emptyList() // 必须全是对象
            val name = firstStr(o, "name", "title", "songName", "musicName", "song_name") ?: return emptyList()
            if (name.isBlank() || name.length > 120) return emptyList()
            val artist = firstStr(o, "singer", "artist", "author", "userName")
                ?: optArrJoin(o, "singers", "artists")
                ?: ""
            val cover = firstStr(o, "pic", "cover", "img", "picture", "albumpic", "picUrl", "coverImg")
            val playUrl = firstAudioish(o, "url", "link", "src", "src_url", "play_url", "playUrl", "mp3", "file")
            songs.add(SniffedSong(name, artist, cover, playUrl))
        }
        return songs
    }

    private fun firstStr(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return null
    }

    private fun optArrJoin(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is JSONArray && v.length() > 0) {
                val names = mutableListOf<String>()
                for (i in 0 until v.length()) {
                    val el = v.opt(i)
                    val n = when (el) {
                        is String -> el
                        is JSONObject -> firstStr(el, "name", "title") ?: continue
                        else -> continue
                    }
                    if (n.isNotBlank()) names.add(n)
                }
                if (names.isNotEmpty()) return names.joinToString("/")
            }
        }
        return null
    }

    private fun firstAudioish(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.startsWith("http")) return v.trim()
        }
        return null
    }

    /** 兜底：从任意文本里抓音频直链 */
    fun extractAudioUrls(body: String): List<String> =
        AUDIO_URL_REGEX.findAll(body).map { it.value }.distinct().toList()
}

/** 注入 WebView 的嗅探脚本：包装 fetch / XHR，把响应回传原生层 */
private val SNIFFER_JS = """
(function(){
  if (window.__musicSnifferInstalled) return;
  window.__musicSnifferInstalled = true;
  var send = function(url, body){
    try {
      if (body && body.length && body.length < 3000000) {
        window.MusicSniffer && window.MusicSniffer.onApi(String(url), String(body));
      }
    } catch(e) {}
  };
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function(){
      var url = (arguments[0] && arguments[0].url) || arguments[0] || '';
      var p = origFetch.apply(this, arguments);
      try {
        p.then(function(r){
          try { r.clone().text().then(function(t){ send(url, t); }).catch(function(){}); } catch(e) {}
        }).catch(function(){});
      } catch(e) {}
      return p;
    };
  }
  var xo = XMLHttpRequest.prototype.open, xs = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u){ this.__sniffUrl = u; return xo.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(){
    this.addEventListener('load', function(){
      try { send(this.__sniffUrl, this.responseText); } catch(e) {}
    });
    return xs.apply(this, arguments);
  };
})();
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSearchScreen(nav: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val songs = remember { mutableStateListOf<SniffedSong>() }
    val seenUrls = remember { mutableStateListOf<String>() }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var captured by remember { mutableIntStateOf(0) }
    val savingKeys = remember { mutableStateListOf<String>() }
    val savedKeys = remember { mutableStateListOf<String>() }
    var parsing by remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // ---- 原生播放状态（点歌行播放） ----
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playingKey by remember { mutableStateOf<String?>(null) }
    var playTitle by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }

    fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        isPlaying = false
    }

    fun playSong(song: SniffedSong) {
        val url = song.playUrl
        if (url.isNullOrBlank()) {
            playError = "「${song.name}」暂无直链，请在上方网页中点一次播放，App 捕获后即可播"
            return
        }
        releasePlayer()
        playError = null
        preparing = true
        playingKey = "${song.name}::${song.artist}"
        playTitle = if (song.artist.isBlank()) song.name else "${song.name} - ${song.artist}"
        val mp = android.media.MediaPlayer()
        runCatching {
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                durationMs = it.duration
                it.start()
                isPlaying = true
                preparing = false
            }
            mp.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
            }
            mp.setOnErrorListener { _, what, extra ->
                playError = "播放失败（$what/$extra），直链可能已失效"
                isPlaying = false
                preparing = false
                true
            }
            mp.prepareAsync()
            player = mp
        }.onFailure {
            preparing = false
            playError = "无法播放：${it.message ?: "链接无效"}"
            runCatching { mp.release() }
        }
    }

    fun togglePlay() {
        val mp = player ?: return
        runCatching {
            if (mp.isPlaying) { mp.pause(); isPlaying = false }
            else { mp.start(); isPlaying = true }
        }
    }

    // 退出页面释放播放器
    DisposableEffect(Unit) {
        onDispose { releasePlayer() }
    }

    // 播放中刷新进度
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val mp = player
            if (mp != null) runCatching {
                positionMs = mp.currentPosition
                durationMs = mp.duration
            }
            kotlinx.coroutines.delay(500)
        }
    }

    val sniffer = remember {
        object {
            @JavascriptInterface
            fun onApi(url: String, body: String) {
                if (url in seenUrls) return
                seenUrls.add(url)
                if (seenUrls.size > 200) seenUrls.clear()
                parsing = true
                val parsed = SnifferParser.parse(body)
                if (parsed.isNotEmpty()) {
                    val fresh = parsed.filter { p -> songs.none { it.name == p.name && it.artist == p.artist } }
                    if (fresh.isNotEmpty()) {
                        songs.addAll(0, fresh)
                        captured += fresh.size
                    }
                }
                parsing = false
            }
        }
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
                    "首次使用：在下方网页中完成人机验证，然后在网页里搜索/播放歌曲，App 会自动捕获歌曲列表，点右侧 + 收藏到app。",
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
                            // mini 播放条
                            if (playingKey != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                                ) {
                                    IconButton(onClick = { togglePlay() }, modifier = Modifier.size(34.dp)) {
                                        if (preparing) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(
                                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = "播放/暂停",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            playTitle,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        LinearProgressIndicator(
                                            progress = {
                                                if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                                            },
                                            modifier = Modifier.fillMaxWidth().height(3.dp)
                                        )
                                        Text(
                                            "${fmtMs(positionMs)} / ${fmtMs(durationMs)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                            ) {
                                items(songs) { song ->
                                    val key = "${song.name}::${song.artist}"
                                    val saved = key in savedKeys
                                    val playing = key == playingKey
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable { playSong(song) }  // 点歌行 = 播放
                                            .padding(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                song.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                                                color = if (playing) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
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
                                        if (playing) {
                                            Icon(
                                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
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
                                                    if (!saved) saveSong(song, key, scope, context, savedKeys)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            TextButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        songs.forEachIndexed { idx, s ->
                                            val k = "${s.name}::${s.artist}"
                                            if (k !in savedKeys) {
                                                withContext(Dispatchers.Main) { savingKeys.add(k) }
                                                runCatching {
                                                    Repo.saveMusic(
                                                        name = s.name,
                                                        artist = s.artist,
                                                        coverUrl = s.cover,
                                                        playUrl = s.playUrl
                                                    )
                                                }
                                                withContext(Dispatchers.Main) {
                                                    savingKeys.remove(k)
                                                    if (!savedKeys.contains(k)) savedKeys.add(k)
                                                }
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(
                                                context, "全部收藏完成", android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
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

private fun saveSong(
    song: SniffedSong,
    key: String,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    savedKeys: MutableList<String>
) {
    scope.launch(Dispatchers.IO) {
        val id = runCatching {
            Repo.saveMusic(
                name = song.name,
                artist = song.artist,
                coverUrl = song.cover,
                playUrl = song.playUrl
            )
        }.getOrDefault(-1L)
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            if (id != -1L) {
                if (!savedKeys.contains(key)) savedKeys.add(key)
                android.widget.Toast.makeText(
                    context, "已收藏到主页「音乐」清单", android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                android.widget.Toast.makeText(context, "收藏失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun fmtMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    return String.format(java.util.Locale.getDefault(), "%d:%02d", ms / 60000, (ms % 60000) / 1000)
}
