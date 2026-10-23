package com.shangyin.app.data

/**
 * 详情页进程内缓存（v0.308）：吃瓜 / X站 / 游戏 / 图书 的详情页以前每次进入都重新联网，
 * 用户反馈「看过的页面再打开还要重新加载」。这里统一做一层「先展示缓存 + 静默刷新」：
 * 命中即立刻渲染，同时后台再请求一次覆盖（不显示 loading，失败也不打扰）。
 *
 * 不做 TTL：详情内容变化不频繁，需要最新时下拉/重进都会触发静默刷新。
 * 容量按 LRU 上限淘汰，避免长会话里无界增长；杀进程即失效（不需要跨进程持久化）。
 */
object DetailCache {

    private const val MAX_ENTRIES = 80

    private val map = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean =
            size > MAX_ENTRIES
    }

    fun put(key: String, value: Any) {
        synchronized(map) { map[key] = value }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? = synchronized(map) { map[key] as? T }

    fun clear() {
        synchronized(map) { map.clear() }
    }
}
