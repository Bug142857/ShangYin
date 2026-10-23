package com.shangyin.app.data.music

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 音乐下载：把当前音源解析出的播放直链存到手机里。
 *
 * - Android 10+（Q）：写公共 `Download/老郑分享/音乐/`（MediaStore，无需存储权限）
 * - Android 8/9：写应用外部私有目录 `Android/data/<包名>/files/Music/`（免权限，路径会提示给用户）
 *
 * v0.302：下载完成后把收藏时的封面 + 标题/歌手/专辑写进音频文件
 * （mp3 写 ID3v2.3，flac 写 PICTURE 块），系统播放器与下载管理都能直接看到封面。
 */
object MusicDownloader {

    /** 文件名非法字符统一换成下划线，避免个别系统写入失败 */
    private val illegal = Regex("""[\\/:*?"<>|]""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 下载并保存。[onProgress] 回传 0..100（拿不到总长度时不回调）。
     * 返回保存位置的人话描述；失败抛异常（消息直接展示给用户）。
     */
    suspend fun download(
        context: Context,
        song: MusicSong,
        onProgress: (Int) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val info = MusicRepo.resolvePlay(song)
        val ext = extensionOf(info.url)
        val fileName = "${sanitize(song.name)} - ${sanitize(song.artists.ifBlank { "未知歌手" })}.$ext"
        val mime = if (ext == "flac") "audio/flac" else "audio/mpeg"

        // 1) 先下到临时文件（进度 0..100）
        val temp = File(context.cacheDir, "dl_${System.currentTimeMillis()}.$ext")
        val request = Request.Builder().url(info.url).apply {
            info.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("下载失败：HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("下载失败：响应为空")
                val total = body.contentLength()
                temp.outputStream().use { out ->
                    copyWithProgress(body.byteStream(), out, total, onProgress)
                }
            }

            // 2) 取收藏时的封面（失败不阻断下载）
            val cover = fetchCover(song.cover)

            // 3) 带元数据写入最终位置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToPublicDownloads(context, fileName, mime, ext, temp, cover, song)
                "Download/老郑分享/音乐/$fileName"
            } else {
                saveToAppDir(context, fileName, ext, temp, cover, song)
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 拉取封面图片字节（带 UA；失败返回 null） */
    private fun fetchCover(coverUrl: String): Pair<ByteArray, String>? = runCatching {
        if (coverUrl.isBlank()) return@runCatching null
        client.newCall(
            Request.Builder().url(coverUrl)
                .header("User-Agent", "Mozilla/5.0 (Android) ShangYin")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val bytes = resp.body?.bytes() ?: return@runCatching null
            if (bytes.isEmpty()) return@runCatching null
            val mime = when {
                bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
                else -> "image/jpeg"
            }
            bytes to mime
        }
    }.getOrNull()

    // ---------------- 落盘 ----------------

    /** Android 10+：MediaStore 公共下载目录（写入期间 IS_PENDING=1，写完再置 0，避免半截文件被扫描到） */
    private fun saveToPublicDownloads(
        context: Context,
        fileName: String,
        mime: String,
        ext: String,
        temp: File,
        cover: Pair<ByteArray, String>?,
        song: MusicSong
    ) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // 同名文件先删掉，避免出现 (1)(2) 副本
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(fileName, "Download/老郑分享/音乐/")
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/老郑分享/音乐/")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("下载失败：无法创建文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                writeWithMeta(temp, out, ext, cover, song)
            } ?: throw IllegalStateException("下载失败：无法写入文件")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }

    /** Android 8/9：写应用外部私有目录（免权限） */
    private fun saveToAppDir(
        context: Context,
        fileName: String,
        ext: String,
        temp: File,
        cover: Pair<ByteArray, String>?,
        song: MusicSong
    ): String {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
            "音乐"
        ).apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { out -> writeWithMeta(temp, out, ext, cover, song) }
        return file.absolutePath
    }

    // ---------------- 元数据写入 ----------------

    /** 把临时音频文件写出：mp3 前置 ID3v2.3，flac 插入 PICTURE 块，其他格式原样拷贝 */
    private fun writeWithMeta(
        temp: File,
        out: OutputStream,
        ext: String,
        cover: Pair<ByteArray, String>?,
        song: MusicSong
    ) {
        when (ext) {
            "mp3" -> {
                id3v23Tag(cover, song).let { if (it.isNotEmpty()) out.write(it) }
                temp.inputStream().use { it.copyTo(out, 64 * 1024) }
            }
            "flac" -> writeFlac(temp, out, cover, song)
            else -> temp.inputStream().use { it.copyTo(out, 64 * 1024) }
        }
        out.flush()
    }

    /** FLAC：读原始 metadata 块，清掉末位标志后追加新 PICTURE 块（标志 last），再接音频帧 */
    private fun writeFlac(
        temp: File,
        out: OutputStream,
        cover: Pair<ByteArray, String>?,
        song: MusicSong
    ) {
        temp.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || !magic.decodeToString().startsWith("fLaC")) {
                // 不是标准 FLAC，原样拷贝
                out.write(magic)
                input.copyTo(out, 64 * 1024)
                return
            }
            out.write(magic)
            // 读出全部 metadata 块（数量有限，通常 < 几十 KB）
            data class Block(val type: Int, val data: ByteArray)
            val blocks = mutableListOf<Block>()
            var lastFlagged = false
            while (!lastFlagged) {
                val h = readFully(input, 4)
                if (h.size < 4) return  // 流异常，直接结束
                lastFlagged = (h[0].toInt() and 0x80) != 0
                val type = h[0].toInt() and 0x7F
                val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                val data = if (len > 0) readFully(input, len) else ByteArray(0)
                // 旧 PICTURE 块丢弃，用新的
                if (type != 6) blocks.add(Block(type, data))
            }
            blocks.forEachIndexed { i, b ->
                val isLast = i == blocks.lastIndex && cover == null
                out.write((if (isLast) 0x80 else 0) or b.type)
                writeUInt24(out, b.data.size)
                out.write(b.data)
            }
            if (cover != null) {
                val pic = flacPictureBlock(cover.first, cover.second)
                out.write(0x80 or 6) // PICTURE，last flag
                writeUInt24(out, pic.size)
                out.write(pic)
            }
            input.copyTo(out, 64 * 1024)
        }
    }

    /** FLAC PICTURE 块（图片类型 3=正面封面，宽高深度给 0 由解码器自取） */
    private fun flacPictureBlock(cover: ByteArray, mime: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) {
            out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
        }
        u32(3)                                  // picture type: front cover
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        u32(mimeBytes.size); out.write(mimeBytes)
        u32(0)                                  // description 空
        u32(0); u32(0); u32(0); u32(0)          // width/height/depth/colors
        u32(cover.size); out.write(cover)
        return out.toByteArray()
    }

    /** ID3v2.3 标签：TIT2 / TPE1 / TALB 文本帧 + APIC 封面帧 */
    private fun id3v23Tag(cover: Pair<ByteArray, String>?, song: MusicSong): ByteArray {
        val frames = ByteArrayOutputStream()
        fun frame(id: String, payload: ByteArray) {
            if (payload.isEmpty()) return
            frames.write(id.toByteArray(Charsets.US_ASCII))
            val size = payload.size
            frames.write((size ushr 24) and 0xFF); frames.write((size ushr 16) and 0xFF)
            frames.write((size ushr 8) and 0xFF); frames.write(size and 0xFF)
            frames.write(0); frames.write(0) // flags
            frames.write(payload)
        }
        fun utf16Text(text: String): ByteArray {
            // encoding 0x01 = UTF-16 带 BOM
            return byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                text.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        }
        frame("TIT2", utf16Text(song.name))
        frame("TPE1", utf16Text(song.artists.ifBlank { "未知歌手" }))
        if (song.album.isNotBlank()) frame("TALB", utf16Text(song.album))
        cover?.let { (bytes, mime) ->
            // APIC（ID3v2.3）：encoding + mime\0 + type + desc\0 + data
            val payload = ByteArrayOutputStream()
            payload.write(0) // latin1
            payload.write(mime.toByteArray(Charsets.US_ASCII))
            payload.write(0)
            payload.write(3) // front cover
            payload.write(0) // 空 description
            payload.write(bytes)
            frame("APIC", payload.toByteArray())
        }
        val body = frames.toByteArray()
        if (body.isEmpty()) return ByteArray(0)
        val tag = ByteArrayOutputStream()
        tag.write('I'.code); tag.write('D'.code); tag.write('3'.code)
        tag.write(3); tag.write(0)   // v2.3
        tag.write(0)                 // flags
        // tag size 用 syncsafe 编码
        val s = body.size
        tag.write((s ushr 21) and 0x7F); tag.write((s ushr 14) and 0x7F)
        tag.write((s ushr 7) and 0x7F); tag.write(s and 0x7F)
        tag.write(body)
        return tag.toByteArray()
    }

    private fun writeUInt24(out: OutputStream, v: Int) {
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    /** 读满 n 字节（兼容低版本 Android，不用 InputStream.readNBytes） */
    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r <= 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private fun copyWithProgress(
        input: InputStream,
        out: OutputStream,
        total: Long,
        onProgress: (Int) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var lastPercent = -1
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
            copied += n
            if (total > 0) {
                val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
        }
        out.flush()
    }

    /** 从直链推断扩展名（各家音源直链以 .mp3 / .flac / .m4a 结尾） */
    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".flac") -> "flac"
            path.endsWith(".m4a") -> "m4a"
            path.endsWith(".wav") -> "wav"
            path.endsWith(".ogg") -> "ogg"
            else -> "mp3"
        }
    }

    private fun sanitize(text: String): String =
        illegal.replace(text, "_").trim().take(60).ifBlank { "未命名" }
}
