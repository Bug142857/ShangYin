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

/** 音频直链识别（文件级，WebView 拦截与解析共用） */
private val AUDIO_URL_REGEX = Regex(
    """https?://[^\s"'<>\\]+?\.(?:mp3|flac|m4a|aac|wav|ogg|ape)(?:\?[^\s"'<>\\]*)?""",
    RegexOption.IGNORE_CASE
)

/** 嗅探到的单曲 */
data class SniffedSong(
    val name: String,
    val artist: String,
    val cover: String?,
    val playUrl: String?
)

/** 启发式解析 WebView 捕获的 API 响应体 */
object SnifferParser {

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
                // 通用单曲提取：对象里有歌名字段 + 任意字段值是音频直链 → 认一首
                // 播放接口返回的单对象/嵌套 data 结构千奇百怪，不挑 key 名
                parseSongObj(node)?.let { song ->
                    if (song.playUrl != null) out.putIfAbsent(keyOf(song), song)
                }
                node.keys().forEach { k -> walk(node.opt(k), out) }
            }
            is JSONArray -> {
                // 对象数组：>=2 直接按歌曲列表解析；长度1（单曲播放接口）要求带直链才收，避免误判
                if (node.length() >= 2) {
                    val parsed = parseSongArray(node)
                    parsed.forEach { out.putIfAbsent(keyOf(it), it) }
                } else if (node.length() == 1) {
                    parseSongArray(node).firstOrNull { it.playUrl != null }?.let {
                        out.putIfAbsent(keyOf(it), it)
                    }
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
            val song = parseSongObj(o) ?: return emptyList()   // 必须都带名称字段
            songs.add(song)
        }
        return songs
    }

    /** 单曲解析：名称字段（必须）+ 任意 string 字段值是音频直链（不限 key 名） */
    private fun parseSongObj(o: JSONObject): SniffedSong? {
        val name = firstStr(o, "name", "title", "songName", "musicName", "song_name") ?: return null
        if (name.isBlank() || name.length > 120) return null
        val artist = firstStr(o, "singer", "artist", "author", "userName")
            ?: optArrJoin(o, "singers", "artists")
            ?: ""
        val cover = firstStr(o, "pic", "cover", "img", "picture", "albumpic", "picUrl", "coverImg")
        val playUrl = findAudioInFields(o)
        return SniffedSong(name, artist, cover, playUrl)
    }

    /** 遍历对象所有 string 字段取第一个音频直链（接口的直链字段名五花八门，干脆不挑） */
    private fun findAudioInFields(o: JSONObject): String? {
        val keys = o.keys()
        while (keys.hasNext()) {
            val v = o.opt(keys.next())
            if (v is String && isAudioStreamUrl(v)) return v.trim()
        }
        return null
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

    /** 兜底：从任意文本里抓音频直链 */
    fun extractAudioUrls(body: String): List<String> =
        AUDIO_URL_REGEX.findAll(body).map { it.value }.distinct().toList()
}

/** 音频流判定：音频扩展名，或带播放接口特征（stream/play/audio/media/type=mp3 等）且不是网页 */
internal fun isAudioStreamUrl(u: String): Boolean {
    if (AUDIO_URL_REGEX.containsMatchIn(u)) return true
    val lower = u.lowercase()
    if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".shtml")) return false
    return listOf(
        "stream", "/play?", "/play/", "play.mp3", "playaudio", "audio", "media",
        "type=mp3", "type=flac", "format=mp3", "format=flac", ".mp3?", ".flac?"
    ).any { it in lower }
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
      try {
        // responseType='json'/'blob' 等时访问 responseText 会直接抛异常，必须按类型取
        var rt = this.responseType;
        var body = (!rt || rt === 'text') ? this.responseText
                 : (rt === 'json' && this.response ? JSON.stringify(this.response) : null);
        if (body) send(this.__sniffUrl, body);
      } catch(e) {}
    });
    return xs.apply(this, arguments);
  };
  // 定时扫描播放器 <audio>/<video> 标签的 src，直接回传真实音频地址（兜底 fetch/XHR hook 覆盖不到的场景）
  setInterval(function(){
    try {
      var els = document.querySelectorAll('audio,video');
      for (var i = 0; i < els.length; i++) {
        var s = els[i].currentSrc || els[i].src;
        if (s && s.indexOf('http') === 0) {
          window.MusicSniffer && window.MusicSniffer.onAudio(String(s));
        }
      }
    } catch(e) {}
  }, 1500);
  // 收藏时自动试听：在网页里找到文本匹配歌名的最小元素并模拟点击，触发真实播放以捕获直链
  window.__playByName = function(name, artist){
    try {
      var norm = function(s){ return String(s || '').toLowerCase().replace(/\s+/g, ''); };
      var kw = norm(name), art = norm(artist);
      var best = null, bestLen = 999;
      var cands = document.querySelectorAll('a,div,span,li,p,button,td,tr,h3,h4,h5,em,strong,b,label');
      for (var i = 0; i < cands.length; i++) {
        var el = cands[i];
        var t = norm(el.innerText || el.textContent || '');
        if (!t || t.length > 120) continue;
        var ok = kw && t.indexOf(kw) >= 0;
        if (!ok && art && kw && t.indexOf(art) >= 0) {
          // 文本只含歌手时，要求长度接近"歌手+歌名"组合，避免点开歌手主页
          ok = t.length <= kw.length + art.length + 8;
        }
        if (ok && t.length < bestLen) { best = el; bestLen = t.length; }
      }
      if (best) { best.click(); return '1'; }
      return '0';
    } catch(e) { return 'e'; }
  };
})();
"""

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
                    // 同一首歌再次出现时用新直链覆盖（网页里重新播放会拿到新链接，旧的可能已过期）
                    parsed.forEach { p ->
                        val idx = songs.indexOfFirst { it.name == p.name && it.artist == p.artist }
                        if (idx >= 0 && !p.playUrl.isNullOrBlank() && p.playUrl != songs[idx].playUrl) {
                            songs[idx] = songs[idx].copy(playUrl = p.playUrl)
                        }
                    }
                }
                parsing = false
            }

            @JavascriptInterface
            fun onAudio(url: String) {
                // 网页播放器 <audio>/<video> 标签的 src（http 开头才收，blob: 无法播）
                if (!url.startsWith("http")) return
                mainHandler.post {
                    audioStreamUrls.removeAll { it == url }
                    audioStreamUrls.add(0, url)
                    if (audioStreamUrls.size > 20) audioStreamUrls.removeAt(audioStreamUrls.size - 1)
                }
            }
        }
    }

    /** 解析这首歌的可播直链：API 字段 → 池内歌名匹配 → 自动试听（让网页点播，最多两轮） */
    suspend fun resolvePlayUrl(song: SniffedSong): String? {
        song.playUrl?.takeIf { it.startsWith("http") }?.let { return it }
        // 池里只认"歌名匹配"的直链——池里可能混着别的歌/误捕获的链接，不能随便兜底
        audioStreamUrls.firstOrNull { audioUrlMatchesSong(song, it) }?.let { return it }
        val web = webViewRef.value ?: return null
        val jsName = JSONObject.quote(song.name)
        val jsArtist = JSONObject.quote(song.artist)
        val js = "window.__playByName ? window.__playByName($jsName, $jsArtist) : '0'"
        // 两轮尝试：第一轮没抓到（元素没找到/点击无效）隔 1 秒再点一次
        repeat(2) {
            val before = audioStreamUrls.toSet()
            withContext(Dispatchers.Main) { web.evaluateJavascript(js, null) }
            repeat(16) { // 每轮最多等 5.6 秒
                kotlinx.coroutines.delay(350)
                val fresh = audioStreamUrls.filter { it !in before }
                if (fresh.isNotEmpty()) {
                    fresh.firstOrNull { audioUrlMatchesSong(song, it) }?.let { return it }
                    // 点播触发的新请求，即使 URL 不含歌名也基本是这首歌
                    return fresh.first()
                }
            }
            kotlinx.coroutines.delay(1000)
        }
        return null
    }

    /** 收藏一首歌（含自动试听获取直链）。开始前按 key 重新取最新解析结果——播放接口回填的直链立即生效 */
    suspend fun saveSongNow(key: String, onDone: (Boolean, String?) -> Unit) {
        savingKeys.add(key)
        val song = songs.firstOrNull { "${it.name}::${it.artist}" == key } ?: run {
            savingKeys.remove(key)
            onDone(false, "歌曲已不在列表中")
            return
        }
        val url = runCatching { resolvePlayUrl(song) }.getOrNull()
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
            url != null -> "已收藏（自动试听获取直链）"
            else -> "已收藏，但未获取直链——请在网页试听后重新收藏"
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
                    "在下方网页中搜索歌曲，点 + 收藏时会自动试听并抓取直链（需几秒）；收藏后到主页「音乐」清单点歌即播。若提示未获取直链，请在网页里手动试听那首歌后重新收藏。",
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
                                        view.post {
                                            audioStreamUrls.removeAll { it == u }
                                            audioStreamUrls.add(0, u)
                                            if (audioStreamUrls.size > 20) {
                                                audioStreamUrls.removeAt(audioStreamUrls.size - 1)
                                            }
                                        }
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

/** URL（解码、去空格、小写）包含歌名才算这首歌的直链；防止把池里别的歌/误捕获链接错配 */
private fun audioUrlMatchesSong(song: SniffedSong, url: String): Boolean {
    val name = song.name.lowercase().replace(" ", "")
    if (name.length < 2) return false
    val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    return decoded.lowercase().replace(" ", "").contains(name)
}
