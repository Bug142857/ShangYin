package com.shangyin.app.ui.music

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.shangyin.app.R
import com.shangyin.app.data.music.MusicQueueRegistry
import com.shangyin.app.data.music.MusicRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * 音乐播放服务：媒体会话 + 后台播放 + 通知栏/锁屏控制。
 *
 * 播放地址不预先解析：播放列表里每首歌的 URI 是 `lxmusic://song/{songKey}` 占位，
 * 真正播放时由 [LxAudioDataSourceFactory] 在数据源层向 LX 音源换直链
 * （音源直链有时效，播放时才解析能拿到最新链接，也避免点开列表就把整页歌都解析一遍）。
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory = LxAudioDataSourceFactory()
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // 后台播放：熄屏/切后台也继续播；耳机拔出自动暂停
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        player = exoPlayer

        session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, com.shangyin.app.MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

        // 通知栏图标换成音符（默认是 media3 的播放三角）
        val provider = DefaultMediaNotificationProvider.Builder(this).build()
        provider.setSmallIcon(R.drawable.ic_music_note)
        setMediaNotificationProvider(provider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 用户划掉最近任务：没在播就收工，不残留前台服务 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }
}

/**
 * LX 音源直链解析数据源：
 * 把 `lxmusic://song/{songKey}` 占位地址在真正发起请求时换成音源解析出来的 http(s) 直链，
 * 并补上各平台防盗链请求头（Referer / UA）。
 */
@OptIn(UnstableApi::class)
class LxAudioDataSourceFactory : DataSource.Factory {

    private val upstream = DefaultHttpDataSource.Factory()
        .setUserAgent(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)

    override fun createDataSource(): DataSource =
        ResolvingDataSource(upstream.createDataSource()) { dataSpec ->
            val key = dataSpec.uri.lastPathSegment.orEmpty()
            val song = MusicQueueRegistry.get(key)
                ?: throw IOException("歌曲信息已失效，请重新播放")
            // 数据源在播放线程上同步取直链：音源解析本身是挂起的（要过 WebView），这里阻塞等待
            val info = runBlocking(Dispatchers.IO) { MusicRepo.resolvePlay(song) }
            val headers = LinkedHashMap<String, String>()
            headers.putAll(info.headers)
            headers.putAll(dataSpec.httpRequestHeaders)
            dataSpec.withUri(Uri.parse(info.url)).withRequestHeaders(headers)
        }
}
