package com.shangyin.app.data.music

import com.shangyin.app.data.Repo
import com.shangyin.app.data.db.CollectionItemEntity
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

    // ---------------- 搜索 / 歌词 ----------------

    /** 搜索（24bit 两个曲库合并） */
    suspend fun search(keyword: String, page: Int = 1): List<MusicSong> = Bit24.search(keyword, page)

    suspend fun lyric(song: MusicSong): MusicLyric = Bit24.lyric(song)

    /** 给搜索结果补封面（只补前几首未缓存的，24bit 详情页有每日限额，不能整页抓） */
    suspend fun fillCovers(songs: List<MusicSong>, max: Int = 5): List<MusicSong> =
        Bit24.fillCovers(songs, max)

    /** 收藏/清空直链缓存（换音源后强制重解析） */
    fun clearUrlCache() = urlCache.clear()

    // ---------------- 播放直链 ----------------

    /**
     * 解析播放直链（24bit 的直链带时效签名，所以每次播放时实时解析，配合播放数据源的惰性解析）。
     * 失败抛 [MusicResolveException]，消息直接展示给用户。
     */
    suspend fun resolvePlay(song: MusicSong): MusicPlayInfo {
        val cached = urlCache[song.key]
        if (cached != null && System.currentTimeMillis() - cached.second < URL_TTL_MS) {
            return MusicPlayInfo(cached.first, Bit24.PLAY_HEADERS)
        }
        val result = Bit24.resolve(song)
        val detail = result.detail ?: throw MusicResolveException(result.error ?: "没有可用的播放直连")
        val url = detail.freshUrl(System.currentTimeMillis())
            ?: detail.url ?: throw MusicResolveException("24bit 没给这首歌直链（可能已下架）")
        urlCache[song.key] = url to System.currentTimeMillis()
        return MusicPlayInfo(url, Bit24.PLAY_HEADERS)
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
