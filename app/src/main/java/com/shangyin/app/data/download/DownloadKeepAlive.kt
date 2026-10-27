package com.shangyin.app.data.download

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载保活（v0.319）：有下载任务时启动前台服务 + CPU/Wi-Fi 唤醒锁，
 * 让息屏、切后台时下载不被系统休眠打断；所有任务结束后延迟几秒自动停止。
 *
 * 用法必须成对：`begin(context)` ... `finally { end() }`。
 * 引用计数支持视频多任务 / 漫画多章节并发下载。
 */
object DownloadKeepAlive {

    /** 最后一个任务结束后延迟停止，避免连续短下载（音乐/书籍）反复启停服务 */
    private const val STOP_DELAY_MS = 3000L

    private val activeCount = AtomicInteger(0)
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val isActive: Boolean get() = activeCount.get() > 0

    fun begin(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (activeCount.incrementAndGet() == 1) {
            // App 在前台发起下载时允许启动前台服务；被系统拒绝也不会影响下载本身
            runCatching {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, DownloadKeepAliveService::class.java)
                )
            }
        }
    }

    fun end() {
        val left = activeCount.decrementAndGet()
        if (left > 0) return
        activeCount.set(0)
        val ctx = appContext ?: return
        mainHandler.postDelayed({
            if (!isActive) {
                runCatching {
                    ctx.stopService(Intent(ctx, DownloadKeepAliveService::class.java))
                }
            }
        }, STOP_DELAY_MS)
    }
}
