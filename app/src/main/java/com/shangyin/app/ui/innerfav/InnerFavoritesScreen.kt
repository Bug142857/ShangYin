package com.shangyin.app.ui.innerfav

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.shangyin.app.data.InnerFav
import com.shangyin.app.data.InnerFavorites
import com.shangyin.app.data.xsite.XSiteClient
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 里世界「点爱心」收藏页（吃瓜 / X站 / P站共用，按 module 区分）：
 * 点条目继续消费（吃瓜进详情 / X、P 站直接播放），长按弹确认删除。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun InnerFavoritesScreen(nav: NavHostController, module: String) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val allFavs by InnerFavorites.favs.collectAsStateWithLifecycle()
    val items = allFavs.filter { it.module == module }
    var pendingDelete by remember { mutableStateOf<InnerFav?>(null) }
    var resolving by remember { mutableStateOf(false) } // P站重放时的直链解析遮罩

    val title = when (module) {
        "melon" -> "吃瓜收藏"
        "xsite" -> "X站收藏"
        "psite" -> "P站收藏"
        else -> "收藏"
    }

    fun open(f: InnerFav) {
        when (module) {
            "melon" -> nav.safeNavigate("melonDetail/${Uri.encode(f.id)}")
            "xsite" -> {
                val url = f.videoUrl
                if (url.isNullOrBlank()) {
                    Toast.makeText(context, "该收藏缺少直链，无法播放", Toast.LENGTH_SHORT).show()
                    return
                }
                com.shangyin.app.ui.xsite.playPost(
                    nav,
                    XSiteClient.XPost(
                        urlCd = f.id, mediaId = f.mediaId, videoUrl = url,
                        thumbnail = f.cover, durationSec = f.durationSec,
                        favorite = 0, pv = 0, comments = 0,
                        account = f.subtitle.removePrefix("@"), tweetUrl = f.tweetUrl
                    )
                )
            }
            "psite" -> com.shangyin.app.ui.psite.playVideo(
                scope, nav, context,
                com.shangyin.app.data.psite.PsiteClient.PVideo(
                    vkey = f.id, title = f.title, thumbnail = f.cover,
                    durationSec = f.durationSec, views = "",
                    uploader = f.subtitle.substringBefore(" · ")
                )
            ) { resolving = it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$title（${items.size}）") },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        if (items.isEmpty()) {
            Box(
                Modifier.padding(pad).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                EmptyView("还没有收藏\n在模块列表卡片的爱心处点一下即可收藏")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.module + it.id }) { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .combinedClickable(
                                onClick = { open(f) },
                                onLongClick = { pendingDelete = f }
                            )
                            .padding(8.dp)
                    ) {
                        Box(
                            Modifier
                                .width(96.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            if (f.cover != null) {
                                AsyncImage(
                                    model = f.cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.align(Alignment.Center).size(24.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (f.subtitle.isNotBlank()) {
                                Text(
                                    f.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Text(
                                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    .format(Date(f.savedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Icon(
                            Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = Color(0xFFFF5A76),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // P站重放：解析直链遮罩
        if (resolving) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.30f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        "正在解析播放地址…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp)
                    )
                }
            }
        }
    }

    // 长按删除确认（项目约定：长按 + 确认，不放假常驻按钮）
    pendingDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("取消收藏") },
            text = { Text("确定取消收藏「${f.title}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    InnerFavorites.remove(f.module, f.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 爱心按钮共用的激活色 */
val FavHeartColor = Color(0xFFFF5A76)

/** 顶栏爱心（详情页用）：已收藏 = 实心红，未收藏 = 描边 */
@Composable
fun FavTopBarIcon(favModule: String, favId: String, build: () -> InnerFav) {
    val allFavs by InnerFavorites.favs.collectAsStateWithLifecycle()
    val active = allFavs.any { it.module == favModule && it.id == favId }
    IconButton(onClick = { InnerFavorites.toggle(build()) }) {
        Icon(
            if (active) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (active) "取消收藏" else "收藏",
            tint = if (active) FavHeartColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 封面上的爱心（列表卡片右上角）：小圆底保证任何封面上都可见 */
@Composable
fun FavHeartButton(
    favModule: String,
    favId: String,
    build: () -> InnerFav,
    modifier: Modifier = Modifier
) {
    val allFavs by InnerFavorites.favs.collectAsStateWithLifecycle()
    val active = allFavs.any { it.module == favModule && it.id == favId }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable { InnerFavorites.toggle(build()) }
    ) {
        Icon(
            if (active) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (active) "取消收藏" else "收藏",
            tint = if (active) FavHeartColor else Color.White,
            modifier = Modifier.size(17.dp)
        )
    }
}
