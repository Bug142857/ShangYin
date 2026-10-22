package com.shangyin.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 里世界「点爱心」收藏（吃瓜 / X站 / P站）：本机 SharedPreferences 持久化，
 * 与表世界豆瓣收藏体系（Room 数据库）完全独立。
 *
 * 每条收藏只存重放所需的最小字段：
 * - melon：帖子 id（详情页按 id 重新拉取）
 * - xsite：直链 mp4 + mediaId + 时长（播放无需再请求详情）
 * - psite：vkey（播放时再解析 flashvars 直链）
 */
data class InnerFav(
    val module: String,      // melon / xsite / psite
    val id: String,          // melon=帖子id / xsite=urlCd / psite=vkey
    val title: String,
    val subtitle: String,    // 作者 / @推主 / 频道·观看数
    val cover: String?,
    val savedAt: Long,
    // ---- 以下仅 xsite / psite 重放需要（melon 恒为默认值） ----
    val videoUrl: String? = null,   // xsite 直链 mp4
    val mediaId: Long = 0L,         // xsite 数字 id（播放进度 key）
    val durationSec: Int = 0,
    val tweetUrl: String? = null,   // xsite 原推链接
)

object InnerFavorites {

    private const val NAME = "inner_favorites"
    private const val KEY_JSON = "favorites_json"

    private lateinit var sp: SharedPreferences

    private val _favs = MutableStateFlow<List<InnerFav>>(emptyList())
    val favs: StateFlow<List<InnerFav>> = _favs

    fun init(context: Context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        _favs.value = runCatching { decode(sp.getString(KEY_JSON, "[]").orEmpty()) }
            .getOrDefault(emptyList())
    }

    fun isFav(module: String, id: String): Boolean =
        _favs.value.any { it.module == module && it.id == id }

    /** 已收藏 → 取消；未收藏 → 收藏（新收藏置顶） */
    fun toggle(fav: InnerFav) {
        val cur = _favs.value.toMutableList()
        val idx = cur.indexOfFirst { it.module == fav.module && it.id == fav.id }
        if (idx >= 0) cur.removeAt(idx) else cur.add(0, fav)
        persist(cur)
    }

    fun remove(module: String, id: String) {
        persist(_favs.value.filterNot { it.module == module && it.id == id })
    }

    /** 全量替换（数据导入/云同步恢复用）：备份里无此字段时调用方不会走到这里 */
    fun replaceAll(list: List<InnerFav>) {
        persist(list)
    }

    private fun persist(list: List<InnerFav>) {
        _favs.value = list
        sp.edit().putString(KEY_JSON, encode(list)).apply()
    }

    // ---------- JSON（kotlinx 手动映射，字段变更向后兼容） ----------

    private fun encode(list: List<InnerFav>): String = JsonArray(list.map { f ->
        buildJsonObject {
            put("module", f.module)
            put("id", f.id)
            put("title", f.title)
            put("subtitle", f.subtitle)
            f.cover?.let { put("cover", it) }
            put("savedAt", f.savedAt)
            f.videoUrl?.let { put("videoUrl", it) }
            if (f.mediaId != 0L) put("mediaId", f.mediaId)
            if (f.durationSec != 0) put("durationSec", f.durationSec)
            f.tweetUrl?.let { put("tweetUrl", it) }
        }
    }).toString()

    private fun decode(raw: String): List<InnerFav> {
        if (raw.isBlank()) return emptyList()
        return raw.let { kotlinx.serialization.json.Json.parseToJsonElement(it) }
            .jsonArray
            .mapNotNull { e ->
                runCatching {
                    val o = e.jsonObject
                    fun str(key: String) = o[key]?.jsonPrimitive?.contentOrNull
                    InnerFav(
                        module = str("module").orEmpty(),
                        id = str("id").orEmpty(),
                        title = str("title").orEmpty(),
                        subtitle = str("subtitle").orEmpty(),
                        cover = str("cover"),
                        savedAt = o["savedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                        videoUrl = str("videoUrl"),
                        mediaId = o["mediaId"]?.jsonPrimitive?.longOrNull ?: 0L,
                        durationSec = o["durationSec"]?.jsonPrimitive?.intOrNull ?: 0,
                        tweetUrl = str("tweetUrl"),
                    )
                }.getOrNull()
            }
            .filter { it.module.isNotBlank() && it.id.isNotBlank() }
    }
}
