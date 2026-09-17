package com.shangyin.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.shangyin.app.data.Repo
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Repo.init(this)
        SettingsStore.init(this)
        // 关键配置恢复（豆瓣Cookie/坚果云/片源）——从公共目录备份文件补缺，防卸载重装丢配置
        runCatching { com.shangyin.app.data.ConfigBackup.restoreIfNeeded(this) }
        // 首次使用播种内置默认采集源（在线观影）
        SettingsStore.ensureDefaultVodSourcesSeeded()
        // 配置变更（登录/云同步/片源等）时自动备份到公共目录
        SettingsStore.registerListener { _, key ->
            if (key in com.shangyin.app.data.ConfigBackup.KEYS) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { com.shangyin.app.data.ConfigBackup.backup(this@App) }
                }
            }
        }
        // 零孤儿机制：启动时静默清理历史遗留的孤立收藏（v2.4.0 起新孤儿不会再产生）
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { Repo.pruneOrphans() }
        }
    }

    /** 构建带磁盘缓存的共享 OkHttpClient，DoubanClient 和 Coil 共用 */
    private fun getOrCreateOkHttpClient(): OkHttpClient {
        val cacheDir = File(cacheDir, "http").apply { mkdirs() }
        val cache = Cache(cacheDir, 20 * 1024 * 1024L) // 20MB HTTP 缓存
        return OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val newReq = if (host.endsWith("doubanio.com") || host.endsWith("douban.com")) {
                    req.newBuilder()
                        .header("Referer", "https://m.douban.com/")
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
                                "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                                "Version/16.0 Mobile/15E148 Safari/604.1"
                        )
                        .build()
                } else if ((host == "komiic.com" || host == "komiic.cc") && req.url.encodedPath.startsWith("/api/image/")) {
                    // Komiic 章节图防盗链（主/镜像域）：fragment "#c/{comicId}/{chapterId}" → 完整路径 Referer
                    // （fragment 不会发送到服务器，仅本地编码归属信息）；UA 与 KomiicClient 同款 Chrome/119
                    req.url.fragment?.takeIf { it.startsWith("c/") }?.let { f ->
                        val p = f.removePrefix("c/").split('/')
                        if (p.size == 2) {
                            req.newBuilder()
                                .header("Referer", "https://${req.url.host}/comic/${p[0]}/chapter/${p[1]}")
                                .header("User-Agent", com.shangyin.app.data.komiic.KomiicClient.CHROME_UA)
                                .build()
                        } else req
                    } ?: req
                } else req
                chain.proceed(newReq)
            }
            .build()
    }

    /**
     * Coil 图片加载器：显式配置 diskCache / memoryCache（LRU 自动清理，不再手动管理）。
     * - 磁盘缓存 150MB（Android 低存储时系统自动限制）
     * - 内存缓存默认（按可用 RAM 自动计算）
     * 缓存目录：<appCacheDir>/image_cache（Coil 内部管理，clear/delete 都由 LRU 触发）
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(getOrCreateOkHttpClient())
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024) // 150MB
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 可用 RAM 的 25%
                    .build()
            }
            .build()
    }

    companion object {
        lateinit var instance: App
            private set

        /** 清理 Coil 图片缓存 + OkHttp HTTP 缓存 */
        fun clearAllCaches(ctx: Context) {
            // 直接删缓存子目录：OkHttp 和 Coil 都会在下次请求时重建
            runCatching { deleteRecursive(File(ctx.cacheDir, "http")) }
            runCatching { deleteRecursive(File(ctx.cacheDir, "image_cache")) }
            // 兜底：清理 Coil 内存缓存
            runCatching {
                (ctx as? coil.ImageLoaderFactory)?.newImageLoader()?.memoryCache?.clear()
            }
        }

        /** 查询当前缓存总大小（字节） */
        fun cacheSizeBytes(ctx: Context): Long {
            val http = dirSize(File(ctx.cacheDir, "http"))
            val img = dirSize(File(ctx.cacheDir, "image_cache"))
            return http + img
        }

        private fun deleteRecursive(f: File) {
            if (!f.exists()) return
            if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
            f.delete()
        }

        private fun dirSize(f: File): Long {
            if (!f.exists()) return 0L
            return if (f.isFile) f.length() else f.listFiles()?.sumOf { dirSize(it) } ?: 0L
        }
    }
}
