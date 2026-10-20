package com.shangyin.app.data.melon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * v0.258 根因修复：站点 HLS 列表缺 #EXT-X-ENDLIST（0.254 黑匣子定案 dur≈32天），
 * ExoPlayer 把几十分钟的 VOD 当成无限直播流——进度条全长失真、seek 落点必然落在
 * 真实内容之外、永远缓冲不到。
 *
 * 修复 = 在线列表加载时原位补一行 #EXT-X-ENDLIST（几 KB 内存补丁）。VOD 化后 media3
 * 按 EXTINF 求和得出真实时长，时长/进度条/seek 全部恢复正确，无需下载整片。
 * 0.254~0.257 的「整片下载镜像本地播放」是同一根因的重型弥补方案，被此补丁取代。
 *
 * 仅拦截 .m3u8 请求（切片/密钥原样透传）；响应非 M3U8 文本（风控页等）原样放行，
 * 由既有解析失败路径处理。主列表（含 #EXT-X-STREAM-INF 无 EXTINF）不动，只补媒体列表。
 */
class EndlistPatchDataSourceFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = EndlistPatchDataSource(upstream.createDataSource())
}

private class EndlistPatchDataSource(private val upstream: DataSource) : DataSource {
    private var serving: ByteArrayInputStream? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        serving = null
        val path = dataSpec.uri.toString().substringBefore('?')
        if (!path.endsWith(".m3u8")) return upstream.open(dataSpec)
        // 列表只有几 KB，整读进内存补一行字节再回放
        upstream.open(dataSpec)
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(16 * 1024)
        while (true) {
            val n = upstream.read(tmp, 0, tmp.size)
            if (n == C.RESULT_END_OF_INPUT) break
            buf.write(tmp, 0, n)
        }
        upstream.close()
        var bytes = buf.toByteArray()
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains("#EXTM3U") && text.contains("#EXTINF") && !text.contains("#EXT-X-ENDLIST")) {
            bytes = (text.trimEnd() + "\n#EXT-X-ENDLIST\n").toByteArray(Charsets.UTF_8)
            MelonFlightLog.log("PLAYER", "ENDLIST补丁: 直播化列表已转VOD (len=${bytes.size})")
        } else if (!text.contains("#EXTM3U")) {
            // 门未命中/异常响应留痕——0.254 教训：零日志无法区分「没触发」和「没生效」
            MelonFlightLog.log("PLAYER", "ENDLIST补丁跳过: m3u8地址返回非列表内容 (len=${bytes.size})")
        }
        serving = ByteArrayInputStream(bytes)
        return bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = serving
        return if (s != null) s.read(buffer, offset, length)
        else upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): MutableMap<String, MutableList<String>> = upstream.responseHeaders

    override fun close() {
        serving = null
        upstream.close()
    }
}
