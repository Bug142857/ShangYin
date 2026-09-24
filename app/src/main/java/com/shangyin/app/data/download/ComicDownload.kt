package com.shangyin.app.data.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.shangyin.app.ImageDownloader
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 已下载的章节（key 为数据源章节标识：Komiic=chapterId，哔咔=order） */
@Serializable
data class DownloadedChapter(val key: String, val name: String, val count: Int)

/** 已下载的漫画/本子（source: "komiic" / "bika"） */
@Serializable
data class DownloadedComic(
    val source: String,
    val id: String,
    val title: String,
    val cover: String? = null,
    val chapters: List<DownloadedChapter> = emptyList()
)

/**
 * 漫画/本子离线下载存储（**漫画与本子分开两个根目录**）：
 * - 漫画（source != bika）：`Download/老郑分享/漫画/{source}/{id}/meta.json` + `{章节key}/0001.jpg ...`
 * - 本子（source == bika）：`Download/老郑分享/本子/{id}/meta.json` + `{章节key}/0001.jpg ...`
 *
 * Android 10+（Q）走 MediaStore.Downloads 写公共下载目录（无需存储权限，系统文件管理器可直接打开）；
 * Android 8/9 仍写应用私有目录（外部优先，[SettingsStore.downloadInternal] 打开时退回内部 filesDir），
 * 该位置在 Android 11+ 已无法被系统文件管理器打开，仅作为旧数据的兼容读取位置保留。
 */
object ComicDownloadStore {

    private val json = Json { ignoreUnknownKeys = true }

    /** 本子来源标识：单独一个根目录，与漫画分开 */
    const val BIKA = "bika"

    private const val DIR_COMICS = "comics"
    private const val DIR_BIKA = "bika"
    private const val META_NAME = "meta.json"

    /** 漫画/本子在公共下载目录下的相对根（Android 10+；末尾带 / 与 MediaStore 的 RELATIVE_PATH 一致） */
    private const val PUBLIC_COMICS = "Download/老郑分享/漫画/"
    private const val PUBLIC_BIKA = "Download/老郑分享/本子/"

    /**
     * 目录展示信息（下载管理页的目录卡用）：
     * [path] 展示给用户的路径；[isPublic] 是否公共目录；[relative] 仅公共目录有值，供"打开文件夹"跳转。
     */
    data class DirInfo(val path: String, val isPublic: Boolean, val relative: String?)

    /** 某个根目录：外部私有优先，内部开关打开或外部不可用时退回内部私有 */
    private fun baseRoot(context: Context, dirName: String): File {
        val ext = context.getExternalFilesDir(null)
        return if (SettingsStore.downloadInternal || ext == null) File(context.filesDir, dirName)
        else File(ext, dirName)
    }

    /** 漫画根目录（非 bika 来源，结构 {root}/{source}/{id}/…；仅 Android 8/9 的写入位置与旧数据位置） */
    fun comicRoot(context: Context): File = baseRoot(context, DIR_COMICS)

    /** 本子根目录（bika 来源，结构 {root}/{id}/…；仅 Android 8/9 的写入位置与旧数据位置） */
    fun bikaRoot(context: Context): File = baseRoot(context, DIR_BIKA)

    /** 漫画目录展示信息（Android 10+ 为公共目录，8/9 为私有目录绝对路径） */
    fun comicDirInfo(context: Context): DirInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DirInfo(PUBLIC_COMICS.trimEnd('/'), true, PUBLIC_COMICS.trimEnd('/'))
        else DirInfo(comicRoot(context).absolutePath, false, null)

    /** 本子目录展示信息 */
    fun bikaDirInfo(context: Context): DirInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DirInfo(PUBLIC_BIKA.trimEnd('/'), true, PUBLIC_BIKA.trimEnd('/'))
        else DirInfo(bikaRoot(context).absolutePath, false, null)

    /** 某来源应写入的私有根目录 */
    fun root(context: Context, source: String): File =
        if (source == BIKA) bikaRoot(context) else comicRoot(context)

    /** 某来源的候选根目录（外部私有 + 内部私有；扫描用，兼容切换过存储位置） */
    private fun candidateRoots(context: Context, dirName: String): List<File> =
        listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, dirName) },
            File(context.filesDir, dirName)
        ).distinctBy { it.absolutePath }

    /** 单本的私有写入目录：本子在 bika 根下直接是 {id}，漫画在 comics 根下是 {source}/{id} */
    fun comicDir(context: Context, source: String, id: String): File =
        if (source == BIKA) File(bikaRoot(context), id)
        else File(File(comicRoot(context), source), id)

    fun chapterDir(context: Context, source: String, id: String, key: String): File =
        File(comicDir(context, source, id), key)

    /** 单本在公共目录下的相对目录（末尾带 /） */
    private fun publicComicRel(source: String, id: String): String =
        if (source == BIKA) "$PUBLIC_BIKA$id/" else "$PUBLIC_COMICS$source/$id/"

    /** 某章在公共目录下的相对目录（末尾带 /） */
    private fun publicChapterRel(source: String, id: String, key: String): String =
        publicComicRel(source, id) + "$key/"

    /** 同一本书在各存储位置的可能私有目录（含旧位置，兼容迁移前/迁移失败/切换过存储位置） */
    private fun candidateDirs(context: Context, source: String, id: String): List<File> {
        val all = if (source == BIKA) {
            // 本子：新位置 {bika根}/{id}，兼容旧位置 {comics根}/bika/{id}
            candidateRoots(context, DIR_BIKA).map { File(it, id) } +
                candidateRoots(context, DIR_COMICS).map { File(File(it, BIKA), id) }
        } else {
            candidateRoots(context, DIR_COMICS).map { File(File(it, source), id) }
        }
        // 写入位置排最前，优先读最新内容
        return (listOf(comicDir(context, source, id)) + all).distinctBy { it.absolutePath }
    }

    // ---------- 公共目录（Android 10+，MediaStore）读写 ----------

    /** 公共目录下 meta.json 的 content URI（没有则 null） */
    private fun queryMetaUri(context: Context, rel: String): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(rel, META_NAME),
            null
        )?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            ) else null
        }
    }.getOrNull()

    /** 读取公共目录里的 meta.json（读不出返回 null，不抛异常） */
    private fun readMeta(context: Context, uri: Uri): DownloadedComic? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?.let { json.decodeFromString<DownloadedComic>(it) }
    }.getOrNull()

    /** 写公共目录里的 meta.json（同名先删再写，避免出现 (1)(2) 副本） */
    private fun writeMetaPublic(context: Context, rel: String, text: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(rel, META_NAME)
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, META_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("下载失败：无法创建 meta.json")
        try {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw IllegalStateException("下载失败：无法写入 meta.json")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** 某章目录下已有的文件名集合（公共目录，用于断点续传的"已存在则跳过"） */
    private fun queryFileNames(context: Context, rel: String): Set<String> {
        val out = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(rel),
                null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) c.getString(idx)?.let { out += it }
            }
        }
        return out
    }

    /** 公共目录下某章的图片（返回可直接加载的 content:// URL，按文件名正序） */
    private fun queryPagesPublic(context: Context, rel: String): List<String> {
        val out = mutableListOf<Pair<String, String>>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}<>?",
                arrayOf(rel, META_NAME),
                null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx).orEmpty()
                    if (name.isNotBlank()) {
                        out += name to ContentUris.withAppendedId(collection, c.getLong(idIdx)).toString()
                    }
                }
            }
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** 公共目录下某前缀（一本书）的文件总字节数 */
    private fun sizePublic(context: Context, relPrefix: String): Long = runCatching {
        var total = 0L
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$relPrefix%"),
            null
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (c.moveToNext()) total += c.getLong(idx)
        }
        total
    }.getOrDefault(0L)

    /** 扫描公共目录里的全部 meta.json（source/id 取自 meta 内容，不靠路径猜） */
    private fun loadLibraryPublic(context: Context): List<Pair<DownloadedComic, Long>> {
        val out = mutableListOf<Pair<DownloadedComic, Long>>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND (${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)",
                arrayOf(META_NAME, "$PUBLIC_COMICS%", "$PUBLIC_BIKA%"),
                null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val timeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idIdx))
                    val meta = readMeta(context, uri)
                    // DATE_MODIFIED 单位是秒，统一乘 1000 变毫秒
                    if (meta != null) out += meta to (c.getLong(timeIdx) * 1000L)
                }
            }
        }
        return out
    }

    // ---------- 私有目录（Android 8/9 或历史数据）读写 ----------

    private fun loadMetaIn(dir: File): DownloadedComic? {
        val f = File(dir, META_NAME)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<DownloadedComic>(f.readText()) }.getOrNull()
    }

    /** 读某本的 meta：Android 10+ 先看公共目录，再看私有目录（旧数据） */
    fun loadMeta(context: Context, source: String, id: String): DownloadedComic? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMetaUri(context, publicComicRel(source, id) + META_NAME)?.let { uri ->
                readMeta(context, uri)?.let { return it }
            }
        }
        return candidateDirs(context, source, id).firstNotNullOfOrNull { loadMetaIn(it) }
    }

    private fun saveMeta(context: Context, meta: DownloadedComic) {
        val text = json.encodeToString(DownloadedComic.serializer(), meta)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeMetaPublic(context, publicComicRel(meta.source, meta.id), text)
        } else {
            val dir = comicDir(context, meta.source, meta.id).apply { mkdirs() }
            File(dir, META_NAME).writeText(text)
        }
    }

    /** 写入/更新某一章记录（下载完成后调用） */
    fun upsertChapter(context: Context, comic: DownloadedComic, chapter: DownloadedChapter) {
        val cur = loadMeta(context, comic.source, comic.id) ?: comic
        val chapters = cur.chapters.filterNot { it.key == chapter.key } + chapter
        saveMeta(context, cur.copy(title = comic.title, cover = comic.cover ?: cur.cover, chapters = chapters))
    }

    /** 全部可能存在下载记录的私有目录（漫画 {comics根}/{source}/{id}；本子 {bika根}/{id} + 旧位置 {comics根}/bika/{id}） */
    private fun allComicDirs(context: Context): List<File> {
        val out = mutableListOf<File>()
        candidateRoots(context, DIR_COMICS).forEach { root ->
            runCatching { root.listFiles() }.getOrNull().orEmpty().filter { it.isDirectory }.forEach { srcDir ->
                runCatching { srcDir.listFiles() }.getOrNull().orEmpty()
                    .filter { it.isDirectory }.forEach { out += it }
            }
        }
        candidateRoots(context, DIR_BIKA).forEach { root ->
            runCatching { root.listFiles() }.getOrNull().orEmpty()
                .filter { it.isDirectory }.forEach { out += it }
        }
        return out
    }

    /** 扫描全部下载记录（公共 + 私有旧位置；按修改时间倒序，同一本取最新位置；读不出的目录直接跳过） */
    fun loadLibrary(context: Context): List<DownloadedComic> = runCatching {
        val found = mutableListOf<Pair<DownloadedComic, Long>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) found += loadLibraryPublic(context)
        found += allComicDirs(context).mapNotNull { dir ->
            loadMetaIn(dir)?.let { it to dir.lastModified() }
        }
        found.sortedByDescending { it.second }
            .map { it.first }
            .filter { it.chapters.isNotEmpty() }
            .distinctBy { it.source to it.id }
    }.getOrDefault(emptyList())

    /**
     * 章节的图片 URL（按文件名正序，可直接交给 Coil 加载）。
     * Android 10+ 返回公共目录的 `content://`，Android 8/9 返回 `file://`；各存储位置都找，谁有内容用谁。
     */
    fun chapterPages(context: Context, source: String, id: String, key: String): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pages = queryPagesPublic(context, publicChapterRel(source, id, key))
            if (pages.isNotEmpty()) return pages
        }
        return candidateDirs(context, source, id).firstNotNullOfOrNull { dir ->
            runCatching { File(dir, key).listFiles() }.getOrNull().orEmpty()
                .filter { it.isFile && it.name != META_NAME }
                .takeIf { it.isNotEmpty() }
                ?.sortedBy { it.name }
                ?.map { "file://${it.absolutePath}" }
        } ?: emptyList()
    }

    /** 目录占用字节数（公共 + 各私有位置合计） */
    fun size(context: Context, source: String, id: String): Long {
        var total = 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            total += sizePublic(context, publicComicRel(source, id))
        }
        return total + candidateDirs(context, source, id).sumOf { dir ->
            runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        }
    }

    fun deleteChapter(context: Context, source: String, id: String, key: String) {
        // 公共目录（Android 10+）：删该章目录全部文件；meta 里最后一章删完则整本目录一并清掉
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
            val resolver = context.contentResolver
            val comicRel = publicComicRel(source, id)
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(publicChapterRel(source, id, key))
            )
            val meta = queryMetaUri(context, comicRel + META_NAME)?.let { readMeta(context, it) }
            if (meta != null) {
                val left = meta.chapters.filterNot { it.key == key }
                if (left.isEmpty()) {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("$comicRel%")
                    )
                } else {
                    writeMetaPublic(context, comicRel, json.encodeToString(DownloadedComic.serializer(), meta.copy(chapters = left)))
                }
            }
        }
        // 私有目录（Android 8/9 或历史遗留）：各位置的该章目录都删除
        candidateDirs(context, source, id).forEach { dir ->
            runCatching { File(dir, key).deleteRecursively() }
            val metaFile = File(dir, META_NAME)
            if (!metaFile.isFile) return@forEach
            val cur = runCatching { json.decodeFromString<DownloadedComic>(metaFile.readText()) }.getOrNull()
                ?: return@forEach
            val left = cur.chapters.filterNot { it.key == key }
            runCatching {
                if (left.isEmpty()) dir.deleteRecursively()
                else metaFile.writeText(json.encodeToString(DownloadedComic.serializer(), cur.copy(chapters = left)))
            }
        }
    }

    fun deleteComic(context: Context, source: String, id: String) {
        // 公共目录（Android 10+）：按路径前缀删掉整本
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching {
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("${publicComicRel(source, id)}%")
            )
        }
        candidateDirs(context, source, id).forEach { dir ->
            runCatching { dir.deleteRecursively() }
        }
    }

    // ---------- 一次性迁移：comics/bika/** → bika/** ----------

    /**
     * 把旧版存在 `comics/bika/` 里的本子搬到独立根目录 `bika/`（保留 {id} 目录结构）。
     *
     * 触发时机：刷新下载库时（[ComicDownloadManager.refresh]）在 IO 线程调用，也就是
     * "首次读取下载列表"时执行；无需持久化标记——旧目录搬空后会被删除，下次调用直接空跑（幂等）。
     *
     * 防覆盖：目标已存在同名目录时**合并**而不是覆盖/删源——
     * 同名文件保留目标、源文件尽量搬走；meta.json 先按章节取并集（避免"图片搬了记录没搬"）。
     * 失败：单个文件搬不动就留在源目录，下次启动继续尝试，绝不丢数据；整体用 runCatching 包住。
     */
    fun migrateBikaOnce(context: Context) {
        val oldRoots = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, DIR_COMICS) },
            File(context.filesDir, DIR_COMICS)
        )
        val newRoots = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, DIR_BIKA) },
            File(context.filesDir, DIR_BIKA)
        )
        oldRoots.forEachIndexed { i, oldRoot ->
            val newRoot = newRoots.getOrNull(i) ?: return@forEachIndexed
            val oldBika = File(oldRoot, BIKA)
            if (!oldBika.isDirectory) return@forEachIndexed
            runCatching {
                oldBika.listFiles().orEmpty().forEach { src ->
                    val dst = File(newRoot, src.name)
                    if (src.isDirectory && dst.isDirectory) {
                        // 两边都有同名目录：先合并章节记录，再逐文件合并（不覆盖）
                        mergeMeta(File(src, "meta.json"), File(dst, "meta.json"))
                    }
                    mergeMove(src, dst)
                }
                // 旧目录空了才删（还有没搬走的就留着，下次继续）
                if (oldBika.listFiles().orEmpty().isEmpty()) oldBika.delete()
            }
        }
    }

    /** 迁移时合并两边的 meta.json：章节按 key 取并集 */
    private fun mergeMeta(srcMeta: File, dstMeta: File) {
        val src = runCatching { json.decodeFromString<DownloadedComic>(srcMeta.readText()) }.getOrNull() ?: return
        val dst = runCatching { json.decodeFromString<DownloadedComic>(dstMeta.readText()) }.getOrNull() ?: return
        val merged = (dst.chapters + src.chapters).distinctBy { it.key }
        runCatching {
            dstMeta.writeText(json.encodeToString(DownloadedComic.serializer(), dst.copy(chapters = merged)))
        }
    }

    /** 递归合并式移动：目标已存在的文件保留（不覆盖），能移的移走；源目录搬空后删除 */
    private fun mergeMove(src: File, dst: File) {
        if (src.isFile) {
            if (!dst.exists()) {
                dst.parentFile?.mkdirs()
                val moved = runCatching { src.renameTo(dst) }.getOrDefault(false)
                if (!moved) {
                    runCatching { src.copyTo(dst, overwrite = false) }.onSuccess { src.delete() }
                }
            }
            return
        }
        if (!src.isDirectory) return
        dst.mkdirs()
        runCatching { src.listFiles() }.getOrNull().orEmpty().forEach { child ->
            mergeMove(child, File(dst, child.name))
        }
        if (runCatching { src.listFiles() }.getOrNull().isNullOrEmpty()) src.delete()
    }

    /**
     * 下载一章的全部图片（已存在的文件跳过 → 支持中断后继续）。
     * Android 10+ 写公共下载目录（MediaStore，IS_PENDING 保护），8/9 写私有目录。
     * @param onProgress (已完成张数, 总张数)
     */
    suspend fun downloadChapter(
        context: Context,
        comic: DownloadedComic,
        chapter: DownloadedChapter,
        urls: List<String>,
        onProgress: (Int, Int) -> Unit
    ): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return downloadChapterPublic(context, comic, chapter, urls, onProgress)
        }
        val dir = chapterDir(context, comic.source, comic.id, chapter.key).apply { mkdirs() }
        var done = 0
        urls.forEachIndexed { i, url ->
            val prefix = "%04d".format(i + 1)
            val exists = dir.listFiles()?.any { it.name.startsWith("$prefix.") } == true
            if (!exists) {
                val (bytes, contentType) = ImageDownloader.fetchBytes(url)
                File(dir, "$prefix.${ImageDownloader.extOf(url, contentType)}").writeBytes(bytes)
            }
            done++
            onProgress(done, urls.size)
        }
        return done
    }

    /** Android 10+：逐张写公共下载目录（同名同前缀已存在则跳过 → 支持中断后继续） */
    private suspend fun downloadChapterPublic(
        context: Context,
        comic: DownloadedComic,
        chapter: DownloadedChapter,
        urls: List<String>,
        onProgress: (Int, Int) -> Unit
    ): Int {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val rel = publicChapterRel(comic.source, comic.id, chapter.key)
        val existing = queryFileNames(context, rel)
        var done = 0
        urls.forEachIndexed { i, url ->
            val prefix = "%04d".format(i + 1)
            if (existing.none { it.startsWith("$prefix.") }) {
                val (bytes, contentType) = ImageDownloader.fetchBytes(url)
                val ext = ImageDownloader.extOf(url, contentType)
                val name = "$prefix.$ext"
                // 同名先删，避免 insert 时生成 (1) 副本
                runCatching {
                    resolver.delete(
                        collection,
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(rel, name)
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, imageMime(ext))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    // 写入期间 IS_PENDING=1，写完再置 0，避免半截文件被系统扫描到
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("下载失败：无法创建文件 $name")
                try {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("下载失败：无法写入文件 $name")
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null, null
                    )
                } catch (e: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            }
            done++
            onProgress(done, urls.size)
        }
        return done
    }

    /** 图片 MIME（jpg 用标准写法 image/jpeg） */
    private fun imageMime(ext: String): String = if (ext == "jpg" || ext == "jpeg") "image/jpeg" else "image/$ext"

    /** 人类可读大小 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * 下载管理器：后台串行下载（离开页面不中断），并对外暴露
 * - library：已下载库（详情页判断"已下载"、下载管理页展示）
 * - active：进行中的任务（key = source/id/chapterKey）
 */
object ComicDownloadManager {

    data class Task(
        val source: String,
        val id: String,
        val title: String,
        val chapterKey: String,
        val chapterName: String,
        val done: Int = 0,
        val total: Int = 0,
        val error: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // ⚠️ 并发 Map：enqueue 在 UI 线程写、协程结束在 IO 线程删，普通 HashMap 会有竞态
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val _library = MutableStateFlow<List<DownloadedComic>>(emptyList())
    val library: StateFlow<List<DownloadedComic>> = _library.asStateFlow()

    private val _active = MutableStateFlow<Map<String, Task>>(emptyMap())
    val active: StateFlow<Map<String, Task>> = _active.asStateFlow()

    fun key(source: String, id: String, chapterKey: String) = "$source/$id/$chapterKey"

    /** 从磁盘刷新已下载库（进入详情页/下载页时调用一次即可）；顺带做一次本子目录迁移（幂等） */
    suspend fun refresh(context: Context) {
        val ctx = context.applicationContext
        withContext(Dispatchers.IO) {
            ComicDownloadStore.migrateBikaOnce(ctx)
            _library.value = ComicDownloadStore.loadLibrary(ctx)
        }
    }

    /** 已完成下载的章节 key 集合 */
    fun downloadedKeys(source: String, id: String): Set<String> =
        _library.value.firstOrNull { it.source == source && it.id == id }
            ?.chapters?.map { it.key }?.toSet() ?: emptySet()

    /**
     * 批量入队下载（整套/单章共用）。
     * @param fetchImages 取某章图片地址（各数据源不同）
     */
    fun enqueue(
        context: Context,
        comic: DownloadedComic,
        chapters: List<DownloadedChapter>,
        fetchImages: suspend (chapterKey: String) -> List<String>
    ) {
        val ctx = context.applicationContext
        val done = downloadedKeys(comic.source, comic.id)
        chapters.filterNot { it.key in done }.forEach { ch ->
            val k = key(comic.source, comic.id, ch.key)
            val running = _active.value[k]
            if (running?.error == null && running != null) return@forEach
            _active.update {
                it + (k to Task(comic.source, comic.id, comic.title, ch.key, ch.name))
            }
            jobs[k] = scope.launch {
                runCatching {
                    val urls = fetchImages(ch.key)
                    ComicDownloadStore.downloadChapter(ctx, comic, ch, urls) { d, t ->
                        _active.update { m -> m[k]?.let { cur -> m + (k to cur.copy(done = d, total = t)) } ?: m }
                    }
                    ComicDownloadStore.upsertChapter(ctx, comic, DownloadedChapter(ch.key, ch.name, urls.size))
                    refresh(ctx)
                    _active.update { it - k }
                }.onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) {
                        // 主动取消：清掉下载到一半的章节文件，不留半成品
                        runCatching { ComicDownloadStore.deleteChapter(ctx, comic.source, comic.id, ch.key) }
                        throw e
                    }
                    _active.update { m ->
                        m[k]?.let { cur -> m + (k to cur.copy(error = e.message ?: "下载失败")) } ?: m
                    }
                }
                jobs.remove(k)
            }
        }
    }

    /** 取消下载（同时清理该章已下载到一半的文件） */
    fun cancel(source: String, id: String, chapterKey: String) {
        val k = key(source, id, chapterKey)
        jobs.remove(k)?.cancel()
        _active.update { it - k }
    }

    /** 全部取消（同时清理各任务下载到一半的文件） */
    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _active.value = emptyMap()
    }

    /** 清除失败任务（便于重试） */
    fun dismiss(source: String, id: String, chapterKey: String) {
        _active.update { it - key(source, id, chapterKey) }
    }

    /** 删除已下载章节/整套，同步刷新库 */
    fun deleteChapter(context: Context, source: String, id: String, chapterKey: String) {
        val ctx = context.applicationContext
        scope.launch {
            ComicDownloadStore.deleteChapter(ctx, source, id, chapterKey)
            refresh(ctx)
        }
    }

    fun deleteComic(context: Context, source: String, id: String) {
        val ctx = context.applicationContext
        scope.launch {
            ComicDownloadStore.deleteComic(ctx, source, id)
            refresh(ctx)
        }
    }
}
