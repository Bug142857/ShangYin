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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.Category
import com.shangyin.app.data.Repo
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.data.douban.DoubanResult
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.DoubanRating
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var query by rememberSaveable { mutableStateOf("") }
    // 用单例缓存搜索结果，避免导航后丢失
    var results by remember { mutableStateOf(SearchCache.results) }
    var searching by remember { mutableStateOf(false) }
    var searched by rememberSaveable { mutableStateOf(SearchCache.searched) }

    val allItems by Repo.observeItems(null).collectAsStateWithLifecycle(initialValue = emptyList())
    val savedKeys = remember(allItems) { allItems.map { it.category to it.doubanId }.toSet() }

    // 添加到哪个分类
    var pendingAdd by remember { mutableStateOf<DoubanResult?>(null) }
    val lists by Repo.observeAllLists().collectAsStateWithLifecycle(initialValue = emptyList())

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            searching = true
            searched = true
            results = emptyList()
            try {
                val movieTv = async { runCatching { DoubanClient.search(Category.MOVIE, q) }.getOrDefault(emptyList()) }
                val tv = async { runCatching { DoubanClient.search(Category.TV, q) }.getOrDefault(emptyList()) }
                val book = async { runCatching { DoubanClient.search(Category.BOOK, q) }.getOrDefault(emptyList()) }
                val music = async { runCatching { DoubanClient.search(Category.MUSIC, q) }.getOrDefault(emptyList()) }
                val game = async { runCatching { DoubanClient.search(Category.GAME, q) }.getOrDefault(emptyList()) }
                val merged = (movieTv.await() + tv.await() + book.await() + music.await() + game.await())
                    .distinctBy { it.category.name + it.doubanId }
                results = merged.sortedWith(compareBy({ it.year.isBlank() }, { -(it.year.toIntOrNull() ?: 0) }))
            } catch (e: Exception) {
                Toast.makeText(context, "搜索出错：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                SearchCache.results = results
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

    fun importLink() {
        val text = clipboard.getText()?.toString()?.trim().orEmpty()
        val parsed = DoubanClient.parseDoubanUrl(text)
        if (parsed == null) {
            Toast.makeText(context, "剪贴板里没有可识别的链接", Toast.LENGTH_SHORT).show()
            return
        }
        val (cat, id) = parsed
        scope.launch {
            val itemId = runCatching {
                Repo.saveFromDouban(DoubanResult(cat, id, title = "豆瓣导入", url = text))
            }.getOrNull()
            if (itemId != null && itemId > 0) {
                Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
                nav.navigate("item/$itemId")
            } else {
                Toast.makeText(context, "导入失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { importLink() }) {
                        Text("链接导入")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("") },
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
            } else if (results.isEmpty()) {
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
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results, key = { it.category.name + it.doubanId }) { r ->
                        ResultRow(
                            r = r,
                            saved = (r.category.name to r.doubanId) in savedKeys,
                            onClick = {
                                scope.launch {
                                    runCatching { Repo.saveFromDouban(r) }
                                        .onSuccess { id ->
                                            if (id > 0) nav.navigate("item/$id")
                                            else Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { e ->
                                            Toast.makeText(context, "加载失败：${e.message}", Toast.LENGTH_LONG).show()
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
        if (lists.isEmpty()) {
            AlertDialog(
                onDismissRequest = { pendingAdd = null },
                title = { Text("还没有分类") },
                text = { Text("请先到设置里创建一个分类，才能把《${result.title}》添加进去。") },
                confirmButton = {
                    TextButton(onClick = { pendingAdd = null; nav.navigate("settings") }) {
                        Text("去创建分类")
                    }
                },
                dismissButton = { TextButton(onClick = { pendingAdd = null }) { Text("取消") } }
            )
        } else {
            var selectedId by remember(lists) { mutableStateOf(lists.first().id) }
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
    onClick: () -> Unit,
    onAdd: () -> Unit
) {
    Card(onClick = onClick) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                url = r.coverUrl,
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
    var searched: Boolean = false
}
