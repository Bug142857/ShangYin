package com.shangyin.app.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.shangyin.app.ui.settings.SettingsStore
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.delay

/**
 * 在线观影播放页：ExoPlayer 播 HLS。
 * - 剧集作为播放列表喂给 ExoPlayer（自动连播，控制器自带上一/下一集）
 * - 竖屏：视频 16:9 在顶部 + 下方信息面板（标题/线路/集数网格）
 * - 横屏：沉浸全屏（Activity 配置了 configChanges，旋转不重建，组合保持）
 * - 退出/返回/退到后台都会停止或暂停播放，防止后台继续运行
 */
@Composable
fun PlayerScreen(nav: NavHostController) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val config = LocalConfiguration.current
    val view = LocalView.current

    val groups = PlayerSession.groups
    var groupIndex by remember {
        mutableIntStateOf(PlayerSession.groupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
    }
    var currentEp by remember {
        mutableIntStateOf(
            PlayerSession.startIndex.coerceIn(0, (groups.firstOrNull()?.episodes?.size ?: 1) - 1).coerceAtLeast(0)
        )
    }
    var firstLoad by remember { mutableStateOf(true) } // 首次加载（带续播位置）
    var released by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(context).build() }
    val playerView = remember {
        PlayerView(context).apply {
            useController = true
        }
    }
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun toggleFullscreen() {
        activity?.requestedOrientation = if (isLandscape)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    fun saveProgress() {
        if (released) return
        val eps = groups.getOrNull(groupIndex)?.episodes ?: return
        val ep = eps.getOrNull(player.currentMediaItemIndex) ?: return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0L) return
        if (pos in 1 until (dur - 5000)) {
            SettingsStore.saveVodProgress(SettingsStore.vodProgressKey(PlayerSession.itemId, ep.url), pos, dur)
        } else if (pos >= dur - 5000) {
            SettingsStore.clearVodProgress(SettingsStore.vodProgressKey(PlayerSession.itemId, ep.url))
        }
    }

    /** 彻底停止并释放播放器（返回/离开页面时调用，防止后台继续播放） */
    fun cleanup() {
        if (released) return
        released = true
        runCatching { playerView.player = null }
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    // 剧集列表（每次加载线路时重建播放列表）
    LaunchedEffect(groupIndex) {
        if (released) return@LaunchedEffect
        val eps = groups.getOrNull(groupIndex)?.episodes ?: return@LaunchedEffect
        if (eps.isEmpty()) return@LaunchedEffect
        playerView.player = player
        val items = eps.map { ep ->
            val b = MediaItem.Builder().setUri(ep.url)
            if (ep.url.substringBefore('?').endsWith(".m3u8")) {
                b.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            b.build()
        }
        val startIdx = currentEp.coerceIn(0, eps.size - 1)
        val startPos = if (firstLoad) PlayerSession.startPosMs else 0L
        firstLoad = false
        player.setMediaItems(items, startIdx, startPos)
        player.prepare()
        player.playWhenReady = true
        currentEp = startIdx
        if (startPos > 0L) Toast.makeText(context, "已从上次位置继续播放", Toast.LENGTH_SHORT).show()
    }

    // 换集同步（自动连播 / 控制器切集）
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentEp = player.currentMediaItemIndex
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 每 5 秒落盘进度
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.isPlaying) saveProgress()
        }
    }

    // 退到后台：暂停并保存（防止后台继续出声）；离开页面：彻底释放
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                saveProgress()
                runCatching { player.pause() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            saveProgress()
            cleanup()
        }
    }

    // 横屏沉浸：隐藏系统栏；退出时恢复
    LaunchedEffect(isLandscape) {
        val window = activity?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        if (isLandscape) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler(enabled = true) {
        saveProgress()
        cleanup()
        nav.safePopBackStack()
    }

    Box(Modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("没有可播放的剧集", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    "返回后换个线路试试",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // 视频区：横屏铺满全屏，竖屏 16:9 置顶
                Box(
                    Modifier
                        .then(
                            if (isLandscape) Modifier.fillMaxSize()
                            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { playerView },
                        update = { it.player = if (released) null else player },
                        modifier = Modifier.fillMaxSize()
                    )
                    // 返回按钮悬浮
                    IconButton(
                        onClick = {
                            saveProgress()
                            cleanup()
                            nav.safePopBackStack()
                        },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 竖屏下方：标题 + 线路 + 集数网格（横屏隐藏）
                if (!isLandscape) {
                    val eps = groups.getOrNull(groupIndex)?.episodes.orEmpty()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            PlayerSession.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "来源：${groups.getOrNull(groupIndex)?.name.orEmpty()} · 共 ${eps.size} 集",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))

                        // 线路切换（多线路才显示）
                        if (groups.size > 1) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(groups.size) { gi ->
                                    FilterChip(
                                        selected = gi == groupIndex,
                                        onClick = {
                                            if (gi != groupIndex) {
                                                saveProgress()
                                                currentEp = currentEp.coerceIn(
                                                    0, (groups.getOrNull(gi)?.episodes?.size ?: 1) - 1
                                                )
                                                groupIndex = gi
                                            }
                                        },
                                        label = { Text(groups[gi].name) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        Text(
                            "选集",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        // 集数网格（占满剩余空间，可滚动）
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(eps.size) { idx ->
                                val ep = eps[idx]
                                val selected = idx == currentEp
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    onClick = {
                                        if (idx != currentEp) {
                                            player.seekTo(idx, 0L)
                                            player.playWhenReady = true
                                            currentEp = idx
                                        }
                                    },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            ep.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
