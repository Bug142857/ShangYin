package com.shangyin.app.ui.player

import android.content.Context
import android.media.MediaCrypto
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * MP2（MPEG-1/2 Layer I/II）音频兼容渲染器。
 *
 * **为什么需要它（实测取证）**：IPTV / 电视源里不少频道的音频是 MP2 —— 例如默认源里
 * 央视 3（`112.30.73.119:229`）、央视 5（`112.30.73.119:9901`）的 TS 分片里
 * `stream_type = 0x03`（MPEG-1 Layer II）；而央视 9（`74.91.26.218:82`）是 `0x0F`（AAC）。
 *
 * media3 会把 MP2 的音轨标成 `audio/mpeg-L2`，而 Android 平台基本只注册 `audio/mpeg`（MP3）解码器、
 * 不注册 `audio/mpeg-L2` → `DefaultTrackSelector` 判定"渲染器不支持这条音轨"，**直接把音轨整条丢掉**，
 * 于是出现「画面正常、完全没有声音、也不报错」。VLC 这类内置 FFmpeg 的播放器能放，是因为它自带 MP2 软解。
 *
 * **处理**：把这层 MIME 换成 `audio/mpeg` 再走系统解码器 —— Android 的软件 MP3 解码器
 * （`c2.android.mp3.decoder`，libmpg123 系）本身就能解 Layer I/II 帧，只是没把能力注册成 `audio/mpeg-L2`。
 * 只在 L1/L2 两种 MIME 上生效，AAC / MP3 等其它编码完全不受影响。
 */
@OptIn(UnstableApi::class)
class MpegAudioCompatRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // 本项目没有引入 media3 的扩展渲染器（extensionRendererMode 恒为 OFF），
        // 所以这里只挂我们的音频渲染器，不调 super（否则会再挂一个平台音频渲染器）
        out.add(
            MpegAudioCompatRenderer(
                context = context,
                mediaCodecSelector = mediaCodecSelector,
                enableDecoderFallback = enableDecoderFallback,
                eventHandler = eventHandler,
                eventListener = eventListener,
                audioSink = audioSink
            )
        )
    }
}

@OptIn(UnstableApi::class)
private class MpegAudioCompatRenderer(
    context: Context,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink
) : MediaCodecAudioRenderer(
    context, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink
) {

    /** MP2（L1/L2）→ 按 `audio/mpeg` 交给系统解码器；其它编码原样返回 */
    private fun compat(format: Format): Format =
        when (format.sampleMimeType) {
            MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 ->
                format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_MPEG).build()
            else -> format
        }

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, compat(format))

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        tunneling: Boolean
    ): List<MediaCodecInfo> =
        super.getDecoderInfos(mediaCodecSelector, compat(format), tunneling)

    override fun getMediaCodecConfiguration(
        codecInfo: MediaCodecInfo,
        format: Format,
        crypto: MediaCrypto?,
        operatingRate: Float
    ): MediaCodecAdapter.Configuration =
        super.getMediaCodecConfiguration(codecInfo, compat(format), crypto, operatingRate)
}
