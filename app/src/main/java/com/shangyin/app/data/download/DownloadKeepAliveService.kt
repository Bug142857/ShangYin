package com.shangyin.app.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.shangyin.app.MainActivity

/**
 * 下载前台服务（v0.319）：仅在有下载任务时存在。
 *
 * 作用：① startForeground 让 App 在息屏/后台仍被视为活跃，网络不被 Doze 挂起；
 * ② 持有 PARTIAL_WAKE_LOCK 防止 CPU 休眠；③ 持有 Wi-Fi 高性能锁防止 Wi-Fi 省电断流。
 * 停服时全部释放（见 [onDestroy]），不会长期占用电池。
 */
class DownloadKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 任务已经结束（stopService 与 startService 竞态）→ 立刻退出
        if (!DownloadKeepAlive.isActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireLocks()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        if (wakeLock == null) {
            runCatching {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShangYin:download")
                    .apply {
                        setReferenceCounted(false)
                        // 兜底超时：即使异常路径漏掉 stopService，也不会无限耗电
                        acquire(6 * 60 * 60 * 1000L)
                    }
            }
        }
        if (wifiLock == null) {
            runCatching {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ShangYin:download")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText("息屏或切到后台也会继续下载")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID, "后台下载", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                description = "下载时保持后台运行，任务结束后自动消失"
            }
            nm.createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL_ID = "download_keep_alive"
        const val NOTIFICATION_ID = 0x5344 // "SD"，避免与其他通知 id 冲突
    }
}
