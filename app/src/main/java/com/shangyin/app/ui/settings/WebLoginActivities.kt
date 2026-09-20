package com.shangyin.app.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import com.shangyin.app.data.wygamer.WygamerClient
import com.shangyin.app.data.zlib.ZlibClient
import com.shangyin.app.ui.theme.ShangYinTheme

/**
 * 「网站账号」类登录的统一实现：内嵌 WebView 打开站点，登录成功后抓 Cookie 存进 SettingsStore。
 *
 * 两个站点都需要真实浏览器环境：
 *  - 无忧游戏库：Zibll 登录表单带 slider 滑块验证码，无法程序化提交
 *  - Z-Library：全站 DiamWall JS 反爬（普通 HTTP 请求返回 513 挑战页）
 * 因此统一走 WebView，且 Cookie 必须与 WebView 同款 UA 一起使用才有效。
 *
 * 约定：
 *  - 返回键 = 放弃登录（不返回 RESULT_OK，设置页不会提示"登录成功"）
 *  - 候选线路可多个，主框架加载失败会自动换下一条，也可在右上角手动切换
 */

/** 一个站点的登录参数 */
private class LoginSpec(
    val title: String,
    /** WebView 打开的登录地址（多个 = 候选线路，加载失败自动换下一条） */
    val startUrls: List<String>,
    /** 抓取 Cookie 的站点地址（按域名分别取，再合并去重） */
    val cookieUrls: List<String>,
    val hint: String,
    /** 判断 cookie 字符串是否代表登录态 */
    val loginDetect: (String) -> Boolean,
    val isLoggedIn: () -> Boolean,
    val save: (String) -> Unit,
    val logout: () -> Unit,
    /** 主框架加载成功后的回调，用于记住真正可用的线路 */
    val onHostResolved: ((String) -> Unit)? = null,
    /** 打开页面前预设的 Cookie（如关掉站点自动弹窗的标记） */
    val preCookies: List<Pair<String, String>> = emptyList(),
    /** 每次页面加载完成后注入的 JS（清理遮挡层等） */
    val afterLoadJs: String? = null,
    /** 动态线路解析：打开登录页前先解析（如 Z-Library 从 getzlib.com 取每日验证地址），结果插到候选最前 */
    val dynamicUrls: (suspend () -> List<String>)? = null,
    /** 允许手动输入地址（浏览器能打开、这里打不开时用） */
    val allowUrlInput: Boolean = false,
    /** 用户手动输入地址解析出的域名 */
    val onCustomHost: ((String) -> Unit)? = null,
    /** 加载失败时附带的排查提示 */
    val failHint: String = ""
)

/**
 * 关闭站点自动弹出的遮挡层。
 * 无忧游戏库（Zibll 主题）在页面 onload 后 500ms 无条件弹出「系统公告」弹窗，
 * WebView 里只剩一层黑色遮罩盖住登录表单（验证码/登录框都点不到），故加载后强制关掉并移除。
 */
private const val CLEAR_OVERLAY_JS = """
(function(){
  function kill(){
    try{
      var m = document.getElementById('modal-system-notice');
      if (m) {
        try { if (window.jQuery && jQuery.fn && jQuery.fn.modal) jQuery(m).modal('hide'); } catch(e){}
        m.classList.remove('show');
        m.style.display = 'none';
        m.setAttribute('aria-hidden','true');
        if (m.parentNode) m.parentNode.removeChild(m);
      }
      var bs = document.querySelectorAll('.modal-backdrop');
      for (var i = 0; i < bs.length; i++) { if (bs[i].parentNode) bs[i].parentNode.removeChild(bs[i]); }
      document.body.classList.remove('modal-open');
      document.body.style.overflow = '';
      document.body.style.paddingRight = '';
    } catch(e){}
  }
  kill();
  setTimeout(kill, 400);
  setTimeout(kill, 900);
  setTimeout(kill, 1800);
  setTimeout(kill, 3200);
})()
"""

/**
 * 诊断埋点：记录「点击 → 谁接管 → 有没有发请求 → 有没有报错」。
 * 主题 JS 一旦没接管点击（或接管后请求静默失败），页面就表现为「能看、点不动」，
 * 静态快照看不出原因，必须把点击后的运行时证据留下来。
 */
private const val INSTRUMENT_JS = """
(function(){
  if (window.__syProbe) return;
  var P = { click: '(无)', ajax: [], err: [] };
  window.__syProbe = P;
  function desc(el){
    if (!el) return '(?';
    var cls = (typeof el.className === 'string' ? el.className : '') || '';
    return el.tagName + (el.id ? '#' + el.id : '') + (cls ? '.' + cls.split(/\s+/).slice(0,2).join('.') : '');
  }
  document.addEventListener('click', function(e){
    try {
      var t = e.target;
      var el = (t && t.closest) ? (t.closest('.signsubmit-loader,.captchsubmit,a,button') || t) : t;
      P.click = desc(el) + ' | 已阻止默认=' + e.defaultPrevented;
    } catch (err) {}
  }, true);
  window.addEventListener('error', function(ev){
    try { P.err.push((ev.message || String(ev)) + ' @' + (ev.filename || '') + ':' + (ev.lineno || 0)); } catch (err) {}
  }, true);
  if (window.jQuery) {
    jQuery(document).ajaxSend(function(e, x, s){ P.ajax.push('发送 ' + s.type + ' ' + s.url); });
    jQuery(document).ajaxError(function(e, x, s, er){ P.ajax.push('失败 ' + ((x && x.status) || '') + ' ' + s.url + ' ' + ((er && er.message) || '')); });
    jQuery(document).ajaxSuccess(function(e, x, s){ P.ajax.push('成功 ' + ((x && x.status) || '') + ' ' + s.url); });
  }
})()
"""

/**
 * 实时探针：打开诊断面板时当场取一次（点击、请求都发生在页面加载之后，静态快照看不到）。
 * 返回「每行一条」的纯文本，由界面按行展示。
 */
private const val LIVE_PROBE_JS = """
(function(){
  try{
    var out = [];
    out.push('地址: ' + location.href);
    out.push('文档状态: ' + document.readyState);
    try {
      var ctx = window.tbquire && window.tbquire.s && window.tbquire.s.contexts && window.tbquire.s.contexts._;
      var def = ctx && ctx.defined ? Object.keys(ctx.defined) : [];
      out.push('已加载模块: ' + (def.length ? def.join(',') : '(无)'));
      var reg = ctx && ctx.registry ? Object.keys(ctx.registry) : [];
      out.push('待加载模块: ' + (reg.length ? reg.join(',') : '(无)'));
    } catch(e) { out.push('模块信息: 读取失败 ' + e); }
    var p = window.__syProbe;
    out.push('最近点击: ' + (p ? (p.click || '(无)') : '埋点未注入'));
    out.push('AJAX: ' + (p && p.ajax && p.ajax.length ? p.ajax.slice(-4).join(' ／ ') : '(无)'));
    out.push('JS 错误: ' + (p && p.err && p.err.length ? p.err.slice(-3).join(' ／ ') : '(无)'));
    var m = document.getElementById('SliderCaptcha');
    if (m) {
      var st = getComputedStyle(m);
      out.push('滑块弹层: 存在 display=' + st.display + ' opacity=' + st.opacity + ' visibility=' + st.visibility);
      out.push('滑块组件: .slidercaptcha 数量=' + document.querySelectorAll('.slidercaptcha').length);
    } else {
      out.push('滑块弹层: 未创建');
    }
    var s = document.querySelector('[machine-verification]');
    out.push('验证标记: ' + (s ? (s.getAttribute('machine-verification') || '?') + ' captcha_mode=' + (s.getAttribute('value') || '') + ' slider-id=' + (s.getAttribute('slider-id') || '(空)') : '(无)'));
    out.push('登录按钮: .signsubmit-loader 数量=' + document.querySelectorAll('.signsubmit-loader').length);
    return out.join('\n');
  }catch(e){ return 'probe-error: ' + e; }
})()
"""

/** 页面加载超时（毫秒）：超时后停止转圈并给出提示，避免一直白屏转圈干等 */
private const val LOAD_TIMEOUT_MS = 25_000L

/** 无忧游戏库登录（直达站点独立登录页，避免首页弹窗遮罩） */
class WygamerLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShangYinTheme {
                WebLoginContent(
                    spec = LoginSpec(
                        title = "无忧游戏库登录",
                        startUrls = listOf(
                            WYGAMER_LOGIN_PAGE,
                            "${WygamerClient.BASE}/"
                        ),
                        cookieUrls = listOf("${WygamerClient.BASE}/"),
                        hint = "登录后可查看部分需要登录才能显示的资源下载链接。\n登录信息仅保存在本机。",
                        loginDetect = { c -> c.contains("wordpress_logged_in", true) },
                        isLoggedIn = { SettingsStore.isWygamerLoggedIn },
                        save = { SettingsStore.wygamerCookie = it },
                        logout = { SettingsStore.clearWygamerLogin() },
                        preCookies = listOf("showed_system_notice" to "showed"),
                        afterLoadJs = CLEAR_OVERLAY_JS,
                        failHint = "站点偶发抽风时可稍后重试。若页面能显示但点「登录」没反应，" +
                            "点右上角 ⓘ 看诊断信息并反馈（多为系统 WebView 版本过旧，" +
                            "可到应用商店更新「Android System WebView」或「Chrome」）。"
                    ),
                    onBack = { finish() },
                    onLoginSuccess = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }

    private companion object {
        /** Zibll 主题的独立登录页（首页那个弹窗在 WebView 里只剩黑色遮罩） */
        const val WYGAMER_LOGIN_PAGE =
            "https://www.wygamer.com/user-sign-2?tab=signin&redirect_to=https%3A%2F%2Fwww.wygamer.com%2F"
    }
}

/** Z-Library 登录（顺带完成 DiamWall 反爬验证） */
class ZlibLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShangYinTheme {
                WebLoginContent(
                    spec = LoginSpec(
                        title = "Z-Library 登录",
                        startUrls = ZlibClient.candidateUrls(),
                        cookieUrls = ZlibClient.candidateUrls(),
                        hint = "登录后即可搜索并下载电子书。\n" +
                            "站点有反爬验证，若接口提示「需要重新验证」，回到这里重新登录一次即可。\n" +
                            "登录信息仅保存在本机。",
                        loginDetect = { c -> c.contains("remix_userkey", true) },
                        isLoggedIn = { SettingsStore.zlibCookie.contains("remix_userkey", true) },
                        save = { SettingsStore.zlibCookie = it },
                        logout = { SettingsStore.clearZlibLogin() },
                        afterLoadJs = CLEAR_OVERLAY_JS,
                        // 站点每日换域名，打开前先从 getzlib.com 取当日验证地址（失败则用内置兜底）
                        dynamicUrls = { ZlibClient.dailyUrls() },
                        allowUrlInput = true,
                        failHint = "若手机上的 Chrome 能打开而这里打不开，是 App 内 WebView 环境的问题" +
                            "（本站反爬会校验浏览器指纹）：请点右上角 ⓘ 把诊断信息发给我，" +
                            "或到应用商店更新「Android System WebView」/「Chrome」后重试。",
                        onHostResolved = { host ->
                            if (host.isNotBlank() && ZlibClient.isZlibHost(host)) {
                                SettingsStore.zlibHost = host
                            }
                        },
                        onCustomHost = { host -> if (host.isNotBlank()) SettingsStore.zlibHost = host }
                    ),
                    onBack = { finish() },
                    onLoginSuccess = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }
}

/** 按域名取 Cookie 合并去重（同名保留首次出现的值） */
private fun collectCookies(cm: CookieManager, urls: List<String>): String {
    val all = LinkedHashMap<String, String>()
    for (u in urls) {
        val raw = cm.getCookie(u) ?: continue
        raw.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val k = part.substring(0, idx).trim()
                val v = part.substring(idx + 1).trim()
                if (k.isNotEmpty() && !all.containsKey(k)) all[k] = v
            }
        }
    }
    return all.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/** 退出登录时把已保存的 Cookie 逐个置为过期，避免 WebView 仍处于登录态 */
private fun expireCookies(cm: CookieManager, urls: List<String>, cookieString: String) {
    val names = cookieString.split(";").mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx > 0) part.substring(0, idx).trim().takeIf { it.isNotEmpty() } else null
    }
    for (u in urls) for (n in names) cm.setCookie(u, "$n=; Max-Age=0; path=/")
    cm.flush()
}

private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore("/").substringBefore("?")

@Composable
private fun WebLoginContent(spec: LoginSpec, onBack: () -> Unit, onLoginSuccess: () -> Unit) {
    // 0 = 显示"已登录"页；1 = 显示 WebView（登录或重新验证）
    var mode by remember { mutableIntStateOf(if (spec.isLoggedIn()) 0 else 1) }

    if (mode == 0) {
        val ctx = LocalContext.current
        AlreadyLoggedInScreen(
            title = spec.title,
            onBack = onBack,
            onRefresh = { mode = 1 },
            onLogout = {
                val saved = collectCookies(CookieManager.getInstance(), spec.cookieUrls)
                spec.logout()
                expireCookies(CookieManager.getInstance(), spec.cookieUrls, saved)
                Toast.makeText(ctx, "已退出登录", Toast.LENGTH_SHORT).show()
                onBack()
            }
        )
    } else {
        WebViewLoginScreen(spec = spec, onBack = onBack, onLoginSuccess = onLoginSuccess)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlreadyLoggedInScreen(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("已登录", style = MaterialTheme.typography.titleLarge)
            Text(
                "搜索 / 详情 / 下载会使用登录态。若接口提示需要重新验证，点下方「重新验证」刷新一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("重新验证 / 切换账号")
            }
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("退出登录")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewLoginScreen(
    spec: LoginSpec,
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val ctx = LocalContext.current
    // 候选线路（手动输入的地址会插到最前）
    var urls by remember { mutableStateOf(spec.startUrls) }
    var urlIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var lineMenu by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf("") }
    val cookieManager = CookieManager.getInstance()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // 诊断信息（右上角 ⓘ 查看）：WebView 环境 + 当前地址 + JS 报错 + 页面探针结果
    var diagOpen by remember { mutableStateOf(false) }
    var consoleErrors by remember { mutableStateOf(listOf<String>()) }
    var probeText by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf("") }
    // 需要动态解析线路时（如 Z-Library 每日地址），先解析完再建 WebView，避免先加载过期地址
    var urlsReady by remember { mutableStateOf(spec.dynamicUrls == null) }

    LaunchedEffect(Unit) {
        val resolve = spec.dynamicUrls ?: return@LaunchedEffect
        val list = runCatching { resolve() }.getOrDefault(emptyList())
        if (list.isNotEmpty()) urls = list + urls
        urlsReady = true
    }

    // 页面加载超时：停止转圈并提示，避免一直白屏转圈干等（反爬挑战页/挂住的子资源会拖住 onPageFinished）
    LaunchedEffect(loading, urlsReady) {
        if (loading && urlsReady) {
            delay(LOAD_TIMEOUT_MS)
            if (loading) {
                loading = false
                val host = if (currentUrl.isNotBlank()) "（${hostOf(currentUrl)}）" else ""
                pageError = "页面加载超过 25 秒仍未完成$host" +
                    "\n\n可换线路 / 重新加载重试，或点右上角 ⓘ 查看诊断信息。"
            }
        }
    }

    fun loadLine(wv: WebView?, index: Int) {
        val url = urls.getOrNull(index) ?: return
        urlIndex = index
        pageError = null
        loading = true
        wv?.loadUrl(url)
    }

    /** 手动输入地址（浏览器里能打开的那个）：插到线路最前并加载，同时记进设置 */
    fun loadCustomUrl(wv: WebView?) {
        val raw = customUrl.trim()
        if (raw.isBlank()) return
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw/"
        spec.onCustomHost?.invoke(hostOf(url))
        urls = listOf(url) + urls
        urlDialog = false
        pageError = null
        loading = true
        urlIndex = 0
        wv?.loadUrl(url)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                wv.stopLoading()
                wv.settings.javaScriptEnabled = false
                wv.clearHistory()
                wv.removeAllViews()
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.destroy()
                webViewRef = null
            }
            cookieManager.flush()
        }
    }

    /** 尝试提取并保存 cookie，成功返回 true */
    fun tryExtractCookies(): Boolean {
        // 动态线路（如 Z-Library 每日地址）不在内置列表里，故把当前页地址也并入采集范围
        val merged = collectCookies(cookieManager, (spec.cookieUrls + currentUrl).filter { it.isNotBlank() })
        if (!spec.loginDetect(merged)) return false
        spec.save(merged)
        cookieManager.flush()
        return true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(spec.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 实时取一次探针：点击/请求都发生在加载之后，必须当场读才看得到
                        webViewRef?.evaluateJavascript(LIVE_PROBE_JS) { r -> probeText = jsResultToText(r) }
                        diagOpen = true
                    }) {
                        Icon(Icons.Rounded.Info, contentDescription = "诊断信息")
                    }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                    if (spec.allowUrlInput) {
                        IconButton(onClick = { urlDialog = true }) {
                            Icon(Icons.Rounded.Link, contentDescription = "手动输入地址")
                        }
                    }
                    if (urls.size > 1) {
                        Box {
                            IconButton(onClick = { lineMenu = true }) {
                                Icon(Icons.Rounded.SwapHoriz, contentDescription = "切换线路")
                            }
                            DropdownMenu(expanded = lineMenu, onDismissRequest = { lineMenu = false }) {
                                urls.forEachIndexed { i, u ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                hostOf(u) + if (i == urlIndex) "（当前）" else "",
                                                maxLines = 1
                                            )
                                        },
                                        onClick = {
                                            lineMenu = false
                                            loadLine(webViewRef, i)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            if (tryExtractCookies()) onLoginSuccess()
                            else Toast.makeText(ctx, "未检测到登录态，请先完成登录", Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("已登录") }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            if (urlsReady) AndroidView(
                factory = { c ->
                    var lastUrl = ""
                    var repeatCount = 0
                    WebView(c).apply {
                        webViewRef = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.loadsImagesAutomatically = true
                        // 验证码/反爬脚本有时走 http 子资源，混合内容一律放行，避免"验证界面不出来"
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // UA 不伪造（伪造的桌面 UA 与 WebView 自动发出的 Client Hints 矛盾，
                        // 反爬会判定为机器人：表现为手机 Chrome 能开、App 里一直转圈或过不了验证）。
                        // 但 window.open / target=_blank 必须放行：站点常用它打开登录或跳转，
                        // WebView 默认会静默丢弃这类新窗口请求，表现为「点了没反应」。
                        settings.setSupportMultipleWindows(true)
                        settings.javaScriptCanOpenWindowsAutomatically = true

                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        // 预置 Cookie：站点会在 onload 后无条件弹公告遮罩，先写入标记 + 加载后强制清理
                        val startUrl = urls.firstOrNull().orEmpty()
                        spec.preCookies.forEach { (k, v) ->
                            if (startUrl.isNotBlank()) cookieManager.setCookie(startUrl, "$k=$v; path=/")
                        }
                        cookieManager.flush()

                        // 收集页面 JS 报错：主题/反爬脚本一旦报错，页面会「能看但点不动」
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                                val m = msg ?: return false
                                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                    val line = "[${m.sourceId()}:${m.lineNumber()}] ${m.message()}"
                                    consoleErrors = (consoleErrors + line).takeLast(8)
                                }
                                return false
                            }

                            // window.open / target=_blank：在当前 WebView 里打开，避免「点了没反应」
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?
                            ): Boolean {
                                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                transport.webView = view
                                resultMsg.sendToTarget()
                                return true
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                                currentUrl = url.orEmpty()
                                // 反爬验证可能自我重定向若干次，超过阈值说明该线路过不去
                                if (url == lastUrl) {
                                    repeatCount++
                                } else {
                                    lastUrl = url ?: ""
                                    repeatCount = 0
                                }
                                if (repeatCount >= 8) {
                                    view?.stopLoading()
                                    loading = false
                                    pageError = "该线路反复重定向（反爬验证未通过），请切换到其他线路重试。"
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                pageError = null
                                currentUrl = url.orEmpty()
                                url?.let { spec.onHostResolved?.invoke(hostOf(it)) }
                                // 埋点：记录点击 / AJAX / JS 报错，供诊断面板取证（页面能看但点不动时唯一线索）
                                view?.evaluateJavascript(INSTRUMENT_JS, null)
                                // 清理站点自动弹出的遮挡层（如 Zibll 主题的「系统公告」弹窗）
                                spec.afterLoadJs?.let { js -> view?.evaluateJavascript(js, null) }
                                if (tryExtractCookies()) onLoginSuccess()
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame != true) return
                                loading = false
                                // 自动换下一条线路
                                val next = urlIndex + 1
                                if (next <= urls.lastIndex) {
                                    loadLine(view, next)
                                    return
                                }
                                val host = hostOf(request.url?.toString().orEmpty())
                                val desc = error?.description?.toString().orEmpty()
                                pageError = buildString {
                                    append("页面打不开")
                                    if (host.isNotBlank()) append("（").append(host).append("）")
                                    if (desc.isNotBlank()) append("：").append(desc)
                                    if (spec.failHint.isNotBlank()) append("\n\n").append(spec.failHint)
                                }
                            }
                        }

                        loadUrl(startUrl)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            pageError?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = {
                                val next = (urlIndex + 1) % urls.size
                                loadLine(webViewRef, next)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (urls.size > 1) "换线路重试" else "重试")
                        }
                        TextButton(onClick = { loadLine(webViewRef, urlIndex) }) { Text("重新加载本线路") }
                        if (spec.allowUrlInput) {
                            TextButton(onClick = { urlDialog = true }) { Text("输入其他地址") }
                        }
                        TextButton(onClick = { pageError = null }) { Text("关闭提示") }
                        Text(
                            spec.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }

    // 手动输入地址：浏览器里能打开、WebView 里打不开时，直接把那个地址粘进来
    if (urlDialog) {
        AlertDialog(
            onDismissRequest = { urlDialog = false },
            title = { Text("输入站点地址") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "把你浏览器里能打开的那个地址粘到这里（支持整串 URL）。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("例：https://zh.z-lib.sk/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { loadCustomUrl(webViewRef) }) { Text("加载") }
            },
            dismissButton = {
                TextButton(onClick = { urlDialog = false }) { Text("取消") }
            }
        )
    }

    // 诊断信息：登录页不对劲时把真实证据带回来（WebView 版本 / JS 报错 / 页面探针）
    if (diagOpen) {
        AlertDialog(
            onDismissRequest = { diagOpen = false },
            title = { Text("诊断信息") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    DiagLine("WebView UA", webViewUaText())
                    DiagLine("页面实时状态", probeText.ifBlank { "（未取到）" })
                    DiagLine(
                        "JS 报错",
                        if (consoleErrors.isEmpty()) "无" else consoleErrors.joinToString("\n")
                    )
                    Text(
                        "若页面能显示但点不动、或一直转圈，把以上内容截图发我即可定位。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = { TextButton(onClick = { diagOpen = false }) { Text("关闭") } }
        )
    }
}

/** 一行诊断信息（等宽小字，方便截图） */
@Composable
private fun DiagLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** WebView 真实 UA（当前页面实际发出的那个） */
private fun webViewUaText(): String =
    com.shangyin.app.App.webViewUa.ifBlank { "（未捕获）" }

/** evaluateJavascript 回来的字符串字面量（带引号、转义换行）转成可读文本 */
private fun jsResultToText(raw: String?): String {
    val s = raw.orEmpty().trim()
    if (s.isEmpty() || s == "null") return "（未取到）"
    return s.removeSurrounding("\"")
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
}