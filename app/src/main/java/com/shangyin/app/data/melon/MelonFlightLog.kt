package com.shangyin.app.data.melon

/**
 * 吃瓜图片管线「飞行黑匣子」（v0.241）。
 *
 * 十余轮发版都没能让图片显示出来，根因每次都隔着一层看不见——PC 无法复现（风控按
 * 环境下发不同内容），设备端又只有最终结果没有过程。本组件把整条管线（解析→Fetcher
 * 分派→WebView 就绪→解密→缓存→解码）每一步的结果都记录在内存环形缓冲里，诊断页
 * 原样输出：设备自己说出卡在哪一步，不再靠猜。
 *
 * 只存内存（~60 条），无持久化开销；App 进程死了日志就没了，正好聚焦「本次会话」。
 */
object MelonFlightLog {

    private const val CAP = 60
    private val buf = ArrayDeque<String>()

    fun log(tag: String, msg: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())}] $tag: ${msg.take(220)}"
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > CAP) buf.removeFirst()
        }
    }

    /** 诊断输出（新→旧，最新的在最上面） */
    fun dump(): List<String> = synchronized(buf) { buf.toList().asReversed() }
}
