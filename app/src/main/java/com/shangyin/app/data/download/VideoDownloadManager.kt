package com.shangyin.app.data.download

import android.content.Context
import android.os.Environment
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 已下载完成的视频记录 */
data class DownloadedVideo(
    val id: String,          // 时间戳
    val title: String,
    val sourceUrl: String,   // 原始 m3u8/mp4 地址
    val filePath: String,    // 本地文件绝对路径
    val sizeBytes: Long = 0,
    val createdAt: Long = 0,
)

/** 进行中的视频下载任务（key = sourceUrl） */
data class VideoTask(
    val title: String,
    val url: String,
    val progress: Float = 0f, // 0..1（切片完成比例）
    val error: String? = null,
)

/**
 * 视频下载器（v0.224）：HLS m3u8 切片合并下载 + mp4 直链流拷贝。
 * 存储位置：应用私有外存「视频/」目录（无需权限、卸载自清、file:// 可直接播放）。
 * 记录持久化到 SettingsStore（JSON），换设备不迁移（本地文件不随备份走）。
 */
object VideoDownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val library = MutableStateFlow<List<DownloadedVideo>>(emptyList())
    val active = MutableStateFlow<Map<String, VideoTask>>(emptyMap())
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 视频目录：Android/包名/files/视频（getExternalFilesDir 私有外存） */
    fun videosDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "视频")

    /** 供下载管理页展示的目录说明 */
    fun dirInfo(context: Context): String = videosDir(context).absolutePath

    fun refresh(context: Context) {
        val list = runCatching {
            val arr = JSONArray(SettingsStore.videoDownloadsJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val f = File(o.optString("filePath"))
                if (!f.isFile) return@mapNotNull null // 文件被手动清掉 → 记录作废
                DownloadedVideo(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    sourceUrl = o.optString("sourceUrl"),
                    filePath = o.optString("filePath"),
                    sizeBytes = f.length(),
                    createdAt = o.optLong("createdAt")
                )
            }
        }.getOrDefault(emptyList())
        library.value = list
    }

    fun isDownloading(url: String): Boolean = jobs.containsKey(url)

    /** 启动下载（同 URL 已在下载中则忽略；返回是否成功启动） */
    fun start(context: Context, title: String, url: String, headers: Map<String, String>): Boolean {
        if (isDownloading(url)) return false
        active.value = active.value + (url to VideoTask(title, url))
        val appContext = context.applicationContext
        jobs[url] = scope.launch {
            val task = active.value[url] ?: return@launch
            try {
                val file = withContext(Dispatchers.IO) { runSuspendable(appContext, task, headers) }
                val record = DownloadedVideo(
                    id = System.currentTimeMillis().toString(),
                    title = task.title,
                    sourceUrl = url,
                    filePath = file.absolutePath,
                    sizeBytes = file.length(),
                    createdAt = System.currentTimeMillis()
                )
                library.value = library.value + record
                save(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                active.value = active.value + (url to task.copy(error = e.message ?: "下载失败"))
            } finally {
                jobs.remove(url)
                // 成功收尾：任务从进行中列表移除；失败保留显示错误，由 UI「移除」清走
                if (active.value[url]?.error == null) {
                    active.value = active.value - url
                }
            }
        }
        return true
    }

    /** 取消下载（清理临时目录） */
    fun cancel(context: Context, url: String) {
        jobs.remove(url)?.cancel()
        active.value = active.value - url
        runCatching { tmpDir(context, url).deleteRecursively() }
    }

    /** 移除失败任务（仅清状态） */
    fun dismiss(url: String) {
        active.value = active.value - url
    }

    /** 删除已下载视频（文件 + 记录） */
    fun delete(context: Context, video: DownloadedVideo) {
        runCatching { File(video.filePath).delete() }
        library.value = library.value.filterNot { it.id == video.id }
        save(context)
    }

    // ---------- 内部实现 ----------

    private fun tmpDir(context: Context, url: String): File {
        // 同一 URL 的临时目录名稳定（hash），重试可复用
        return File(videosDir(context), ".tmp-${url.hashCode().toUInt()}")
    }

    private suspend fun runSuspendable(context: Context, task: VideoTask, headers: Map<String, String>): File {
        val outDir = videosDir(context).apply { mkdirs() }
        val safeName = task.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
        return if (task.url.substringBefore('?').endsWith(".m3u8")) {
            downloadHls(context, task, headers, File(outDir, "$safeName.ts"))
        } else {
            downloadDirect(task, headers, File(outDir, "$safeName.mp4"))
        }
    }

    /** HLS：解析切片 → 并发下载到临时目录 → 顺序拼接（运行在 Dispatchers.IO 上） */
    private suspend fun downloadHls(context: Context, task: VideoTask, headers: Map<String, String>, output: File): File {
        val m3u8 = httpGet(task.url, headers)
        val child = parseHlsMaster(m3u8, task.url)
        val playlist = if (child != null) httpGet(child, headers) else m3u8
        val base = child ?: task.url
        val segments = parseHlsSegments(playlist, base)
        if (segments.isEmpty()) throw IOException("未解析到视频切片")
        val tmp = tmpDir(context, task.url).apply { mkdirs() }
        try {
            val semaphore = Semaphore(3) // 并发 3 路，避免打爆图床/源站
            val total = segments.size
            var done = 0
            segments.mapIndexed { idx, seg ->
                scope.async {
                    semaphore.withPermit {
                        val f = File(tmp, "seg-%05d".format(idx))
                        if (!f.isFile || f.length() == 0L) {
                            var lastErr: Exception? = null
                            repeat(3) { attempt ->
                                if (f.isFile) f.delete()
                                try {
                                    httpGetBytes(seg, headers).use { input ->
                                        f.outputStream().use { input.copyTo(it) }
                                    }
                                    lastErr = null
                                    return@withPermit
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    lastErr = e
                                }
                                if (attempt == 2) throw lastErr ?: IOException("切片下载失败: $seg")
                            }
                        }
                        val n = ++done
                        active.update { it + (task.url to task.copy(progress = n.toFloat() / total)) }
                    }
                }
            }.awaitAll()

            output.outputStream().use { out ->
                segments.indices.forEach { idx ->
                    File(tmp, "seg-%05d".format(idx)).inputStream().use { it.copyTo(out) }
                }
            }
            tmp.deleteRecursively()
            return output
        } catch (e: Exception) {
            if (e !is CancellationException) tmp.deleteRecursively()
            throw e
        }
    }

    /** mp4 等直链：流式拷贝 + 进度按字节比例 */
    private fun downloadDirect(task: VideoTask, headers: Map<String, String>, output: File): File {
        val req = buildRequest(task.url, headers).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val total = resp.body?.contentLength() ?: -1L
            val part = File(output.absolutePath + ".part")
            resp.body!!.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            active.update { it + (task.url to task.copy(progress = copied.toFloat() / total)) }
                        }
                    }
                }
            }
            part.renameTo(output)
            return output
        }
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request.Builder {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) b.header(k, v) }
        return b
    }

    private fun httpGet(url: String, headers: Map<String, String>): String =
        client.newCall(buildRequest(url, headers).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

    private fun httpGetBytes(url: String, headers: Map<String, String>) =
        client.newCall(buildRequest(url, headers).build()).execute().let { resp ->
            if (!resp.isSuccessful) {
                resp.close()
                throw IOException("HTTP ${resp.code}")
            }
            resp.body!!.byteStream()
        }

    private fun save(context: Context) {
        val arr = JSONArray()
        library.value.forEach { v ->
            arr.put(
                JSONObject()
                    .put("id", v.id)
                    .put("title", v.title)
                    .put("sourceUrl", v.sourceUrl)
                    .put("filePath", v.filePath)
                    .put("createdAt", v.createdAt)
            )
        }
        SettingsStore.videoDownloadsJson = arr.toString()
    }
}

/**
 * master 清单检测：含 #EXT-X-STREAM-INF → 返回第一个子 m3u8 的绝对 URL（带宽最高未做排序，取首个够用）。
 * 普通媒体清单返回 null。
 */
fun parseHlsMaster(m3u8: String, baseUrl: String): String? {
    val lines = m3u8.lines()
    var takeNext = false
    for (raw in lines) {
        val line = raw.trim()
        if (line.startsWith("#EXT-X-STREAM-INF")) {
            takeNext = true
            continue
        }
        if (takeNext && line.isNotEmpty() && !line.startsWith("#")) {
            return absUrl(line, baseUrl)
        }
    }
    return null
}

/**
 * 媒体清单切片解析：非 # 非空行 = 切片地址（相对路径基于清单 URL 解析）。
 * 遇到 AES-128 加密清单抛异常（解密不做，避免下出来播不了）。
 */
fun parseHlsSegments(m3u8: String, baseUrl: String): List<String> {
    if (Regex("""#EXT-X-KEY:METHOD=AES-128""").containsMatchIn(m3u8)) {
        throw IOException("该视频流已加密（AES-128），暂不支持下载")
    }
    return m3u8.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { absUrl(it, baseUrl) }
}

/** 相对切片地址 → 绝对 URL（URI.resolve 处理 ./ 与 ../） */
internal fun absUrl(seg: String, baseUrl: String): String =
    runCatching { java.net.URI(baseUrl).resolve(seg.trim()).toString() }.getOrDefault(seg.trim())
