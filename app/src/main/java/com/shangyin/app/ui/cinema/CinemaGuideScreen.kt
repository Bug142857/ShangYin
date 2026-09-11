package com.shangyin.app.ui.cinema

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 一家特效影厅 */
data class CinemaHall(
    val brand: String,      // 影城品牌，如"上海寰映影城"
    val branch: String,     // 分店，如"大融城店"
    val widthM: Double,     // 银幕宽（米）
    val heightM: Double,    // 银幕高（米）
    val areaSqm: Double,    // 银幕面积（m²）
    val href: String        // 站点详情页路径
)

/** 一个规格分组（如 IMAX Commercial Laser 二代激光） */
data class CinemaGroup(val name: String, val halls: List<CinemaHall>)

/**
 * 影厅指南：特效影厅（IMAX/CINITY/杜比等）按城市查询。
 * 数据来自 cinema.gaoliang.me（影迷社区维护）；非默认城市为客户端渲染，
 * 用离屏 WebView 渲染后注入 JS 提取 DOM 数据，结果磁盘缓存 24 小时。
 * 城市默认 IP 定位，可手动切换。
 */

// 内置支持城市（站点已收录的主要城市）
val CINEMA_CITIES = listOf(
    "北京", "上海", "广州", "深圳", "杭州", "南京", "苏州", "成都", "重庆",
    "武汉", "西安", "长沙", "郑州", "天津", "沈阳", "哈尔滨", "大连", "济南",
    "青岛", "合肥", "福州", "厦门", "昆明", "南宁", "贵阳", "南昌", "太原",
    "石家庄", "兰州", "乌鲁木齐", "宁波", "无锡", "佛山", "东莞", "温州", "常州"
)

private const val SITE = "https://cinema.gaoliang.me"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CinemaGuideScreen(nav: NavHostController) {
    val context = LocalContext.current
    var city by remember { mutableStateOf<String?>(null) }          // 当前城市（定位或手选）
    var showCityPicker by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<CinemaGroup>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var detailHall by remember { mutableStateOf<CinemaHall?>(null) }
    var reloadKey by remember { mutableStateOf(0) }                 // 手动刷新

    // 首次进入：IP 定位城市
    LaunchedEffect(Unit) {
        val located = withContext(Dispatchers.IO) { locateCityByIp() }
        city = located?.takeIf { it in CINEMA_CITIES } ?: "上海"
        locating = false
    }

    // 城市变化/刷新 → 加载该城市数据
    LaunchedEffect(city, reloadKey) {
        val c = city ?: return@LaunchedEffect
        if (locating) return@LaunchedEffect
        groups = null
        errorMsg = null
        loading = true
        // 1) 磁盘缓存（24h）
        val cached = withContext(Dispatchers.IO) { readCache(context, c) }
        if (cached != null) {
            groups = cached
            loading = false
            return@LaunchedEffect
        }
        // 2) 离屏 WebView 渲染提取（等待在 IO 线程，WebView 操作在主线程）
        val fetched = withContext(Dispatchers.IO) { fetchCityViaWebView(c) }
        loading = false
        if (fetched != null && fetched.sumOf { it.halls.size } > 0) {
            groups = fetched
            withContext(Dispatchers.IO) { writeCache(context, c, fetched) }
        } else {
            errorMsg = if (fetched == null) "数据加载失败——站点可能限流，请稍后重试"
            else "暂未收录「$c」的特效厅数据"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("影厅指南", fontWeight = FontWeight.Bold)
                        if (!locating) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.padding(start = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { showCityPicker = true }.padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.LocationCity, contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        city ?: "-",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(start = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                locating || loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
                    Text(
                        if (locating) "正在定位城市…" else "正在获取 $city 的影厅数据…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                errorMsg != null -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(errorMsg ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 14.dp)) {
                        OutlinedButton(onClick = { reloadKey++ }) { Text("重试") }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = { showCityPicker = true }) { Text("切换城市") }
                    }
                }
                groups != null -> CinemaList(groups ?: emptyList(), onHallClick = { detailHall = it })
            }
        }
    }

    // 城市选择
    if (showCityPicker) {
        AlertDialog(
            onDismissRequest = { showCityPicker = false },
            confirmButton = {
                TextButton(onClick = { showCityPicker = false }) { Text("关闭") }
            },
            title = { Text("选择城市") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CINEMA_CITIES.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { c ->
                                Text(
                                    c,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (c == city) FontWeight.Bold else FontWeight.Normal,
                                    color = if (c == city) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .clickable { city = c; showCityPicker = false }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    // 影厅详情
    detailHall?.let { hall ->
        AlertDialog(
            onDismissRequest = { detailHall = null },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE + hall.href)))
                    }
                }) {
                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("浏览器打开（3D 座位视角）")
                }
            },
            dismissButton = { TextButton(onClick = { detailHall = null }) { Text("关闭") } },
            title = { Text("${hall.brand} ${hall.branch}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("银幕宽度：${"%.2f".format(hall.widthM)} 米", style = MaterialTheme.typography.bodyMedium)
                    Text("银幕高度：${"%.2f".format(hall.heightM)} 米", style = MaterialTheme.typography.bodyMedium)
                    Text("银幕面积：${"%.1f".format(hall.areaSqm)} m²", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "画面比例约 1.${(hall.widthM / hall.heightM * 100).toInt()}:1",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "详细参数、地址与 3D 座位视角可在浏览器中查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

@Composable
private fun CinemaList(groups: List<CinemaGroup>, onHallClick: (CinemaHall) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        groups.forEach { g ->
            item(key = "h_${g.name}") {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${g.halls.size} 家",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(
                count = g.halls.size,
                key = { i -> "${g.name}_${g.halls[i].href}_$i" }
            ) { i ->
                val hall = g.halls[i]
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().clickable { onHallClick(hall) }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                hall.brand,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                hall.branch,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${"%.1f".format(hall.widthM)} m",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "${"%.0f".format(hall.areaSqm)} m²",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- IP 定位（免权限，链式兜底） ----------
private suspend fun locateCityByIp(): String? = withContext(Dispatchers.IO) {
    // 1) 腾讯
    runCatching {
        val body = httpGet("https://r.inews.qq.com/api/ip2city") ?: return@runCatching null
        val o = org.json.JSONObject(body)
        if (o.optInt("ret") == 0) o.optString("city").removeSuffix("市").takeIf { it.isNotBlank() } else null
    }.getOrNull()?.let { return@withContext it }
    // 2) ipip.net
    runCatching {
        val body = httpGet("https://myip.ipip.net/json") ?: return@runCatching null
        val arr = org.json.JSONObject(body).optJSONObject("data")?.optJSONArray("location") ?: return@runCatching null
        // ["中国","辽宁","大连","","联通"]
        arr.optString(2).removeSuffix("市").takeIf { it.isNotBlank() } ?: arr.optString(1)
    }.getOrNull()
}

private fun httpGet(url: String): String? = runCatching {
    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = (TimeUnit.SECONDS.toMillis(8)).toInt()
    conn.readTimeout = (TimeUnit.SECONDS.toMillis(8)).toInt()
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/124 Mobile")
    if (conn.responseCode != 200) return null
    conn.inputStream.bufferedReader().use { it.readText() }
}.getOrNull()

// ---------- 磁盘缓存（24h） ----------
private fun cacheFile(context: android.content.Context, city: String) =
    java.io.File(java.io.File(context.cacheDir, "cinema_guide").apply { mkdirs() }, "$city.json")

private fun readCache(context: android.content.Context, city: String): List<CinemaGroup>? = runCatching {
    val f = cacheFile(context, city)
    if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 24 * 3_600_000L) return null
    val groups = parseGroupsJson(f.readText())
    if (groups.isEmpty()) null else groups
}.getOrNull()

private fun writeCache(context: android.content.Context, city: String, groups: List<CinemaGroup>) = runCatching {
    val arr = JSONArray()
    groups.forEach { g ->
        val go = org.json.JSONObject()
        go.put("name", g.name)
        val ha = JSONArray()
        g.halls.forEach { h ->
            ha.put(org.json.JSONObject().apply {
                put("brand", h.brand); put("branch", h.branch)
                put("w", h.widthM); put("h", h.heightM); put("a", h.areaSqm)
                put("href", h.href)
            })
        }
        go.put("halls", ha)
        arr.put(go)
    }
    cacheFile(context, city).writeText(arr.toString())
}

private fun parseGroupsJson(json: String): List<CinemaGroup> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val go = arr.optJSONObject(i) ?: return@mapNotNull null
        val ha = go.optJSONArray("halls") ?: return@mapNotNull null
        CinemaGroup(
            go.optString("name"),
            (0 until ha.length()).mapNotNull { j ->
                val h = ha.optJSONObject(j) ?: return@mapNotNull null
                CinemaHall(
                    h.optString("brand"), h.optString("branch"),
                    h.optDouble("w"), h.optDouble("h"), h.optDouble("a"), h.optString("href")
                )
            }
        )
    }
}.getOrDefault(emptyList())

// ---------- 离屏 WebView 渲染提取（WebView 操作在主线程，await 在调用方 IO 线程） ----------
private fun fetchCityViaWebView(city: String): List<CinemaGroup>? {
    val url = "$SITE/city/${URLEncoder.encode(city, "UTF-8")}"
    var webView: WebView? = null
    var parsed: List<CinemaGroup>? = null
    try {
        val latch = java.util.concurrent.CountDownLatch(1)
        var resultJson: String? = null
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            runCatching {
                webView = WebView(android.app.Application())
                webView?.settings?.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    blockNetworkImage = true          // 不加载图片，提速
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                }
                webView?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        // 轮询提取：等 CSR 渲染出影厅卡片（最多 15 次 × 0.8s）
                        var tries = 0
                        val poll = object : Runnable {
                            override fun run() {
                                tries++
                                view.evaluateJavascript(EXTRACT_JS) { json ->
                                    val hasData = runCatching {
                                        val cleaned = json?.trim()?.removeSurrounding("\"")
                                            ?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: "[]"
                                        val arr = JSONArray(cleaned)
                                        var count = 0
                                        for (i in 0 until arr.length()) count += arr.optJSONObject(i)?.optJSONArray("halls")?.length() ?: 0
                                        count >= 3
                                    }.getOrDefault(false)
                                    if (hasData) {
                                        resultJson = json
                                        latch.countDown()
                                    } else if (tries < 15) {
                                        handler.postDelayed(this, 800)
                                    } else {
                                        latch.countDown()
                                    }
                                }
                            }
                        }
                        handler.postDelayed(poll, 800)
                    }
                }
                webView?.loadUrl(url)
            }.onFailure { latch.countDown() }
        }
        latch.await(30, TimeUnit.SECONDS)
        parsed = resultJson?.let { raw ->
            val cleaned = raw.trim().removeSurrounding("\"")
                .replace("\\\"", "\"").replace("\\\\", "\\")
            parseExtracted(cleaned)
        }
    } finally {
        webView?.let { w ->
            Handler(Looper.getMainLooper()).post { runCatching { w.destroy() } }
        }
    }
    return parsed
}

/** 注入站点页面的提取脚本：遍历规格标题 h3 与影厅链接，聚合成组 */
private const val EXTRACT_JS = """
(function() {
  var groups = [];
  var cur = null;
  var els = document.querySelectorAll('h3, h2, a[href*="/cinema/"]');
  for (var i = 0; i < els.length; i++) {
    var el = els[i];
    if (el.tagName === 'H3' || el.tagName === 'H2') {
      var t = (el.textContent || '').trim();
      if (t && t.length < 40) { cur = { name: t, halls: [] }; groups.push(cur); }
    } else if (cur) {
      var href = el.getAttribute('href') || '';
      if (href.indexOf('/cinema/') !== 0) continue;
      var txt = (el.textContent || '').trim();
      if (!txt) continue;
      var found = null;
      for (var j = 0; j < cur.halls.length; j++) if (cur.halls[j].href === href) { found = cur.halls[j]; break; }
      if (!found) { found = { href: href, texts: [] }; cur.halls.push(found); }
      found.texts.push(txt);
    }
  }
  return JSON.stringify(groups);
})();
"""

/** 解析提取脚本回传的 JSON → 分组列表 */
private fun parseExtracted(json: String): List<CinemaGroup> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val go = arr.optJSONObject(i) ?: return@mapNotNull null
        val hallsArr = go.optJSONArray("halls") ?: return@mapNotNull null
        val halls = (0 until hallsArr.length()).mapNotNull { j ->
            val ho = hallsArr.optJSONObject(j) ?: return@mapNotNull null
            val texts = (0 until (ho.optJSONArray("texts")?.length() ?: 0))
                .mapNotNull { ho.optJSONArray("texts")?.optString(it) }
            // 文本序列：[宽 m][面积m²][品牌][分店][高 m]（部分可能缺失）
            val nums = texts.filter { it.matches(Regex("[\\d.]+\\s*(m|m²|m2)")) }
            if (nums.size < 2) return@mapNotNull null
            val w = nums[0].replace("m", "").trim().toDoubleOrNull() ?: return@mapNotNull null
            val area = nums.getOrNull(1)?.replace("m²", "")?.replace("m2", "")?.trim()?.toDoubleOrNull() ?: 0.0
            // 高是最后一个"m"（不含 m²）
            val h = nums.lastOrNull { it.endsWith("m") && !it.endsWith("m²") }
                ?.replace("m", "")?.trim()?.toDoubleOrNull() ?: 0.0
            val names = texts.filter { !it.matches(Regex("[\\d.]+\\s*(m|m²|m2)")) }
            val brand = names.getOrNull(0) ?: ""
            val branch = names.getOrNull(1) ?: ""
            if (brand.isBlank()) return@mapNotNull null
            CinemaHall(brand, branch, w, h, area, ho.optString("href"))
        }.sortedByDescending { it.widthM }
        if (halls.isEmpty()) null else CinemaGroup(go.optString("name"), halls)
    }
}.getOrDefault(emptyList())
