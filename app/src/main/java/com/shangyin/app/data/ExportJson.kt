package com.shangyin.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 导出/导入 JSON 序列化（导出文件、云同步共用，格式 version 1） */

fun buildExportJson(data: ExportData): String {
    val root = JSONObject().apply {
        put("version", 1)
        put("exportAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    }

    val itemsArr = JSONArray()
    data.items.forEach { e ->
        itemsArr.put(JSONObject().apply {
            put("id", e.id)
            put("category", e.category)
            put("doubanId", e.doubanId)
            put("title", e.title)
            put("subTitle", e.subTitle)
            put("year", e.year)
            put("doubanRating", e.doubanRating)
            put("coverUrl", e.coverUrl)
            put("summary", e.summary)
            put("info", e.info)
            put("directors", e.directors)
            put("casts", e.casts)
            put("genres", e.genres)
            put("doubanUrl", e.doubanUrl)
            put("status", e.status)
            put("myRating", e.myRating)
            put("note", e.note)
            put("createdAt", e.createdAt)
            put("updatedAt", e.updatedAt)
        })
    }
    root.put("items", itemsArr)

    val listsArr = JSONArray()
    data.lists.forEach { l ->
        listsArr.put(JSONObject().apply {
            put("id", l.id)
            put("name", l.name)
            put("description", l.description)
            put("coverUrl", l.coverUrl)
            put("parentId", l.parentId)
            put("sortIndex", l.sortIndex)
            // 归属世界与音乐清单类型：曾经漏过 world，导致导入后清单全变表世界
            put("world", l.world)
            put("musicList", l.musicList)
            put("createdAt", l.createdAt)
        })
    }
    root.put("lists", listsArr)

    val relArr = JSONArray()
    data.listItems.forEach { li ->
        relArr.put(JSONObject().apply {
            put("listId", li.listId)
            put("itemId", li.itemId)
            put("orderIndex", li.orderIndex)
        })
    }
    root.put("listItems", relArr)

    // 片源配置（含目录归属/测试结果），旧客户端导入时自动忽略此字段
    val vodArr = JSONArray()
    data.vodSources.forEach { v ->
        vodArr.put(JSONObject().apply {
            put("id", v.id)
            put("name", v.name)
            put("baseUrl", v.baseUrl)
            put("enabled", v.enabled)
            put("region", v.region)
            // v0.308：手动分组标记必须跟着走，否则导入后自动归组会覆盖用户手选的目录
            put("regionManual", v.regionManual)
            v.testStatus?.let { put("testStatus", it) }
            v.testMsg?.let { put("testMsg", it) }
            put("testAt", v.testAt)
        })
    }
    root.put("vodSources", vodArr)

    // 里世界爱心收藏（吃瓜/X站/P站），旧客户端导入时自动忽略此字段
    val favArr = JSONArray()
    data.innerFavs.forEach { f ->
        favArr.put(JSONObject().apply {
            put("module", f.module)
            put("id", f.id)
            put("title", f.title)
            put("subtitle", f.subtitle)
            f.cover?.let { put("cover", it) }
            put("savedAt", f.savedAt)
            f.videoUrl?.let { put("videoUrl", it) }
            put("mediaId", f.mediaId)
            put("durationSec", f.durationSec)
            f.tweetUrl?.let { put("tweetUrl", it) }
            f.payload?.let { put("payload", it) }
        })
    }
    root.put("innerFavs", favArr)

    // 配置快照（登录态/主题/昵称/电视源/模块开关/下载记录等），旧客户端导入时自动忽略此字段
    if (data.config.isNotEmpty()) {
        root.put("config", JSONObject(data.config))
    }

    return root.toString(2)
}

fun parseExportJson(json: String): ExportData {
    val root = JSONObject(json)

    val items = root.optJSONArray("items")?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            com.shangyin.app.data.db.CollectionItemEntity(
                id = o.getLong("id"),
                category = o.optString("category", ""),
                doubanId = o.optString("doubanId", ""),
                title = o.optString("title", ""),
                subTitle = o.optString("subTitle", ""),
                year = o.optString("year", ""),
                doubanRating = o.opt("doubanRating")?.let {
                    if (it is Number) it.toFloat() else null
                },
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
    } ?: emptyList()

    val lists = root.optJSONArray("lists")?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            com.shangyin.app.data.db.ItemListEntity(
                id = o.getLong("id"),
                name = o.optString("name", ""),
                description = o.optString("description", ""),
                coverUrl = o.optString("coverUrl").ifBlank { null },
                parentId = o.opt("parentId")?.let { (it as? Number)?.toLong() },
                // 旧备份文件无 sortIndex，默认 0（未手动排序，按创建时间展示）
                sortIndex = o.optInt("sortIndex", 0),
                // 旧备份文件无 world / musicList，默认表世界 + 普通清单
                world = o.optInt("world", 0),
                musicList = o.optBoolean("musicList", false),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    } ?: emptyList()

    val rels = root.optJSONArray("listItems")?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            com.shangyin.app.data.db.ListItemEntity(
                listId = o.getLong("listId"),
                itemId = o.getLong("itemId"),
                orderIndex = o.optInt("orderIndex", 0)
            )
        }
    } ?: emptyList()

    val vodSources = root.optJSONArray("vodSources")?.let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            com.shangyin.app.data.vod.VodSource(
                id = o.optString("id"),
                name = o.optString("name"),
                baseUrl = o.optString("baseUrl"),
                enabled = o.optBoolean("enabled", true),
                region = o.optString("region", "cn").ifBlank { "cn" },
                regionManual = o.optBoolean("regionManual", false),
                testStatus = o.optString("testStatus", "").ifBlank { null },
                testMsg = o.optString("testMsg", "").ifBlank { null },
                testAt = o.optLong("testAt", 0L)
            )
        }
    } ?: emptyList()

    val innerFavs = root.optJSONArray("innerFavs")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val module = o.optString("module")
            val id = o.optString("id")
            if (module.isBlank() || id.isBlank()) return@mapNotNull null
            InnerFav(
                module = module,
                id = id,
                title = o.optString("title"),
                subtitle = o.optString("subtitle"),
                cover = o.optString("cover").ifBlank { null },
                savedAt = o.optLong("savedAt", 0L),
                videoUrl = o.optString("videoUrl").ifBlank { null },
                mediaId = o.optLong("mediaId", 0L),
                durationSec = o.optInt("durationSec", 0),
                tweetUrl = o.optString("tweetUrl").ifBlank { null },
                payload = o.optString("payload").ifBlank { null }
            )
        }
    } ?: emptyList()

    // 配置快照：旧备份文件无此字段时为空 Map，导入时保留现有配置
    val config = root.optJSONObject("config")?.let { o ->
        val m = mutableMapOf<String, String>()
        for (key in o.keys()) m[key] = o.optString(key, "")
        m
    } ?: emptyMap()

    return ExportData(items, lists, rels, vodSources, innerFavs, config)
}
