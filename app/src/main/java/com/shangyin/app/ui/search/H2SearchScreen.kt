package com.shangyin.app.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.shangyin.app.data.bika.BikaClient
import com.shangyin.app.data.bika.BikaCategory
import com.shangyin.app.data.bika.BikaComic
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.launch

/** 排序选项（哔咔 sort 参数）：dd=新到旧 da=旧到新 ld=最多喜欢 vd=最多观看 */
private val SORTS = listOf("新到旧" to "dd", "旧到新" to "da", "最多喜欢" to "ld", "最多观看" to "vd")

/**
 * H2 哔咔漫画搜索页（数据源来自 haka_comic 项目内置的哔咔 API，需哔咔账号登录）。
 * 未登录时显示登录面板；登录后可按分类浏览或关键词搜索，双列网格，点击进详情阅读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H2SearchScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var token by remember { mutableStateOf(SettingsStore.bikaToken) }

    if (token.isBlank()) {
        BikaLoginPanel(
            onLoggedIn = { t ->
                SettingsStore.bikaToken = t
                token = t
            }
        )
        return
    }

    var input by remember { mutableStateOf("") }
    var keyword by remember { mutableStateOf("") }                 // 已提交的搜索词
    var selectedCat by remember { mutableStateOf<String?>(null) }  // 分类标题，null=全部
    var sort by remember { mutableStateOf(SORTS[0].second) }
    var categories by remember { mutableStateOf<List<BikaCategory>>(emptyList()) }

    var items by remember { mutableStateOf<List<BikaComic>>(emptyList()) }
    var page by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val reqId = remember { mutableIntStateOf(0) } // 过期响应丢弃

    /** 登录失效处理：清 token 回登录面板 */
    fun handleAuthError() {
        SettingsStore.clearBikaToken()
        token = ""
        items = emptyList()
        Toast.makeText(context, "哔咔登录已失效，请重新登录", Toast.LENGTH_SHORT).show()
    }

    /** 加载一页：有关键词走搜索，否则走分类浏览（selectedCat=null 时为全站列表） */
    fun runLoad(q: String, cat: String?, pg: Int) {
        if (loading || loadingMore) return
        val my = ++reqId.value
        if (pg == 1) {
            keyword = q
            items = emptyList()
            noMore = false
            error = null
        }
        scope.launch {
            if (pg == 1) loading = true else loadingMore = true
            runCatching {
                if (q.isNotBlank()) {
                    BikaClient.searchComics(token, q, pg, sort)
                } else {
                    BikaClient.fetchComics(token, pg, cat, sort)
                }
            }.onSuccess { resp ->
                if (reqId.value != my) return@launch
                items = if (pg == 1) resp.docs else items + resp.docs
                page = resp.page
                total = resp.total
                noMore = resp.page >= resp.pages || resp.docs.isEmpty()
            }.onFailure { e ->
                if (reqId.value != my) return@launch
                if (e is BikaClient.BikaAuthException) {
                    handleAuthError()
                } else if (pg == 1) {
                    error = "加载失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (reqId.value == my) {
                loading = false
                loadingMore = false
            }
        }
    }

    fun reload() = runLoad(keyword, selectedCat, 1)

    fun doSearch() {
        val q = input.trim()
        keyboard?.hide()
        selectedCat = null
        runLoad(q, null, 1)
    }

    // 已登录时预加载分类列表
    LaunchedEffect(Unit) {
        runCatching { BikaClient.fetchCategories(token) }
            .onSuccess { categories = it }
            .onFailure { if (it is BikaClient.BikaAuthException) handleAuthError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("哔咔漫画") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 搜索框
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("搜索漫画关键词…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { doSearch() }) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索")
                }
            }
            // 分类 chips（全部 + 官方分类）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                val cats = listOf("全部") + categories.map { it.title }
                cats.forEach { c ->
                    FilterChip(
                        selected = (c == "全部" && selectedCat == null) || selectedCat == c,
                        onClick = {
                            val newCat = if (c == "全部") null else c
                            if (newCat != selectedCat) {
                                selectedCat = newCat
                                keyboard?.hide()
                                runLoad(input.trim().ifBlank { "" }, newCat, 1)
                            }
                        },
                        label = { Text(c, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            // 排序 chips
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Text(
                    "排序",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                SORTS.forEach { (label, value) ->
                    FilterChip(
                        selected = sort == value,
                        onClick = { sort = value; reload() },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            when {
                // 未发起任何浏览/搜索
                keyword.isBlank() && selectedCat == null && items.isEmpty() && !loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(32.dp)
                ) {
                    Text("选择分类浏览，或输入关键词搜索", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "数据来自哔咔漫画，共 ${total.coerceAtLeast(0)} 部可看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 首页加载中
                loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("加载中…", style = MaterialTheme.typography.bodySmall)
                }
                // 首页失败
                error != null && items.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(32.dp)
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { reload() }) { Text("重试") }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items.size) { i ->
                        val d = items[i]
                        ComicCard(d = d, onClick = {
                            nav.safeNavigate("bikaComic/" + java.net.URLEncoder.encode(d.id, "UTF-8"))
                        })
                    }
                    item(span = { GridItemSpan(2) }) {
                        when {
                            loadingMore -> Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("加载中…", style = MaterialTheme.typography.bodySmall)
                            }
                            noMore && items.isNotEmpty() -> Text(
                                "没有更多了",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                textAlign = TextAlign.Center
                            )
                            items.isNotEmpty() -> TextButton(
                                onClick = { runLoad(keyword, selectedCat, page + 1) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) { Text("加载更多") }
                        }
                    }
                }
            }
        }
    }
}

/** 登录面板：哔咔账号（邮箱 + 密码） */
@Composable
private fun BikaLoginPanel(onLoggedIn: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(32.dp)
    ) {
        Spacer(Modifier.weight(1f))
        Text("哔咔漫画", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "使用哔咔漫画账号登录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = { Text("邮箱") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!busy && email.isNotBlank() && password.isNotBlank()) {
                        scope.launch {
                            busy = true
                            error = null
                            runCatching { BikaClient.signIn(email.trim(), password) }
                                .onSuccess { onLoggedIn(it) }
                                .onFailure { error = "登录失败：${it.message ?: "网络错误"}" }
                            busy = false
                        }
                    }
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    runCatching { BikaClient.signIn(email.trim(), password) }
                        .onSuccess { onLoggedIn(it) }
                        .onFailure { error = "登录失败：${it.message ?: "网络错误"}" }
                    busy = false
                }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("登录")
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "需要哔咔漫画账号（可在哔咔官方 App 注册）\n登录信息仅保存在本机",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 漫画卡片：封面 + 标题 + 作者 + 喜欢数 */
@Composable
private fun ComicCard(d: BikaComic, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box {
            CoverImage(
                url = d.thumbUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                corner = 10.dp,
                onClick = onClick
            )
            if (d.finished) {
                Text(
                    "完结",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            d.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            buildString {
                append(d.author.ifBlank { "佚名" })
                if (d.likes > 0) append(" · ${d.likes} 赞")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
