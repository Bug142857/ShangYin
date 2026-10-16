package com.shangyin.app.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 已下载完成的视频记录（location = 播放地址：content:// 或文件绝对路径） */
data class DownloadedVideo(
    val id: String,          // 时间戳
    val title: String,
    val sourceUrl: String,   // 原始 m3u8/mp4 地址
    val location: String,    // 播放地址（content://xxx 或 file 路径），播放时 Uri.parse
    val displayPath: String = "",  // 展示用相对路径（Download/老郑分享/视频/xxx.ts）
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

/** HLS AES-128 密钥信息 */
data class HlsKeyInfo(val uri: String, val ivHex: String?)

/** 结构化 HLS 清单：AES-128 时携带 key；master 清单时携带首个子清单地址（v0.225 支持解密下载） */
data class HlsPlaylist(val key: HlsKeyInfo?, val segments: List<String>, val masterChild: String? = null)

/**
 * 视频下载器（v0.225）：HLS 切片下载（AES-128 自动解密）合并 + mp4 直链。
 * 存储与音乐/书籍统一（v0.225）：Android 10+ 公共目录 `Download/老郑分享/视频/`
 * （MediaStore.Downloads，MIME video/mp2t，文件管理器可见）；Android 8/9 应用私有外存。
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

    /** Android 8/9 的兜底目录（Q+ 走 MediaStore 公共目录） */
    fun videosDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "视频")

    /** 下载管理页展示的目录说明（Q+ 公共目录，8/9 私有） */
    fun dirInfo(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaFileStore.VIDEO_RELATIVE
        else videosDir(context).absolutePath

    fun refresh(context: Context) {
        val list = runCatching {
            val arr = JSONArray(SettingsStore.videoDownloadsJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val loc = o.optString("location")
                val exists = if (loc.startsWith("content:")) {
                    runCatching {
                        context.contentResolver.query(
                            Uri.parse(loc), arrayOf(MediaStore.MediaColumns._ID), null, null, null
                        )?.use { it.moveToFirst() } ?: false
                    }.getOrDefault(false)
                } else File(loc).isFile
                if (!exists) return@mapNotNull null // 文件被清掉 → 记录作废
                DownloadedVideo(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    sourceUrl = o.optString("sourceUrl"),
                    location = loc,
                    displayPath = o.optString("displayPath"),
                    sizeBytes = o.optLong("sizeBytes"),
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
                val video = withContext(Dispatchers.IO) { run(appContext, task, headers) }
                library.value = library.value + video
                save(appContext)
                notifyDone(appContext, task.title, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                active.value = active.value + (url to task.copy(error = e.message ?: "下载失败"))
                notifyDone(appContext, task.title, e.message ?: "下载失败")
            } finally {
                jobs.remove(url)
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
        runCatching {
            if (video.location.startsWith("content:")) {
                context.contentResolver.delete(Uri.parse(video.location), null, null)
            } else {
                File(video.location).delete()
            }
        }
        library.value = library.value.filterNot { it.id == video.id }
        save(context)
    }

    // ---------- 内部实现 ----------

    /** 下载完成/失败轻提示（应用级 Toast，无需通知权限） */
    private fun notifyDone(context: Context, title: String, error: String?) {
        android.os.Handler(context.mainLooper).post {
            android.widget.Toast.makeText(
                context,
                if (error == null) "「$title」下载完成，已保存到下载管理的视频列表"
                else "「$title」下载失败：$error",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun tmpDir(context: Context, url: String): File {
        // 同一 URL 的临时目录名稳定（hash），重试可复用；放 cacheDir（拼接到最终目标后删除）
        return File(context.cacheDir, "vdl-${url.hashCode().toUInt()}")
    }

    private fun safeName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)

    private suspend fun run(context: Context, task: VideoTask, headers: Map<String, String>): DownloadedVideo {
        val name = safeName(task.title)
        val createdAt = System.currentTimeMillis()
        val isHls = task.url.substringBefore('?').endsWith(".m3u8")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToPublicDownloads(context, task, headers, name, createdAt, isHls)
        } else {
            saveToAppDir(context, task, headers, name, createdAt, isHls)
        }
    }

    /** Android 10+：MediaStore 公共下载目录 Download/老郑分享/视频/（与音乐/书籍统一） */
    private suspend fun saveToPublicDownloads(
        context: Context, task: VideoTask, headers: Map<String, String>,
        name: String, createdAt: Long, isHls: Boolean
    ): DownloadedVideo {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val rel = MediaFileStore.VIDEO_RELATIVE
        val mime = if (isHls) "video/mp2t" else "video/mp4"
        val fileName = if (isHls) "$name.ts" else "$name.mp4"
        // 同名先删，避免 (1)(2) 副本
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(fileName, rel)
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                if (isHls) downloadHls(context, task, headers, out)
                else downloadDirect(task, headers, out)
            }
            val size = runCatching {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) it.getLong(0) else 0L
                } ?: 0L
            }.getOrDefault(0L)
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return DownloadedVideo(
                id = createdAt.toString(),
                title = task.title,
                sourceUrl = task.url,
                location = uri.toString(),
                displayPath = "$rel$fileName",
                sizeBytes = size,
                createdAt = createdAt
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** Android 8/9：应用私有外存（无 MediaStore 支持） */
    private suspend fun saveToAppDir(
        context: Context, task: VideoTask, headers: Map<String, String>,
        name: String, createdAt: Long, isHls: Boolean
    ): DownloadedVideo {
        val outDir = videosDir(context).apply { mkdirs() }
        val out = File(outDir, if (isHls) "$name.ts" else "$name.mp4")
        out.outputStream().use {
            if (isHls) downloadHls(context, task, headers, it)
            else downloadDirect(task, headers, it)
        }
        return DownloadedVideo(
            id = createdAt.toString(),
            title = task.title,
            sourceUrl = task.url,
            location = out.absolutePath,
            displayPath = out.absolutePath,
            sizeBytes = out.length(),
            createdAt = createdAt
        )
    }

    /** HLS：解析清单 →（master 取子清单）→（AES-128 时下载密钥）→ 并发下载（解密）切片 → 顺序拼接写入 out */
    private suspend fun downloadHls(context: Context, task: VideoTask, headers: Map<String, String>, out: java.io.OutputStream) {
        var playlist = parseHlsPlaylist(httpGet(task.url, headers), task.url)
        if (playlist.segments.isEmpty() && playlist.masterChild != null) {
            val child = playlist.masterChild!!
            playlist = parseHlsPlaylist(httpGet(child, headers), child)
        }
        val segments = playlist.segments
        if (segments.isEmpty()) throw IOException("未解析到视频切片")
        // AES-128 密钥（显式 IV；缺省按 HLS 规范用媒体序号——本站给了显式 IV）
        val keyBytes: ByteArray? = playlist.key?.let { k ->
            httpGetBytes(k.uri, headers).use { it.readBytes() }.also {
                if (it.size != 16) throw IOException("密钥长度异常(${it.size}B)")
            }
        }
        val tmp = tmpDir(context, task.url).apply { mkdirs() }
        try {
            val semaphore = Semaphore(3)
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
                                    val raw = httpGetBytes(seg, headers).use { s -> s.readBytes() }
                                    val plain = if (keyBytes != null) aesDecrypt(raw, keyBytes, playlist.key!!.ivHex, idx) else raw
                                    f.writeBytes(plain)
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
            segments.indices.forEach { idx -> File(tmp, "seg-%05d".format(idx)).inputStream().use { it.copyTo(out) } }
        } finally {
            if (jobs[task.url] == null || active.value[task.url]?.error != null) {
                // 失败/取消时清临时片；成功路径由 cancel/正常结束统一清
            }
            tmp.deleteRecursively()
        }
    }

    /** AES-128-CBC/PKCS5 解密：显式 IV 优先；无 IV 按 HLS 规范 = 媒体序号 64bit 大端 */
    internal fun aesDecrypt(data: ByteArray, key: ByteArray, ivHex: String?, sequence: Int): ByteArray {
        val iv: ByteArray = if (!ivHex.isNullOrBlank()) {
            val h = ivHex.removePrefix("0x")
            (0 until 16).map { i ->
                ((h.getOrNull(i * 2)?.digitToIntOrNull(16) ?: 0).shl(4) or
                        (h.getOrNull(i * 2 + 1)?.digitToIntOrNull(16) ?: 0)).toByte()
            }.toByteArray()
        } else {
            ByteArray(8) + ByteArray(8).also { it[7] = sequence.toByte() }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** mp4 等直链：流式拷贝 + 进度按字节比例 */
    private fun downloadDirect(task: VideoTask, headers: Map<String, String>, out: java.io.OutputStream) {
        val req = buildRequest(task.url, headers).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val total = resp.body?.contentLength() ?: -1L
            resp.body!!.byteStream().use { input ->
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

    private fun httpGetBytes(url: String, headers: Map<String, String>): InputStream =
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
                    .put("location", v.location)
                    .put("displayPath", v.displayPath)
                    .put("sizeBytes", v.sizeBytes)
                    .put("createdAt", v.createdAt)
            )
        }
        SettingsStore.videoDownloadsJson = arr.toString()
    }
}

/**
 * 结构化 HLS 清单解析（v0.225）：非 # 非空行 = 切片；AES-128 KEY 行解析出密钥 URI 与 IV
 * （不再抛异常——支持解密下载）。仅 METHOD=AES-128；NONE 忽略。
 * master 清单（含 #EXT-X-STREAM-INF）取首个子清单地址到 [HlsPlaylist.masterChild]，
 * 由调用方拉取子清单后重解析。
 */
fun parseHlsPlaylist(m3u8: String, baseUrl: String): HlsPlaylist {
    var key: HlsKeyInfo? = null
    var masterChild: String? = null
    var takeNext = false
    val segments = mutableListOf<String>()
    for (raw in m3u8.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXT-X-STREAM-INF") -> { takeNext = true; continue }
            line.startsWith("#EXT-X-KEY") -> {
                val method = Regex("""METHOD=([A-Za-z0-9-]+)""").find(line)?.groupValues?.get(1) ?: ""
                if (method == "AES-128") {
                    val uri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                        ?: throw IOException("AES-128 清单缺少密钥地址")
                    val iv = Regex("""IV=(0x[0-9a-fA-F]+)""").find(line)?.groupValues?.get(1)
                    key = HlsKeyInfo(absUrl(uri, baseUrl), iv)
                }
            }
            line.isNotEmpty() && !line.startsWith("#") -> {
                if (takeNext && masterChild == null) {
                    masterChild = absUrl(line, baseUrl) // master：下一行为子清单地址
                    takeNext = false
                } else {
                    segments += absUrl(line, baseUrl)
                }
            }
        }
    }
    return HlsPlaylist(key, segments, masterChild)
}

/** 相对切片地址 → 绝对 URL（URI.resolve 处理 ./ 与 ../） */
internal fun absUrl(seg: String, baseUrl: String): String =
    runCatching { java.net.URI(baseUrl).resolve(seg.trim()).toString() }.getOrDefault(seg.trim())
