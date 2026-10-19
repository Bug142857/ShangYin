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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
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
    // 下载确认弹窗（v0.225）：title to url；null = 不显示
    var pendingDownload by remember { mutableStateOf<Pair<String, String>?>(null) }
    // v0.254：播放页实时下载进度角标——用户点下载后留在播放页，眼睛不在这，
    // 「后台进度条没动」的感知一半来自这里没有可见反馈。订阅同一 StateFlow，
    // 每次变化打观察日志（UI 侧是否真收到更新，下轮诊断一步定性）
    val dlTasks by com.shangyin.app.data.download.VideoDownloadManager.active.collectAsState()
    LaunchedEffect(dlTasks) {
        val t = dlTasks.values.firstOrNull() ?: return@LaunchedEffect
        com.shangyin.app.data.melon.MelonFlightLog.log(
            "DOWNLOADUI", "播放页观察 ${t.title.take(20)} ${(t.progress * 100).toInt()}% " +
                "${"%.1f".format(t.bytesCopied / 1048576f)}MB err=${t.error?.take(20) ?: "无"}"
        )
    }
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
        // v0.230：数据源从 DefaultHttpDataSource 换 OkHttpDataSource。
        // 「有的视频点未缓冲位置就卡住」与「部分视频下载失败」同根因：其切片域名的
        // 系统 DNS 解析到的 CDN 节点在本运营商网内不可达（个别坏节点）。ExoPlayer 内建
        // HTTP 栈无法接自定义 DNS；okhttp 栈 + GeneralDohDns（系统+DoH 合并解析）让
        // 坏节点被逐 IP 试连自动跳过。采集站校验 UA 的 http↔https 跳转 okhttp 默认跟随。
        val okhttpClient = okhttp3.OkHttpClient.Builder()
            .dns(com.shangyin.app.data.melon.GeneralDohDns)
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            // v0.250：15s→10s——CDN 有间歇超时切片（0.249 探针片20 SocketTimeout），
            // 快速失败重试好过死等；media3 重试耗尽进 error 态由看门狗自愈接管
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            // 直播防盗链 / 站点 Referer：进播放页前由 PlayerSession 传好（每请求动态读，
            // 本地播放为空表），UA 固定浏览器指纹
            .addInterceptor { chain ->
                val b = chain.request().newBuilder().header("User-Agent", VodClient.UA)
                PlayerSession.streamHeaders.forEach { (k, v) ->
                    if (k.isNotBlank() && v.isNotBlank()) b.header(k, v)
                }
                chain.proceed(b.build())
            }
            .build()
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okhttpClient)
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
            // FFmpeg 软解兜底：电视源里常见的 MP2（audio/mpeg-L2）多数手机没有系统解码器
            // （音轨会被静默丢掉 → 有画面没声音）。EXTENSION_RENDERER_MODE_ON = 只在平台
            // 渲染器不支持时才用 FFmpeg，AAC/MP3 等照旧走硬件/平台解码，不影响性能。
            .setRenderersFactory(
                io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(context)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(
                        androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    )
            )
            .setLoadControl(loadControl)
            // seek 参数（v0.249 回滚到 EXACT）：v0.224 换 CLOSEST_SYNC 的前提是「切片加载
            // 稍慢就卡」，0.248 探针实测 CDN 单切片仅 15-34ms，前提已不成立；且 CLOSEST_SYNC
            // 是「画面定住但缓冲在走」的头号嫌疑——跳到切片中间最近关键帧，media3 对加密 TS
            // 的 seek-to-sync 处理不如 EXACT 路径标准（EXACT 从关键帧解码到目标位，时间戳
            // 偏移处理成熟）。25s 自愈阈值已兜底加载慢的场景
            .setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
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
    // 音频编码本机不支持（电视源里常见的 MP2）只提示一次，别反复弹
    var unsupportedAudioHinted by remember { mutableStateOf(false) }
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
        // v0.254：本地镜像修复「点进度条必卡」——站点 m3u8 缺 ENDLIST 被当直播流
        // （黑匣子实锤 dur≈32天），进度条映射到天文数字，seek 落点必然在真实内容之外。
        // 后台镜像全部切片+密钥本地化+补 ENDLIST，就绪后无缝切换（保持位置）。
        // 仅单集视频且非直播时启用；镜像失败保持原流（看门狗自愈兜底）
        if (!isLive && eps.size == 1 && eps[0].url.substringBefore('?').endsWith(".m3u8")) {
            val epUrl = eps[0].url
            scope.launch {
                val mirror = runCatching {
                    com.shangyin.app.data.melon.MelonVodMirror.prepare(context, epUrl)
                }.getOrNull() ?: return@launch
                // 还在这集且没离开页面才切换（自动连播/返回后镜像就绪则丢弃）
                if (released) return@launch
                val sameEp = groups.getOrNull(groupIndex)?.episodes?.firstOrNull()?.url == epUrl &&
                    player.currentMediaItemIndex == startIdx
                if (!sameEp) return@launch
                val pos = run {
                    // v0.254：钳制——直播化时间轴上被 seek 过的位置可达 28 亿 ms（dur 实锤），
                    // 直接带入本地 VOD 会越界立刻 ENDED；超出镜像真实时长一律回片头
                    val d = com.shangyin.app.data.melon.MelonVodMirror.durationOf(epUrl)
                    val cur = player.currentPosition.coerceAtLeast(0)
                    if (d > 0 && cur >= d) 0L else cur
                }
                com.shangyin.app.data.melon.MelonFlightLog.log(
                    "MIRROR", "切换本地播放 pos=$pos"
                )
                val local = MediaItem.Builder()
                    .setUri(android.net.Uri.fromFile(java.io.File(mirror, "index.m3u8")))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                player.setMediaItem(local, pos)
                player.prepare()
                // playWhenReady 保持原状（切换瞬间不打断播放意图）
            }
        }
    }

    // 换集同步（自动连播 / 控制器切集）+ 播放出错提示
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentEp = player.currentMediaItemIndex
            }

            /**
             * 音频编码本机不支持时（典型：IPTV 里的 MP2 = `audio/mpeg-L2`），
             * 系统会把整条音轨**静默丢掉** → 画面正常、完全没声音、也不报错。
             * 这里把它明说出来，别让用户以为"这频道本来就这样"。
             */
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (released || unsupportedAudioHinted) return
                val dropped = tracks.groups.any { g ->
                    g.type == C.TRACK_TYPE_AUDIO && !g.isSelected &&
                        (0 until g.length).any { i ->
                            val mime = g.getTrackFormat(i).sampleMimeType
                            mime == MimeTypes.AUDIO_MPEG_L1 || mime == MimeTypes.AUDIO_MPEG_L2
                        }
                }
                if (dropped) {
                    unsupportedAudioHinted = true
                    Toast.makeText(
                        context,
                        "该频道音频是 MP2，本机解码器不支持：会有画面没声音，建议换线路或换源",
                        Toast.LENGTH_LONG
                    ).show()
                }
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
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                        "本机解不了这个编码"
                    androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
                        "音频输出失败"
                    // 兜底带上错误码，方便定位（别只说"播放出错"）
                    else -> "播放出错（${error.errorCodeName}）"
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

    // seek 缓冲自愈（v0.226）：点进度条跳到未缓冲区后，慢源/签名切片地址下重新拉切片
    // 可能长时间卡在 STATE_BUFFERING（TS 加密流尤其如此，用户实测"选进度后画面卡住"）。
    // 缓冲超 10s 自动原地重载。⚠️ v0.225 教训：seekTo(当前精确位置) 是 no-op——media3
    // 认为目标位置就是当前位置，复用现有请求不重新加载，卡死照旧。必须 stop()+prepare()
    // 强制丢弃已加载的 MediaPeriod 重新拉清单/切片，seekTo 只用于钉住进度，再恢复播放。
    var bufferingSince by remember { mutableLongStateOf(0L) }
    // v0.251：视频渲染帧计数器引用——0.250 黑匣子实锤第四盲区：seek 后解码器只出 1 帧
    //（9 次首帧渲染、0 条卡住自愈）但 isPlaying=true 位置照走 = 媒体时钟与画面脱钩，
    // state/pos/suppression 全部"健康"，看门狗无从下手。DecoderCounters.renderedOutputBufferCount
    // 每渲染一帧 +1，停帧检测的唯一可靠信号
    val countersRef = remember {
        java.util.concurrent.atomic.AtomicReference<androidx.media3.exoplayer.DecoderCounters?>(null)
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                bufferingSince = if (playbackState == Player.STATE_BUFFERING) System.currentTimeMillis() else 0L
            }
        }
        // v0.247：加载错误进黑匣子——看门狗只知「卡在 BUFFERING」，不知「为什么」；
        // onLoadError 带异常类型（InvalidResponseCodeException.responseCode=HTTP 码：
        // 403=签名过期 / 404=切片缺失；SocketTimeout=慢源），一轮数据定性
        // v0.249：画面定住取证——「缓冲在走但画面定住」的两大嫌疑：
        // ① 播放抑制（音频焦点被抢：isPlaying=false 但 state=READY，画面定帧缓冲照走）
        // ② 解码器不渲染（首帧后不再出帧）。两个回调落日志，下轮黑匣子直接区分
        val al = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            // v0.251：捕获视频渲染计数器（renderer 启用/停用生命周期），供看门狗停帧检测
            override fun onVideoEnabled(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                counters: androidx.media3.exoplayer.DecoderCounters
            ) {
                countersRef.set(counters)
            }

            override fun onVideoDisabled(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                counters: androidx.media3.exoplayer.DecoderCounters
            ) {
                countersRef.compareAndSet(counters, null)
            }

            override fun onPlaybackSuppressionReasonChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                playbackSuppressionReason: Int
            ) {
                val why = when (playbackSuppressionReason) {
                    1 -> "AUDIO_BECOMING_NOISY"; 2 -> "TRANSIENT_AUDIO_LOSS"
                    else -> "$playbackSuppressionReason"
                }
                com.shangyin.app.data.melon.MelonFlightLog.log(
                    "PLAYER",
                    if (playbackSuppressionReason == 0) "播放抑制解除 pos=${player.currentPosition}"
                    else "播放抑制=$why pos=${player.currentPosition} state=${player.playbackState}"
                )
            }

            override fun onRenderedFirstFrame(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                output: Any,
                mediaDurationMs: Long
            ) {
                com.shangyin.app.data.melon.MelonFlightLog.log(
                    "PLAYER", "首帧渲染 pos=${player.currentPosition} dur=${mediaDurationMs}ms"
                )
            }

            // v0.250：加载错误耗尽的最终落点——之前的 onLoadError 只记单次加载失败，
            // 重试耗尽后的 PlaybackException（进 error 态、画面定住）从未进黑匣子
            override fun onPlayerError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                error: androidx.media3.common.PlaybackException
            ) {
                val cause = error.cause
                com.shangyin.app.data.melon.MelonFlightLog.log(
                    "PLAYER",
                    "播放错误 ${error.errorCodeName} ${cause?.javaClass?.simpleName ?: ""}: ${cause?.message?.take(60) ?: error.message?.take(60)}"
                )
            }

            override fun onLoadError(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean
            ) {
                val t = when (mediaLoadData.dataType) {
                    androidx.media3.common.C.DATA_TYPE_MANIFEST -> "清单"
                    androidx.media3.common.C.DATA_TYPE_MEDIA -> "切片"
                    androidx.media3.common.C.DATA_TYPE_DRM -> "KEY"
                    else -> "type${mediaLoadData.dataType}"
                }
                val code = (error as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                com.shangyin.app.data.melon.MelonFlightLog.log(
                    "PLAYER",
                    "加载失败[$t] code=${code ?: "-"} ${error.javaClass.simpleName}: ${error.message?.take(60)}"
                )
            }
        }
        player.addListener(l)
        player.addAnalyticsListener(al)
        onDispose { player.removeListener(l); player.removeAnalyticsListener(al) }
    }
    LaunchedEffect(player) {
        var lastPos = -1L
        var stuckSince = 0L
        var reloadRound = 0 // 本轮卡住的自愈次数（位置恢复前进即清零）
        var gaveUp = false  // 连续 8 轮自愈无效 = 片源级故障，停止重试（换集自动复位）
        var curUri: String? = null
        var lastRendered = -1 // 上次采样的已渲染帧数（停帧检测，Int=renderedOutputBufferCount）
        var stallSince = 0L    // 帧数首次停止增长的时刻
        while (true) {
            delay(2000)
            // 换集/换线路（MediaItem uri 变化）→ 自愈状态复位，看门狗重新上岗
            val uriNow = player.currentMediaItem?.localConfiguration?.uri?.toString()
            if (uriNow != curUri) {
                curUri = uriNow
                gaveUp = false
                reloadRound = 0
                stuckSince = 0L
            }
            if (gaveUp) continue
            val now = System.currentTimeMillis()
            val pos = player.currentPosition
            // v0.250：加载失败耗尽（STATE_IDLE + playerError）——坏切片重试耗尽后 media3
            // 停在 error 态，画面定在最后一帧不再前进（0.249 探针实锤 CDN 有间歇超时切片，
            // 片20 SocketTimeout）。「画面定住不动」的第三个盲区：前两判（BUFFERING 超时/
            // READY 不播）都覆盖不到 error 态 → 永久定住。立即自愈重载
            player.playerError?.let { err ->
                // 直播流不走看门狗（无 seek/有限时长语义，卡住走直播自己的重连）
                if (!isLive) reloadRound++
                com.shangyin.app.data.melon.MelonFlightLog.log(
                    "PLAYER", "加载失败耗尽#$reloadRound ${err.errorCodeName} pos=$pos → 自愈重载"
                )
                if (reloadRound >= 8) {
                    gaveUp = true
                    com.shangyin.app.data.melon.MelonFlightLog.log("PLAYER", "error态自愈放弃 → 建议刷新/切线路")
                    Toast.makeText(context, "片源无法继续播放，建议点刷新或切换线路", Toast.LENGTH_LONG).show()
                    return@let
                }
                runCatching {
                    player.stop()
                    // v0.253：先 seekTo 再 prepare——stop 后 player 回到 IDLE，prepare 会从
                    // 默认位置 0 开始缓冲（0.252 诊断实锤：自愈后首帧渲染 pos=0，用户进度被
                    // 丢回片头）；seekTo 在 IDLE 态设定的是「prepare 后的初始位置」
                    player.seekTo(pos)
                    player.prepare()
                    player.playWhenReady = true
                }.onFailure { e ->
                    com.shangyin.app.data.melon.MelonFlightLog.log(
                        "PLAYER", "error态自愈异常 ${e.javaClass.simpleName}: ${e.message?.take(80)}"
                    )
                }
                bufferingSince = System.currentTimeMillis()
                stuckSince = 0L
                lastPos = pos
                return@let
            }
            if (gaveUp) continue
            // v0.251：视频停帧检测（第四盲区）——0.250 黑匣子实锤：seek 后 9 次首帧渲染、
            // 0 条卡住自愈 = 解码器只出 1 帧就停，但 isPlaying=true 位置照走（媒体时钟与
            // 画面脱钩），state/pos/suppression 全部"健康"，前三种判据全部被骗过。
            // renderedOutputBufferCount 每渲染一帧 +1：时钟在走而帧数不涨 = 停帧。
            // v0.252：12s→8s（用户感知"卡住"远早于此，快检测快恢复）；停帧自愈第 1 轮
            // 就上 MediaItem 重建（解码器级卡死 stop+prepare 不一定重建编解码器实例）
            var videoStalled = false
            countersRef.get()?.let { cnt ->
                val rendered = cnt.renderedOutputBufferCount
                if (rendered != lastRendered) {
                    if (lastRendered >= 0 && stallSince > 0) {
                        com.shangyin.app.data.melon.MelonFlightLog.log(
                            "PLAYER", "渲染恢复 frames=$rendered（自愈${if (reloadRound > 0) "有效" else "未介入"}）"
                        )
                    }
                    lastRendered = rendered
                    stallSince = 0L
                } else if (rendered > 0 && player.isPlaying && !isLive) {
                    if (stallSince == 0L) stallSince = now
                    else if (now - stallSince > 8_000) videoStalled = true
                } else {
                    stallSince = 0L
                }
            }
            // 停帧时不能走正常"在播"清零通道（会把自愈计数清掉），但位置确实在动，
            // lastPos 仍要跟上，避免 READY-不播分支误判位置停滞
            if (player.isPlaying && !videoStalled) {
                reloadRound = 0
                stuckSince = 0L
                gaveUp = false
                lastPos = pos
                continue
            }
            if (player.isPlaying) lastPos = pos
            // v0.226/v0.248：BUFFERING 超 25s 才判卡——黑匣子实锤（0.247）：seek 后
            // BUFFERING 期间**没有任何加载错误**、探针实测全部切片 200/完整、浏览器能播，
            // = 「慢」不是「死」：首切片 1.6MB 下载 5s 级 + 解密 + 关键帧对齐，10s 窗口
            // 不够且每次自愈 stop+prepare 把已下载进度全扔掉重来，反而把慢打成死循环。
            var needReload = !isLive && bufferingSince > 0 &&
                now - bufferingSince > 25_000
            // v0.231：READY 却不播——seek 后 STATE_READY、playWhenReady=true 但画面不动
            //（位置停滞）。位置 25s 无变化即重载（阈值与 BUFFERING 同步放宽）。
            // v0.249：播放抑制（音频焦点被抢）时 isPlaying=false 但那不是流的问题，
            // stop+prepare 无用 → 不自愈，等抑制解除（或用户点播放键重新夺回焦点）
            if (!needReload && !isLive && player.playWhenReady && !player.isPlaying &&
                !player.isPlayingAd && pos == lastPos &&
                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            ) {
                if (stuckSince == 0L) stuckSince = now
                else if (now - stuckSince > 25_000) needReload = true
            } else stuckSince = 0L
            lastPos = pos
            if (needReload || videoStalled) {
                val src = if (videoStalled) "停帧" else "卡住"
                reloadRound++
                // 连续 8 轮自愈全部无效 = 片源级故障（后半段缺失/签名全过期等），
                // 重试只是空转 → 放弃并明说（换集时 uri 变化会自动复位重新上岗）
                if (reloadRound >= 8) {
                    gaveUp = true
                    com.shangyin.app.data.melon.MelonFlightLog.log(
                        "PLAYER", "卡住自愈放弃 pos=$pos 连续8轮无效 → 停止重试"
                    )
                    Toast.makeText(context, "片源无法继续播放，建议点刷新或切换线路", Toast.LENGTH_LONG).show()
                    continue
                }
                val item = player.currentMediaItem
                runCatching {
                    when {
                        // v0.252：停帧 = 解码器级卡死（0.250 实锤时钟走画面停），第 1 轮
                        // 就直接 MediaItem 重建——完整重置解码器/清单/密钥/切片状态，
                        // stop+prepare 不一定重建编解码器实例，对停帧是弱药
                        videoStalled && item != null -> {
                            com.shangyin.app.data.melon.MelonFlightLog.log(
                                "PLAYER", "${src}自愈#$reloadRound frames冻结 pos=$pos → MediaItem重建(解码器级)"
                            )
                            player.setMediaItem(item, pos)
                            player.prepare()
                            player.playWhenReady = true
                        }
                        // 第 1 次（BUFFERING/READY 场景）：轻量重载——stop()+prepare() 强制丢弃
                        // 已加载的 MediaPeriod 重新拉清单/切片（⚠️ v0.225 教训：seekTo(当前位置)
                        // 是 no-op，media3 认为目标位置就是当前位置不重新加载；seekTo 只用于钉住进度）
                        reloadRound <= 1 || item == null -> {
                            com.shangyin.app.data.melon.MelonFlightLog.log(
                                "PLAYER", "${src}自愈#$reloadRound state=${player.playbackState} pos=$pos → stop+prepare"
                            )
                            player.stop()
                            // v0.253：先 seekTo 再 prepare（IDLE 态 seekTo 设定 prepare 后初始
                            // 位置；反序会先从 0 缓冲——0.252 诊断实锤自愈后首帧渲染 pos=0）
                            player.seekTo(pos)
                            player.prepare()
                            player.playWhenReady = true
                        }
                        // 第 2 次：整条 MediaItem 原位重建——彻底丢弃清单/解密密钥/切片状态从头解析
                        // （对付切片地址过期、密钥失效等轻量重载救不回的情况）
                        else -> {
                            // 第 3 次起原位重建已证明无效（0.246 黑匣子实测：6 轮全部
                            // BUFFERING 卡在同一 pos → 该位置的切片在 CDN 侧持续失败，
                            // 任何原位重试都不会好），改为**向前跳过坏段**继续播，
                            // 跳过量随轮次递增（#3→+15s，#4→+30s…），越界则回退原位重建
                            val dur = player.duration
                            val skipTarget = pos + 15_000L * (reloadRound - 2)
                            val target = if (dur > 0) skipTarget.coerceAtMost(dur - 1_000) else skipTarget
                            val msg = when {
                                target <= pos && reloadRound <= 2 -> "原位重建"
                                target <= pos -> "原位重建(无处可跳,已近片尾)"
                                else -> "重建+跳过坏段至${target / 1000}s"
                            }
                            com.shangyin.app.data.melon.MelonFlightLog.log(
                                "PLAYER", "${src}自愈#$reloadRound state=${player.playbackState} pos=$pos → $msg"
                            )
                            player.setMediaItem(item, target)
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }
                }.onFailure { e ->
                    com.shangyin.app.data.melon.MelonFlightLog.log(
                        "PLAYER", "卡住自愈#$reloadRound 异常 ${e.javaClass.simpleName}: ${e.message?.take(80)}"
                    )
                }
                // 第 3 次仍卡：自愈姿势已用尽，明说让用户主动干预（刷新/切线路）
                if (reloadRound == 3) {
                    Toast.makeText(context, "多次自动恢复未成功，建议点刷新或切换线路", Toast.LENGTH_LONG).show()
                }
                bufferingSince = System.currentTimeMillis() // 重置计时避免无限循环
                stuckSince = 0L
                stallSince = 0L
                lastRendered = -1 // 自愈后渲染器重建，计数器归零重来
            }
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
                            Row {
                                // 下载当前视频（v0.225）：先确认再下载（用户反馈不要静默开下）
                                if (!isLive) {
                                    IconButton(
                                        onClick = {
                                            val ep = groups.getOrNull(groupIndex)?.episodes?.getOrNull(currentEp)
                                            val url = ep?.url
                                            if (url.isNullOrBlank()) {
                                                Toast.makeText(context, "没有可下载的地址", Toast.LENGTH_SHORT).show()
                                            } else if (com.shangyin.app.data.download.VideoDownloadManager.isDownloading(url)) {
                                                Toast.makeText(context, "该视频已在下载列表", Toast.LENGTH_SHORT).show()
                                            } else {
                                                val eps = groups.getOrNull(groupIndex)?.episodes
                                                val label = if ((eps?.size ?: 0) > 1)
                                                    "${PlayerSession.title} ${ep.name}".trim()
                                                else PlayerSession.title.ifBlank { "视频" }
                                                pendingDownload = label to url
                                            }
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Download,
                                            contentDescription = "下载视频",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                // v0.254：下载中角标——实时 % + MB，用户点完下载立刻有可见反馈
                                dlTasks.values.firstOrNull()?.let { dt ->
                                    Text(
                                        if (dt.error != null) "下载失败"
                                        else "↓${(dt.progress * 100).toInt()}%",
                                        color = Color.White,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                        modifier = Modifier
                                            .align(Alignment.CenterVertically)
                                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
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

    // 下载确认弹窗（v0.225）：用户反馈"点下载没询问就直接下载了"
    pendingDownload?.let { (dlTitle, dlUrl) ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("下载视频") },
            text = {
                Text(
                    "「$dlTitle」\n\n下载后将保存到「下载管理 → 视频」（Download/老郑分享/视频/），" +
                        "已加密的切片会自动解密合并为本地文件，可离线播放。\n\n确定开始下载吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    com.shangyin.app.data.download.VideoDownloadManager.start(
                        context, dlTitle, dlUrl, PlayerSession.streamHeaders
                    )
                    Toast.makeText(context, "已开始下载，可在「我的下载 → 视频」查看进度", Toast.LENGTH_SHORT).show()
                    pendingDownload = null
                }) { Text("开始下载") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) { Text("取消") }
            }
        )
    }
}
