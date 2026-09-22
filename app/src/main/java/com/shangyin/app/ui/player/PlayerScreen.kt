package com.shangyin.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
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
import com.shangyin.app.ui.live.resolveLive
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
 * 选集面板进程内记忆（按条目 id 区分）：
 * 面板收起重开、退出播放页再进，都能恢复升/降序与网格滚动位置，不用重新往下滑
 */
private object EpisodePanelMemory {
    var itemId: Long = Long.MIN_VALUE
    var sortDesc: Boolean = false
    var scrollIndex: Int = 0
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
    // 直播模式：不记忆进度、不显示选集/线路/倍速（由里世界「电视」模块进入）
    val isLive = PlayerSession.isLive
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
            // 直播防盗链：个别源要求带 Referer（进播放页前由 PlayerSession 传好；电视源通常为空）
            .setDefaultRequestProperties(PlayerSession.streamHeaders)
        val dsFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpFactory)
        // 起播优化：ExoPlayer 默认攒 2500ms 缓冲才开播，调到 1200ms 明显加快出画面；
        // 后续仍缓冲 30~60s 保证播放流畅，卡住再播阈值 3000ms
        // 直播的缓冲与点播分开：直播流可能"发一段就断"，
        // 缓冲给大一些才能把这一段吃掉、减少重连次数；同时起播别太慢
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ if (isLive) 10000 else 30000,
                /* maxBufferMs = */ if (isLive) 30000 else 60000,
                /* bufferForPlaybackMs = */ if (isLive) 1500 else 1200,
                /* bufferForRebufferMs = */ if (isLive) 3000 else 3000
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

    // 直播画质/线路（电视源同名频道的多条地址就是多档；只有一档时不显示菜单）
    var liveQualities by remember { mutableStateOf(PlayerSession.liveQualities) }
    var qualityIdx by remember {
        mutableIntStateOf(
            liveQualities.indexOfFirst { it.url == PlayerSession.groups.firstOrNull()?.episodes?.firstOrNull()?.url }
                .coerceAtLeast(0)
        )
    }
    var qualityMenuOpen by remember { mutableStateOf(false) }

    // 直播重连/提示（用户要「刷新」按钮；直播流可能断开，必须能自动续上）
    val liveRoom = remember { PlayerSession.liveRoom }
    var reconnecting by remember { mutableStateOf(false) }
    var liveHint by remember { mutableStateOf<String?>(null) }
    var reconnectAttempts by remember { mutableIntStateOf(0) }
    // 视频真实比例（竖屏直播流 h>w → 按比例撑满纵向屏幕，就是用户要的"纵向全屏"）
    var videoAspect by remember { mutableFloatStateOf(0f) }
    var scrubbingMs by remember { mutableStateOf<Long?>(null) } // 拖动进度时的时间气泡
    val barBound = remember { mutableStateOf(false) }        // TimeBar listener 只绑一次

    // 选集排序（升/降序）+ 网格滚动状态提升到页面级：面板收起/旋转不丢；
    // 同一部视频退出重进时从 EpisodePanelMemory 恢复
    val sameItem = EpisodePanelMemory.itemId == PlayerSession.itemId
    var sortDesc by remember { mutableStateOf(sameItem && EpisodePanelMemory.sortDesc) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    /** 切换排序后把当前集滚回可视位置（长剧倒序时直接看到最末几集） */
    fun toggleEpisodeSort() {
        val n = groups.getOrNull(groupIndex)?.episodes?.size ?: return
        if (n <= 0) return
        sortDesc = !sortDesc
        val disp = (if (sortDesc) n - 1 - currentEp else currentEp).coerceIn(0, n - 1)
        scope.launch { gridState.scrollToItem(disp) }
    }

    /** 全屏切换：点击时读设备实时方向（避免 remember 捕获过期值） */
    fun toggleFullscreen() {
        val act = activity ?: return
        val landscape = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        act.requestedOrientation = if (landscape)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /** 用新地址接着播（直播地址过期/断流都必须换新地址，不能复用旧的） */
    fun playLiveUrl(q: com.shangyin.app.data.live.LiveQuality) {
        val b = MediaItem.Builder().setUri(q.url)
        if (q.isHls || q.url.substringBefore('?').endsWith(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(b.build(), 0L)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * 重新解析并接着播：「刷新」按钮、切清晰度、断流自动重连都走这里。
     *
     * 为什么必须重新解析而不是复用地址：直播流断开后旧地址可能已失效，
     * 复用旧地址正是用户看到的「该线路网络连接失败」。
     */
    fun reloadLive(preferLabel: String? = null, reason: String? = null) {
        val room = liveRoom
        if (room == null) {
            liveQualities.getOrNull(qualityIdx)?.let { playLiveUrl(it) }
            return
        }
        scope.launch {
            reconnecting = true
            liveHint = reason
            val res = runCatching { resolveLive(room) }.getOrNull()
            val info = res?.info
            if (info == null) {
                liveHint = res?.error ?: "重新获取直播地址失败"
                reconnecting = false
                return@launch
            }
            // 重连只保证「用同一档继续播」：档位用这次解析出来的结果，不在重连里换线路
            // （电视的多线路由调用方通过 PlayerSession.liveQualities 传进来）
            val fresh = res.qualities.ifEmpty {
                listOf(com.shangyin.app.data.live.LiveQuality("默认", info.url, info.isHls))
            }
            val wantLabel = preferLabel ?: liveQualities.getOrNull(qualityIdx)?.label
            val idx = fresh.indexOfFirst { it.label == wantLabel }.let { if (it >= 0) it else 0 }
            liveQualities = fresh
            qualityIdx = idx
            // 地址变了，防盗链 Referer 也跟着刷新
            if (info.referer.isNotBlank()) {
                PlayerSession.streamHeaders = PlayerSession.streamHeaders + ("Referer" to info.referer)
            }
            playLiveUrl(fresh[idx])
        }
    }

    /** 切清晰度/线路：电视源自带多线路的直接换地址；其余重新解析（旧地址可能已失效） */
    fun switchQuality(idx: Int) {
        val q = liveQualities.getOrNull(idx) ?: return
        if (idx == qualityIdx) return
        qualityIdx = idx
        if (liveQualities.size > 1 && liveRoom?.platform == "custom") {
            playLiveUrl(q)
        } else {
            reloadLive(preferLabel = q.label, reason = "正在切换画质…")
        }
    }

    val playerView = remember {
        // 标准 media3 控制器布局（v2.11.4 自定义布局有闪退风险已回退）：
        // 公开 API 隐藏 上一集/下一集/快退5s/快进15s → 中间只剩播放/暂停，时间/进度条保留；
        // 设置齿轮运行时隐藏（菜单里的"立体声"音轨项无公开 API 移除），倍速改自建按钮
        PlayerView(context).apply {
            useController = true
            keepScreenOn = true  // 播放页保持屏幕常亮，防止系统超时息屏
            controllerShowTimeoutMs = 5000
            setShowPreviousButton(false)
            setShowNextButton(false)
            setShowRewindButton(false)
            setShowFastForwardButton(false)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
                controlsVisible = vis == android.view.View.VISIBLE
                // 每次控制器显隐都强制隐藏（幂等）：变暗遮罩 exo_controls_background + 设置齿轮 exo_settings。
                // 不能只在 AndroidView update 里绑一次——controller 是延迟 inflate 的，
                // 早期 findViewById 返回 null 会被 ?. 静默跳过（v2.11.6 遮罩没消失的原因）
                this@apply.findViewById<android.view.View>(media3R.id.exo_controls_background)?.visibility =
                    android.view.View.GONE
                this@apply.findViewById<android.view.View>(media3R.id.exo_settings)?.visibility =
                    android.view.View.GONE
                // 拖动进度时间气泡：controller 首次出现后绑定（此时必已 inflate）
                if (!barBound.value) {
                    this@apply.findViewById<DefaultTimeBar>(media3R.id.exo_progress)?.let { bar ->
                        barBound.value = true
                        bar.addListener(object : TimeBar.OnScrubListener {
                            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                                scrubbingMs = position
                            }

                            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                                scrubbingMs = position
                            }

                            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                                scrubbingMs = null
                            }
                        })
                    }
                }
            })
        }
    }

    // 进入播放页默认横屏全屏（画面最大化）。**直播也自动横屏**（用户明确要求），
    // 唯一的例外是**竖屏直播流**：那种要竖着看、并按视频比例撑满纵向屏幕。
    // 是不是竖屏流只能等第一帧画面尺寸出来才知道（宽高比 < 1 = 竖屏流），所以这里依赖 videoAspect 二次判断。
    LaunchedEffect(Unit, videoAspect) {
        val isPortraitStream = videoAspect > 0f && videoAspect < 1f
        val keepPortrait = isLive && isPortraitStream
        if (!keepPortrait && config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // 视频尺寸监听：竖屏直播流要按真实比例铺满高度，横屏/点播照旧
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                videoAspect = if (videoSize.height == 0) 0f
                else videoSize.width.toFloat() / videoSize.height.toFloat()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 直播流可能断开 → 自动重新解析续上，最多连续 5 次
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    reconnectAttempts = 0
                    reconnecting = false
                    liveHint = null
                }
                if (playbackState == androidx.media3.common.Player.STATE_ENDED &&
                    isLive && liveRoom != null && reconnectAttempts < 5
                ) {
                    reconnectAttempts++
                    reloadLive(reason = "直播流已断开，正在重连…（$reconnectAttempts/5）")
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (isLive && liveRoom != null && reconnectAttempts < 5) {
                    reconnectAttempts++
                    reloadLive(reason = "线路断了，正在重连…（$reconnectAttempts/5）")
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    fun saveProgress() {
        if (released) return
        if (isLive) return // 直播没有"看到哪儿"的概念，不落盘进度
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

    // 首次进入（或切线路）时定位选集网格：同一条目有记忆滚动位置则恢复，否则滚到当前集
    var scrollRestoredOnce by remember { mutableStateOf(false) }
    LaunchedEffect(groupIndex) {
        val n = groups.getOrNull(groupIndex)?.episodes?.size ?: return@LaunchedEffect
        if (n <= 0) return@LaunchedEffect
        if (!scrollRestoredOnce) {
            scrollRestoredOnce = true
            if (EpisodePanelMemory.itemId == PlayerSession.itemId &&
                EpisodePanelMemory.scrollIndex in 0 until n
            ) {
                gridState.scrollToItem(EpisodePanelMemory.scrollIndex)
                return@LaunchedEffect
            }
        }
        val disp = (if (sortDesc) n - 1 - currentEp else currentEp).coerceIn(0, n - 1)
        gridState.scrollToItem(disp)
    }

    // 滚动位置实时写入记忆（面板收起、退出播放页后重进可恢复）
    LaunchedEffect(gridState, sortDesc, groupIndex) {
        snapshotFlow { gridState.firstVisibleItemIndex }.collect { i ->
            EpisodePanelMemory.itemId = PlayerSession.itemId
            EpisodePanelMemory.sortDesc = sortDesc
            EpisodePanelMemory.scrollIndex = i
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
                // 视频区：横屏铺满全屏；竖屏 16:9 置顶；
                // **竖屏直播流（h>w）**按视频真实比例撑满纵向屏幕并居中（用户要的"纵向全屏"）
                val isPortraitStream = isLive && videoAspect > 0f && videoAspect < 1f
                Box(
                    Modifier
                        .then(
                            when {
                                isLandscape -> Modifier.fillMaxSize()
                                isPortraitStream -> Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(videoAspect)
                                    .align(Alignment.CenterHorizontally)
                                else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            }
                        )
                        .background(Color.Black)
                ) {
                    // 直播重连/刷新提示：浮在画面中央（控制器隐藏时也能看到，避免"卡住了却没提示"）
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isLive && liveHint != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text(
                            liveHint.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }

                    AndroidView(
                        factory = { playerView },
                        update = { pv -> pv.player = if (released) null else player },
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

                    // 右下角悬浮按钮：选集（横屏）+ 倍速（设置齿轮已隐藏，倍速自建）
                    androidx.compose.animation.AnimatedVisibility(
                        visible = controlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 2.dp, bottom = 2.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isLandscape && groups.isNotEmpty() && !isLive) {
                                TextButton(onClick = { panelOpen = !panelOpen }) {
                                    Text("选集", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            // 直播画质/线路（多档才显示）
                            if (isLive && liveQualities.size > 1) {
                                TextButton(onClick = { qualityMenuOpen = true }) {
                                    Text(
                                        "画质·" + liveQualities.getOrNull(qualityIdx)?.label.orEmpty(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            // 直播刷新：重新解析地址接着播（流断开时点一下最有效）
                            if (isLive) {
                                TextButton(onClick = {
                                    reconnectAttempts = 0
                                    reloadLive(reason = "正在刷新…")
                                }) {
                                    Text(
                                        if (reconnecting) "刷新中…" else "刷新",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            // 直播不提供倍速（直播流倍速没有意义）
                            if (!isLive) {
                                IconButton(
                                    onClick = { speedMenuOpen = true },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_speed),
                                        contentDescription = "倍速",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 倍速菜单（锚在右下角）
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

                    // 画质菜单（直播多档清晰度时；锚在右下角与倍速菜单一致）
                    DropdownMenu(
                        expanded = qualityMenuOpen,
                        onDismissRequest = { qualityMenuOpen = false },
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        liveQualities.forEachIndexed { i, q ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        q.label,
                                        color = if (i == qualityIdx) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    qualityMenuOpen = false
                                    switchQuality(i)
                                }
                            )
                        }
                    }

                    // 横屏右侧面板：标题 + 线路 + 选集网格（控制条右下角"选集"按钮触发；
                    // 面板底部抬高，不遮挡控制条；控制器隐藏时面板一起收起）
                    if (isLandscape && groups.isNotEmpty() && !isLive) {
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

                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "选集",
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.White.copy(alpha = 0.12f),
                                        onClick = { toggleEpisodeSort() },
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                if (sortDesc) Icons.Rounded.ArrowDownward
                                                else Icons.Rounded.ArrowUpward,
                                                contentDescription = if (sortDesc) "降序" else "升序",
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                if (sortDesc) "倒序" else "正序",
                                                color = Color.White.copy(alpha = 0.85f),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                // 集数网格（占满面板剩余高度，可滚动；支持升/降序）
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    state = gridState,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(epsR.size) { i ->
                                        val idx = if (sortDesc) epsR.size - 1 - i else i
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

                // 竖屏直播信息（直播没有选集/线路，只显示频道信息）
                if (!isLandscape && isLive) {
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
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOf(PlayerSession.subTitle, "直播中").filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        // 直播：画质切换（多档才显示）+ 刷新（重新解析地址接着播，卡住时点这里最有效）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (liveQualities.size > 1) {
                                TextButton(onClick = { qualityMenuOpen = true }) {
                                    Text("画质：" + liveQualities.getOrNull(qualityIdx)?.label.orEmpty())
                                }
                            }
                            TextButton(onClick = {
                                reconnectAttempts = 0
                                reloadLive(reason = "正在刷新…")
                            }) {
                                Text(if (reconnecting) "刷新中…" else "刷新")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "直播不支持拖动进度；若一直缓冲，先点「刷新」，再考虑切换线路或换个频道",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // 竖屏下方：标题 + 线路 + 集数网格（横屏隐藏）
                if (!isLandscape && !isLive) {
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

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "选集",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                onClick = { toggleEpisodeSort() },
                                modifier = Modifier.height(28.dp)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        if (sortDesc) Icons.Rounded.ArrowDownward
                                        else Icons.Rounded.ArrowUpward,
                                        contentDescription = if (sortDesc) "降序" else "升序",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        if (sortDesc) "倒序" else "正序",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // 集数网格（占满剩余空间，可滚动；支持升/降序）
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            state = gridState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(eps.size) { i ->
                                val idx = if (sortDesc) eps.size - 1 - i else i
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
