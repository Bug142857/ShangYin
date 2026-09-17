package com.shangyin.app.data.download

import android.content.Context
import com.shangyin.app.ImageDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * 漫画/本子离线下载存储：
 * 目录 {外部私有目录}/comics/{source}/{id}/meta.json + {章节key}/0001.jpg ...
 * 使用应用外部私有目录，无需任何存储权限，卸载应用时一并清除。
 */
object ComicDownloadStore {

    private val json = Json { ignoreUnknownKeys = true }

    fun root(context: Context): File = File(context.getExternalFilesDir(null), "comics")

    fun comicDir(context: Context, source: String, id: String): File =
        File(root(context), "$source/$id")

    fun chapterDir(context: Context, source: String, id: String, key: String): File =
        File(comicDir(context, source, id), key)

    fun loadMeta(context: Context, source: String, id: String): DownloadedComic? {
        val f = File(comicDir(context, source, id), "meta.json")
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<DownloadedComic>(f.readText()) }.getOrNull()
    }

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

    /** 扫描磁盘上的全部下载记录（按加入时间倒序：以目录修改时间为准） */
    fun loadLibrary(context: Context): List<DownloadedComic> {
        val root = root(context)
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { srcDir ->
            srcDir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { comic ->
                loadMeta(context, srcDir.name, comic.name)?.takeIf { it.chapters.isNotEmpty() }
                    ?.let { it to comic.lastModified() }
            }
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** 章节的本地图片路径（按文件名正序） */
    fun chapterPages(context: Context, source: String, id: String, key: String): List<String> =
        chapterDir(context, source, id, key).listFiles().orEmpty()
            .filter { it.isFile && it.name != "meta.json" }
            .sortedBy { it.name }
            .map { it.absolutePath }

    /** 目录占用字节数 */
    fun size(context: Context, source: String, id: String): Long {
        val dir = comicDir(context, source, id)
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    fun deleteChapter(context: Context, source: String, id: String, key: String) {
        chapterDir(context, source, id, key).deleteRecursively()
        val cur = loadMeta(context, source, id) ?: return
        val left = cur.chapters.filterNot { it.key == key }
        if (left.isEmpty()) deleteComic(context, source, id) else saveMeta(context, cur.copy(chapters = left))
    }

    fun deleteComic(context: Context, source: String, id: String) {
        comicDir(context, source, id).deleteRecursively()
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
    private val jobs = mutableMapOf<String, Job>()

    private val _library = MutableStateFlow<List<DownloadedComic>>(emptyList())
    val library: StateFlow<List<DownloadedComic>> = _library.asStateFlow()

    private val _active = MutableStateFlow<Map<String, Task>>(emptyMap())
    val active: StateFlow<Map<String, Task>> = _active.asStateFlow()

    fun key(source: String, id: String, chapterKey: String) = "$source/$id/$chapterKey"

    /** 从磁盘刷新已下载库（进入详情页/下载页时调用一次即可） */
    suspend fun refresh(context: Context) {
        _library.value = ComicDownloadStore.loadLibrary(context.applicationContext)
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _active.update { m ->
                        m[k]?.let { cur -> m + (k to cur.copy(error = e.message ?: "下载失败")) } ?: m
                    }
                }
                jobs.remove(k)
            }
        }
    }

    fun cancel(source: String, id: String, chapterKey: String) {
        val k = key(source, id, chapterKey)
        jobs.remove(k)?.cancel()
        _active.update { it - k }
    }

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