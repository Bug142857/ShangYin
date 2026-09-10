package com.shangyin.app.ui.item

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shangyin.app.data.music.MusicSourceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 音乐详情页"在线试听"区块：
 * 进页面自动按"歌手 + 专辑名"搜歌（网易云公开接口），
 * 点歌曲播放（MediaPlayer 流式），支持上一首/下一首/暂停/进度。
 * 与 flac.music.hi.cn 等无损站的"搜索-列表-播放"形态一致。
 */
@Composable
fun MusicPlayerSection(
    albumTitle: String,
    artist: String
) {
    val scope = rememberCoroutineScope()

    var songs by remember { mutableStateOf<List<MusicSourceClient.Song>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchKey by remember { mutableStateOf("") }
    var searchTick by remember { mutableIntStateOf(0) } // 手动重搜

    // 播放状态
    var currentIndex by remember { mutableIntStateOf(-1) }
    var isPlaying by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    // 自动搜索一次：歌手 + 专辑名（无歌手就用专辑名）
    LaunchedEffect(albumTitle, artist, searchTick) {
        val key = buildString {
            if (artist.isNotBlank()) append("$artist ")
            append(albumTitle)
        }.trim()
        if (key.isBlank() || loading) return@LaunchedEffect
        searchKey = key
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) { MusicSourceClient.searchSongs(key) }
        }.onSuccess {
            songs = it
            if (it.isEmpty()) error = "没找到可试听的歌曲"
        }.onFailure { e ->
            error = "搜索失败：${e.message ?: "网络错误"}"
        }
        loading = false
    }

    // 释放播放器
    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }

    // 播放指定歌曲
    fun playAt(index: Int) {
        val song = songs.getOrNull(index) ?: return
        preparing = true
        playError = null
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                runCatching { MusicSourceClient.songUrl(song.id) }.getOrNull()
            }
            if (url == null) {
                preparing = false
                playError = "「${song.name}」暂无试听源（版权限制）"
                return@launch
            }
            runCatching {
                player?.release()
                val mp = android.media.MediaPlayer()
                mp.setDataSource(url)
                mp.setOnPreparedListener {
                    durationMs = it.duration
                    it.start()
                    isPlaying = true
                    preparing = false
                }
                mp.setOnCompletionListener {
                    isPlaying = false
                    positionMs = 0
                    // 自动下一首
                    if (currentIndex < songs.size - 1) playAt(currentIndex + 1)
                }
                mp.setOnErrorListener { _, what, extra ->
                    playError = "播放出错（$what/$extra）"
                    isPlaying = false
                    preparing = false
                    true
                }
                mp.prepareAsync()
                player = mp
                currentIndex = index
            }.onFailure {
                preparing = false
                playError = "播放失败：${it.message ?: "未知错误"}"
            }
        }
    }

    fun togglePlay() {
        val mp = player ?: return
        runCatching {
            if (mp.isPlaying) {
                mp.pause()
                isPlaying = false
            } else {
                mp.start()
                isPlaying = true
            }
        }
    }

    fun seekTo(fraction: Float) {
        val mp = player ?: return
        if (durationMs <= 0) return
        runCatching {
            mp.seekTo((durationMs * fraction).toInt())
            positionMs = (durationMs * fraction).toInt()
        }
    }

    // 播放时刷新进度
    LaunchedEffect(isPlaying, currentIndex) {
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

    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("在线试听", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { songs = emptyList(); searchTick++ }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新搜索", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Text(
                "按「${searchKey.ifBlank { "歌手 + 专辑" }}」匹配，音源为公开试听接口",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 当前播放控制条
            if (currentIndex >= 0) {
                val song = songs.getOrNull(currentIndex)
                Column {
                    Text(
                        song?.let { "${it.name} - ${it.artist}" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
                        },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (currentIndex > 0) playAt(currentIndex - 1) }) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首")
                        }
                        IconButton(onClick = { togglePlay() }) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放"
                            )
                        }
                        IconButton(onClick = { if (currentIndex < songs.size - 1) playAt(currentIndex + 1) }) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "下一首")
                        }
                        if (preparing) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            playError?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 歌曲列表（详情页外层是 verticalScroll，这里用普通 Column 避免嵌套滚动崩溃）
            songs.forEachIndexed { index, song ->
                val active = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (index != currentIndex) playAt(index) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            song.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (song.artist.isNotBlank()) {
                            Text(
                                "${song.artist} · ${song.album}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        formatMs(song.durationMs.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 搜索失败/无结果的兜底操作
            if (songs.isEmpty() && !loading && error != null) {
                TextButton(onClick = { searchTick++ }) { Text("重新搜索") }
            }
        }
    }
}

private fun formatMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    return String.format(Locale.getDefault(), "%d:%02d", ms / 60000, (ms % 60000) / 1000)
}
