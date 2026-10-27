package com.shangyin.app.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shangyin.app.data.download.ComicDownloadStore
import com.shangyin.app.data.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 版本更新对话框（仅手动触发）：
 * 检查 → 发现新版本 → 下载（可取消）→ 拉起系统安装器。
 */
private sealed class UpdateState {
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Failed(val message: String) : UpdateState()
    data class Available(val info: UpdateChecker.UpdateInfo) : UpdateState()
    /** 优选下载线路（并发测速中） */
    data class Probing(val info: UpdateChecker.UpdateInfo) : UpdateState()
    data class Downloading(
        val info: UpdateChecker.UpdateInfo,
        val done: Long,
        val total: Long,
        val route: String = ""
    ) : UpdateState()
    data class Downloaded(val info: UpdateChecker.UpdateInfo, val file: File) : UpdateState()
}

@Composable
fun UpdateDialog(context: Context, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    fun startCheck() {
        state = UpdateState.Checking
        scope.launch {
            state = when (val result = UpdateChecker.check(context)) {
                is UpdateChecker.CheckResult.NewVersion -> UpdateState.Available(result.info)
                UpdateChecker.CheckResult.UpToDate -> UpdateState.UpToDate
                is UpdateChecker.CheckResult.Error -> UpdateState.Failed(result.message)
            }
        }
    }

    fun startDownload(info: UpdateChecker.UpdateInfo) {
        downloadJob = scope.launch {
            state = UpdateState.Probing(info)
            // 下载回调运行在 OkHttp 线程：进度必须切回主线程更新 Compose 状态，
            // 否则状态写了也不会触发重组（表现为「进度条不动，但实际已在下载」）。
            var lastUiAt = 0L
            var currentRoute = ""
            try {
                // 先并发测速，选出最快线路再下整包（全部失败会回退原顺序）
                val ranked = UpdateChecker.rankChannels(info)
                ensureActive()
                state = UpdateState.Downloading(info, 0L, info.sizeBytes)
                val file = UpdateChecker.download(
                    context = context,
                    info = info,
                    onProgress = { done, total ->
                        val now = System.currentTimeMillis()
                        if (done >= total || now - lastUiAt >= 200) {
                            lastUiAt = now
                            scope.launch {
                                // 下载完成后可能还有滞后的进度回调，别把「下载完成」覆盖回下载中
                                if (state is UpdateState.Downloading) {
                                    state = UpdateState.Downloading(info, done, total, currentRoute)
                                }
                            }
                        }
                    },
                    preferredChannels = ranked,
                    onChannel = { route ->
                        currentRoute = route
                        val s = state
                        if (s is UpdateState.Downloading) state = s.copy(route = route)
                    }
                )
                state = UpdateState.Downloaded(info, file)
            } catch (e: CancellationException) {
                state = UpdateState.Available(info)
                throw e
            } catch (e: Exception) {
                state = UpdateState.Failed(e.message ?: "下载失败")
            }
        }
    }

    LaunchedEffect(Unit) { startCheck() }

    val currentVersion = remember { UpdateChecker.localVersionName(context) }

    AlertDialog(
        onDismissRequest = {
            if (state !is UpdateState.Downloading && state !is UpdateState.Probing) onDismiss()
        },
        title = {
            Text(
                when (val s = state) {
                    UpdateState.Checking -> "检查更新"
                    UpdateState.UpToDate -> "已是最新版本"
                    is UpdateState.Failed -> "更新失败"
                    is UpdateState.Available -> "发现新版本 v${s.info.versionName}"
                    is UpdateState.Probing -> "正在选择下载线路"
                    is UpdateState.Downloading -> "正在下载 v${s.info.versionName}"
                    is UpdateState.Downloaded -> "下载完成"
                }
            )
        },
        text = {
            when (val s = state) {
                UpdateState.Checking -> {
                    Column {
                        Text("正在检查最新版本…", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                UpdateState.UpToDate -> Text(
                    "当前版本 v$currentVersion 已经是最新版本。",
                    style = MaterialTheme.typography.bodyMedium
                )
                is UpdateState.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium)
                is UpdateState.Available -> {
                    Column {
                        val sizeHint = if (s.info.sizeBytes > 0) {
                            " · 安装包 ${ComicDownloadStore.formatSize(s.info.sizeBytes)}"
                        } else ""
                        Text(
                            "当前 v$currentVersion$sizeHint",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            s.info.changelog.ifBlank { "该版本没有填写更新说明。" },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
                is UpdateState.Probing -> {
                    Column {
                        Text(
                            "正在测试 GitHub 直连与镜像速度，自动选择最快线路…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is UpdateState.Downloading -> {
                    val fraction = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f
                    Column {
                        Text(
                            "${(fraction * 100).toInt()}% · " +
                                "${ComicDownloadStore.formatSize(s.done)} / ${ComicDownloadStore.formatSize(s.total)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (s.route.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "线路：${s.route}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is UpdateState.Downloaded -> Text(
                    "安装包已校验，点击「安装」后按系统提示完成覆盖安装。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            when (val s = state) {
                UpdateState.Checking -> TextButton(onClick = onDismiss) { Text("取消") }
                UpdateState.UpToDate -> TextButton(onClick = onDismiss) { Text("知道了") }
                is UpdateState.Failed -> Row {
                    TextButton(onClick = { UpdateChecker.openReleasesPage(context) }) { Text("打开发布页") }
                    TextButton(onClick = { startCheck() }) { Text("重试") }
                }
                is UpdateState.Available -> TextButton(onClick = { startDownload(s.info) }) { Text("立即更新") }
                is UpdateState.Probing -> TextButton(onClick = {
                    // 先回到「发现新版本」，避免测速阻塞取消后界面卡在测速中
                    state = UpdateState.Available(s.info)
                    downloadJob?.cancel()
                    downloadJob = null
                }) { Text("取消") }
                is UpdateState.Downloading -> TextButton(onClick = {
                    downloadJob?.cancel()
                    downloadJob = null
                }) { Text("取消下载") }
                is UpdateState.Downloaded -> TextButton(onClick = {
                    if (UpdateChecker.canInstall(context)) {
                        UpdateChecker.install(context, s.file)
                        onDismiss()
                    } else {
                        UpdateChecker.openInstallPermission(context)
                        Toast.makeText(context, "请允许「安装未知应用」后返回再点安装", Toast.LENGTH_LONG).show()
                    }
                }) { Text("安装") }
            }
        },
        dismissButton = {
            when (state) {
                UpdateState.Checking, is UpdateState.Failed -> TextButton(onClick = onDismiss) { Text("关闭") }
                is UpdateState.Available -> TextButton(onClick = onDismiss) { Text("稍后") }
                is UpdateState.Downloaded -> TextButton(onClick = onDismiss) { Text("取消") }
                else -> Unit
            }
        }
    )
}
