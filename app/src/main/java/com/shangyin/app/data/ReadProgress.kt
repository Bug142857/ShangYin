package com.shangyin.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 漫画/本子阅读进度：记录每个作品最后阅读的章节 key（SharedPreferences 持久化，跨重启保留）。
 * key = "$source/$id"（如 komiic/{漫画id}、bika/{本子id}），value = 章节 key（komiic=chapterId、bika=order）。
 * 用于详情页章节列表高亮"上次看到"的位置。
 */
object ReadProgressStore {

    private const val PREF = "read_progress"

    private val _map = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 全部阅读进度（key=source/id） */
    val all: StateFlow<Map<String, String>> = _map

    @Volatile
    private var loaded = false

    /** 从磁盘加载（幂等，首次访问时调用） */
    fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val sp = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            _map.value = sp.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
            loaded = true
        }
    }

    /** 记录最后阅读章节 */
    fun record(context: Context, source: String, id: String, chapterKey: String) {
        ensure(context)
        val key = "$source/$id"
        // 「读 → 改 → 写」必须串行：多章节/多页面同时上报时，并发写 StateFlow 会互相覆盖（丢进度）
        synchronized(this) {
            if (_map.value[key] == chapterKey) return
            _map.value = _map.value + (key to chapterKey)
        }
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(key, chapterKey).apply()
    }

    /** 读取某作品最后阅读章节 key；无记录返回 null */
    fun get(context: Context, source: String, id: String): String? {
        ensure(context)
        return _map.value["$source/$id"]
    }
}
