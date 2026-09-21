package com.shangyin.app.data.comic

import android.content.Context
import com.shangyin.app.data.ComicCacheStore
import com.shangyin.app.data.bika.BikaClient
import com.shangyin.app.data.komiic.KomiicClient
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** 漫画更新状态（里世界清单封面角标用） */
data class ComicStatus(val finished: Boolean, val latestChapter: Int?) {
    /** 角标文案：完结 / 更新 N 话 / 连载中 */
    val label: String
        get() = when {
            finished -> "已完结"
            latestChapter != null && latestChapter > 0 -> "更新 $latestChapter 话"
            else -> "连载中"
        }
}

/**
 * 漫画更新状态仓库：按里世界条目的来源（category = 漫画 / 本子）去各自站点取
 * 「是否完结 + 最新话数」，带会话内存缓存 + 磁盘缓存（6 小时），
 * 供清单页封面角标使用（不落库，避免迁移）。
 *
 * 列表可能有几十部漫画，这里用信号量把并发压到 3，避免把站点打崩。
 */
object ComicStatusStore {

    private val mem = ConcurrentHashMap<String, ComicStatus>()
    private val gate = Semaphore(3)
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Cache(val finished: Boolean, val latest: Int? = null, val at: Long = 0L)

    suspend fun status(context: Context, category: String, id: String): ComicStatus? {
        if (id.isBlank()) return null
        if (category != "漫画" && category != "本子") return null
        val key = "${category}_$id"
        mem[key]?.let { return it }

        val cached = withContext(Dispatchers.IO) {
            runCatching {
                ComicCacheStore.read(context, "status_$key")?.let { json.decodeFromString<Cache>(it) }
            }.getOrNull()
        }
        if (cached != null) {
            val st = ComicStatus(cached.finished, cached.latest)
            if (System.currentTimeMillis() - cached.at < TTL_MS) {
                mem[key] = st
                return st
            }
            // 过期也先留作兜底值，下面尝试刷新
        }

        val fresh = withContext(Dispatchers.IO) {
            gate.withPermit { runCatching { fetch(category, id) }.getOrNull() }
        }
        if (fresh != null) {
            mem[key] = fresh
            withContext(Dispatchers.IO) {
                runCatching {
                    ComicCacheStore.write(
                        context,
                        "status_$key",
                        json.encodeToString(Cache(fresh.finished, fresh.latestChapter, System.currentTimeMillis()))
                    )
                }
            }
            return fresh
        }
        return cached?.let { ComicStatus(it.finished, it.latest) }
    }

    private suspend fun fetch(category: String, id: String): ComicStatus? = when (category) {
        "漫画" -> {
            // Komiic：status = ONGOING / END；最新话数取章节 serial 最大值（取不到用章节数）
            val comic = KomiicClient.comicById(id)
            val chapters = runCatching { KomiicClient.chapters(id) }.getOrDefault(emptyList())
            val latest = chapters.mapNotNull { it.serial?.trim()?.toIntOrNull() }.maxOrNull()
                ?: chapters.size.takeIf { it > 0 }
            ComicStatus(finished = comic.status?.uppercase() == "END", latestChapter = latest)
        }
        "本子" -> {
            // 哔咔：需登录（未登录/被墙时拿不到就不显示角标）
            val token = SettingsStore.bikaToken
            if (token.isBlank()) null
            else {
                val comic = BikaClient.fetchComicDetail(token, id)
                ComicStatus(finished = comic.finished, latestChapter = comic.epsCount.takeIf { it > 0 })
            }
        }
        else -> null
    }
}
