package com.shangyin.app.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.R
import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.ListWithMeta
import com.shangyin.app.ui.common.EmptyView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val lists by Repo.observeListsWithMeta().collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { nav.navigate("search") }) {
                        Icon(Icons.Rounded.Search, contentDescription = "去搜索")
                    }
                    IconButton(onClick = { nav.navigate("settings") }) {
                        Icon(Icons.Rounded.Menu, contentDescription = "设置")
                    }
                }
            )
        }
    ) { pad ->
        if (lists.isEmpty()) {
            EmptyHomeContent(nav, Modifier.padding(pad).fillMaxSize())
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(pad).fillMaxSize()
            ) {
                items(lists, key = { it.list.id }) { meta ->
                    CategoryTile(meta) { nav.navigate("list/${meta.list.id}") }
                }
            }
        }
    }
}

/** 空主页 */
@Composable
private fun EmptyHomeContent(nav: NavHostController, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EmptyView("还没有分类\n去搜索收藏喜欢的，或到设置里创建分类")
        Row(
            modifier = Modifier.padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = { nav.navigate("settings") }) {
                Icon(Icons.Rounded.List, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(6.dp))
                Text("管理分类")
            }
            Button(onClick = { nav.navigate("search") }) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                Spacer(Modifier.height(6.dp))
                Text("搜索收藏")
            }
        }
    }
}

/** 分类方块：2x2 封面拼图 + 分类名 + 条目数 */
@Composable
private fun CategoryTile(meta: ListWithMeta, onClick: () -> Unit) {
    var covers by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(meta.list.id) {
        covers = withContext(Dispatchers.IO) { Repo.getListCovers(meta.list.id, 4) }
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (covers.isEmpty()) {
                    Icon(
                        Icons.Rounded.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    CoverCollage(covers.take(4))
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = meta.list.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${meta.itemCount} 件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** 封面拼贴 */
@Composable
private fun CoverCollage(covers: List<String>) {
    when (covers.size) {
        1 -> AsyncImage(covers[0])
        2 -> TwoCovers(covers[0], covers[1])
        3 -> ThreeCovers(covers[0], covers[1], covers[2])
        else -> FourCovers(covers[0], covers[1], covers[2], covers[3])
    }
}

@Composable
private fun AsyncImage(url: String) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun TwoCovers(u1: String, u2: String) {
    Row(Modifier.fillMaxSize()) {
        AsyncImage(u1, Modifier.weight(1f))
        Box(Modifier.weight(1f).fillMaxSize()) {
            AsyncImage(u2, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color(0x33000000)))
        }
    }
}

@Composable
private fun ThreeCovers(u1: String, u2: String, u3: String) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxSize()) {
            AsyncImage(u1, Modifier.weight(1f))
            AsyncImage(u2, Modifier.weight(1f))
        }
        AsyncImage(u3, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun FourCovers(u1: String, u2: String, u3: String, u4: String) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxSize()) {
            AsyncImage(u1, Modifier.weight(1f))
            AsyncImage(u2, Modifier.weight(1f))
        }
        Row(Modifier.weight(1f).fillMaxSize()) {
            AsyncImage(u3, Modifier.weight(1f))
            AsyncImage(u4, Modifier.weight(1f))
        }
    }
}

@Composable
private fun AsyncImage(url: String, modifier: Modifier = Modifier) {
    coil.compose.AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize()
    )
}
