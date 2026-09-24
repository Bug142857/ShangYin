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
        // 记录 WebView 的真实默认 UA：登录页与接口请求必须用同一个 UA（Cookie 与 UA 绑定），
        // 且不能伪造——伪造的桌面 UA 会与 WebView 自动发出的 Client Hints（sec-ch-ua-platform: Android）
        // 互相矛盾，反爬系统会判定为机器人，表现为「浏览器能打开、App 里一直转圈/过不了验证」。
        webViewUa = runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }.getOrDefault("")
        // 关键配置恢复（豆瓣Cookie/坚果云/片源）——从公共目录备份文件补缺，防卸载重装丢配置
        runCatching { com.shangyin.app.data.ConfigBackup.restoreIfNeeded(this) }
        // 把已保存的 Cookie 回填 WebView（保证「登录态能一直保持」，见方法注释）
        runCatching { restoreCookiesToWebView() }
        // 清掉 zlib 的风控/一次性 Cookie：历史版本曾把它们存下来并回填，残留的**无效
        // `__diamwall` 会让整站死循环**（实测真浏览器复现 ERR_TOO_MANY_REDIRECTS），
        // 启动时清一次让服务端重新下发（登录票据 remix_* 不受影响）
        runCatching { com.shangyin.app.data.zlib.ZlibClient.clearTransientCookies() }
        // 首次使用播种内置默认采集源（在线观影）
        SettingsStore.ensureDefaultVodSourcesSeeded()
        // 首次使用播种内置默认电视源（里世界 → 电视）
        SettingsStore.ensureDefaultLiveSourcesSeeded()
        // 一次性数据迁移：收藏分类「直播」→「电视」
        // （幂等：UPDATE 匹配不到就是 0 行，重复执行无害）
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { Repo.migrateLiveCategoryToTv() }
        }
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

    /**
     * 把已保存的 Cookie 回填到 WebView 的 CookieManager。
     *
     * 为什么必须做：豆瓣 / 无忧游戏库 / Z-Library 的登录态**存在两处** ——
     * SP（接口请求手动带 Cookie）和 CookieManager（WebView 里发请求时自动带）。
     * 两者互不相关：卸载重装（SP 由 ConfigBackup 恢复）、清理 WebView 数据、换手机之后，
     * SP 里还写着「已登录」，WebView 却以匿名身份加载站点 →
     * 表现就是用户说的「明明登录着、却又要我登录一次」。
     * 启动回填一次，登录态才能真正跟着 SP 保持住。
     *
     * ⚠️⚠️ **不能把整串 Cookie 原样写回**（v2.23.18 就是这么干的，踩了坑）：
     * 保存下来的串里还包含**反爬风控 / 一次性会话** Cookie（zlib 实测有
     * `__diamwall`、`c_token`、`bsrv`）。把**已经失效**的风控票据写回去，反爬会进入
     * 「带错票据 → 307 跳回自己 → 再带同一个错票据」的**死循环**，
     * WebView 直接报 `net:ERR_TOO_MANY_REDIRECTS`（真浏览器注入无效 `__diamwall` 可 100% 复现；
     * 只删掉这一个 Cookie 立刻恢复）。所以：风控/临时 Cookie **既不回填**（见下），
     * 也会被 `ZlibClient.clearTransientCookies()` 主动删掉、让站点重新下发；
     * zlib 更进一步——只回填登录票据 `remix_userid`/`remix_userkey`。
     */
    private fun restoreCookiesToWebView() {
        val cm = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull() ?: return
        runCatching { cm.setAcceptCookie(true) }

        /**
         * 反爬风控 / 一次性会话票据：绝不回填。
         * 名字集中定义在 [com.shangyin.app.data.zlib.ZlibClient.TRANSIENT_COOKIE_NAMES]，
         * 并由 `ZlibClient.clearTransientCookies()` 在启动/开登录页/死循环自愈时**删除**
         * （实测：残留一个无效的 `__diamwall` 就能让整站 `ERR_TOO_MANY_REDIRECTS`，删掉即恢复）。
         */
        val transientNames = com.shangyin.app.data.zlib.ZlibClient.TRANSIENT_COOKIE_NAMES

        /** [only] 非空时只回填这些名字 */
        fun put(url: String, cookie: String, only: Set<String>? = null) {
            if (cookie.isBlank()) return
            cookie.split(";").forEach { part ->
                val kv = part.trim()
                val i = kv.indexOf('=')
                if (i <= 0) return@forEach
                val name = kv.substring(0, i).trim().lowercase()
                if (name in transientNames) return@forEach
                if (only != null && name !in only) return@forEach
                runCatching { cm.setCookie(url, "$kv; path=/") }
            }
        }

        // 豆瓣：搜索/详情/登录会互相跳子域，每个子域都要能带上
        val douban = SettingsStore.doubanCookie
        listOf(
            "https://www.douban.com/",
            "https://movie.douban.com/",
            "https://m.douban.com/",
            "https://book.douban.com/",
            "https://accounts.douban.com/"
        ).forEach { put(it, douban) }

        // 无忧游戏库
        put("https://www.wygamer.com/", SettingsStore.wygamerCookie)

        // Z-Library：站点每日换域名，且登录票据是 host-only（换 host 就不发送），
        // 所以当前线路 + zh. 变体都写一份；只写登录票据，风控 Cookie 由站点重新下发
        val zlibLoginOnly = setOf("remix_userid", "remix_userkey")
        val host = SettingsStore.zlibHost
        setOf(host, "zh.$host", host.removePrefix("zh."))
            .filter { it.isNotBlank() }
            .forEach { put("https://$it/", SettingsStore.zlibCookie, only = zlibLoginOnly) }

        runCatching { cm.flush() }
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
                } else if (host == "pic.ndhixj.cn" || host.endsWith(".ndhixj.cn")) {
                    // 吃瓜（51爆料）图床：Referer 用当前镜像 + 浏览器 UA（默认 okhttp UA 易被风控拦截）
                    req.newBuilder()
                        .header(
                            "Referer",
                            SettingsStore.melonBase.ifBlank { "https://branch.upsqllhj.cc" }.trimEnd('/') + "/"
                        )
                        .header("User-Agent", com.shangyin.app.data.melon.MelonClient.UA)
                        .build()
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
     * - respectCacheHeaders=false：本子/漫画等图床常返回 no-store / no-cache，
     *   默认策略会导致封面每次重新下载（费流量），故强制入磁盘缓存，由 LRU 负责淘汰。
     * - addLastModifiedToFileCacheKey=false：缓存键只认 URL，避免 Last-Modified 变化导致重新下载。
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(getOrCreateOkHttpClient())
            .crossfade(true)
            .respectCacheHeaders(false)
            .addLastModifiedToFileCacheKey(false)
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

        /**
         * WebView 真实默认 UA（启动时捕获）；站点登录页与接口请求共用，保证 Cookie 与 UA 一致。
         * @Volatile：主线程写、OkHttp/WebView 的 IO 线程读，不加会读到旧值（表现为随机「未捕获」）
         */
        @Volatile
        var webViewUa: String = ""

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
