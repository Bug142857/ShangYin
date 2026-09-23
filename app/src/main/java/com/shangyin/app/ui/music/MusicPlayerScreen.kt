package com.shangyin.app.ui.music

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.shangyin.app.data.music.MusicLyric
import com.shangyin.app.data.music.MusicRepo
import com.shangyin.app.data.music.MusicSourceStore
import com.shangyin.app.ui.common.CollectDialog
import com.shangyin.app.ui.common.CoverImage
import com.shangyin.app.ui.common.EmptyView
import com.shangyin.app.ui.safeNavigate
import com.shangyin.app.ui.safePopBackStack
import kotlinx.coroutines.launch

/**
 * 完整播放页：大封面 / 歌词（点击封面切换）、可拖动进度条、播放控制、循环/随机/音质/收藏。
 * 离开页面不停播（播放器在 [MusicPlaybackService] 里，页面只是遥控器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(nav: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by MusicPlayback.state.collectAsStateWithLifecycle()
    val song = state.song

    var showLyric by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var showCollect by remember { mutableStateOf(false) }
    var collected by remember { mutableStateOf(false) }
    var lyric by remember { mutableStateOf(MusicLyric()) }
    var lyricLoading by remember { mutableStateOf(false) }
    // 拖动进度条时先跟手，松手才 seek
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // 播放错误：用对话框展示完整原因（Toast 会截断，用户看不到到底是哪个环节失败）
    var errorText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        errorText = err
        MusicPlayback.consumeError()
    }
    errorText?.let { text ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("播放失败") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { errorText = null }) { Text("知道了") } }
        )
    }

    // 换歌：重置进度拖动、拉歌词、查收藏状态
    LaunchedEffect(song?.key) {
        dragging = false
        collected = song?.let { runCatching { MusicRepo.isCollected(it) }.getOrDefault(false) } == true
        lyric = MusicLyric()
        if (song == null) return@LaunchedEffect
        lyricLoading = true
        lyric = runCatching { MusicRepo.lyric(song) }.getOrDefault(MusicLyric())
        lyricLoading = false
    }

    if (song == null) {
        Scaffold(topBar = { MusicTopBar(nav) }) { pad ->
            Box(Modifier.padding(pad)) { EmptyView("还没有正在播放的歌曲") }
        }
        return
    }

    val lines = remember(lyric) { parseLrc(lyric.lrc) }
    val duration = if (state.durationMs > 0L) state.durationMs else song.durationMs
    val position = if (dragging) dragValue.toLong() else state.positionMs
    val lyricState = rememberLazyListState()
    val currentLine = remember(lines, state.positionMs) {
        if (lines.isEmpty()) -1 else lines.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
    }
    val density = LocalDensity.current

    // 当前行自动滚到中间
    LaunchedEffect(currentLine, lines.size) {
        if (currentLine < 0 || lines.isEmpty()) return@LaunchedEffect
        val viewport = lyricState.layoutInfo.viewportSize.height
        if (viewport <= 0) {
            lyricState.scrollToItem(currentLine)
            return@LaunchedEffect
        }
        val half = viewport / 2 - with(density) { 24.dp.toPx() }.toInt()
        lyricState.animateScrollToItem(currentLine, -half.coerceAtLeast(0))
    }

    Scaffold(topBar = { MusicTopBar(nav) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (showLyric) {
                    when {
                        lyricLoading -> MusicLoadingBox()

                        lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "暂无歌词",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        else -> LazyColumn(
                            state = lyricState,
                            contentPadding = PaddingValues(vertical = 24.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(lines, key = { i, line -> "lyric_${line.timeMs}_$i" }) { index, line ->
                                Text(
                                    line.text,
                                    style = if (index == currentLine) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.bodyMedium,
                                    color = if (index == currentLine) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { MusicPlayback.seekTo(line.timeMs) }
                                        .padding(horizontal = 8.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        CoverImage(
                            url = song.cover,
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .aspectRatio(1f),
                            corner = 16.dp,
                            placeholderText = song.name,
                            onClick = { showLyric = true }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "点击封面看歌词",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // 封面 / 歌词 切换
                TextButton(
                    onClick = { showLyric = !showLyric },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text(if (showLyric) "封面" else "歌词")
                }
            }

            Text(
                song.name,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                song.subtitle.ifBlank { song.platform.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(6.dp))
            Slider(
                value = position.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat(),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    MusicPlayback.seekTo(dragValue.toLong())
                    dragging = false
                },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatMusicTime(position),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatMusicTime(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 上一首 / 播放暂停 / 下一首
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { MusicPlayback.previous() }) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(36.dp)
                    )
                }
                FilledIconButton(
                    onClick = { MusicPlayback.toggle(context) },
                    modifier = Modifier.size(64.dp)
                ) {
                    if (state.buffering) {
                        CircularProgressIndicator(
                            Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                IconButton(onClick = { MusicPlayback.next() }) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // 循环模式 / 随机 / 音质 / 收藏
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { MusicPlayback.setRepeatMode(nextRepeatMode(state.repeatMode)) }) {
                    Icon(
                        repeatIcon(state.repeatMode),
                        contentDescription = "循环模式",
                        tint = if (state.repeatMode == MusicPlayback.RepeatMode.OFF)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { MusicPlayback.toggleShuffle() }) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "随机播放",
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showQuality = true }) {
                    Text(state.quality.label)
                }
                IconButton(onClick = {
                    if (collected) {
                        Toast.makeText(context, "已在清单中", Toast.LENGTH_SHORT).show()
                    } else {
                        showCollect = true
                    }
                }) {
                    Icon(
                        if (collected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (collected) Color(0xFFEF5350)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // 音质选择：只列当前平台音源实际支持的档位
    if (showQuality) {
        val scripts by MusicSourceStore.scripts.collectAsStateWithLifecycle()
        val platform = song.platform
        val qualities = remember(scripts, platform) {
            scripts.filter { it.enabled && it.supports(platform) }
                .flatMap { it.qualitiesOf(platform) }
                .distinct()
                .sortedByDescending { it.level }
        }
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text("选择音质") },
            text = {
                Column {
                    if (qualities.isEmpty()) {
                        Text(
                            "当前没有支持「${platform.label}」的可用音源，请到「音源管理」导入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        qualities.forEach { quality ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        MusicPlayback.setQuality(quality)
                                        showQuality = false
                                    }
                                    .padding(vertical = 2.dp)
                            ) {
                                RadioButton(
                                    selected = quality == state.quality,
                                    onClick = {
                                        MusicPlayback.setQuality(quality)
                                        showQuality = false
                                    }
                                )
                                Text("${quality.label}（${quality.key}）")
                            }
                        }
                        Text(
                            "换音质会重新解析播放直链，本首重播后生效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuality = false }) { Text("关闭") }
            }
        )
    }

    // 收藏到里世界清单
    if (showCollect) {
        CollectDialog(
            onDismiss = {
                showCollect = false
                scope.launch {
                    collected = runCatching { MusicRepo.isCollected(song) }.getOrDefault(false)
                }
            },
            collect = { listId -> MusicRepo.collect(song, listId) }
        )
    }
}

/** 播放页顶部栏（返回 + 音源入口） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicTopBar(nav: NavHostController) {
    TopAppBar(
        title = { Text("正在播放") },
        navigationIcon = {
            IconButton(onClick = { nav.safePopBackStack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = { nav.safeNavigate("musicSources") }) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = "音源")
            }
        }
    )
}

/** 循环模式：列表 → 单曲 → 关闭 → 列表 */
private fun nextRepeatMode(mode: MusicPlayback.RepeatMode): MusicPlayback.RepeatMode = when (mode) {
    MusicPlayback.RepeatMode.LIST -> MusicPlayback.RepeatMode.ONE
    MusicPlayback.RepeatMode.ONE -> MusicPlayback.RepeatMode.OFF
    MusicPlayback.RepeatMode.OFF -> MusicPlayback.RepeatMode.LIST
}

private fun repeatIcon(mode: MusicPlayback.RepeatMode) = when (mode) {
    MusicPlayback.RepeatMode.ONE -> Icons.Rounded.RepeatOne
    else -> Icons.Rounded.Repeat
}

/** 一行歌词：时间（毫秒）+ 文本 */
private data class LrcLine(val timeMs: Long, val text: String)

/**
 * 极简 LRC 解析：支持 [mm:ss.SS] 与 [mm:ss]，一行多个时间标签（同一句重复出现），
 * 按时间升序返回，解析不出文本的行丢掉。
 */
private fun parseLrc(lrc: String): List<LrcLine> {
    if (lrc.isBlank()) return emptyList()
    val timeRegex = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    val result = mutableListOf<LrcLine>()
    lrc.lineSequence().forEach { raw ->
        val tags = timeRegex.findAll(raw).toList()
        if (tags.isEmpty()) return@forEach
        val text = raw.substringAfterLast(']').trim()
        if (text.isBlank()) return@forEach
        tags.forEach { tag ->
            val minute = tag.groupValues[1].toLongOrNull() ?: 0L
            val second = tag.groupValues[2].toLongOrNull() ?: 0L
            val frac = tag.groupValues[3]
            val millis = when (frac.length) {
                1 -> (frac.toLongOrNull() ?: 0L) * 100
                2 -> (frac.toLongOrNull() ?: 0L) * 10
                3 -> frac.toLongOrNull() ?: 0L
                else -> 0L
            }
            result += LrcLine(minute * 60_000 + second * 1000 + millis, text)
        }
    }
    return result.sortedBy { it.timeMs }
}
