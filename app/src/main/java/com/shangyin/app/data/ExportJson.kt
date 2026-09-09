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

    return ExportData(items, lists, rels)
}
