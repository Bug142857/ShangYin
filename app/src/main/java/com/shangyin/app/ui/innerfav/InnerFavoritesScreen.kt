package com.shangyin.app.ui.innerfav

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.InnerFav
import com.shangyin.app.data.InnerFavorites
import com.shangyin.app.data.xsite.XSiteClient
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.music.MusicMiniPlayer
import com.shangyin.app.ui.music.MusicPlayback
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 收藏排序模式：更新 = 按收藏时间倒序（默认）；歌手/作者 = 按副标题中文拼音序（同音乐清单） */
private enum class FavSortMode { ADDED, ARTIST }

/**
 * 里世界「点爱心」收藏页（吃瓜/X站/P站/番号/本子/漫画/电视/音乐共用，按 module 区分）。
 * 样式沿用音乐清单：标题 + 计数、「排序」行（更新/歌手 chips）、52dp 方形封面行 + 歌名式两行文本。
 * 点条目继续消费（详情/直接播放），长按弹确认删除。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun InnerFavoritesScreen(nav: NavHostController, module: String) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val allFavs by InnerFavorites.favs.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<InnerFav?>(null) }
    var resolving by remember { mutableStateOf(false) } // P站重放时的直链解析遮罩
    var sortMode by remember { mutableStateOf(FavSortMode.ADDED) }

    val title = when (module) {
        "melon" -> "吃瓜收藏"
        "xsite" -> "X站收藏"
        "psite" -> "P站收藏"
        "h1" -> "番号收藏"
        "h2" -> "本子收藏"
        "comic" -> "漫画收藏"
        "tv" -> "电视收藏"
        "music" -> "音乐收藏"
        else -> "收藏"
    }
    // 第二个排序 chip 的文案：音乐叫「歌手」，其余模块副标题是作者/UP主/片名，叫「作者」
    val artistLabel = if (module == "music") "歌手" else "作者"

    // 排序：更新 = 收藏时间倒序；歌手/作者 = 副标题排序（英文在前按字母、中文在后按拼音，同音乐清单算法）
    val items = remember(allFavs, sortMode) {
        val mine = allFavs.filter { it.module == module }
        when (sortMode) {
            FavSortMode.ADDED -> mine.sortedByDescending { it.savedAt }
            FavSortMode.ARTIST -> {
                val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
                mine.sortedWith(
                    compareBy(
                        { (it.subtitle.firstOrNull()?.code ?: Int.MAX_VALUE) >= 128 },
                        { collator.getCollationKey(it.subtitle.trim().lowercase()) }
                    )
                )
            }
        }
    }

    fun open(f: InnerFav) {
        when (module) {
            "melon" -> nav.safeNavigate("melonDetail/${Uri.encode(f.id)}")
            "h1" -> {
                // id = "srcId|vodId"：按片源重建条目后走公共播放流程（详情需现拉）
                val srcId = f.id.substringBefore("|")
                val vodId = f.id.substringAfter("|").toLongOrNull() ?: 0L
                val src = com.shangyin.app.ui.settings.SettingsStore.getVodSources()
                    .find { it.id == srcId }
                if (src == null) {
                    Toast.makeText(context, "片源已被删除，无法播放", Toast.LENGTH_SHORT).show()
                    return
                }
                scope.launch {
                    com.shangyin.app.ui.search.openVodAndPlay(
                        nav, context, src,
                        com.shangyin.app.data.vod.VodItem(
                            vod_id = vodId, vod_name = f.title, vod_pic = f.cover.orEmpty()
                        )
                    )
                }
            }
            "h2" -> nav.safeNavigate("bikaComic/${Uri.encode(f.id)}")
            "comic" -> nav.safeNavigate("comicDetail/${Uri.encode(f.id)}")
            "tv" -> {
                // id = 频道地址：M3U 直播流直接解析开播
                val url = f.videoUrl ?: f.id
                scope.launch {
                    com.shangyin.app.ui.live.openLiveAndPlay(
                        nav, context,
                        com.shangyin.app.data.live.LiveRoom(
                            platform = com.shangyin.app.data.live.LivePlatforms.CUSTOM,
                            roomId = url,
                            title = f.title,
                            streamer = f.subtitle.substringAfter(" · ")
                        )
                    )
                }
            }
            "music" -> {
                // payload = MusicSong JSON：还原后直接进播放队列（直链播放时现解析）
                val song = f.payload?.let { restoreSong(it) }
                if (song == null) {
                    Toast.makeText(context, "该收藏缺少歌曲信息，无法播放", Toast.LENGTH_SHORT).show()
                    return
                }
                MusicPlayback.init(context)
                MusicPlayback.play(context, listOf(song), 0)
            }
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${items.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.safePopBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        // 迷你播放条：在收藏页点歌后立即在本页底部出现（没在播时自隐藏为零高度）
        bottomBar = { MusicMiniPlayer(nav) }
    ) { pad ->
        if (items.isEmpty()) {
            Box(
                Modifier.padding(pad).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                EmptyView("还没有收藏\n在模块列表卡片的爱心处点一下即可收藏")
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                // 排序行：与音乐清单同款（「排序」标签 + 右侧 chips，常驻内容区顶部）
                item(key = "sort_row") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "排序",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        FilterChip(
                            selected = sortMode == FavSortMode.ADDED,
                            onClick = { sortMode = FavSortMode.ADDED },
                            label = { Text("更新") }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = sortMode == FavSortMode.ARTIST,
                            onClick = { sortMode = FavSortMode.ARTIST },
                            label = { Text(artistLabel) }
                        )
                    }
                }
                items(items, key = { it.module + it.id }) { f ->
                    // 音乐清单行样式：52dp 方形圆角封面 + 标题 + 副标题，整行可点、长按删除
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { open(f) },
                                onLongClick = { pendingDelete = f }
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        CoverImage(
                            url = f.cover,
                            placeholderText = f.title,
                            modifier = Modifier.size(52.dp)
                        )
                        Column(
                            Modifier.weight(1f).padding(start = 10.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                f.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                f.subtitle.ifBlank { title.removeSuffix("收藏") },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
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

/** payload JSON → MusicSong（音乐收藏重放用；解析失败返回 null 由调用方提示） */
private fun restoreSong(payload: String): com.shangyin.app.data.music.MusicSong? = runCatching {
    val o = JSONObject(payload)
    val platform = com.shangyin.app.data.music.MusicPlatform.of(o.optString("platform"))
        ?: return null
    val raw = o.optJSONObject("raw")?.let { ro ->
        val m = mutableMapOf<String, String>()
        ro.keys().forEach { k -> m[k] = ro.optString(k) }
        m
    } ?: emptyMap()
    com.shangyin.app.data.music.MusicSong(
        platform = platform,
        id = o.optString("id"),
        name = o.optString("name"),
        artists = o.optString("artists"),
        album = o.optString("album"),
        cover = o.optString("cover"),
        durationMs = o.optLong("durationMs", 0L),
        from = o.optString("from"),
        raw = raw
    )
}.getOrNull()

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
