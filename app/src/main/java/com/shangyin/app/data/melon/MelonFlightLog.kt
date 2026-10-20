package com.shangyin.app.data.melon

/**
 * 吃瓜图片管线「飞行黑匣子」（v0.241）。
 *
 * 十余轮发版都没能让图片显示出来，根因每次都隔着一层看不见——PC 无法复现（风控按
 * 环境下发不同内容），设备端又只有最终结果没有过程。本组件把整条管线（解析→Fetcher
 * 分派→WebView 就绪→解密→缓存→解码）每一步的结果都记录在内存环形缓冲里，诊断页
 * 原样输出：设备自己说出卡在哪一步，不再靠猜。
 *
 * 只存内存，无持久化开销；App 进程死了日志就没了，正好聚焦「本次会话」。
 *
 * v0.250：PLAYER 标签独立缓冲——0.247~0.249 连续三轮取证都栽在同一件事上：图片
 * FETCH 日志刷屏（一次列表加载几十条），把播放器卡住瞬间的 PLAYER 记录全部挤出
 * 60 条环形缓冲，诊断页拿到的永远是「卡完之后」的图墙日志。
 * v0.251：DOWNLOAD（下载里程碑）与 LIST（分页取证）同入优先缓冲——低频取证标签
 * 一律不该与刷屏标签抢缓冲；PLAYER_CAP 24→32。
 */
object MelonFlightLog {


    private const val CAP = 60
    private const val PLAYER_CAP = 32
    private val buf = ArrayDeque<String>()
    private val playerBuf = ArrayDeque<String>()

    fun log(tag: String, msg: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())}] $tag: ${msg.take(220)}"
        synchronized(buf) {
            // v0.251：DOWNLOAD 与 PLAYER 同入优先缓冲——下载全程只打 ~5 条里程碑，
            // 混进主缓冲照样被 FETCH 刷屏挤掉；两取证源都不该靠运气活下来
            // v0.255：MIRROR 同入——0.254 镜像日志被 FETCH 挤掉，「门挡死」定性失去证据，
            // 低频取证标签一律进优先缓冲（教训重演即补白名单）
            if (tag == "PLAYER" || tag == "DOWNLOAD" || tag == "LIST" || tag == "MIRROR") {
                playerBuf.addLast(line)
                while (playerBuf.size > PLAYER_CAP) playerBuf.removeFirst()
            } else {
                buf.addLast(line)
                while (buf.size > CAP) buf.removeFirst()
            }
        }
    }

    /** 诊断输出（新→旧，最新的在最上面；PLAYER 独立缓冲置顶——播放取证优先） */
    fun dump(): List<String> = synchronized(buf) {
        playerBuf.toList().asReversed() + buf.toList().asReversed()
    }
}

/**
 * v0.268 崩溃捕获：黑匣子的「坠机记录舱」。
 *
 * 0.267 闪退无栈可查——内存环形日志随进程死亡清零，诊断页只剩重启后的首页记录，
 * 崩溃瞬间发生了什么彻底失明。本处理器在进程死亡前把完整栈写盘（filesDir/crash_last.txt，
 * 每次覆盖），诊断页原样带出：下一轮「闪退了」三个字背后必有现场。
 */
object CrashCapture {

    fun install(context: android.content.Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(context.filesDir, "crash_last.txt").writeText(
                    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date()) + " thread=" + t.name + "\n" + sw.toString()
                )
                runCatching { MelonFlightLog.log("CRASH", e.toString().take(180)) }
            }
            prev?.uncaughtException(t, e)
        }
    }
}
