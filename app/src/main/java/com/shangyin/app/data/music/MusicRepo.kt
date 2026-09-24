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

    /**
     * 搜索。搜索页可以按来源切换：
     * - [MusicPlatform.MVMMP3] → 无名音乐网（搜索页 HTML 里自带封面，不用额外抓）
     * - [MusicPlatform.S33VE] → 33ve（与 mvmp3 同系统，不进 chips，仅兜底用）
     * - [MusicPlatform.JOOX] / [MusicPlatform.NETEASE] → gdstudio 聚合接口
     */
    suspend fun search(
        keyword: String,
        page: Int = 1,
        platform: MusicPlatform = MusicPlatform.MVMMP3
    ): List<MusicSong> = when (platform) {
        MusicPlatform.S33VE -> Site33.search(keyword, page)
        MusicPlatform.MVMMP3 -> MvMp3.search(keyword, page)
        MusicPlatform.JOOX, MusicPlatform.NETEASE -> GdStudio.search(platform, keyword, page)
    }

    suspend fun lyric(song: MusicSong): MusicLyric = when (song.platform) {
        MusicPlatform.S33VE -> Site33.lyric(song)
        MusicPlatform.MVMMP3 -> MvMp3.lyric(song)
        MusicPlatform.JOOX, MusicPlatform.NETEASE -> GdStudio.lyric(song.platform, song.id)
    }

    /** 清空直链缓存（直链带时效签名，需要强制换新时用） */
    fun clearUrlCache() = urlCache.clear()

    // ---------------- 播放直链 ----------------

    /**
     * 解析播放直链（直链带时效签名，所以播放时实时解析 + 4 分钟短缓存）。
     * 失败抛 [MusicResolveException]，消息直接展示给用户。
     */
    suspend fun resolvePlay(song: MusicSong): MusicPlayInfo {
        val cached = urlCache[song.key]
        if (cached != null && System.currentTimeMillis() - cached.second < URL_TTL_MS) {
            return MusicPlayInfo(cached.first, Site33.PLAY_HEADERS)
        }
        val info = when (song.platform) {
            MusicPlatform.S33VE -> {
                val result = Site33.resolve(song)
                val url = result.url
                if (url.isNullOrBlank()) throw MusicResolveException(result.error ?: "没有可用的播放直连")
                MusicPlayInfo(url, Site33.PLAY_HEADERS)
            }

            MusicPlatform.MVMMP3 -> {
                val result = MvMp3.resolve(song)
                val url = result.url
                if (url.isNullOrBlank()) throw MusicResolveException(result.error ?: "没有可用的播放直连")
                MusicPlayInfo(url, MvMp3.PLAY_HEADERS)
            }

            // JOOX / 网易云（gdstudio 聚合源）：先走原源直链（JOOX 直链时有时无，GdStudio 内部已多轮重试）；
            // 仍然拿不到就按"歌名 + 歌手"去 mvmp3 / 33ve 找同名歌播放，尽量让用户点得响
            MusicPlatform.JOOX, MusicPlatform.NETEASE -> {
                val direct = runCatching { GdStudio.resolveUrl(song.platform, song.id) }.getOrNull()
                direct?.let { MusicPlayInfo(it, GdStudio.PLAY_HEADERS) }
                    ?: resolveViaSite(song, useMvmp3 = true)
                    ?: resolveViaSite(song, useMvmp3 = false)
                    ?: throw MusicResolveException("没有找到这首歌的可播放资源（mvmp3 / 33ve 里也没有同名的歌）")
            }
        }
        urlCache[song.key] = info.url to System.currentTimeMillis()
        return info
    }

    /**
     * 回退解析：拿"歌名 歌手"去 mvmp3 或 33ve 搜一次，取同名（或同名同歌手）的第一首解析直链。
     * 用于 JOOX / 网易云直链拿不到时兜底；找不到返回 null，由调用方换下一个站点或报错。
     */
    private suspend fun resolveViaSite(song: MusicSong, useMvmp3: Boolean): MusicPlayInfo? {
        val keyword = listOf(song.name, song.artists).filter { it.isNotBlank() }.joinToString(" ")
        val candidates = runCatching {
            if (useMvmp3) MvMp3.search(keyword) else Site33.search(keyword)
        }.getOrNull().orEmpty()
        // 优先同名 + 歌手前几位匹配的，其次同名，最后放弃（避免播成完全无关的歌）
        val target = candidates.firstOrNull { it.name == song.name && sameArtist(it, song) }
            ?: candidates.firstOrNull { it.name == song.name }
        if (target == null) return null
        // 两个站点的 PlayResult 是不同类型（结构相同），在分支里分别取 url 才能通过编译
        val url = if (useMvmp3) MvMp3.resolve(target).url else Site33.resolve(target).url
        if (url.isNullOrBlank()) return null
        return MusicPlayInfo(url, if (useMvmp3) MvMp3.PLAY_HEADERS else Site33.PLAY_HEADERS)
    }

    /** 歌手是否大致相同（多歌手的歌两边排序/分隔符可能不同，只比对首个歌手名） */
    private fun sameArtist(a: MusicSong, b: MusicSong): Boolean {
        val x = a.artists.split("/", "&", "、", ",").firstOrNull()?.trim().orEmpty()
        val y = b.artists.split("/", "&", "、", ",").firstOrNull()?.trim().orEmpty()
        return x.isNotBlank() && x == y
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
