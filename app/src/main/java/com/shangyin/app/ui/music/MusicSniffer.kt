package com.shangyin.app.ui.music

import org.json.JSONArray
import org.json.JSONObject

/**
 * 音乐嗅探公共模块（搜索页 WebView 与离屏直链刷新器共用）。
 *
 * 泡椒音源（flac.music.hi.cn）有 WAF（雷池）拦截一切程序化请求（HTTP 468 空响应），
 * 只能由真实浏览器访问。方案 = WebView 自适应嗅探：
 *  1. WebView 完成人机验证（首次，Cookie 系统级全局共享，之后直接放行）；
 *  2. 注入 JS Hook 包装 fetch/XHR，把页面自己发出的 API 响应回传原生层；
 *  3. 启发式解析出歌曲（歌名/歌手/封面/播放直链），不依赖任何固定 API 路径。
 */
const val MUSIC_SITE = "https://flac.music.hi.cn/"

/** 音频直链识别（WebView 拦截与解析共用） */
val AUDIO_URL_REGEX = Regex(
    """https?://[^\s"'<>\\]+?\.(?:mp3|flac|m4a|aac|wav|ogg|ape)(?:\?[^\s"'<>\\]*)?""",
    RegexOption.IGNORE_CASE
)

/** 嗅探到的单曲 */
data class SniffedSong(
    val name: String,
    val artist: String,
    val cover: String?,
    val playUrl: String?,
    val lyrics: String? = null
)

/** LRC 时间轴标签特征 */
val LRC_REGEX = Regex("""\[\d{1,2}:\d{2}""")

/** 一行歌词 */
data class LyricLine(val timeMs: Long, val text: String)

/** 解析 LRC 歌词 → (时间, 文本) 列表；纯文本歌词（无时间轴）时间记 -1，仅顺序显示 */
fun parseLrc(lrc: String): List<LyricLine> {
    if (lrc.isBlank()) return emptyList()
    val tag = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    val out = mutableListOf<LyricLine>()
    lrc.lines().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEach
        val times = tag.findAll(line).toList()
        val text = line.replace(tag, "").trim()
        if (text.isEmpty() && times.isEmpty()) return@forEach
        if (times.isEmpty()) {
            // 无时间标签的文本行（或元信息标签行）
            if (!line.startsWith("[") && text.isNotEmpty()) out.add(LyricLine(-1, text))
        } else {
            times.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                out.add(LyricLine(min * 60_000 + sec * 1000 + frac, text))
            }
        }
    }
    return out.sortedBy { if (it.timeMs < 0) Long.MAX_VALUE else it.timeMs }
}

/** 歌词会话缓存（清单页播放时避免重复抓取） */
object MusicLyricsCache {
    val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun key(name: String, artist: String) = "$name::$artist"
}

/**
 * 歌词抓取器：公开歌词源（网易云公共接口，OkHttp 直连，1-2 秒）。
 * 搜索歌曲 → 按歌名+歌手匹配 → 拉 LRC。与音源无关，只要歌能匹配上就行。
 */
object MusicLyricsFetcher {
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    suspend fun fetch(name: String, artist: String): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // 1) 搜索歌曲（关键词=歌名+歌手）
                val kw = if (artist.isBlank()) name else "$name $artist"
                val form = okhttp3.FormBody.Builder()
                    .add("s", kw).add("type", "1").add("limit", "8").add("offset", "0")
                    .build()
                val searchReq = okhttp3.Request.Builder()
                    .url("https://music.163.com/api/search/get/web")
                    .header("Referer", "https://music.163.com")
                    .header("User-Agent", UA)
                    .post(form).build()
                val searchJson = client.newCall(searchReq).execute().use { r ->
                    r.body?.string() ?: return@runCatching null
                }
                val songs = JSONObject(searchJson)
                    .optJSONObject("result")?.optJSONArray("songs")
                    ?: return@runCatching null

                // 2) 匹配：剥离歌名尾部后缀（"(Live)"、"（周深版）"等）后比较，
                //    候选按优先级收集：歌名+歌手都对 > 仅歌名对 > 歌名互相包含
                fun norm(s: String) = s.replace(" ", "").lowercase()
                fun stripSuffix(s: String) = s
                    .replace(Regex("""\s*[(（][^)）]*[)）]\s*$"""), "")
                    .replace(Regex("""\s*[-–]\s*(live|cover|翻唱).*$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                val withArtist = mutableListOf<Long>()
                val nameOnly = mutableListOf<Long>()
                val partial = mutableListOf<Long>()
                for (i in 0 until songs.length()) {
                    val so = songs.optJSONObject(i) ?: continue
                    val id = so.optLong("id", -1)
                    if (id <= 0) continue
                    val sname = so.optString("name")
                    val arr = so.optJSONArray("artists")
                    val sartists = (0 until (arr?.length() ?: 0))
                        .mapNotNull { arr?.optJSONObject(it)?.optString("name") }
                        .joinToString("/")
                    val artistOk = artist.isBlank() || sartists.contains(artist, ignoreCase = true)
                    when {
                        norm(stripSuffix(sname)) == norm(name) ->
                            (if (artistOk) withArtist else nameOnly).add(id)
                        norm(sname).isNotEmpty() &&
                            (norm(sname).contains(norm(name)) || norm(name).contains(norm(sname))) ->
                            partial.add(id)
                    }
                }
                val candidates = (withArtist + nameOnly + partial).take(3)
                if (candidates.isEmpty()) return@runCatching null

                // 3) 按优先级逐个拉歌词（匹配版本歌词可能为空，如 VOCALOID 原版），
                //    拿到非空 LRC 即返回
                for (cid in candidates) {
                    val lyricReq = okhttp3.Request.Builder()
                        .url("https://music.163.com/api/song/lyric?id=$cid&lv=1&tv=-1")
                        .header("Referer", "https://music.163.com")
                        .header("User-Agent", UA)
                        .get().build()
                    val lyric = runCatching {
                        client.newCall(lyricReq).execute().use { r ->
                            val json = JSONObject(r.body?.string() ?: return@runCatching null)
                            json.optJSONObject("lrc")?.optString("lyric")?.trim().orEmpty()
                        }
                    }.getOrNull().orEmpty()
                    if (lyric.length > 20) return@runCatching lyric
                }
                null
            }.getOrNull()
        }
}

/** 音频流判定：音频扩展名，或带播放接口特征（stream/play/audio/media/type=mp3 等）且不是网页 */
fun isAudioStreamUrl(u: String): Boolean {
    if (AUDIO_URL_REGEX.containsMatchIn(u)) return true
    val lower = u.lowercase()
    if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".shtml")) return false
    return listOf(
        "stream", "/play?", "/play/", "play.mp3", "playaudio", "audio", "media",
        "type=mp3", "type=flac", "format=mp3", "format=flac", ".mp3?", ".flac?"
    ).any { it in lower }
}

/** URL（解码、去空格、小写）包含歌名才算这首歌的直链；防止把池里别的歌/误捕获链接错配 */
fun audioUrlMatchesSong(song: SniffedSong, url: String): Boolean {
    val name = song.name.lowercase().replace(" ", "")
    if (name.length < 2) return false
    val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
    return decoded.lowercase().replace(" ", "").contains(name)
}

/** 启发式解析 WebView 捕获的 API 响应体 */
object SnifferParser {

    fun parse(body: String): List<SniffedSong> {
        if (body.length > 3_000_000) return emptyList()
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        val root = try {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = LinkedHashMap<String, SniffedSong>()
        walk(root, out)
        return out.values.toList()
    }

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

    /** 单曲解析：名称字段（必须）+ 任意 string 字段值是音频直链（不限 key 名）+ 歌词字段（可选） */
    private fun parseSongObj(o: JSONObject): SniffedSong? {
        val name = firstStr(o, "name", "title", "songName", "musicName", "song_name") ?: return null
        if (name.isBlank() || name.length > 120) return null
        val artist = firstStr(o, "singer", "artist", "author", "userName")
            ?: optArrJoin(o, "singers", "artists")
            ?: ""
        val cover = firstStr(o, "pic", "cover", "img", "picture", "albumpic", "picUrl", "coverImg")
        val playUrl = findAudioInFields(o)
        val lyrics = findLyricsInFields(o)
        return SniffedSong(name, artist, cover, playUrl, lyrics)
    }

    /** 遍历 string 字段找歌词：内容含 LRC 时间轴（或超长多行文本）视为歌词 */
    private fun findLyricsInFields(o: JSONObject): String? {
        val keys = o.keys()
        while (keys.hasNext()) {
            val v = o.opt(keys.next())
            if (v is String && v.length > 20) {
                if (LRC_REGEX.containsMatchIn(v)) return v.trim()
            }
        }
        return null
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

    fun keyOf(s: SniffedSong): String = "${s.name}::${s.artist}"
}

/**
 * 站内 API 学习器：用户正常使用搜索页时，把"返回多首歌曲"的接口（搜索/列表类）
 * 和"返回带直链单曲"的接口（播放/详情类）的完整 URL 记到本地。
 * 直链刷新器重放这些 URL（把关键词参数替换成目标歌名），在页面上下文里直接
 * fetch 拿数据——完全不依赖 DOM 搜索框和播放按钮，是直链刷新最可靠的路径。
 */
object MusicApiLearn {
    private const val SP = "music_api_learn"
    private const val KEY_SEARCH = "search_urls"
    private const val KEY_PLAY = "play_urls"
    private const val MAX = 8

    private fun sp() = com.shangyin.app.App.instance
        .getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)

    /** 模板签名：路径 + 参数名集合（关键词每次不同，按签名去重） */
    private fun signature(url: String): String = runCatching {
        val u = java.net.URL(url)
        val names = u.query?.split('&')
            ?.mapNotNull { p -> p.substringBefore('=').takeIf { it.isNotBlank() } }
            ?.sorted().orEmpty()
        u.path + "?" + names.joinToString(",")
    }.getOrDefault(url)

    private fun read(key: String): List<String> = runCatching {
        val a = JSONArray(sp().getString(key, "[]"))
        (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.startsWith("http") } }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun put(key: String, url: String) {
        val sig = signature(url)
        val cur = read(key).toMutableList()
        cur.removeAll { signature(it) == sig }
        cur.add(0, url)
        while (cur.size > MAX) cur.removeAt(cur.size - 1)
        sp().edit().putString(key, JSONArray(cur).toString()).apply()
    }

    /** 记录一次 API 命中：多首 → 搜索类；单曲带直链 → 播放类 */
    @Synchronized
    fun record(url: String, parsed: List<SniffedSong>) {
        if (!url.startsWith("http") || parsed.isEmpty()) return
        if (parsed.size >= 2) {
            put(KEY_SEARCH, url)
        } else if (parsed.any { !it.playUrl.isNullOrBlank() }) {
            put(KEY_PLAY, url)
        }
    }

    /** 搜索重放变体：模板里每个"非数字"参数值分别替换为目标歌名（另试路径末段） */
    fun searchVariants(kw: String): List<String> {
        val out = LinkedHashSet<String>()
        val enc = runCatching { java.net.URLEncoder.encode(kw, "UTF-8") }.getOrDefault(kw)
        for (tpl in read(KEY_SEARCH)) {
            val qIdx = tpl.indexOf('?')
            val base = if (qIdx >= 0) tpl.substring(0, qIdx) else tpl
            val query = if (qIdx >= 0) tpl.substring(qIdx + 1) else ""
            val pairs = if (query.isEmpty()) emptyList() else query.split('&')
            pairs.forEachIndexed { i, p ->
                val eq = p.indexOf('=')
                if (eq <= 0) return@forEachIndexed
                val raw = p.substring(eq + 1)
                val v = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                // 纯数字/超长值是分页、签名类参数，不是关键词
                if (v.isEmpty() || v.length > 64 || v.toDoubleOrNull() != null) return@forEachIndexed
                val nl = pairs.toMutableList()
                    .also { it[i] = p.substring(0, eq + 1) + enc }.joinToString("&")
                out.add("$base?$nl")
            }
            // 路径末段替换（/search/关键词、/so/关键词）
            val segs = base.trimEnd('/').split('/')
            val last = segs.lastOrNull() ?: ""
            if (segs.size > 3 && last.isNotEmpty() && !last.contains('.') && last.toDoubleOrNull() == null) {
                val nb = segs.dropLast(1).joinToString("/") + "/" + enc
                out.add(if (query.isEmpty()) nb else "$nb?$query")
            }
        }
        return out.take(6).toList()
    }

    /** 播放重放变体：把模板中 id/歌名类参数替换为搜索结果里目标歌的候选值 */
    fun playVariants(values: List<String>): List<String> {
        if (values.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (tpl in read(KEY_PLAY)) {
            val qIdx = tpl.indexOf('?')
            if (qIdx <= 0) continue
            val base = tpl.substring(0, qIdx)
            val pairs = tpl.substring(qIdx + 1).split('&')
            // 可替换参数：参数名像主键；没有像的就用第一个带值参数
            val idxs = pairs.indices.filter { i ->
                val p = pairs[i]
                p.contains('=') && listOf("id", "hash", "mid", "name", "kw", "q", "key", "song", "title")
                    .any { p.substringBefore('=').lowercase().contains(it) }
            }.ifEmpty { pairs.indices.filter { pairs[it].contains('=') }.take(1) }
            for (v in values.take(4)) {
                val enc = runCatching { java.net.URLEncoder.encode(v, "UTF-8") }.getOrDefault(v)
                for (i in idxs) {
                    val p = pairs[i]
                    val np = p.substring(0, p.indexOf('=') + 1) + enc
                    out.add("$base?" + pairs.toMutableList().also { it[i] = np }.joinToString("&"))
                }
            }
        }
        return out.take(8).toList()
    }

    /** 从搜索响应 JSON 里找目标歌的条目，提取可作播放接口参数的候选值（id/hash/歌名等） */
    fun extractCandidates(body: String, songName: String): List<String> {
        if (body.length > 3_000_000) return emptyList()
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        val root = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        val norm = songName.replace(" ", "").lowercase()
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val nm = firstStrField(node) ?: ""
                    if (nm.replace(" ", "").lowercase().contains(norm) && norm.length >= 2) {
                        node.keys().forEach { k ->
                            val lk = k.lowercase()
                            if (listOf("id", "hash", "mid").any { lk.contains(it) }) {
                                val v = node.opt(k)
                                val s = when (v) {
                                    is String -> v
                                    is Number -> v.toString()
                                    else -> null
                                }
                                if (!s.isNullOrBlank() && s.length <= 64) out.add(s)
                            }
                        }
                    }
                    node.keys().forEach { k -> walk(node.opt(k)) }
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        return out.toList().take(5)
    }

    private fun firstStrField(o: JSONObject): String? {
        for (k in listOf("name", "title", "songName", "musicName", "song_name")) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return null
    }
}

/** 注入 WebView 的嗅探脚本：包装 fetch / XHR + 音频标签扫描 + 按歌名自动点播（mode=1 点元素 / mode=2 点行内播放按钮） */
val SNIFFER_JS = """
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

  // 重放学到的站内接口：在页面上下文里直接 fetch（带 Cookie，能过 WAF），
  // 响应回传原生走通用解析——不依赖页面 DOM，是直链刷新最可靠的路径
  window.__replayFetch = function(urls){
    try {
      (urls || []).forEach(function(u){
        try {
          fetch(u, { credentials: 'include' }).then(function(r){
            try {
              r.text().then(function(t){
                if (t && t.length < 3000000) window.MusicSniffer && window.MusicSniffer.onApi(u, t);
              }).catch(function(){});
            } catch(e) {}
          }).catch(function(){});
        } catch(e) {}
      });
      return '1';
    } catch(e) { return 'e'; }
  };

  // 自动点播：按歌名找元素模拟点击（mode=1 直接点 / mode=2 点所在行的播放按钮）
  // 支持 iframe（同源）；补发完整指针事件链，兼容事件委托式播放器
  window.__playByName = function(name, artist, mode){
    try {
      var norm = function(s){ return String(s || '').toLowerCase().replace(/\s+/g, ''); };
      var kw = norm(name), art = norm(artist);
      var docs = [document];
      try {
        var fr = document.querySelectorAll('iframe');
        for (var f = 0; f < fr.length; f++) {
          try { if (fr[f].contentDocument) docs.push(fr[f].contentDocument); } catch(e) {}
        }
      } catch(e) {}
      for (var d = 0; d < docs.length; d++) {
        var doc = docs[d];
        try {
          var best = null, bestLen = 999;
          var cands = doc.querySelectorAll('a,div,span,li,p,button,td,tr,h3,h4,h5,em,strong,b,label');
          for (var i = 0; i < cands.length; i++) {
            var el = cands[i];
            var t = norm(el.innerText || el.textContent || '');
            if (!t || t.length > 120) continue;
            var ok = kw && t.indexOf(kw) >= 0;
            if (!ok && art && kw && t.indexOf(art) >= 0) {
              ok = t.length <= kw.length + art.length + 8;
            }
            if (ok && t.length < bestLen) { best = el; bestLen = t.length; }
          }
          if (best) {
            // mode=2：在歌名元素的行容器里找真正的播放按钮
            var target = best;
            if (mode == 2) {
              var p = best;
              for (var up = 0; up < 3 && p; up++) {
                var pb = p.querySelector('button,[class*="play"],i[class*="play"],[onclick],[data-url]');
                if (pb) { target = pb; break; }
                p = p.parentElement;
              }
            }
            var r = target.getBoundingClientRect();
            var x = r.left + r.width / 2, y = r.top + r.height / 2;
            // 补发完整事件链：部分播放器监听 pointerdown/mousedown，仅 .click() 不够
            ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(tn) {
              try {
                var Ev = (window.PointerEvent && tn.indexOf('pointer') === 0) ? PointerEvent : MouseEvent;
                target.dispatchEvent(new Ev(tn, { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y }));
              } catch(e) {}
            });
            try { target.click(); } catch(e) {}
            return '1';
          }
        } catch(e) {}
      }
      return '0';
    } catch(e) { return 'e'; }
  };

  // 自动搜索：把关键词填进页面搜索框并触发搜索（MPA 跳转或 SPA 原地渲染均可）
  window.__searchPlay = function(kw){
    try {
      var vis = function(el){ return el.offsetWidth > 0 && el.offsetHeight > 0; };
      var inputs = document.querySelectorAll('input, textarea');
      var box = null;
      // 优先：类型或属性带搜索特征的可见输入框
      for (var i = 0; i < inputs.length; i++) {
        var el = inputs[i];
        var typ = (el.type || '').toLowerCase();
        var sig = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.className || '') + ' ' + (el.placeholder || '')).toLowerCase();
        if (vis(el) && (typ === 'search' || /search|keyword|kw|\bq\b/.test(sig))) { box = el; break; }
      }
      // 兜底：任何可见文本输入框
      if (!box) for (var j = 0; j < inputs.length; j++) {
        var t2 = (inputs[j].type || 'text').toLowerCase();
        if (vis(inputs[j]) && (t2 === 'text' || t2 === '')) { box = inputs[j]; break; }
      }
      if (!box) return 'noinput';
      box.focus();
      var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value') ||
                   Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value');
      if (setter && setter.set) setter.set.call(box, kw); else box.value = kw;
      box.dispatchEvent(new Event('input', { bubbles: true }));
      box.dispatchEvent(new Event('change', { bubbles: true }));
      ['keydown', 'keypress', 'keyup'].forEach(function(tn) {
        try {
          box.dispatchEvent(new KeyboardEvent(tn, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
        } catch(e) {}
      });
      // Enter 不生效的站点很多：补点搜索按钮（form 的提交按钮 / 输入框附近的按钮或放大镜图标）
      var fire = function(el){
        if (!el) return;
        var r = el.getBoundingClientRect();
        var x = r.left + r.width / 2, y = r.top + r.height / 2;
        ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(tn) {
          try {
            var Ev = (window.PointerEvent && tn.indexOf('pointer') === 0) ? PointerEvent : MouseEvent;
            el.dispatchEvent(new Ev(tn, { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y }));
          } catch(e) {}
        });
        try { el.click(); } catch(e) {}
      };
      var form = box.closest ? box.closest('form') : null;
      if (form) {
        var sb = form.querySelector('button[type="submit"], button, input[type="submit"], [role="button"]');
        fire(sb);
        try { form.submit(); } catch(e) {}
      } else {
        // 无 form：在输入框的父级链上找带 search 特征的可见按钮/图标
        var root = box.parentElement, btn = null;
        for (var k = 0; k < 3 && root && !btn; k++) {
          var bts = root.querySelectorAll('button, [role="button"], [class*="search"], [id*="search"], svg');
          for (var b = 0; b < bts.length; b++) {
            var be = bts[b];
            if (!vis(be) || be === box) continue;
            var sig2 = ((be.id || '') + ' ' + (be.className && be.className.baseVal !== undefined ? be.className.baseVal : be.className || '')).toLowerCase();
            if (/search|submit|icon|btn|button/.test(sig2) || be.tagName === 'BUTTON') { btn = be; break; }
          }
          root = root.parentElement;
        }
        fire(btn);
      }
      return 'typed';
    } catch(e) { return 'e'; }
  };

  // 定时扫描网页播放器的 <audio>/<video> 标签，把真实 src 回传原生（含 blob: 的跳过）
  setInterval(function(){
    try {
      var medias = document.querySelectorAll('audio,video');
      for (var i = 0; i < medias.length; i++) {
        var s = medias[i].src || medias[i].currentSrc;
        if (s && String(s).indexOf('http') === 0 && window.MusicSniffer) {
          window.MusicSniffer.onAudio(String(s));
        }
      }
    } catch(e) {}
  }, 1500);
})();
"""
