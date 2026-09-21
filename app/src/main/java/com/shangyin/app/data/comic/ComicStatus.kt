package com.shangyin.app.data.comic

import android.content.Context
import com.shangyin.app.data.ComicCacheStore
import com.shangyin.app.data.ReadProgressStore
import com.shangyin.app.data.bika.BikaClient
import com.shangyin.app.data.komiic.KomiicClient
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * 漫画更新状态（里世界清单封面角标用）。
 *
 * [hasNew] 是与用户自己的「上次看到」进度对比后的结果：
 * 已读到最新 → false（界面不显示角标）；有新章节 → true（显示「更新 N 话」）。
 * 进度存在 `ReadProgressStore`：Komiic 的值是**章节 id**，哔咔的值是 **order（话数）**。
 */
data class ComicStatus(
    val finished: Boolean,
    val latestChapter: Int?,
    val lastReadChapter: Int?,
    val hasNew: Boolean
) {
    /** 角标文案：已完结 / 更新 N 话 / null（连载中且已读到最新，不显示） */
    val label: String?
        get() = when {
            finished -> "已完结"
            hasNew && latestChapter != null && latestChapter > 0 -> "更新 $latestChapter 话"
            else -> null
        }
}

/**
 * 漫画更新状态仓库：按里世界条目的来源（category = 漫画 / 本子）去各自站点取
 * 「是否完结 + 最新话数 + 章节顺序」，再和本机「上次看到」进度实时比较得出有没有更新。
 *
 * ⚠️ **缓存只存站点侧数据（finished / latest / orderedIds），不存 hasNew**：
 * 用户可能刚把最新一话看完，回到清单就该立刻不再显示「更新」角标，
 * 所以进度比较必须在每次调用时现算（读 SharedPreferences 很便宜）。
 *
 * 内存在内存 + `ComicCacheStore` 磁盘缓存（6 小时），不落库（避免数据库迁移）；
 * 列表可能有几十部漫画，用信号量把并发压到 3。
 */
object ComicStatusStore {

    private val mem = ConcurrentHashMap<String, Snapshot>()
    private val gate = Semaphore(3)
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    /** 站点侧数据快照（Komiic 额外带有序章节 id，用来把进度 id 映射成位置） */
    @Serializable
    private data class Snapshot(
        val finished: Boolean,
        val latest: Int? = null,
        val orderedIds: List<String> = emptyList(),
        val at: Long = 0L
    )

    suspend fun status(context: Context, category: String, id: String): ComicStatus? {
        if (id.isBlank()) return null
        val source = when (category) {
            "漫画" -> "komiic"
            "本子" -> "bika"
            else -> return null
        }
        val key = "${category}_$id"
        val lastReadRaw = withContext(Dispatchers.IO) { ReadProgressStore.get(context, source, id) }

        val cached = mem[key] ?: withContext(Dispatchers.IO) {
            runCatching {
                ComicCacheStore.read(context, "status_$key")?.let { json.decodeFromString<Snapshot>(it) }
            }.getOrNull()?.also { mem[key] = it }
        }
        val snap = cached?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }
            ?: withContext(Dispatchers.IO) {
                gate.withPermit { runCatching { fetch(source, id) }.getOrNull() }
            }?.also { fresh ->
                mem[key] = fresh
                withContext(Dispatchers.IO) {
                    runCatching {
                        ComicCacheStore.write(context, "status_$key", json.encodeToString(fresh))
                    }
                }
            }
            ?: cached   // 刷新失败就用过期缓存兜底

        if (snap == null) return null

        if (source == "komiic") {
            // 进度存的是章节 id：用它在有序章节里的位置判断「是否已读到最新」
            val readIdx = snap.orderedIds.indexOfFirst { it == lastReadRaw }
            val hasNew = when {
                snap.orderedIds.isEmpty() -> false
                lastReadRaw == null -> true        // 没看过：整本都是新的
                readIdx < 0 -> false               // 进度失效（换源/章节变了）→ 不打扰
                else -> readIdx < snap.orderedIds.lastIndex
            }
            return ComicStatus(
                finished = snap.finished,
                latestChapter = snap.latest,
                lastReadChapter = if (readIdx >= 0) readIdx + 1 else null,
                hasNew = hasNew
            )
        }

        // 哔咔：进度就是 order（话数）
        val lastRead = lastReadRaw?.trim()?.toIntOrNull()
        val hasNew = when {
            snap.latest == null -> false
            lastRead == null -> true
            else -> snap.latest > lastRead
        }
        return ComicStatus(
            finished = snap.finished,
            latestChapter = snap.latest,
            lastReadChapter = lastRead,
            hasNew = hasNew
        )
    }

    private suspend fun fetch(source: String, id: String): Snapshot? = when (source) {
        "komiic" -> {
            val comic = KomiicClient.comicById(id)
            val chapters = runCatching { KomiicClient.chapters(id) }.getOrDefault(emptyList())
            // 与详情页一致：按 serial 升序；serial 非数字的排到最后
            val ordered = chapters.sortedBy { it.serial?.toIntOrNull() ?: Int.MAX_VALUE }
            Snapshot(
                finished = comic.status?.uppercase() == "END",
                latest = ordered.mapNotNull { it.serial?.trim()?.toIntOrNull() }.maxOrNull()
                    ?: ordered.size.takeIf { it > 0 },
                orderedIds = ordered.map { it.id },
                at = System.currentTimeMillis()
            )
        }
        "bika" -> {
            // 哔咔：需登录（未登录/被墙时拿不到就不显示角标）
            val token = SettingsStore.bikaToken
            if (token.isBlank()) null
            else {
                val comic = BikaClient.fetchComicDetail(token, id)
                Snapshot(
                    finished = comic.finished,
                    latest = comic.epsCount.takeIf { it > 0 },
                    at = System.currentTimeMillis()
                )
            }
        }
        else -> null
    }
}
