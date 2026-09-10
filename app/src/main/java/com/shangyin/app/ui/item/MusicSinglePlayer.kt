package com.shangyin.app.ui.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shangyin.app.ui.music.MusicRefresher
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 音乐条目详情页的单曲播放器：
 * 播放地址来自收藏时嗅探到的音频直链（存在 doubanUrl 字段）。
 * 直链有时效——失效时自动用离屏 WebView 重新嗅探新直链并续播（onNewUrl 回调给调用方存库）。
 */
@Composable
fun MusicSinglePlayer(
    playUrl: String,
    title: String,
    artist: String = "",
    onNewUrl: (String) -> Unit = {}
) {
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var currentUrl by remember(playUrl) { mutableStateOf(playUrl) }
    var refreshing by remember { mutableStateOf(false) }
    var userStarted by remember { mutableStateOf(false) } // 用户点过播放后才允许自动续播
    val context = androidx.compose.ui.platform.LocalContext.current

    fun release() {
        runCatching { player?.release() }
        player = null
        isPlaying = false
    }

    fun start() {
        release()
        preparing = true
        error = null
        val mp = android.media.MediaPlayer()
        runCatching {
            // 带上泡椒站的 Cookie/Referer/UA，绕过防盗链
            val ctx = context
            val headers = mutableMapOf(
                "Referer" to com.shangyin.app.ui.music.MUSIC_SITE,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            android.webkit.CookieManager.getInstance()
                .getCookie(com.shangyin.app.ui.music.MUSIC_SITE)?.let { headers["Cookie"] = it }
            mp.setDataSource(ctx, android.net.Uri.parse(currentUrl), headers)
            mp.setOnPreparedListener {
                durationMs = it.duration
                it.start()
                isPlaying = true
                preparing = false
                started = true
            }
            mp.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
            }
            mp.setOnErrorListener { _, what, extra ->
                isPlaying = false
                preparing = false
                if (!refreshing) {
                    // 直链过期 → 自动重新嗅探
                    error = "直链已失效，正在自动刷新…"
                    refreshing = true
                } else {
                    error = "刷新后的直链仍无法播放，请到「搜索 → 音乐」重新收藏"
                }
                true
            }
            mp.prepareAsync()
            player = mp
        }.onFailure {
            preparing = false
            error = "无法播放该音频：${it.message ?: "链接无效"}"
            runCatching { mp.release() }
        }
    }

    // 换链后自动续播（仅当用户此前主动播放过）
    LaunchedEffect(currentUrl) {
        if (userStarted && currentUrl.isNotBlank()) start()
    }

    DisposableEffect(Unit) {
        onDispose { release() }
    }

    // 播放中刷新进度
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val mp = player
            if (mp != null) {
                runCatching {
                    positionMs = mp.currentPosition
                    durationMs = mp.duration
                }
            }
            delay(500)
        }
    }

    if (refreshing) {
        MusicRefresher(
            songName = title,
            artist = artist,
            onCaptured = { newUrl ->
                onNewUrl(newUrl)
                currentUrl = newUrl
                refreshing = false
            },
            onTimeout = {
                refreshing = false
                error = "自动刷新失败——到「搜索 → 音乐」重新试听一次即可"
            }
        )
    }

    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("在线播放", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val mp = player
                    if (mp == null || !started) {
                        userStarted = true
                        start()
                    } else if (mp.isPlaying) {
                        mp.pause(); isPlaying = false
                    } else {
                        mp.start(); isPlaying = true
                    }
                }) {
                    when {
                        preparing || refreshing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        isPlaying -> Icon(Icons.Rounded.Pause, contentDescription = "暂停")
                        else -> Icon(Icons.Rounded.PlayArrow, contentDescription = "播放")
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                        },
                        modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 4.dp)
                    )
                    Text(
                        "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    return String.format(Locale.getDefault(), "%d:%02d", ms / 60000, (ms % 60000) / 1000)
}
