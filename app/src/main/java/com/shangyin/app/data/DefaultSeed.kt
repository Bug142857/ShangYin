package com.shangyin.app.data

import android.content.Context
import com.shangyin.app.data.db.CollectionItemEntity
import com.shangyin.app.data.db.ItemListEntity
import com.shangyin.app.data.db.ListItemEntity
import com.shangyin.app.data.live.LiveSource
import com.shangyin.app.data.vod.VodSource
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首次启动的默认数据（v0.311）：影视源 / 电视源 / 表世界清单（含条目）。
 *
 * 数据来自 `assets/default_seed.json`（由用户导出的备份裁剪出的三部分），
 * 只在对应数据为空、且从未播种过时写入 —— 用户自己改过的配置一律不覆盖。
 */
object DefaultSeed {

    private const val FILE = "default_seed.json"

    suspend fun seedIfNeeded(context: Context) {
        if (SettingsStore.defaultSeedDone) return
        withContext(Dispatchers.IO) {
            val root = runCatching {
                context.assets.open(FILE).use {
                    JSONObject(it.readBytes().toString(Charsets.UTF_8))
                }
            }.getOrNull()

            if (root == null) {
                // 资产缺失/解析失败：退回代码里的内置默认（至少保证有源可用）
                SettingsStore.ensureDefaultVodSourcesSeeded()
                SettingsStore.ensureDefaultLiveSourcesSeeded()
                SettingsStore.defaultSeedDone = true
                return@withContext
            }

            seedVodSources(root)
            seedLiveSources(root)
            seedCollections(root)

            SettingsStore.defaultSeedDone = true
        }
    }

    private fun seedVodSources(root: JSONObject) {
        if (SettingsStore.getVodSources().isNotEmpty()) return
        val list = root.optJSONArray("vodSources").mapObjects { o ->
            VodSource(
                id = o.optString("id"),
                name = o.optString("name"),
                baseUrl = o.optString("baseUrl"),
                enabled = o.optBoolean("enabled", true),
                region = o.optString("region", "cn").ifBlank { "cn" },
                regionManual = o.optBoolean("regionManual", false)
            )
        }.filter { it.id.isNotBlank() && it.baseUrl.isNotBlank() }
        if (list.isNotEmpty()) SettingsStore.setVodSources(list)
        // 资产里没有/格式坏了：退回代码内置默认，至少保证有源可用
        else SettingsStore.ensureDefaultVodSourcesSeeded()
    }

    private fun seedLiveSources(root: JSONObject) {
        if (SettingsStore.getLiveSources().isNotEmpty()) return
        val list = root.optJSONArray("liveSources").mapObjects { o ->
            LiveSource(
                id = o.optString("id"),
                name = o.optString("name"),
                url = o.optString("url"),
                content = o.optString("content", ""),
                enabled = o.optBoolean("enabled", true)
            )
        }.filter { it.id.isNotBlank() && it.url.isNotBlank() }
        if (list.isNotEmpty()) SettingsStore.setLiveSources(list)
        // v0.312 修复：资产里 liveSources 曾因生成脚本问题不是数组（optJSONArray 直接返回 null），
        // 导致电视源一条都没播上；这里兜底用代码内置默认源。
        else SettingsStore.ensureDefaultLiveSourcesSeeded()
    }

    /** 表世界清单：清单 + 条目 + 关联一次性灌入（保留原 id，维持父子层级） */
    private suspend fun seedCollections(root: JSONObject) {
        if (Repo.hasAnyCollection()) return

        val items = root.optJSONArray("items").mapObjects { o ->
            CollectionItemEntity(
                id = o.getLong("id"),
                category = o.optString("category", ""),
                doubanId = o.optString("doubanId", ""),
                title = o.optString("title", ""),
                subTitle = o.optString("subTitle", ""),
                year = o.optString("year", ""),
                doubanRating = o.opt("doubanRating")?.let { if (it is Number) it.toFloat() else null },
                coverUrl = o.optString("coverUrl").ifBlank { null },
                summary = o.optString("summary", ""),
                info = o.optString("info", ""),
                directors = o.optString("directors", ""),
                casts = o.optString("casts", ""),
                genres = o.optString("genres", ""),
                doubanUrl = o.optString("doubanUrl").ifBlank { null },
                status = o.optString("status", ""),
                myRating = o.optInt("myRating", 0),
                note = o.optString("note", ""),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }

        val lists = root.optJSONArray("lists").mapObjects { o ->
            ItemListEntity(
                id = o.getLong("id"),
                name = o.optString("name", ""),
                description = o.optString("description", ""),
                coverUrl = o.optString("coverUrl").ifBlank { null },
                parentId = o.opt("parentId")?.let { (it as? Number)?.toLong() },
                sortIndex = o.optInt("sortIndex", 0),
                world = o.optInt("world", 0),
                musicList = o.optBoolean("musicList", false),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }.filter { it.name.isNotBlank() }

        if (lists.isEmpty()) return

        val rels = root.optJSONArray("listItems").mapObjects { o ->
            ListItemEntity(
                listId = o.getLong("listId"),
                itemId = o.getLong("itemId"),
                orderIndex = o.optInt("orderIndex", 0)
            )
        }.filter { rel -> lists.any { it.id == rel.listId } && items.any { it.id == rel.itemId } }

        Repo.seedCollectionData(items, lists, rels)
    }

    private fun <T> JSONArray?.mapObjects(map: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            optJSONObject(i)?.let { out.add(map(it)) }
        }
        return out
    }
}
