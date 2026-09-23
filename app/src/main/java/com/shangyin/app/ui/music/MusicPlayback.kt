package com.shangyin.app.ui.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.shangyin.app.data.music.MusicQueueRegistry
import com.shangyin.app.data.music.MusicQuality
import com.shangyin.app.data.music.MusicRepo
import com.shangyin.app.data.music.MusicSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 全局音乐播放控制器（单例）。
 *
 * UI 只跟它打交道：观察 [state] 画画，调 play/toggle/next… 控制播放。
 * 真正的播放器在 [MusicPlaybackService] 里（MediaSessionService，负责后台播放与通知栏），
 * 这里通过 media3 的 [MediaController] 连过去 —— 这样通知栏/锁屏上的操作也会回流到 [state]。
 */
object MusicPlayback {

    enum class RepeatMode { LIST, ONE, OFF }

    data class State(
        val song: MusicSong? = null,
        val isPlaying: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val queue: List<MusicSong> = emptyList(),
        val index: Int = 0,
        val repeatMode: RepeatMode = RepeatMode.LIST,
        val shuffle: Boolean = false,
        val quality: MusicQuality = MusicQuality.Q320,
        /** 播放失败原因（音源解析失败等），展示一次后由 [consumeError] 清掉 */
        val error: String? = null
    ) {
        val hasSong: Boolean get() = song != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null

    /** 连接播放服务（幂等，UI 首次进入音乐模块时调用即可） */
    fun init(context: Context) {
        if (controllerFuture != null) return
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, MusicPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            _state.update {
                it.copy(
                    quality = MusicRepo.preferredQuality(),
                    repeatMode = repeatModeOf(c.repeatMode),
                    shuffle = c.shuffleModeEnabled
                )
            }
            syncFromController()
            startProgressLoop()
        }, MoreExecutors.directExecutor())
        _state.update { it.copy(quality = MusicRepo.preferredQuality()) }
    }

    /** 用整份列表起播（[startIndex] 为起始位置） */
    fun play(context: Context, songs: List<MusicSong>, startIndex: Int = 0, quality: MusicQuality = MusicRepo.preferredQuality()) {
        if (songs.isEmpty()) return
        init(context)
        val index = startIndex.coerceIn(0, songs.lastIndex)
        MusicQueueRegistry.register(songs)
        val items = songs.map { it.toMediaItem() }
        runWhenConnected {
            setMediaItems(items, index, 0L)
            prepare()
            play()
        }
        // 先把状态改成"这首歌正在起播"，界面立刻有反馈（真实状态随后由监听器刷新）
        _state.update {
            it.copy(
                song = songs[index], index = index, queue = songs, quality = quality,
                positionMs = 0L, durationMs = songs[index].durationMs,
                buffering = true, error = null
            )
        }
        MusicRepo.clearUrlCache()
    }

    fun playSingle(context: Context, song: MusicSong, quality: MusicQuality = MusicRepo.preferredQuality()) =
        play(context, listOf(song), 0, quality)

    fun toggle(context: Context) {
        init(context)
        runWhenConnected { if (isPlaying) pause() else play() }
    }

    fun next() {
        runWhenConnected { seekToNextMediaItem() }
    }

    fun previous() {
        runWhenConnected {
            // 播过 3 秒以上先回到本曲开头（与主流播放器一致）
            if (currentPosition > 3000) seekTo(0) else seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        runWhenConnected { seekTo(positionMs) }
    }

    fun stop() {
        runWhenConnected { stop(); clearMediaItems() }
        _state.update { State(quality = it.quality) }
    }

    fun setRepeatMode(mode: RepeatMode) {
        runWhenConnected {
            repeatMode = when (mode) {
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.LIST -> Player.REPEAT_MODE_ALL
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            }
        }
        _state.update { it.copy(repeatMode = mode) }
    }

    fun toggleShuffle() {
        runWhenConnected { shuffleModeEnabled = !shuffleModeEnabled }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    // ---------------- 内部 ----------------

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromController()
        }

        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause?.message ?: error.message
            _state.update { it.copy(buffering = false, isPlaying = false, error = cause) }
        }
    }

    private fun syncFromController() {
        val c = controller ?: return
        val current = c.currentMediaItem?.mediaId?.let { key ->
            MusicQueueRegistry.get(key)
        }
        val queue = (0 until c.mediaItemCount).mapNotNull { i ->
            c.getMediaItemAt(i).mediaId.let { MusicQueueRegistry.get(it) }
        }
        val duration = if (c.duration > 0) c.duration else (current?.durationMs ?: 0L)
        _state.update {
            it.copy(
                song = current ?: it.song,
                isPlaying = c.isPlaying,
                buffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = if (c.currentPosition > 0) c.currentPosition else it.positionMs,
                durationMs = duration,
                queue = queue.ifEmpty { it.queue },
                index = c.currentMediaItemIndex.coerceAtLeast(0),
                repeatMode = repeatModeOf(c.repeatMode),
                shuffle = c.shuffleModeEnabled
            )
        }
    }

    private fun repeatModeOf(media3Mode: Int): RepeatMode = when (media3Mode) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_OFF -> RepeatMode.OFF
        else -> RepeatMode.LIST
    }

    /** 播放进度轮询（只在有歌且未暂停时跑，避免后台空转） */
    private fun startProgressLoop() {
        if (progressJob != null) return
        progressJob = scope.launch {
            while (true) {
                val c = controller
                if (c != null && c.isPlaying) {
                    val duration = if (c.duration > 0) c.duration else _state.value.durationMs
                    _state.update { it.copy(positionMs = c.currentPosition, durationMs = duration) }
                }
                delay(500)
            }
        }
    }

    private inline fun runWhenConnected(crossinline block: MediaController.() -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
            return
        }
        controllerFuture?.let { future ->
            future.addListener({
                runCatching { future.get() }.getOrNull()?.let { block(it) }
            }, MoreExecutors.directExecutor())
        }
    }
}

/** 歌曲 → media3 播放项（URI 是占位符，真正地址由数据源在播放时向音源解析） */
internal fun MusicSong.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(key)
    .setUri(Uri.parse("lxmusic://song/${Uri.encode(key)}"))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(artists)
            .setAlbumTitle(album)
            .setArtworkUri(cover.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .build()
    )
    .build()
