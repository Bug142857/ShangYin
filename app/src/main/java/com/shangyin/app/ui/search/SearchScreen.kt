package com.shangyin.app.ui.search

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.shangyin.app.data.Category
import com.shangyin.app.data.Repo
import com.shangyin.app.data.douban.DoubanCelebrity
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.data.douban.DoubanResult
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.DoubanRating
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch

/** 记住上次选择的清单 ID（跨对话框、跨搜索保持） */
private var lastSelectedListId: Long = -1L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavHostController, targetListId: Long = -1L) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var query by rememberSaveable { mutableStateOf("") }
    // 用单例缓存搜索结果，避免导航后丢失
    var results by remember { mutableStateOf(SearchCache.results) }
    var celebrityResults by remember { mutableStateOf(SearchCache.celebrities) }
    var searching by remember { mutableStateOf(false) }
    var searched by rememberSaveable { mutableStateOf(SearchCache.searched) }
    // 搜索错误信息：失败时显示在结果区域，让用户看到具体原因（不只 Toast）
    var searchError by remember { mutableStateOf<String?>(null) }
    // 分类筛选：必须先选分类才能搜索（影视/图书/游戏/人物），防止结果互相干扰
    // 默认选中「影视」（最常用），用户可切换
    var selectedCat by rememberSaveable { mutableStateOf("影视") }
    // 防频繁点击：导航中禁用所有点击
    var navigating by remember { mutableStateOf(false) }

    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val savedKeys = remember(allItems) { allItems.map { it.category to it.doubanId }.toSet() }

    // 添加到哪个分类
    var pendingAdd by remember { mutableStateOf<DoubanResult?>(null) }
    val lists by Repo.observeAllLists().collectAsStateWithLifecycle(initialValue = emptyList())

    // 返回时重置 navigating 状态
    val navBackStackEntry by nav.currentBackStackEntryAsState()
    LaunchedEffect(navBackStackEntry) {
        navigating = false
    }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        if (selectedCat.isEmpty()) {
            Toast.makeText(context, "请先选择要搜索的分类", Toast.LENGTH_SHORT).show()
            return
        }
        keyboard?.hide()  // 搜索后自动收起键盘
        query = ""  // 搜索后自动清空输入框
        scope.launch {
            searching = true
            searched = true
            searchError = null  // 清空上一次的错误
            results = emptyList()
            celebrityResults = emptyList()
            try {
                if (selectedCat == "人物") {
                    // 失败抛 IOException 由这里捕获，提示用户网络问题，而不是静默返回空
                    celebrityResults = DoubanClient.searchCelebrities(q)
                } else {
                    val list = if (selectedCat == "影视") {
                        // 影视 = 电影 + 电视剧，任一失败都提示用户
                        val movie = DoubanClient.search(Category.MOVIE, q)
                        val tv = DoubanClient.search(Category.TV, q)
                        movie + tv
                    } else {
                        val cat = Category.values().firstOrNull { it.label == selectedCat }
                        if (cat != null) DoubanClient.search(cat, q) else emptyList()
                    }
                    results = list.distinctBy { it.category.name + it.doubanId }
                }
            } catch (e: Exception) {
                // 错误信息同时通过 Toast（短暂提示）和结果区域文字（持久显示）显示
                // 这样用户不会因为 Toast 弹一下没看到而以为"搜不到"
                val msg = "搜索失败：${e.message ?: e.javaClass.simpleName}"
                searchError = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } finally {
                SearchCache.results = results
                SearchCache.celebrities = celebrityResults
                SearchCache.searched = true
                searching = false
            }
        }
    }

    fun addToList(r: DoubanResult, listId: Long) {
        scope.launch {
            runCatching { Repo.saveFromDouban(r) }
                .onSuccess { itemId ->
                    if (itemId > 0) {
                        Repo.addItemToList(listId, itemId)
                        Toast.makeText(context, "已添加《${r.title}》", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "添加失败，请稍后再试", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { e ->
                    Toast.makeText(context, "网络错误：${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (targetListId > 0) "搜索添加" else "搜索") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 分类筛选（必选）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                listOf("影视", "图书", "游戏", "人物").forEach { label ->
                    FilterChip(
                        selected = selectedCat == label,
                        onClick = { selectedCat = if (selectedCat == label) "" else label },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(if (selectedCat.isEmpty()) "先选分类，再输入关键词" else "在${selectedCat}中搜索…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { doSearch() }) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索")
                }
            }

            if (searching) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            } else if (searchError != null) {
                // 搜索失败：把具体错误信息持久显示在结果区域，让用户看到原因
                // （Toast 弹一下容易错过，红色文字会一直显示直到下次搜索）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 40.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        searchError!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "可尝试：1) 等几秒再搜 2) 换更精确的关键词 3) 在设置里配置豆瓣登录 Cookie",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (results.isEmpty() && celebrityResults.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 40.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (searched) "没有找到相关内容" else "输入关键词，把喜欢的一切收进来",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (searched) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "豆瓣搜索接口可能未收录该条目，可尝试更精确的名字（如剧场版用全名）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 人物搜索结果（无添加按钮，点击直接进影人详情）
                    if (celebrityResults.isNotEmpty()) {
                        item(key = "celeb_header") {
                            Text(
                                "相关人物",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(celebrityResults, key = { "celeb_${it.id}" }) { c ->
                            CelebrityResultRow(c) {
                                if (!navigating) {
                                    navigating = true
                                    // 路由必须四段齐全：celebrity/{id}/{fromCategory}/{name}/{avatar}
                                    val encName = java.net.URLEncoder.encode(c.name, "UTF-8")
                                    val encAvatar = java.net.URLEncoder.encode(c.avatarUrl.orEmpty(), "UTF-8")
                                    nav.safeNavigate("celebrity/${c.id}/film/$encName/$encAvatar")
                                }
                            }
                        }
                        if (results.isNotEmpty()) {
                            item(key = "celeb_divider") {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "相关作品",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                    items(results, key = { it.category.name + it.doubanId }) { r ->
                        ResultRow(
                            r = r,
                            saved = (r.category.name to r.doubanId) in savedKeys,
                            enabled = !navigating,
                            onClick = {
                                if (!navigating) {
                                    navigating = true
                                    // 先快速落库立即跳转（不等详情网络请求），详情页打开后自动补全
                                    scope.launch {
                                        val id = runCatching { Repo.saveFromDoubanFast(r) }.getOrDefault(-1L)
                                        if (id > 0) nav.safeNavigate("item/$id")
                                        else Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                                        navigating = false
                                    }
                                }
                            },
                            onAdd = { pendingAdd = r }
                        )
                    }
                }
            }
        }
    }

    // 添加时选分类
    pendingAdd?.let { result ->
        if (targetListId > 0) {
            // 从清单内进入搜索：直接添加到目标清单，不弹选择框
            LaunchedEffect(result) {
                addToList(result, targetListId)
                pendingAdd = null
            }
        } else if (lists.isEmpty()) {
            AlertDialog(
                onDismissRequest = { pendingAdd = null },
                title = { Text("还没有分类") },
                text = { Text("请先到设置里创建一个分类，才能把《${result.title}》添加进去。") },
                confirmButton = {
                    TextButton(onClick = { pendingAdd = null; nav.safeNavigate("settings") }) {
                        Text("去创建分类")
                    }
                },
                dismissButton = { TextButton(onClick = { pendingAdd = null }) { Text("取消") } }
            )
        } else {
            var selectedId by remember(lists) {
                mutableStateOf(lists.firstOrNull { it.id == lastSelectedListId }?.id ?: lists.first().id)
            }
            AlertDialog(
                onDismissRequest = { pendingAdd = null },
                title = { Text("添加到分类") },
                text = {
                    Column {
                        Text(
                            "把《${result.title}》放到哪个分类？",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(lists) { l ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedId == l.id,
                                        onClick = { selectedId = l.id }
                                    )
                                    Text(l.name, modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        lastSelectedListId = selectedId
                        addToList(result, selectedId)
                        pendingAdd = null
                    }) { Text("添加") }
                },
                dismissButton = { TextButton(onClick = { pendingAdd = null }) { Text("取消") } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultRow(
    r: DoubanResult,
    saved: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onAdd: () -> Unit
) {
    Card(onClick = onClick, enabled = enabled) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                url = r.coverUrl,
                onClick = onClick,
                modifier = Modifier.width(56.dp).height(78.dp)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    r.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (r.subTitle.isNotBlank()) {
                    Text(
                        r.subTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.year.isNotBlank()) {
                        Text(
                            r.year,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        r.category.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    DoubanRating(r.rating)
                }
            }
            if (saved) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = "已收藏",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                IconButton(onClick = onAdd) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加到分类")
                }
            }
        }
    }
}

/** 搜索结果缓存，防止导航后丢失 */
private object SearchCache {
    var results: List<DoubanResult> = emptyList()
    var celebrities: List<DoubanCelebrity> = emptyList()
    var searched: Boolean = false
}

/** 人物搜索结果行（无添加按钮，点击进影人详情） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CelebrityResultRow(
    c: DoubanCelebrity,
    onClick: () -> Unit
) {
    Card(onClick = onClick) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                url = c.avatarUrl,
                onClick = onClick,
                modifier = Modifier.size(56.dp),
                corner = 28.dp
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    c.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (c.role.isNotBlank()) {
                    Text(
                        c.role,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "人物",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
