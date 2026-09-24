package com.shangyin.app.data.download

import android.content.Context
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
 * - 漫画（source != bika）：{外部私有目录}/comics/{source}/{id}/meta.json + {章节key}/0001.jpg ...
 * - 本子（source == bika）：{外部私有目录}/bika/{id}/meta.json + {章节key}/0001.jpg ...
 * 使用应用外部私有目录，无需任何存储权限，卸载应用时一并清除；
 * 设置里打开"内部存储"（[SettingsStore.downloadInternal]）时退回到内部私有目录（filesDir 下的同名目录）。
 */
object ComicDownloadStore {

    private val json = Json { ignoreUnknownKeys = true }

    /** 本子来源标识：单独一个根目录，与漫画分开 */
    const val BIKA = "bika"

    private const val DIR_COMICS = "comics"
    private const val DIR_BIKA = "bika"

    /** 某个根目录：外部私有优先，内部开关打开或外部不可用时退回内部私有 */
    private fun baseRoot(context: Context, dirName: String): File {
        val ext = context.getExternalFilesDir(null)
        return if (SettingsStore.downloadInternal || ext == null) File(context.filesDir, dirName)
        else File(ext, dirName)
    }

    /** 漫画根目录（非 bika 来源，结构 {root}/{source}/{id}/…） */
    fun comicRoot(context: Context): File = baseRoot(context, DIR_COMICS)

    /** 本子根目录（bika 来源，结构 {root}/{id}/…，与漫画不再共用一个根目录） */
    fun bikaRoot(context: Context): File = baseRoot(context, DIR_BIKA)

    /** 某来源应写入的根目录 */
    fun root(context: Context, source: String): File =
        if (source == BIKA) bikaRoot(context) else comicRoot(context)

    /** 某来源的候选根目录（外部私有 + 内部私有；扫描用，兼容切换过存储位置） */
    private fun candidateRoots(context: Context, dirName: String): List<File> =
        listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, dirName) },
            File(context.filesDir, dirName)
        ).distinctBy { it.absolutePath }

    /** 单本的写入目录：本子在 bika 根下直接是 {id}，漫画在 comics 根下是 {source}/{id} */
    fun comicDir(context: Context, source: String, id: String): File =
        if (source == BIKA) File(bikaRoot(context), id)
        else File(File(comicRoot(context), source), id)

    fun chapterDir(context: Context, source: String, id: String, key: String): File =
        File(comicDir(context, source, id), key)

    /** 同一本书在各存储位置的可能目录（含旧位置，兼容迁移前/迁移失败/切换过存储位置） */
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

    private fun loadMetaIn(dir: File): DownloadedComic? {
        val f = File(dir, "meta.json")
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<DownloadedComic>(f.readText()) }.getOrNull()
    }

    fun loadMeta(context: Context, source: String, id: String): DownloadedComic? =
        candidateDirs(context, source, id).firstNotNullOfOrNull { loadMetaIn(it) }

    private fun saveMeta(context: Context, meta: DownloadedComic) {
        val dir = comicDir(context, meta.source, meta.id).apply { mkdirs() }
        File(dir, "meta.json").writeText(json.encodeToString(DownloadedComic.serializer(), meta))
    }

    /** 写入/更新某一章记录（下载完成后调用） */
    fun upsertChapter(context: Context, comic: DownloadedComic, chapter: DownloadedChapter) {
        val cur = loadMeta(context, comic.source, comic.id) ?: comic
        val chapters = cur.chapters.filterNot { it.key == chapter.key } + chapter
        saveMeta(context, cur.copy(title = comic.title, cover = comic.cover ?: cur.cover, chapters = chapters))
    }

    /** 全部可能存在下载记录的目录（漫画 {comics根}/{source}/{id}；本子 {bika根}/{id} + 旧位置 {comics根}/bika/{id}） */
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

    /** 扫描全部下载记录（按目录修改时间倒序，同一本取最新位置；读不出的目录直接跳过） */
    fun loadLibrary(context: Context): List<DownloadedComic> = runCatching {
        allComicDirs(context)
            .mapNotNull { dir ->
                loadMetaIn(dir)?.takeIf { it.chapters.isNotEmpty() }?.let { it to dir.lastModified() }
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.source to it.id }
    }.getOrDefault(emptyList())

    /** 章节的本地图片路径（按文件名正序；各存储位置都找，谁有内容用谁） */
    fun chapterPages(context: Context, source: String, id: String, key: String): List<String> =
        candidateDirs(context, source, id).firstNotNullOfOrNull { dir ->
            runCatching { File(dir, key).listFiles() }.getOrNull().orEmpty()
                .filter { it.isFile && it.name != "meta.json" }
                .takeIf { it.isNotEmpty() }
                ?.sortedBy { it.name }
                ?.map { it.absolutePath }
        } ?: emptyList()

    /** 目录占用字节数（各存储位置合计） */
    fun size(context: Context, source: String, id: String): Long =
        candidateDirs(context, source, id).sumOf { dir ->
            runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        }

    fun deleteChapter(context: Context, source: String, id: String, key: String) {
        // 各位置的该章目录都删除；meta 里最后一章删完则整本目录一并清掉
        candidateDirs(context, source, id).forEach { dir ->
            runCatching { File(dir, key).deleteRecursively() }
            val metaFile = File(dir, "meta.json")
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
     * @param onProgress (已完成张数, 总张数)
     */
    suspend fun downloadChapter(
        context: Context,
        comic: DownloadedComic,
        chapter: DownloadedChapter,
        urls: List<String>,
        onProgress: (Int, Int) -> Unit
    ): Int {
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
