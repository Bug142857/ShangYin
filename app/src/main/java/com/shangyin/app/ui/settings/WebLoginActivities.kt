package com.shangyin.app.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.shangyin.app.data.wygamer.WygamerClient
import com.shangyin.app.data.zlib.ZlibClient
import com.shangyin.app.data.zlib.ZlibWeb
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
    /**
     * 服务端校验登录态（登录页与账号管理共用）。
     * ⚠️ 「Cookie 里有关键字」不等于会话有效：服务端可能早已让旧会话失效，
     * 此时拿 Cookie 判定会一直误报「已登录」，用户也就永远重登不了。
     *
     * @return true = 有效；false = 确认失效；**null = 网络/被墙等无法判断**
     *         （⚠️ 调用方必须区分 null：不能把"测不出来"当成"已过期"，否则会骗用户重新登录）
     */
    val verifyLogin: (suspend () -> Boolean?)? = null,
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
 * 诊断埋点：记录「点击 → 谁接管 → 有没有发请求 → 有没有报错 → 哪些资源没加载上」。
 * 主题 JS 一旦没接管点击（或接管后请求静默失败），页面就表现为「能看、点不动」，
 * 静态快照看不出原因，必须把点击后的运行时证据留下来。
 */
private const val INSTRUMENT_JS = """
(function(){
  if (window.__syProbe) return;
  var P = { click: '(无)', ajax: [], err: [], res: [] };
  window.__syProbe = P;
  function logAjax(s){
    P.ajax.push(s);
    while (P.ajax.length > 6) P.ajax.shift();
  }
  function desc(el){
    if (!el) return '(?';
    var cls = (typeof el.className === 'string' ? el.className : '') || '';
    var s = el.tagName + (el.id ? '#' + el.id : '') + (cls ? '.' + cls.split(/\s+/).slice(0,2).join('.') : '');
    var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
    if (t) s += ' 「' + t.slice(0, 12) + '」';
    if (el.tagName === 'A') s += ' href=' + (el.getAttribute('href') || '(空)') + ' target=' + (el.getAttribute('target') || '(空)');
    return s;
  }
  document.addEventListener('click', function(e){
    try {
      var t = e.target;
      var el = (t && t.closest) ? (t.closest('.signsubmit-loader,.captchsubmit,a,button') || t) : t;
      P.click = desc(el);
      // 站点自己的处理器跑完后（冒泡阶段之后）再看一次，判断这次点击是否被吞掉
      setTimeout(function(){
        try { P.click = desc(el) + ' | 已被站点阻止默认=' + e.defaultPrevented; } catch (x) {}
      }, 0);
    } catch (err) {}
  }, true);
  window.addEventListener('error', function(ev){
    try {
      var t = ev.target;
      // 资源（图片/样式/脚本）加载失败：只有 URL 能说明问题，message 是空的
      if (t && t !== window && t.tagName && (t.tagName === 'IMG' || t.tagName === 'LINK' || t.tagName === 'SCRIPT')) {
        var line = t.tagName + ' 加载失败 ' + (t.src || t.href || '(无地址)');
        if (P.res.indexOf(line) < 0) P.res.push(line);
        while (P.res.length > 4) P.res.shift();
        return;
      }
      P.err.push((ev.message || String(ev)) + ' @' + (ev.filename || '') + ':' + (ev.lineno || 0));
      while (P.err.length > 3) P.err.shift();
    } catch (err) {}
  }, true);
  if (window.fetch) {
    var of = window.fetch;
    window.fetch = function(){
      var a = arguments[0];
      var u = String((a && a.url) || a || '');
      logAjax('fetch ' + u.slice(0, 70));
      return of.apply(this, arguments).then(function(r){
        logAjax('fetch ' + r.status + ' ' + u.slice(0, 60));
        return r;
      }, function(e){
        logAjax('fetch 失败 ' + u.slice(0, 60) + ' ' + e);
        throw e;
      });
    };
  }
  try {
    var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u){
      this.__syU = m + ' ' + String(u || '').slice(0, 70);
      return oo.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(){
      var x = this;
      logAjax('xhr ' + (x.__syU || ''));
      x.addEventListener('loadend', function(){ logAjax('xhr ' + x.status + ' ' + (x.__syU || '')); });
      return os.apply(this, arguments);
    };
  } catch (e) {}
  if (window.jQuery) {
    jQuery(document).ajaxSend(function(e, x, s){ logAjax('发送 ' + s.type + ' ' + s.url); });
    jQuery(document).ajaxError(function(e, x, s, er){ logAjax('失败 ' + ((x && x.status) || '') + ' ' + s.url + ' ' + ((er && er.message) || '')); });
    jQuery(document).ajaxSuccess(function(e, x, s){
      var body = (x && x.responseText) ? String(x.responseText).replace(/\s+/g, ' ').slice(0, 70) : '';
      logAjax('成功 ' + ((x && x.status) || '') + ' ' + s.url + (body ? ' → ' + body : ''));
    });
  }
})()
"""

/**
 * 无忧滑块验证弹窗的**强制布局样式**（无条件注入）。
 *
 * 为什么无条件注入：弹窗由主题 JS（slidercaptcha.min.js）在点击「登录」后动态创建，
 * 其内部元素尺寸完全依赖主题 CSS；实测同一 DOM 在桌面 Chrome（卡片 340x372）正常，
 * 在 App 的 WebView 里却只剩黑色遮罩、`.modal-content` 高度 0（子元素全部塌陷）。
 * 与其猜是哪条规则失效，不如直接把整条布局链用 !important 钉死（数值取自桌面实测值），
 * 保证「拼图可见 + 滑块可拖」。
 *
 * ⚠️ 三条硬约束（改这个样式表前必读）：
 *  1. `#SliderCaptcha` 自身**不能**写 `display:flex!important`——弹窗的显示/隐藏靠 jQuery `.hide()`
 *     写 inline display:none，样式表里的 !important 会压过 inline 样式导致弹窗关不掉。
 *  2. `.captcha-body-bar` / `.captcha-slider` 拖动时由 JS 写 inline `left`、`.sliderMask` 写 inline `width`，
 *     这些属性一律不要用 !important（否则拖动失效）。
 *  3. `.modal-colorful-header` 的 inline `height:100px` 与内容区 inline `margin-top:100px` 是对齐的一对，
 *     不要改（改了内容会错位）。
 *
 * 另外顺手把主题的双重转义 bug 修掉：`.sliderText` 的文案是 `&#21521;...` 字面量，
 * 屏幕上显示成一串实体码，这里直接写成中文。
 */
private const val SLIDER_FORCE_CSS_JS = """
;(function(){
  if (window.__sySliderCss) return;
  window.__sySliderCss = 1;
  var CSS = [
    '#SliderCaptcha{visibility:visible!important;opacity:1!important;background:rgba(0,0,0,.5)!important}',
    '#SliderCaptcha *{transform:none!important}',
    '#SliderCaptcha .modal-dialog{display:block!important;position:relative!important;height:auto!important;' +
      'min-height:372px!important;max-height:none!important}',
    '#SliderCaptcha .modal-content{display:block!important;height:auto!important;min-height:372px!important;' +
      'max-height:none!important;overflow:visible!important;background:#fff!important;border-radius:12px!important}',
    '#SliderCaptcha .modal-body{display:block!important;position:relative!important;height:auto!important;' +
      'min-height:340px!important;max-height:none!important}',
    '#SliderCaptcha .slidercaptcha{display:block!important;position:relative!important;height:auto!important;' +
      'min-height:222px!important;overflow:visible!important}',
    '#SliderCaptcha canvas.captcha-body-bg{display:inline-block!important;position:static!important;' +
      'visibility:visible!important;opacity:1!important;width:278px!important;height:170px!important}',
    '#SliderCaptcha canvas.captcha-body-bar{display:block!important;position:absolute!important;top:0!important;' +
      'visibility:visible!important;opacity:1!important}',
    '#SliderCaptcha .sliderContainer{display:block!important;position:relative!important;height:40px!important;' +
      'line-height:40px!important;margin-top:8px!important;text-align:center!important;background:#f2f3f5!important;' +
      'color:#787878!important;border-radius:4px!important}',
    '#SliderCaptcha .sliderMask{position:absolute!important;top:0!important;height:40px!important;' +
      'background:rgba(0,153,255,.25)!important;border-radius:4px!important}',
    '#SliderCaptcha .captcha-slider{display:block!important;position:absolute!important;top:0!important;' +
      'width:40px!important;height:40px!important;background:#fff!important;border-radius:4px!important;' +
      'box-shadow:0 0 5px rgba(0,0,0,.25)!important;cursor:pointer!important;z-index:3!important}',
    '#SliderCaptcha .sliderText{position:absolute!important;left:0!important;top:0!important;width:100%!important;' +
      'height:40px!important;line-height:40px!important;text-align:center!important;font-size:14px!important;' +
      'color:#787878!important;z-index:1!important}',
    '#SliderCaptcha .refreshIcon{position:absolute!important;right:6px!important;top:6px!important;' +
      'padding:4px 6px!important;background:rgba(255,255,255,.85)!important;color:#888!important;border:0!important;' +
      'border-radius:4px!important;cursor:pointer!important;z-index:6!important}'
  ].join('');
  try {
    var s = document.createElement('style');
    s.id = 'sy-slider-css';
    s.appendChild(document.createTextNode(CSS));
    (document.head || document.body).appendChild(s);
  } catch (e) {}
  // 主题把「向右滑动填充拼图」写成了 HTML 实体字面量，屏幕上显示的是 &#21521;...，直接改成中文
  function fixText(){
    try {
      var t = document.querySelector('#SliderCaptcha .sliderText');
      if (t && t.textContent && t.textContent.indexOf('&#') >= 0) t.textContent = '向右滑动填充拼图';
    } catch (e) {}
  }
  // 记录弹窗实际布局：万一还是渲染不出来，诊断面板里能直接看到是哪一层塌了
  var SEL = ['.modal-dialog', '.modal-content', '.modal-body', '.slidercaptcha',
    'canvas.captcha-body-bg', 'canvas.captcha-body-bar', '.sliderContainer'];
  function check(){
    try {
      var m = document.getElementById('SliderCaptcha');
      if (!m) return;
      fixText();
      var parts = [];
      for (var i = 0; i < SEL.length; i++) {
        var el = m.querySelector(SEL[i]);
        if (!el) { parts.push(SEL[i] + '=(无)'); continue; }
        var st = getComputedStyle(el), r = el.getBoundingClientRect();
        parts.push(SEL[i] + '=' + st.display + '/' + st.position + ' ' +
          Math.round(r.width) + 'x' + Math.round(r.height) + ' maxH=' + st.maxHeight +
          ' op=' + st.opacity + ' vis=' + st.visibility);
      }
      if (window.__syProbe) window.__syProbe.style = parts.join(' ｜ ');
    } catch (e) {}
  }
  document.addEventListener('click', function(){
    setTimeout(check, 400); setTimeout(check, 1200); setTimeout(check, 2500);
  }, true);
  setTimeout(check, 400);
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
    out.push('视口: ' + window.innerWidth + 'x' + window.innerHeight +
      ' dpr=' + (window.devicePixelRatio || 1) +
      ' 可视缩放=' + (window.visualViewport ? window.visualViewport.scale : '?'));
    try {
      var ctx = window.tbquire && window.tbquire.s && window.tbquire.s.contexts && window.tbquire.s.contexts._;
      var def = ctx && ctx.defined ? Object.keys(ctx.defined) : [];
      out.push('已加载模块: ' + (def.length ? def.join(',') : '(该站点不用 tbquire)'));
      var reg = ctx && ctx.registry ? Object.keys(ctx.registry) : [];
      if (reg.length) out.push('待加载模块: ' + reg.join(','));
    } catch(e) { out.push('模块信息: 读取失败 ' + e); }
    var p = window.__syProbe;
    out.push('最近点击: ' + (p ? (p.click || '(无)') : '埋点未注入'));
    out.push('请求: ' + (p && p.ajax && p.ajax.length ? p.ajax.join(' ／ ') : '(无)'));
    out.push('JS 错误: ' + (p && p.err && p.err.length ? p.err.join(' ／ ') : '(无)'));
    out.push('资源加载失败: ' + (p && p.res && p.res.length ? p.res.join(' ／ ') : '(无)'));
    var m = document.getElementById('SliderCaptcha');
    if (m) {
      var st = getComputedStyle(m);
      out.push('滑块弹窗: display=' + st.display + ' opacity=' + st.opacity + ' visibility=' + st.visibility);
      var card = m.querySelector('.modal-content') || m.querySelector('.modal-dialog');
      if (card) {
        var r = card.getBoundingClientRect();
        out.push('滑块卡片: 宽=' + Math.round(r.width) + ' 高=' + Math.round(r.height) +
          ' 背景=' + getComputedStyle(card).backgroundColor);
      } else {
        out.push('滑块卡片: 不存在');
      }
      var t = m.querySelector('.sliderText');
      out.push('滑块提示文字: ' + (t ? (t.textContent || '(空)').trim() : '(无)'));
      var c = m.querySelector('canvas');
      out.push('滑块画布: 数量=' + m.querySelectorAll('canvas').length +
        (c ? ' 尺寸=' + c.width + 'x' + c.height : ''));
      var sc = m.querySelector('.sliderContainer');
      out.push('滑块容器: ' + (sc ? ('class=' + sc.className) : '(无)'));
      if (p && p.style) out.push('弹窗样式链: ' + p.style);
    } else {
      out.push('滑块弹窗: 未创建');
    }
    var s = document.querySelector('[machine-verification]');
    out.push('验证标记: ' + (s ? (s.getAttribute('machine-verification') || '?') + ' captcha_mode=' + (s.getAttribute('value') || '') : '(无)'));
    out.push('登录按钮: .signsubmit-loader 数量=' + document.querySelectorAll('.signsubmit-loader').length +
      ' ／ 表单: ' + document.querySelectorAll('form').length +
      ' ／ 密码框: ' + document.querySelectorAll('input[type=password]').length);
    return out.join('\n');
  }catch(e){ return 'probe-error: ' + e; }
})()
"""

/** 页面加载超时（毫秒）：超时后停止转圈并给出提示，避免一直白屏转圈干等 */
private const val LOAD_TIMEOUT_MS = 25_000L

/** 登录态校验超时（毫秒）：通道卡住时按"测不出来"处理，落到登录页而不是一直转圈 */
private const val VERIFY_TIMEOUT_MS = 8_000L

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
                        // 服务端说了算：Cookie 里有 wordpress_logged_in ≠ 会话有效（过期 Cookie 会让
                        // 界面显示已登录、下载链接却拿不到）。判据见 WygamerClient.sessionOk 注释：
                        // ⚠️ 不能用 WP REST（无 nonce 时已登录也回 401），改用 /wp-admin/profile.php 是否被弹回登录页
                        verifyLogin = { WygamerClient.sessionOk() },
                        save = { SettingsStore.wygamerCookie = it },
                        logout = { SettingsStore.clearWygamerLogin() },
                        preCookies = listOf("showed_system_notice" to "showed"),
                        // ⚠️ 两段 JS 拼接时必须确保前一段以分号结束：IIFE 之间少了分号会被解析成
                        // `})()(function(){...})()`（报 "is not a function"），后一段整段不执行。
                        afterLoadJs = CLEAR_OVERLAY_JS + SLIDER_FORCE_CSS_JS,
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
                        startUrls = ZlibClient.loginUrls(),
                        cookieUrls = ZlibClient.cookieUrls(),
                        hint = "登录后即可搜索并下载电子书。\n" +
                            "站点的反爬验证由 App 自动完成；若接口提示「未登录或登录已失效」，回到这里重新登录一次即可。\n" +
                            "登录信息仅保存在本机。",
                        loginDetect = { c -> c.contains("remix_userkey", true) },
                        isLoggedIn = { SettingsStore.zlibCookie.contains("remix_userkey", true) },
                        // 登录态变化后让接口通道丢掉旧页面与线路记忆，下次请求重新建会话
                        save = { SettingsStore.zlibCookie = it; ZlibWeb.reset() },
                        logout = { SettingsStore.clearZlibLogin(); ZlibWeb.reset() },
                        afterLoadJs = CLEAR_OVERLAY_JS,
                        // 服务端说了算：Cookie 存在不等于会话有效，必须问 /eapi/user/profile 才自动关页
                        verifyLogin = { ZlibClient.sessionOk() },
                        // 站点每日换域名，打开前先从 getzlib.com 取当日验证地址（失败则用内置兜底）
                        dynamicUrls = { ZlibClient.dailyLoginUrls() },
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

/**
 * 按域名取 Cookie 合并去重（同名保留首次出现的值）。
 *
 * ⚠️ **反爬风控 / 一次性会话 Cookie（`__diamwall`/`c_token`/`bsrv`）一律不采集**：
 * 它们只对"当下这次会话"有效，存下来再写回去会让风控判定为伪造票据 ——
 * 实测（真浏览器复现）：把 `__diamwall` 置成无效值后，zlib 全站稳定报
 * `net::ERR_TOO_MANY_REDIRECTS`；只删掉这一个 Cookie 就立刻恢复。
 * 所以这类 Cookie 既不保存、也不参与"登录态是否变化"的比较（它们每次响应都会被重新下发）。
 */
private fun collectCookies(cm: CookieManager, urls: List<String>): String {
    val all = LinkedHashMap<String, String>()
    for (u in urls) {
        val raw = cm.getCookie(u) ?: continue
        raw.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val k = part.substring(0, idx).trim()
                val v = part.substring(idx + 1).trim()
                if (k.isEmpty()) return@forEach
                if (k.lowercase() in ZlibClient.TRANSIENT_COOKIE_NAMES) return@forEach
                if (!all.containsKey(k)) all[k] = v
            }
        }
    }
    return all.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/** 清掉站点风控 / 一次性 Cookie（进登录页前调用，避免残留的无效票据把整站打成重定向死循环） */
private fun clearTransientCookies(cm: CookieManager, urls: List<String>) {
    ZlibClient.clearTransientCookies(cm, urls)
}

/**
 * 退出登录时把已保存的 Cookie 逐个置为过期，避免 WebView 仍处于登录态。
 *
 * ⚠️ 关键坑：域 Cookie（`Domain=.z-lib.sk`）**必须带同样的 Domain 属性**才删得掉，
 * 只写 `name=; Max-Age=0; path=/` 只会命中 host-only 的同名 Cookie —— 实测表现就是
 * 「退出登录后重进登录页，页面还没加载出来就又被判成已登录并自动关闭」。
 * 所以这里对每个名字把 5 种 Domain 写法都试一遍，最后校验残留（还有残留就再来一轮）。
 */
private fun expireCookies(cm: CookieManager, urls: List<String>, cookieString: String) {
    val names = cookieString.split(";").mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx > 0) part.substring(0, idx).trim().takeIf { it.isNotEmpty() } else null
    }.distinct()
    if (names.isEmpty() || urls.isEmpty()) return
    val expired = "Max-Age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/"
    repeat(2) {
        for (u in urls) {
            val host = hostOf(u)
            val bare = host.split(".").takeLast(2).joinToString(".")
            val domains = listOf("", "; domain=$host", "; domain=.$host", "; domain=$bare", "; domain=.$bare")
            for (n in names) for (d in domains) cm.setCookie(u, "$n=; $expired$d")
        }
        cm.flush()
        val left = urls.joinToString(";") { cm.getCookie(it).orEmpty() }
        if (names.none { left.contains("$it=") }) return
    }
}

private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore("/").substringBefore("?")

@Composable
private fun WebLoginContent(spec: LoginSpec, onBack: () -> Unit, onLoginSuccess: () -> Unit) {
    // 0 = 显示"已登录"页；1 = 显示 WebView（登录 / 切换账号）；2 = 正在向服务端校验登录态
    var mode by remember {
        mutableIntStateOf(
            when {
                !spec.isLoggedIn() -> 1        // 本地没凭据 → 直接进登录页
                spec.verifyLogin == null -> 0  // 没有服务端校验手段，只能信本地
                else -> 2                      // 本地有凭据，但本地凭据只是启发式
            }
        )
    }
    var confirmSwitch by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    /** 退出当前账号：清 CookieManager 里的 Cookie + 清设置 */
    fun signOut() {
        val cm = CookieManager.getInstance()
        val saved = collectCookies(cm, spec.cookieUrls)
        expireCookies(cm, spec.cookieUrls, saved)
        spec.logout()
        cm.flush()
    }

    // 入口判定：**只有服务端明确确认有效（== true）才显示"已登录"页**；
    // false（确认失效）与 null（测不出来：网络/被墙/反爬挑战）都进登录页。
    // ⚠️ v2.23.18 把 null 也当成"已登录"，结果 zlib 会话测不出来时**登录页永远打不开**
    // （用户："账号管理里 zlib 登录页打不开了，上个版本还能打开"）——
    // 登录页必须始终可达，用户才有机会重新登录；"已登录"页只是便利，不能挡住入口。
    // 另外加超时：校验通道（zlib 走隐藏 WebView）卡住时也必须落到登录页，不能一直转圈。
    LaunchedEffect(Unit) {
        if (mode != 2) return@LaunchedEffect
        val ok = withTimeoutOrNull(VERIFY_TIMEOUT_MS) {
            runCatching { spec.verifyLogin?.invoke() }.getOrNull()
        }
        mode = if (ok == true) 0 else 1
    }

    if (mode == 2) {
        // 校验中：先转圈，避免"已登录"页闪一下再跳登录页
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Text(
                "正在校验登录状态…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    } else if (mode == 0) {
        AlreadyLoggedInScreen(
            title = spec.title,
            onBack = onBack,
            onSwitch = { confirmSwitch = true },
            onLogout = {
                signOut()
                Toast.makeText(ctx, "已退出登录", Toast.LENGTH_SHORT).show()
                onBack()
            }
        )
        // 「切换账号」必须先退出当前账号：否则登录页一加载就带着旧 Cookie，
        // tryExtractCookies() 立刻判定"已登录"并自动关页面，用户根本没有输入新账号的机会。
        if (confirmSwitch) {
            AlertDialog(
                onDismissRequest = { confirmSwitch = false },
                title = { Text("切换账号 / 重新登录") },
                text = { Text("会先退出当前账号，然后打开登录页，你可以用另一个账号登录。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmSwitch = false
                        signOut()
                        mode = 1
                    }) { Text("退出并重新登录") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmSwitch = false }) { Text("取消") }
                }
            )
        }
    } else {
        WebViewLoginScreen(spec = spec, onBack = onBack, onLoginSuccess = onLoginSuccess)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlreadyLoggedInScreen(
    title: String,
    onBack: () -> Unit,
    onSwitch: () -> Unit,
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
                "搜索 / 详情 / 下载会使用登录态。\n" +
                    "若搜索提示「未登录或登录已失效」，点下方「切换账号 / 重新登录」——" +
                    "它会先退出当前账号，再打开登录页让你重新登录（也可以换另一个账号）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Button(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
                Text("切换账号 / 重新登录")
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
    // 页面跳转记录：点击链接后到底有没有真的发起导航（首页链接点了不动时靠它判断）
    var navLog by remember { mutableStateOf(listOf<String>()) }
    // 需要动态解析线路时（如 Z-Library 每日地址），先解析完再建 WebView，避免先加载过期地址
    var urlsReady by remember { mutableStateOf(spec.dynamicUrls == null) }
    val scope = rememberCoroutineScope()
    // 打开本页时的 Cookie 快照：只有「本次操作期间 Cookie 真的变了」才算登录成功并自动关页。
    // 打开时就存在的 Cookie 可能是服务端早已失效的陈旧值，若据此自动关页，用户永远没机会输入账号。
    val initialCookies = remember { collectCookies(cookieManager, spec.cookieUrls) }

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
                            // 手动确认：Cookie 关键字命中就直接关页；否则退一步问服务端
                            // （站点改了 Cookie 名时也要能登录成功，别让用户卡在登录页反复登）
                            if (tryExtractCookies()) {
                                onLoginSuccess()
                            } else {
                                val nav = webViewRef?.url.orEmpty()
                                scope.launch {
                                    val ok = runCatching { spec.verifyLogin?.invoke() }.getOrNull() == true
                                    if (ok) {
                                        val merged = collectCookies(
                                            cookieManager,
                                            (spec.cookieUrls + currentUrl + nav)
                                                .filter { it.isNotBlank() }
                                        )
                                        if (merged.isNotBlank()) {
                                            spec.save(merged)
                                            cookieManager.flush()
                                        }
                                        onLoginSuccess()
                                    } else {
                                        Toast.makeText(
                                            ctx,
                                            "未检测到登录态，请先完成登录",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
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
                    // 重定向死循环只自愈一次（清理 Cookie 后重载），再犯就按普通失败处理，避免无限重载
                    var redirectLoopFixed = false
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
                                navLog = (navLog + url.orEmpty()).takeLast(4)
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
                                // 先把 Cookie 存下来（哪怕还没验证通过，保证稍后「已登录」按钮能保存最新值）
                                val current = collectCookies(
                                    cookieManager,
                                    (spec.cookieUrls + currentUrl).filter { it.isNotBlank() }
                                )
                                if (spec.loginDetect(current)) spec.save(current)
                                // 只有「本次进来的 Cookie 真的变了」才自动判定成功并关页；
                                // 进来时就已经存在的 Cookie 一律不自动关页（否则用户没机会输入账号）。
                                if (current == initialCookies) return
                                val verify = spec.verifyLogin
                                if (verify == null) {
                                    onLoginSuccess()
                                } else {
                                    scope.launch {
                                        // 只有服务端**明确确认有效**（== true）才自动关页；
                                        // false/null 都留在登录页（用户还能点右上角「已登录」兜底）
                                        if (runCatching { verify() }.getOrNull() == true) {
                                            spec.save(current)
                                            cookieManager.flush()
                                            onLoginSuccess()
                                        }
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame != true) return
                                loading = false
                                // ★ ERR_TOO_MANY_REDIRECTS（= ERROR_REDIRECT_LOOP，-9）自愈：
                                // 站点风控票据失效时它会反复 307 跳回自己（zlib 实测），页面永远打不开。
                                // 清掉这类一次性 Cookie 并重载一次（见下方说明）。
                                if (error?.errorCode == android.webkit.WebViewClient.ERROR_REDIRECT_LOOP &&
                                    !redirectLoopFixed
                                ) {
                                    redirectLoopFixed = true
                                    // 实测真因：残留的**无效风控 Cookie**（`__diamwall`）会让整站死循环。
                                    // 只清这类一次性 Cookie 再重载即可恢复 —— **不动登录票据**，
                                    // 免得为了一次加载失败就把用户白登出。
                                    clearTransientCookies(cookieManager, (spec.cookieUrls + urls).distinct())
                                    Toast.makeText(
                                        ctx,
                                        "站点风控票据异常，已自动清理，正在重试…",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    urls.getOrNull(urlIndex)?.let { view?.loadUrl(it) }
                                    return
                                }
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

                        // ★ 打开登录页前先清掉残留的风控 / 一次性 Cookie：
                        // 实测（真浏览器复现）残留一个**无效的 `__diamwall`** 就会让 zlib 整站
                        // （含 /login）稳定报 `net::ERR_TOO_MANY_REDIRECTS`，清掉即恢复；
                        // 服务端会在下一次响应里重新下发有效票据，所以清理是安全且必要的。
                        clearTransientCookies(cookieManager, (spec.cookieUrls + urls).distinct())
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
    // 提供「复制」：截图里常带站点图片，容易被内容安全审核拦下，纯文本粘贴最稳
    if (diagOpen) {
        val context = LocalContext.current
        val uaValue = webViewUaText()
        val probeValue = probeText.ifBlank { "（未取到）" }
        val navValue = if (navLog.isEmpty()) "无" else navLog.joinToString("\n")
        val errValue = if (consoleErrors.isEmpty()) "无" else consoleErrors.joinToString("\n")
        AlertDialog(
            onDismissRequest = { diagOpen = false },
            title = { Text("诊断信息") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    DiagLine("WebView UA", uaValue)
                    DiagLine("页面实时状态", probeValue)
                    DiagLine("页面跳转记录", navValue)
                    DiagLine("JS 报错", errValue)
                    Text(
                        "若页面能显示但点不动、或一直转圈，点「复制」把文字发我即可定位。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val dump = listOf(
                            "WebView UA" to uaValue,
                            "页面实时状态" to probeValue,
                            "页面跳转记录" to navValue,
                            "JS 报错" to errValue
                        ).joinToString("\n\n") { it.first + "：\n" + it.second }
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("诊断信息", dump))
                        Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                    TextButton(onClick = { diagOpen = false }) { Text("关闭") }
                }
            }
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