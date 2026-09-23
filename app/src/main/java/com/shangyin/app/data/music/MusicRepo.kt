package com.shangyin.app.data.music

import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.CollectionItemEntity
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * 音乐数据仓库：把「内置平台接口（搜索/榜单/歌词）」与「LX 自定义音源（播放直链）」拼成完整链路，
 * 并负责歌曲在里世界清单里的收藏与还原。
 *
 * 收藏口径（与番号/本子/漫画一致）：条目 category = "音乐"，doubanId = "{平台}|{歌曲ID}"，
 * 歌名 → title、歌手 → subTitle、封面 → coverUrl，音源解析需要的原始字段存 info(JSON)。
 */
object MusicRepo {

    /** 音乐收藏条目的分类名（里世界清单里与 番号/本子/漫画/游戏/图书/电视 同级） */
    const val CATEGORY = "音乐"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 直链缓存：songKey → (url, 解析时间)；音源直链有时效，过期后重新解析 */
    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val URL_TTL_MS = 4 * 60 * 1000L

    // ---------------- 搜索 / 榜单 / 歌词（内置平台接口） ----------------

    suspend fun search(platform: MusicPlatform, keyword: String, page: Int = 1): List<MusicSong> =
        MusicApis.search(platform, keyword, page)

    suspend fun lyric(song: MusicSong): MusicLyric = MusicApis.lyric(song)

    /** 收藏/清空直链缓存（换音源后强制重解析） */
    fun clearUrlCache() = urlCache.clear()

    // ---------------- 播放直链（LX 音源） ----------------

    /** 当前默认音质（设置里选，音源不支持时自动降级） */
    fun preferredQuality(): MusicQuality = MusicQuality.of(SettingsStore.musicQuality)

    /**
     * 解析播放直链：按已启用音源顺序尝试，音源不支持的音质自动往下降级。
     * 全部失败抛 [MusicResolveException]（消息直接展示给用户，不静默吞）。
     */
    suspend fun resolvePlay(song: MusicSong, quality: MusicQuality = preferredQuality()): MusicPlayInfo {
        val cached = urlCache[song.key]
        if (cached != null && System.currentTimeMillis() - cached.second < URL_TTL_MS) {
            return MusicPlayInfo(cached.first, MusicPlayHeaders.forPlatform(song.platform))
        }
        MusicSourceStore.ensureInitialized()
        val scripts = MusicSourceStore.enabledFor(song.platform)
        if (scripts.isEmpty()) {
            throw MusicResolveException(noSourceMessage(song.platform))
        }
        val errors = mutableListOf<String>()
        for (script in scripts) {
            val qualities = script.qualitiesOf(song.platform)
                .filter { it.level <= quality.level }
                .ifEmpty { script.qualitiesOf(song.platform) }
            for (q in qualities) {
                val url = try {
                    LxSourceEngine.resolveUrl(script, song.platform, q, song)
                } catch (e: Exception) {
                    errors += "${script.name}（${q.label}）：${e.message ?: "解析失败"}"
                    continue
                }
                urlCache[song.key] = url to System.currentTimeMillis()
                return MusicPlayInfo(url, MusicPlayHeaders.forPlatform(song.platform))
            }
        }
        throw MusicResolveException(
            "音源解析失败：" + (errors.firstOrNull() ?: "没有可用音源") +
                if (errors.size > 1) "（共 ${errors.size} 个音源尝试失败）" else ""
        )
    }

    /** 没有音源可用时的提示（区分"没导入音源"与"导入了但不支持该平台"） */
    fun noSourceMessage(platform: MusicPlatform): String {
        val enabled = MusicSourceStore.all().filter { it.enabled }
        return when {
            enabled.isEmpty() -> "还没有可用的音乐音源，请到「音源管理」导入（推荐六音音源）"
            enabled.any { it.support.isEmpty() } -> "音源尚未就绪，请到「音源管理」检查音源状态"
            else -> "已启用的音源不支持「${platform.label}」，请到「音源管理」导入支持该平台的音源"
        }
    }

    // ---------------- 收藏（进里世界清单） ----------------

    /** 歌曲是否已收藏（存在于任意里世界清单） */
    suspend fun isCollected(song: MusicSong): Boolean =
        Repo.isCollected(CATEGORY, song.key)

    /** 收藏到指定里世界清单：落库条目 + 挂入清单 */
    suspend fun collect(song: MusicSong, listId: Long): Boolean {
        val itemId = Repo.saveMusicItem(
            doubanId = song.key,
            title = song.name,
            subTitle = song.artists,
            coverUrl = song.cover.takeIf { it.isNotBlank() },
            info = encodeInfo(song)
        )
        if (itemId <= 0) return false
        Repo.addItemToList(listId, itemId)
        return true
    }

    /** 某清单里的歌曲（非音乐条目自动忽略） */
    fun observeSongs(listId: Long): Flow<List<MusicSong>> =
        Repo.observeItemsIn(listId).map { list -> list.mapNotNull { songOf(it) } }

    /** 全部已收藏歌曲（"我的"页用） */
    fun observeAllSongs(): Flow<List<MusicSong>> =
        Repo.observeItems(CATEGORY).map { list -> list.mapNotNull { songOf(it) } }

    // ---------------- 条目 ↔ 歌曲 互转 ----------------

    fun encodeInfo(song: MusicSong): String = buildJsonObject {
        put("album", song.album)
        put("dur", song.durationMs)
        put("raw", buildJsonObject { song.raw.forEach { (k, v) -> put(k, v) } })
    }.toString()

    /** 清单条目还原成歌曲；不是音乐条目（或缺少平台信息）返回 null */
    fun songOf(entity: CollectionItemEntity): MusicSong? {
        if (entity.category != CATEGORY) return null
        val parts = entity.doubanId.split("|", limit = 2)
        val platform = MusicPlatform.of(parts.firstOrNull().orEmpty()) ?: return null
        val id = parts.getOrNull(1).orEmpty()
        if (id.isBlank()) return null
        val info = runCatching { json.parseToJsonElement(entity.info) as? JsonObject }.getOrNull()
        val raw = (info?.get("raw") as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
            ?.toMap()
            .orEmpty()
        return MusicSong(
            platform = platform,
            id = id,
            name = entity.title,
            artists = entity.subTitle,
            album = (info?.get("album") as? JsonPrimitive)?.content.orEmpty(),
            cover = entity.coverUrl.orEmpty(),
            durationMs = (info?.get("dur") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            raw = raw
        )
    }
}
