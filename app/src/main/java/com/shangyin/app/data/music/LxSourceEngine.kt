package com.shangyin.app.data.music

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.shangyin.app.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 音源脚本初始化结果：[support] 非空即成功（平台 key → 支持音质列表），[error] 非空即失败 */
data class LxInitResult(
    val support: Map<String, List<String>>? = null,
    val error: String? = null
)

/**
 * LX 自定义音源（洛雪音源脚本）运行时。
 *
 * 音源脚本是纯 JS，靠 `globalThis.lx` 这个宿主 API 与 App 通信，所以必须在 App 内嵌一个 JS 引擎；
 * 这里用隐藏 WebView 承载（项目里 ZlibWebHost 已有先例），脚本的 HTTP 请求全部经
 * [NativeBridge.httpRequest] 转到原生 OkHttp —— 既绕开浏览器 CORS，又能复用系统代理/DNS。
 *
 * 生命周期：一个脚本一个 [Runtime]（各自独立的全局环境，互不污染），
 * 删除/停用音源时 [release] 销毁 WebView；App 退出时 [releaseAll]。
 */
object LxSourceEngine {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reqSeq = AtomicInteger(0)

    /** 脚本 id → 运行环境（主线程创建/销毁，JavaBridge 线程也会读，故用并发容器） */
    private val runtimes = ConcurrentHashMap<String, Runtime>()

    /** 正在等待结果的 musicUrl 请求：reqId → (url, error) */
    private val pendingUrls = ConcurrentHashMap<String, CompletableDeferred<Pair<String?, String?>>>()

    /** 正在执行的原生 HTTP 请求：reqId → Call（供脚本取消） */
    private val pendingHttp = ConcurrentHashMap<String, Call>()

    /** 音源脚本自己的 cookie 会话（网易云/酷我等要带 cookie 才能过接口） */
    private val cookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 宿主页（内含 crypto-js / jsencrypt / pako 与 lx shim），只在首次用时组装一次 */
    private val hostHtml: String by lazy {
        val assets = App.instance.assets
        fun read(path: String) = assets.open(path).bufferedReader().use { it.readText() }
        val libs = buildString {
            append(read("lx/vendor/crypto-js.min.js")); append('\n')
            append(read("lx/vendor/jsencrypt.min.js")); append('\n')
            append(read("lx/vendor/pako.min.js")); append('\n')
            append(read("lx/lx_shim.js"))
        }
        // 内联进 <script>，防止库内容里出现 </script 破坏页面
        read("lx/host.html").replace("/*__LX_LIBS__*/", libs.replace("</script", "<\\/script"))
    }

    private class Runtime(val scriptId: String, val webView: WebView) {
        @Volatile var inited: CompletableDeferred<LxInitResult>? = null
        @Volatile var pageReady: CompletableDeferred<Unit>? = null
    }

    /** 初始化脚本，拿回它声明的平台与音质（失败也返回结果对象，不抛异常） */
    suspend fun initScript(script: MusicSourceScript): LxInitResult = withContext(Dispatchers.Main) {
        if (script.content.isBlank()) return@withContext LxInitResult(error = "脚本内容为空")
        val rt = runtimes[script.id] ?: createRuntime(script.id).also { runtimes[script.id] = it }
        val inited = CompletableDeferred<LxInitResult>()
        val ready = CompletableDeferred<Unit>()
        rt.inited = inited
        rt.pageReady = ready
        rt.webView.loadDataWithBaseURL("https://lx.local/", hostHtml, "text/html; charset=utf-8", "utf-8", null)

        if (withTimeoutOrNull(15_000) { ready.await() } == null) {
            rt.inited = null
            rt.pageReady = null
            return@withContext LxInitResult(error = "脚本运行环境加载超时")
        }
        val infoJson = buildJsonObject {
            put("name", script.name)
            put("description", script.description)
            put("version", script.version)
            put("author", script.author)
            put("homepage", script.homepage)
            put("rawScript", script.content)
        }.toString()
        rt.webView.evaluateJavascript(
            "__lxLoadScript(${jsLiteral(script.id)}, ${jsLiteral(script.content)}, ${jsLiteral(infoJson)}, 20000)",
            null
        )
        val result = withTimeoutOrNull(25_000) { inited.await() }
        rt.inited = null
        rt.pageReady = null
        result ?: LxInitResult(error = "脚本初始化超时（未收到 inited 事件）")
    }

    /**
     * 解析播放直链。失败抛 [MusicResolveException]，消息直接给用户看。
     * [quality] 必须是脚本声明支持的音质（调用方按 [MusicSourceScript.qualitiesOf] 选）。
     */
    suspend fun resolveUrl(
        script: MusicSourceScript,
        platform: MusicPlatform,
        quality: MusicQuality,
        song: MusicSong,
        timeoutMs: Long = 30_000
    ): String = withContext(Dispatchers.Main) {
        val rt = runtimes[script.id] ?: throw MusicResolveException("音源「${script.name}」未就绪")
        val reqId = "q" + reqSeq.incrementAndGet()
        val deferred = CompletableDeferred<Pair<String?, String?>>()
        pendingUrls[reqId] = deferred

        val payload = buildJsonObject {
            put("source", platform.key)
            put("action", "musicUrl")
            put("info", buildJsonObject {
                put("type", quality.key)
                put("musicInfo", buildMusicInfo(platform, song))
            })
        }
        rt.webView.evaluateJavascript(
            "__lxRequest(${jsLiteral(script.id)}, ${jsLiteral(reqId)}, ${payload})",
            null
        )
        val (url, error) = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: (null to "音源「${script.name}」解析超时")
        pendingUrls.remove(reqId)
        url?.takeIf { it.isNotBlank() && it.startsWith("http") }
            ?: throw MusicResolveException(error ?: "音源「${script.name}」未返回有效链接")
    }

    /** 脚本是否已在本地跑起来（用于「音源管理」页显示状态） */
    fun isReady(scriptId: String): Boolean = runtimes.containsKey(scriptId)

    /** 销毁某个脚本的运行环境（停用/删除/重新导入时调用） */
    fun release(scriptId: String) {
        mainHandler.post {
            runtimes.remove(scriptId)?.webView?.destroy()
        }
    }

    fun releaseAll() {
        mainHandler.post {
            runtimes.values.forEach { runCatching { it.webView.destroy() } }
            runtimes.clear()
        }
    }

    // ---------------- 内部实现 ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createRuntime(scriptId: String): Runtime {
        val webView = WebView(App.instance)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            // 脚本的网络请求一律走 lx.request（原生 OkHttp），页面自身不需要联网
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.isVerticalScrollBarEnabled = false
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                runtimes[scriptId]?.pageReady?.complete(Unit)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest?): Boolean = true
        }
        webView.addJavascriptInterface(NativeBridge(scriptId, webView), "LxNative")
        return Runtime(scriptId, webView)
    }

    /** 传给脚本的 musicInfo：统一字段 + 平台专属 ID 字段（脚本里字段名写死，缺了取不到 ID） */
    private fun buildMusicInfo(platform: MusicPlatform, song: MusicSong): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["source"] = JsonPrimitive(platform.key)
        fields["name"] = JsonPrimitive(song.name)
        fields["singer"] = JsonPrimitive(song.artists)
        fields["albumName"] = JsonPrimitive(song.album)
        fields["picUrl"] = JsonPrimitive(song.cover)
        song.raw.forEach { (key, value) ->
            fields[key] = if (key == "duration") {
                // duration 必须是数字（毫秒）；时长缺失时给 0，避免脚本算出 NaN
                JsonPrimitive(value.toLongOrNull() ?: 0L)
            } else {
                primitiveOf(value)
            }
        }
        if (!fields.containsKey("songmid")) fields["songmid"] = primitiveOf(song.id)
        if (!fields.containsKey("duration")) fields["duration"] = JsonPrimitive(song.durationMs)
        return JsonObject(fields)
    }

    /** 全数字的串按 JSON 数字下发（脚本里常做数值比较），其余按字符串 */
    private fun primitiveOf(value: String): JsonPrimitive {
        val num = value.toLongOrNull()
        return if (num != null && value.length <= 18) JsonPrimitive(num) else JsonPrimitive(value)
    }

    /** Kotlin 字符串 → JS 字符串字面量（JSON 字符串就是合法 JS 字面量） */
    private fun jsLiteral(text: String): String = JsonPrimitive(text).toString()

    /**
     * JS 侧调回来的入口（JavaBridge 线程调用，内部自己切线程）。
     * 注意不能用 inner class（LxSourceEngine 是 object，没有外部实例），
     * 需要访问的单例成员一律用 `LxSourceEngine.` 限定。
     */
    private class NativeBridge(private val scriptId: String, private val webView: WebView) {

        /** 脚本发起的 HTTP 请求：解析参数 → 原生发起 → 结果回传 JS */
        @JavascriptInterface
        fun httpRequest(reqId: String, payloadJson: String) {
            val payload = runCatching { LxSourceEngine.json.parseToJsonElement(payloadJson) as JsonObject }.getOrNull()
            if (payload == null) {
                LxSourceEngine.respondHttp(webView, reqId, "请求参数解析失败", null, null)
                return
            }
            val url = payload["url"]?.text().orEmpty()
            if (url.isBlank()) {
                LxSourceEngine.respondHttp(webView, reqId, "请求地址为空", null, null)
                return
            }
            val method = payload["method"]?.text()?.uppercase() ?: "GET"
            val timeoutMs = payload["timeout"]?.text()?.toLongOrNull() ?: 15_000L
            val headers = payload["headers"] as? JsonObject

            Thread {
                runCatching {
                    LxSourceEngine.executeHttp(webView, reqId, url, method, headers, payload, timeoutMs)
                }.onFailure { e ->
                    LxSourceEngine.respondHttp(webView, reqId, e.message ?: "网络请求失败", null, null)
                }
            }.start()
        }

        /** 脚本取消请求（lx.request 的返回值） */
        @JavascriptInterface
        fun httpCancel(reqId: String) {
            LxSourceEngine.pendingHttp.remove(reqId)?.cancel()
        }

        /** 脚本发送 inited / updateAlert 事件 */
        @JavascriptInterface
        fun onInited(scriptIdFromJs: String, payloadJson: String) {
            val payload = runCatching { LxSourceEngine.json.parseToJsonElement(payloadJson) as JsonObject }.getOrNull()
            val status = payload?.get("status")?.text() != "false"
            val result = if (!status) {
                LxInitResult(error = payload?.get("error")?.text() ?: "脚本初始化失败")
            } else {
                val sources = payload?.get("sources") as? JsonObject
                val support = sources?.mapNotNull { (key, value) ->
                    val obj = value as? JsonObject ?: return@mapNotNull null
                    val qualities = (obj["qualitys"] as? JsonArray)?.mapNotNull { it.text() }.orEmpty()
                    key to qualities
                }?.toMap().orEmpty()
                if (support.isEmpty()) LxInitResult(error = "脚本未声明任何可用平台")
                else LxInitResult(support = support)
            }
            LxSourceEngine.runtimes[scriptId]?.inited?.complete(result)
        }

        @JavascriptInterface
        fun onMusicUrl(reqId: String, url: String?, error: String?) {
            LxSourceEngine.pendingUrls.remove(reqId)?.complete(url to error)
        }

        @JavascriptInterface
        fun onUpdateAlert(scriptIdFromJs: String, payloadJson: String) {
            // 源更新提示：只记日志，不打断用户（音源失效时会由解析失败直接提示）
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("LxSource", "[$scriptId] $message")
        }
    }

    private fun executeHttp(
        webView: WebView,
        reqId: String,
        url: String,
        method: String,
        headers: JsonObject?,
        payload: JsonObject,
        timeoutMs: Long
    ) {
        val builder = Request.Builder().url(url)
        headers?.forEach { (k, v) -> v.text()?.let { builder.header(k, it) } }
        val body: RequestBody? = when {
            payload.containsKey("form") -> {
                val form = FormBody.Builder()
                (payload["form"] as? JsonObject)?.forEach { (k, v) -> form.add(k, v.text().orEmpty()) }
                form.build()
            }
            payload.containsKey("formData") -> {
                val multi = MultipartBody.Builder().setType(MultipartBody.FORM)
                (payload["formData"] as? JsonArray)?.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val key = obj["key"]?.text().orEmpty()
                    val value = obj["value"]?.text().orEmpty()
                    val fileName = obj["fileName"]?.text()
                    val contentType = obj["contentType"]?.text()
                    if (fileName != null) {
                        multi.addFormDataPart(key, fileName, value.toRequestBody(contentType?.toMediaType()))
                    } else {
                        multi.addFormDataPart(key, value)
                    }
                }
                multi.build()
            }
            payload.containsKey("bodyB64") -> {
                val bytes = android.util.Base64.decode(
                    payload["bodyB64"]?.text().orEmpty(),
                    android.util.Base64.DEFAULT
                )
                bytes.toRequestBody(null)
            }
            payload.containsKey("body") -> {
                val text = payload["body"]?.text().orEmpty()
                val type = headers?.get("Content-Type")?.text() ?: "application/json; charset=utf-8"
                text.toRequestBody(type.toMediaType())
            }
            else -> null
        }
        if (method == "GET" || method == "HEAD") {
            builder.method(method, null)
        } else {
            builder.method(method, body ?: ByteArray(0).toRequestBody(null))
        }

        val client = httpClient.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val call = client.newCall(builder.build())
        pendingHttp[reqId] = call
        val response: Response = try {
            call.execute()
        } finally {
            pendingHttp.remove(reqId)
        }
        response.use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val respJson = buildJsonObject {
                put("statusCode", resp.code)
                put("statusMessage", resp.message)
                put("headers", buildJsonObject {
                    resp.headers.names().forEach { name ->
                        put(name, resp.headers.values(name).joinToString(", "))
                    }
                })
                put("charset", charsetOf(resp))
            }.toString()
            respondHttp(webView, reqId, null, respJson, bytes)
        }
    }

    private fun charsetOf(resp: Response): String {
        val contentType = resp.body?.contentType()?.toString().orEmpty()
        val match = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)
        return match?.groupValues?.getOrNull(1).orEmpty().ifBlank { "utf-8" }
    }

    /** HTTP 结果回传 JS（evaluateJavascript 必须在主线程） */
    private fun respondHttp(webView: WebView?, reqId: String, error: String?, respJson: String?, bytes: ByteArray?) {
        val bodyB64 = bytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }.orEmpty()
        val script = "window.__lxHttpResponse && window.__lxHttpResponse(" +
            "${jsLiteral(reqId)}, ${error?.let { jsLiteral(it) } ?: "null"}, " +
            "${respJson?.let { jsLiteral(it) } ?: "null"}, ${jsLiteral(bodyB64)});"
        mainHandler.post { runCatching { webView?.evaluateJavascript(script, null) } }
    }
}

/** [JsonElement] 安全取字符串（缺字段 / JsonNull / 非原始类型都返回 null） */
private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
