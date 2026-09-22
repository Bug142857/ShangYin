package com.shangyin.app.data.live

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * 直播用的常驻隐藏 WebView 通道（与 ZlibWeb 同一套思路）。
 *
 * 为什么需要它：**斗鱼的播放地址只有页面自己的 JS 能拿到** ——
 * PC/移动接口 `/lapi/live/getH5PlayV1` 要求页面运行时生成的 `enc_data` 签名
 * （无签名一律 `{"error":-9,"msg":"时间戳错误"}`，实测），而页面自己请求成功后会把
 * 完整可播放的 FLV 直链写进 DOM（`<link rel="preload" as="fetch" href="...flv?...">`，实测）。
 * 所以这里不逆向签名，只在隐藏 WebView 里打开页面、被动接住页面自己拿到的地址/响应。
 *
 * 注意：WebView 必须由 LiveWebHost（挂 AppNav 根节点）attach 后才能用；
 * 未 attach 时所有方法返回 null，调用方要如实提示"直播通道未就绪"。
 */
object LiveWeb {

    /** 与站点脚本一致的浏览器 UA（实测手机 UA 会让虎牙/斗鱼返回另一套页面） */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile
    private var view: WebView? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 页面启动时注入的钩子：把 fetch/XHR 里流相关的地址与响应体记到 window.__syLiveHit */
    private const val HOOK_JS = """
(function(){
  if (window.__syLiveHook) return;
  window.__syLiveHook = 1;
  window.__syLiveHit = '';
  function save(u, t) {
    try {
      u = String(u || '');
      if (!/\.flv\?|\.m3u8\?|getH5Play|ratestream/.test(u)) return;
      window.__syLiveHit = u + '\n@@BODY@@' + (t || '');
    } catch (e) {}
  }
  try {
    var of = window.fetch;
    if (of) {
      window.fetch = function () {
        var a = arguments;
        var u = (a[0] && a[0].url) || a[0];
        var p = of.apply(this, a);
        try {
          p.then(function (r) {
            try { r.clone().text().then(function (t) { save(u, t); }); } catch (e) {}
          });
        } catch (e) {}
        return p;
      };
    }
    var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) { try { this.__u = u; } catch (e) {} return oo.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function () {
      var self = this;
      try { self.addEventListener('load', function () { try { save(self.__u, self.responseText); } catch (e) {} }); } catch (e) {}
      return os.apply(this, arguments);
    };
  } catch (e) {}
})()
"""

    fun attach(v: WebView) {
        view = v
        runCatching {
            v.settings.javaScriptEnabled = true
            v.settings.domStorageEnabled = true
            v.settings.cacheMode = WebSettings.LOAD_DEFAULT
            v.settings.userAgentString = UA
            v.settings.loadsImagesAutomatically = false // 不需要图，省流量
            CookieManager.getInstance().setAcceptCookie(true)
        }
        v.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                // 尽早注入钩子：页面的播放请求是加载完成后才发的
                runCatching { v?.evaluateJavascript(HOOK_JS, null) }
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                runCatching { v?.evaluateJavascript(HOOK_JS, null) }
            }
        }
    }

    fun detach(v: WebView) {
        if (view === v) view = null
    }

    val isReady: Boolean get() = view != null

    /**
     * 在隐藏 WebView 里打开 [url]，然后每 [intervalMs] 执行一次 [jsExpr]，
     * 直到返回非空字符串（返回值原样返回）或超时（null）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun pollJs(
        url: String,
        jsExpr: String,
        timeoutMs: Long = 15_000,
        intervalMs: Long = 500
    ): String? = withContext(Dispatchers.Main) {
        val v = view ?: return@withContext null
        runCatching { v.loadUrl(url) }
        val hit = withTimeoutOrNull(timeoutMs) {
            var text: String? = null
            while (text.isNullOrBlank()) {
                text = evalValue(v, jsExpr)?.takeIf { it.isNotBlank() }
                if (text.isNullOrBlank()) delay(intervalMs)
            }
            text
        }
        // 拿到（或超时）后停止加载：斗鱼那个页面是重型 SPA，留着一直跑会白占 CPU/流量
        runCatching { v.stopLoading() }
        hit
    }

    /** 打开 [url] 并等页面加载完成，返回整页 HTML（拿不到返回 null） */
    suspend fun pageHtml(url: String, timeoutMs: Long = 20_000): String? = withContext(Dispatchers.Main) {
        val v = view ?: return@withContext null
        runCatching { v.loadUrl(url) }
        var waited = 0L
        while (waited < timeoutMs) {
            delay(300)
            waited += 300
            val done = evalValue(v, "document.readyState")?.contains("complete") == true
            if (done) break
        }
        delay(600)
        evalValue(v, "document.documentElement.outerHTML")
    }

    /** 取 JS 返回值：evaluateJavascript 给的是 JSON 字面量（形如 "abc"），解析回真实字符串 */
    private suspend fun evalValue(v: WebView, js: String): String? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                v.evaluateJavascript(js) { value ->
                    if (cont.isActive) cont.resume(unquote(value))
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }

    /** 去掉 evaluateJavascript 结果外层的 JSON 引号并还原转义（失败则原样返回） */
    private fun unquote(raw: String?): String? {
        if (raw == null) return null
        if (raw == "null") return null
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return runCatching {
                json.parseToJsonElement(raw).jsonPrimitive.content
            }.getOrElse { raw.trim('"') }
        }
        return raw
    }
}
