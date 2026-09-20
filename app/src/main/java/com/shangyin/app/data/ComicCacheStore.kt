package com.shangyin.app.data

import android.content.Context
import java.io.File

/**
 * 本子 / 漫画「详情 + 章节列表」的磁盘缓存。
 *
 * 目的：看过的内容再次打开（含 App 重启后）直接展示缓存内容，不再空等重新联网加载。
 * 位置：`filesDir/comic_cache/<source>_<id>.json`，内容是各页面自己的 JSON 结构。
 * 注意：缓存只负责"先展示"，页面仍会在后台静默刷新，保证章节列表不会过期。
 */
object ComicCacheStore {

    private const val DIR = "comic_cache"

    private fun fileOf(context: Context, key: String): File =
        File(File(context.filesDir, DIR), key.replace('/', '_') + ".json")

    fun read(context: Context, key: String): String? = runCatching {
        val f = fileOf(context, key)
        if (f.exists()) f.readText() else null
    }.getOrNull()

    fun write(context: Context, key: String, json: String) {
        runCatching {
            val f = fileOf(context, key)
            f.parentFile?.mkdirs()
            f.writeText(json)
        }
    }
}