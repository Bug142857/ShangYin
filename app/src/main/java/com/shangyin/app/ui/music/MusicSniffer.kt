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
    val playUrl: String?
)

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

    fun keyOf(s: SniffedSong): String = "${s.name}::${s.artist}"
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
