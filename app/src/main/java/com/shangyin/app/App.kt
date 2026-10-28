package com.shangyin.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.shangyin.app.data.Repo
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.data.melon.MelonClient
import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // v0.243：记录前台 Activity——隐藏 WebView 必须用 Activity context 创建。
        // ApplicationContext 创建的 WebView 在部分设备上 Chromium 静默瘫痪
        // （evaluateJavascript 回调一次都不执行——0.235~0.242 七轮 ensure=false 的根因，
        // 而诊断探针用 Activity context 的 WebView 每次都成功，同设备同网络对照实锤）
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { currentActivity = activity }
            override fun onActivityPaused(activity: android.app.Activity) { }
            override fun onActivityStarted(activity: android.app.Activity) { }
            override fun onActivityStopped(activity: android.app.Activity) { }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) { }
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (currentActivity === activity) currentActivity = null
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) { }
        })
        Repo.init(this)
        SettingsStore.init(this)
        // v0.310：内置哔咔账号（账号管理里已隐藏哔咔登录项，新装用户开箱即用）
        runCatching { SettingsStore.ensureDefaultBikaAccountSeeded() }
        // 漫画（Komiic）与无忧游戏：同样内置账号（账号管理里不展示，漫画侧自动静默登录）
        runCatching { SettingsStore.ensureDefaultKomiicAccountSeeded() }
        runCatching { SettingsStore.ensureDefaultWygamerAccountSeeded() }
        com.shangyin.app.data.InnerFavorites.init(this)
        // 阅读进度（上次看到）单独存 SharedPreferences：启动时加载，导出/云同步才能拿到完整数据
        com.shangyin.app.data.ReadProgressStore.ensure(this)
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
        // 首次启动播种默认数据（v0.311）：影视源 / 电视源 / 表世界清单，来自 assets/default_seed.json
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.shangyin.app.data.DefaultSeed.seedIfNeeded(this@App) }
        }
        // 一次性数据迁移：收藏分类「直播」→「电视」
        // （幂等：UPDATE 匹配不到就是 0 行，重复执行无害）
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { Repo.migrateLiveCategoryToTv() }
        }
        // 配置变更（登录/云同步/片源等）时自动备份到公共目录。
        // v0.308：改为「除内部状态外全部键」——新增设置项自动纳入，不再维护白名单
        SettingsStore.registerListener { _, key ->
            if (key != null && SettingsStore.isBackupKey(key)) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { com.shangyin.app.data.ConfigBackup.backup(this@App) }
                }
            }
        }
        // 零孤儿机制：启动时静默清理历史遗留的孤立收藏（v2.4.0 起新孤儿不会再产生）
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { Repo.pruneOrphans() }
        }
        // 版本升级清一次 Coil 磁盘缓存（v0.226，图片"永久占位图"的定案修复）：
        // 0.218-0.220 图床域名轮换期，风控页/垃圾响应以 HTTP 200 返回，被 Coil 当成功
        // 图片缓存进磁盘（respectCacheHeaders=false + 缓存键只认 URL → 永不过期）。
        // 此后这些 URL「加载成功但解码失败」→ 永远显示占位图，换域名/DoH 均无效
        // （命中磁盘缓存根本不走网络）。清缓存让图片重新下载；配合拦截器的
        // Content-Type 校验，垃圾响应今后进不了缓存。删除目录是幂等操作，Coil 会自动重建。
        if (SettingsStore.cacheClearedVersion != BuildConfig.VERSION_CODE.toLong()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { deleteRecursive(File(cacheDir, "image_cache")) }
                // v0.231 关键补充：okhttp 的 20MB HTTP 缓存（cacheDir/http）同样被 0.218-0.220
                // 时代的垃圾 text/html 污染——0.226 只清了 Coil 层没清这层，请求在 HTTP 缓存
                // 命中垃圾 → 校验拒绝 → 换域也是缓存垃圾 → 图片全挂。设备诊断证实网络本身
                // 通（裸 client 拿真图成功），App 内唯一差异就是这层缓存。
                runCatching { deleteRecursive(File(cacheDir, "http")) }
                // v0.241 补充：WebView 解密明文缓存（melon_web_img）一并清——多轮带病迭代
                // 期间该目录进过什么没人说得清，升级即清最干净（文件本来就是可重建的）
                runCatching {
                    com.shangyin.app.data.melon.MelonWebFetcher.cacheDir(this@App).deleteRecursively()
                }
                SettingsStore.cacheClearedVersion = BuildConfig.VERSION_CODE.toLong()
            }
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
            // 图床域名 DoH 兜底（v0.224）：仅 pic.{词}.cn 生效，DNS 污染/解析失败时走阿里 DoH
            // 图片 DNS（v0.282 从 PicDns 升级为 GeneralDohDns）：无忧游戏库封面分散在
            // queniuqe/eccdnx/steamstatic/imgsygu 等第三方 CDN，手机网络下部分 CDN 被污染/
            // 不可达（PC 直连全部 200 正常，站点/解析/请求头均无问题）→ 封面大面积挂。
            // GeneralDohDns 系统解析 + DoH 双路合并、okhttp 试连自动跳坏节点——播放器
            // v0.230 解决「有的视频能播有的不能」的同款方案，缓存 10 分钟开销可控。
            .dns(com.shangyin.app.data.melon.GeneralDohDns)
            .addInterceptor { chain ->
                val req = chain.request()
                // Komiic 章节图现在需要登录（匿名 402）：请求前带上内置账号 token
                val komiicImage = (req.url.host == "komiic.com" || req.url.host == "komiic.cc") &&
                    req.url.encodedPath.startsWith("/api/image/")
                // 图片请求改写逻辑已抽出为纯函数 rewriteImageRequest（见文件末尾），便于单元测试
                val out = rewriteImageRequest(
                    req,
                    komiicToken = if (komiicImage) com.shangyin.app.data.komiic.KomiicClient.imageToken() else ""
                )
                // 吃瓜图床：域名频繁轮换 + 设备直连当前图床可能不可达（v0.220/0.221 时代
                // 「图全挂」的根因，实测 Referer/UA 均无防盗链）。新旧图床回源同一存储、
                // 路径通用 → 失败自动换域名重试（候选来自页面解析自动收集，见 MelonClient）。
                if (out.url.host.matches(com.shangyin.app.data.melon.MELON_PIC_HOST)) {
                    // 熔断直达（v0.223）：会话内已有成功域名 → 发出前直接改写，
                    // 不再每张图都从死域名开始试错（每张最坏 18s，Coil 排队下全列表拖死）
                    val preferred = com.shangyin.app.data.melon.MelonClient.activePicHost
                    val primary = if (preferred != null && preferred != out.url.host)
                        swapMelonPicHost(out, preferred) ?: out else out
                    var lastErr: java.io.IOException? = null
                    var contentError = false
                    // 200 但不是图片（v0.226 根因修复）：图床风控会返回 HTTP 200 的 HTML/风控页，
                    // 以前被 Coil 当成功图片缓存 → URL 永久"占位图"。必须校验 Content-Type，
                    // 非图片视为该域失败，走换域重试；也确保垃圾响应永远进不了磁盘缓存。
                    // v0.229 关键修正：PC 直连联通边缘节点实测，图床对图片返回的 Content-Type
                    // 是 binary/octet-stream 而非 image/*（293KB 图片字节完整可解码）——
                    // 0.226 的 startsWith("image") 过严，把真图全拦成占位图，是 0.226-0.228
                    // 图片仍挂的真凶。改为只拒绝 text/html（风控/挑战页）；其余放行交给
                    // Coil 解码自证——解码失败 Coil 不会写缓存，不会重现污染。
                    fun isImageResponse(r: okhttp3.Response) =
                        r.header("Content-Type")?.contains("text/html", ignoreCase = true) != true

                    // v0.228 Cookie 直通 + v0.231 强制走网络：WebView 通过挑战写入的 Cookie
                    // 带给 Coil（复刻 WebView 成功路径）；图床请求**永不读 HTTP 缓存**——
                    // 0.218 时代的垃圾响应就存在 okhttp 20MB HTTP 缓存里，命中垃圾 → 校验
                    // 拒绝 → 换域也是缓存垃圾 → 全挂。Coil 自身磁盘缓存已足够，HTTP 缓存
                    // 对图床只有害，FORCE_NETWORK 一劳永逸防再污染。
                    fun prep(r: okhttp3.Request): okhttp3.Request {
                        val c = runCatching {
                            android.webkit.CookieManager.getInstance()
                                .getCookie("https://${r.url.host}/")
                        }.getOrNull()
                        val b = if (c.isNullOrBlank()) r.newBuilder()
                        else r.newBuilder().header("Cookie", c)
                        return b.cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()
                    }
                    try {
                        val resp = chain.proceed(prep(primary))
                        if (resp.isSuccessful && isImageResponse(resp)) {
                            // 回写成功域名（resp.request.url 含 301 跟随后的落地域名）
                            com.shangyin.app.data.melon.MelonClient.promotePicHost(resp.request.url.host)
                            return@addInterceptor resp
                        }
                        if (resp.isSuccessful) {
                            resp.close()
                            lastErr = java.io.IOException("HTTP 200 但非图片响应")
                        } else {
                            val code = resp.code
                            resp.close()
                            // 单图内容问题（图被删），换域也无用 → 不降级不试备选
                            if (code == 404 || code == 410) contentError = true
                            else lastErr = java.io.IOException("HTTP $code")
                        }
                    } catch (e: java.io.IOException) {
                        lastErr = e
                    }
                    if (contentError) throw lastErr ?: java.io.IOException("HTTP 404")
                    com.shangyin.app.data.melon.MelonClient.demotePicHost(primary.url.host)
                    for (h in com.shangyin.app.data.melon.MelonClient.fallbackPicHosts(primary.url.host)) {
                        val retry = swapMelonPicHost(primary, h) ?: continue
                        try {
                            val resp = chain.proceed(prep(retry))
                            if (resp.isSuccessful && isImageResponse(resp)) {
                                com.shangyin.app.data.melon.MelonClient.promotePicHost(resp.request.url.host)
                                return@addInterceptor resp
                            }
                            if (resp.isSuccessful) lastErr = java.io.IOException("HTTP 200 但非图片响应")
                            resp.close()
                        } catch (e: java.io.IOException) {
                            lastErr = e
                        }
                        com.shangyin.app.data.melon.MelonClient.demotePicHost(h)
                    }
                    throw lastErr ?: java.io.IOException("吃瓜图床加载失败")
                }
                if (komiicImage) {
                    // token 24 小时过期（匿名 402）：登录态异常时强制重登一次再重试（并发多图只会登一次）
                    var resp = chain.proceed(out)
                    if (resp.code == 401 || resp.code == 402 || resp.code == 403) {
                        resp.close()
                        val stale = out.header("Authorization")?.removePrefix("Bearer ").orEmpty()
                        val fresh = com.shangyin.app.data.komiic.KomiicClient
                            .imageToken(forceRefresh = true, staleToken = stale)
                        if (fresh.isNotBlank()) {
                            resp = chain.proceed(
                                out.newBuilder().header("Authorization", "Bearer $fresh").build()
                            )
                        }
                    }
                    return@addInterceptor resp
                }
                chain.proceed(out)
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
            // v0.235 吃瓜图床「内容级风控」修复：图床只对 Chromium TLS 指纹下发真密文，
            // 其它所有栈拿到 200+混淆假字节（四栈实测）。自定义 Fetcher 走 WebView 管线
            // （借站点 JS fetch+decryptImage 解密），明文字节交给 Coil 缓存/解码。
            .components {
                // v0.240：Uri 版是决定性修复（Coil 对 String model 先映射为 Uri 再分派，
                // 0.235~0.239 的 Factory<String> 从未命中）；String 版兜底
                add(com.shangyin.app.data.melon.MelonWebFetcher.Factory())
                add(com.shangyin.app.data.melon.MelonWebFetcher.StringFactory())
            }
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

        /** 前台 Activity：隐藏 WebView 必须用它创建（ApplicationContext 创建的 WebView
         *  在部分设备上 Chromium 静默瘫痪，详见 onCreate 里的注释） */
        var currentActivity: android.app.Activity? = null

        /**
         * WebView 真实默认 UA（启动时捕获）；站点登录页与接口请求共用，保证 Cookie 与 UA 一致。
         * @Volatile：主线程写、OkHttp/WebView 的 IO 线程读，不加会读到旧值（表现为随机「未捕获」）
         */
        @Volatile
        var webViewUa: String = ""

        /** 清理 Coil 图片缓存 + OkHttp HTTP 缓存 + 吃瓜解密明文缓存 + 详情页内存缓存 */
        fun clearAllCaches(ctx: Context) {
            // 直接删缓存子目录：OkHttp 和 Coil 都会在下次请求时重建
            runCatching { deleteRecursive(File(ctx.cacheDir, "http")) }
            runCatching { deleteRecursive(File(ctx.cacheDir, "image_cache")) }
            // v0.235：吃瓜 WebView 解密管线的明文缓存（MelonWebFetcher 自建层）
            runCatching { deleteRecursive(com.shangyin.app.data.melon.MelonWebFetcher.cacheDir(ctx)) }
            // v0.321：漫画/本子详情+章节磁盘缓存、已下载的更新安装包、分享临时文件
            runCatching { com.shangyin.app.data.ComicCacheStore.clear(ctx) }
            runCatching { com.shangyin.app.data.comic.ComicStatusStore.clearMemory() }
            runCatching { deleteRecursive(File(ctx.cacheDir, "updates")) }
            runCatching { deleteRecursive(File(ctx.cacheDir, "share")) }
            // v0.308 修复：原来调 newImageLoader() —— 那是工厂方法，每次都会**新建**一个
            // ImageLoader，清的是新实例里空的内存缓存，对正在使用的单例毫无作用。
            // 改用 Coil 全局单例（ImageLoaderFactory 首次创建后复用同一个）。
            runCatching { coil.Coil.imageLoader(ctx).memoryCache?.clear() }
            // 详情页缓存（吃瓜/X站/游戏/图书）：属于"看过的页面不重拉"的一部分，一并清
            runCatching { com.shangyin.app.data.DetailCache.clear() }
        }

        /** 查询当前缓存总大小（字节）—— 与 clearAllCaches 的范围保持一致 */
        fun cacheSizeBytes(ctx: Context): Long {
            val http = dirSize(File(ctx.cacheDir, "http"))
            val img = dirSize(File(ctx.cacheDir, "image_cache"))
            val melon = dirSize(com.shangyin.app.data.melon.MelonWebFetcher.cacheDir(ctx))
            val comic = com.shangyin.app.data.ComicCacheStore.size(ctx)
            val updates = dirSize(File(ctx.cacheDir, "updates"))
            val share = dirSize(File(ctx.cacheDir, "share"))
            return http + img + melon + comic + updates + share
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

/**
 * 图片请求改写（防盗链 Referer / 反爬 UA）——App 拦截器的纯函数化，供单元测试直接驱动。
 * 输入原始请求，输出应发送的请求；不读不改任何全局状态。
 *
 * @param melonRefererBase 吃瓜图床 Referer 基址。生产路径取默认值 [MelonClient.lastBase]
 *   （内存中最近一次成功镜像）。v0.221 教训：曾误用持久化的 melonBase（可能早已失效）做
 *   Referer——回归测试通过显式传参锁定「Referer 来自调用方给的最近成功镜像」这一语义。
 */
internal fun rewriteImageRequest(
    req: Request,
    melonRefererBase: String = MelonClient.lastBase,
    komiicToken: String = ""
): Request {
    val host = req.url.host
    // v0.287/0.288：豆瓣已死图床域重写。2026-10-21 实测两类死域：
    //   qnmob{2,3}.doubanio.com — DNS 全网 ENOTFOUND（整体下线）
    //   so{1,2,3}.doubanio.com  — DNS 存活但连接全失败（IP 失效，「边缘行者 S1」封面所在域）
    // 同路径在 img2/img3.doubanio.com 上 200——豆瓣图片路径是 host 无关的（全 CDN 集群
    // 共享命名空间），重写 host 直接复活死链封面。
    val normalized = if (host.matches(Regex("""(qnmob\d*|so\d*)\.doubanio\.com"""))) {
        req.newBuilder()
            .url(req.url.newBuilder().host("img2.doubanio.com").build())
            .build()
    } else req
    val nHost = normalized.url.host
    return if (nHost.endsWith("doubanio.com") || nHost.endsWith("douban.com")) {
        normalized.newBuilder()
            .header("Referer", "https://m.douban.com/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                    "Version/16.0 Mobile/15E148 Safari/604.1"
            )
            .build()
    } else if ((nHost == "komiic.com" || nHost == "komiic.cc") && normalized.url.encodedPath.startsWith("/api/image/")) {
        // Komiic 章节图防盗链（主/镜像域）：fragment "#c/{comicId}/{chapterId}" → 完整路径 Referer
        // （fragment 不会发送到服务器，仅本地编码归属信息）；UA 与 KomiicClient 同款 Chrome/119
        // 现在还需要登录态（匿名 402）：把调用方给的 token 带上
        val b = normalized.newBuilder()
        if (komiicToken.isNotBlank()) b.header("Authorization", "Bearer $komiicToken")
        normalized.url.fragment?.takeIf { it.startsWith("c/") }?.let { f ->
            val p = f.removePrefix("c/").split('/')
            if (p.size == 2) {
                b.header("Referer", "https://${normalized.url.host}/comic/${p[0]}/chapter/${p[1]}")
                    .header("User-Agent", com.shangyin.app.data.komiic.KomiicClient.CHROME_UA)
            }
        }
        b.build()
    } else if (normalized.url.host.matches(com.shangyin.app.data.melon.MELON_PIC_HOST)) {
        // 吃瓜（51爆料）图床：Referer 用当前镜像 + 浏览器 UA（默认 okhttp UA 易被风控拦截）。
        // 图床域名会轮换（pic.ndhixj.cn → pic.jtzatly.cn，均为 pic.{词}.cn 模式），按模式匹配而非硬编码；
        // 2026-10-15 实测当前图床暂无防盗链，此分支为防御性保留。
        // Referer 用 lastBase（最近一次成功镜像）而非 melonBase（持久化镜像，可能已失效）。
        normalized.newBuilder()
            .header("Referer", melonRefererBase.trimEnd('/') + "/")
            .header("User-Agent", com.shangyin.app.data.melon.MelonClient.UA)
            .build()
    } else if (nHost.endsWith("phncdn.com")) {
        // P站（v0.280）封面：phncdn 部分边缘节点对无 Referer 的批量并发请求会拒绝/丢响应，
        // 带齐 UA + Referer 规范化（与页面抓取同一 UA，保持客户端一致）
        normalized.newBuilder()
            .header("Referer", com.shangyin.app.data.psite.PsiteClient.BASE + "/")
            .header("User-Agent", com.shangyin.app.data.psite.PsiteClient.UA)
            .build()
    } else normalized
}

/**
 * 吃瓜图床 fallback 域名替换（v0.222）：把 pic.{词}.cn 请求整体换到备用图床域名，
 * path/query 原样保留（实测新旧图床同源存储、路径通用）。host 不匹配图床形态时返回 null
 * ——绝不允许换域逻辑波及其它站点的图片请求。
 */
internal fun swapMelonPicHost(req: Request, newHost: String): Request? {
    if (!req.url.host.matches(com.shangyin.app.data.melon.MELON_PIC_HOST)) return null
    return req.newBuilder()
        .url(req.url.newBuilder().host(newHost).build())
        .build()
}
