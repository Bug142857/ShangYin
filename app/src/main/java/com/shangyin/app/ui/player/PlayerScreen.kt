package com.shangyin.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.navigation.NavHostController
import com.shangyin.app.R
import androidx.media3.ui.R as media3R
import com.shangyin.app.data.vod.VodClient
import com.shangyin.app.ui.settings.SettingsStore
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.delay

/** 从 Compose 的 context 逐层解包找 Activity（防止被 ContextThemeWrapper 包裹导致旋转/全屏失效） */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 毫秒 → h:mm:ss / mm:ss */
private fun fmt(ms: Long): String {
    if (ms <= 0) return "00:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s % 60)
    else String.format("%02d:%02d", m, s % 60)
}

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
    val activity = remember { context.findActivity() }
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

    val player = remember {
        // 采集站多数校验 UA 且常见 http↔https 302 跳转：
        // 带浏览器 UA + 允许跨协议重定向，修复部分线路（如 ukyun）直链 403 打不开的问题
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(VodClient.UA)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
        val dsFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        // 起播优化：ExoPlayer 默认攒 2500ms 缓冲才开播，调到 1200ms 明显加快出画面；
        // 后续仍缓冲 30~60s 保证播放流畅，卡住再播阈值 3000ms
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30000,
                /* maxBufferMs = */ 60000,
                /* bufferForPlaybackMs = */ 1200,
                /* bufferForRebufferMs = */ 3000
            )
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsFactory))
            .build()
    }
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    var controlsVisible by remember { mutableStateOf(true) } // 控制器显隐（media3 listener 同步）
    var panelOpen by remember { mutableStateOf(false) }      // 选集面板（控制条右下角按钮触发）
    var speedMenuOpen by remember { mutableStateOf(false) }  // 倍速菜单
    var speed by remember { mutableFloatStateOf(1f) }        // 当前倍速
    var scrubbingMs by remember { mutableStateOf<Long?>(null) } // 拖动进度时的时间气泡
    val barBound = remember { mutableStateOf(false) }        // TimeBar listener 只绑一次

    /** 全屏切换：点击时读设备实时方向（避免 remember 捕获过期值） */
    fun toggleFullscreen() {
        val act = activity ?: return
        val landscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        act.requestedOrientation = if (landscape)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    val playerView = remember {
        // 从 XML inflate（controller_layout_id 挂自定义控制条布局：保留 播放/暂停+时间+进度条，
        // 去掉上一集/下一集/快退/快进与设置齿轮（避免"立体声"音轨项），倍速/选集按钮代码绑定；
        // 拖动进度显示时间气泡（源站无缩略图数据，无法显示画面缩略窗）
        (android.view.LayoutInflater.from(context).inflate(R.layout.vod_player_view, null, false) as PlayerView)
            .apply {
                useController = true
                controllerShowTimeoutMs = 5000
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
                    controlsVisible = vis == android.view.View.VISIBLE
                })
            }
    }

    // 进入播放页默认横屏全屏（画面最大化），退出时在 onDispose 恢复竖屏
    LaunchedEffect(Unit) {
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
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

    // 换集同步（自动连播 / 控制器切集）+ 播放出错提示
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentEp = player.currentMediaItemIndex
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (released) return
                val reason = when (error.errorCode) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "资源被拒或已失效"
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "网络连接失败"
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                        "格式不支持"
                    else -> "播放出错"
                }
                Toast.makeText(context, "该线路 $reason，可切其他线路/集数", Toast.LENGTH_LONG).show()
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

    // 倍速应用
    LaunchedEffect(speed) {
        player.setPlaybackSpeed(speed)
    }

    // 选集面板打开时暂停控制器自动隐藏（防止面板被一起收走），关闭后恢复
    LaunchedEffect(panelOpen) {
        playerView.controllerShowTimeoutMs = if (panelOpen) Int.MAX_VALUE else 5000
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
                        update = { pv ->
                            pv.player = if (released) null else player
                            // 绑定自定义布局里的按钮（playerView 单实例，只绑一次防重复）
                            if (!barBound.value) {
                                barBound.value = true
                                pv.findViewById<TextView>(R.id.btn_episode)?.setOnClickListener {
                                    panelOpen = !panelOpen
                                }
                                pv.findViewById<TextView>(R.id.btn_speed)?.setOnClickListener {
                                    speedMenuOpen = true
                                }
                                // 拖动进度条时显示时间气泡（exo_progress 是 media3 库 id，
                                // nonTransitive R 下需引用库的 R 类）
                                pv.findViewById<DefaultTimeBar>(media3R.id.exo_progress)?.addListener(
                                    object : TimeBar.OnScrubListener {
                                        override fun onScrubStart(timeBar: TimeBar, position: Long) {
                                            scrubbingMs = position
                                        }

                                        override fun onScrubMove(timeBar: TimeBar, position: Long) {
                                            scrubbingMs = position
                                        }

                                        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                                            scrubbingMs = null
                                        }
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    // 顶部悬浮：返回（左）+ 全屏切换（右），跟随控制器显隐
                    androidx.compose.animation.AnimatedVisibility(
                        visible = controlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = {
                                    saveProgress()
                                    cleanup()
                                    nav.safePopBackStack()
                                },
                                modifier = Modifier
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
                            IconButton(
                                onClick = { toggleFullscreen() },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                            ) {
                                Icon(
                                    if (isLandscape) Icons.Rounded.FullscreenExit
                                    else Icons.Rounded.Fullscreen,
                                    contentDescription = if (isLandscape) "退出全屏" else "进入全屏",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // 拖动进度条时的时间气泡（源站无缩略图雪碧图数据，无法显示画面缩略窗）
                    scrubbingMs?.let { ms ->
                        Text(
                            fmt(ms),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 44.dp)
                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    // 倍速菜单（自定义布局无设置齿轮，倍速独立提供；点其他处关闭）
                    DropdownMenu(
                        expanded = speedMenuOpen,
                        onDismissRequest = { speedMenuOpen = false },
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { sp ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (sp == 1f) "1.0x（正常）" else "${sp}x",
                                        color = if (sp == speed) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    speed = sp
                                    speedMenuOpen = false
                                }
                            )
                        }
                    }

                    // 横屏右侧面板：标题 + 线路 + 选集网格（控制条右下角"选集"按钮触发；
                    // 面板底部抬高，不遮挡控制条；控制器隐藏时面板一起收起）
                    if (isLandscape && groups.isNotEmpty()) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = panelOpen && controlsVisible,
                            enter = fadeIn() + slideInHorizontally { it },
                            exit = fadeOut() + slideOutHorizontally { it },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(bottom = 40.dp)
                        ) {
                            val epsR = groups.getOrNull(groupIndex)?.episodes.orEmpty()
                            Column(
                                Modifier
                                    .fillMaxHeight()
                                    .width(300.dp)
                                    .background(Color.Black.copy(alpha = 0.72f))
                                    .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)
                            ) {
                                Text(
                                    PlayerSession.title,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "共 ${epsR.size} 集",
                                    color = Color.White.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(Modifier.height(12.dp))

                                // 线路（多线路才显示）
                                if (groups.size > 1) {
                                    Text(
                                        "线路",
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(groups.size) { gi ->
                                            val sel = gi == groupIndex
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (sel) MaterialTheme.colorScheme.primary
                                                else Color.White.copy(alpha = 0.12f),
                                                onClick = {
                                                    if (gi != groupIndex) {
                                                        saveProgress()
                                                        currentEp = currentEp.coerceIn(
                                                            0, (groups.getOrNull(gi)?.episodes?.size ?: 1) - 1
                                                        )
                                                        groupIndex = gi
                                                    }
                                                },
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier.padding(horizontal = 12.dp)
                                                ) {
                                                    Text(
                                                        groups[gi].name,
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                }

                                Text(
                                    "选集",
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(Modifier.height(6.dp))
                                // 集数网格（占满面板剩余高度，可滚动）
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(epsR.size) { idx ->
                                        val sel = idx == currentEp
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (sel) MaterialTheme.colorScheme.primary
                                            else Color.White.copy(alpha = 0.12f),
                                            onClick = {
                                                if (idx != currentEp) {
                                                    player.seekTo(idx, 0L)
                                                    player.playWhenReady = true
                                                    currentEp = idx
                                                }
                                            },
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    epsR[idx].name,
                                                    color = if (sel) MaterialTheme.colorScheme.onPrimary
                                                    else Color.White.copy(alpha = 0.85f),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
