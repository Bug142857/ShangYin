package com.shangyin.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shangyin.app.data.download.DownloadKeepAlive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 应用内升级：GitHub Releases 作为更新源（与现有发版流程一致，不需要额外服务端）。
 *
 * 能力边界：普通应用只能「检查 → 下载 → 拉起系统安装器」，
 * 静默安装需要 root / DeviceOwner，不在本方案的覆盖范围内。
 */
object UpdateChecker {

    private const val OWNER = "Bug142857"
    private const val REPO = "ShangYin"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    /** GitHub API 要求携带 UA，否则直接 403 */
    private const val UA = "ShangYin-Android"

    /**
     * 直连失败时依次尝试的下载镜像（2026-10 实测：gh-proxy.com 约 1.7MB/s，
     * ghproxy.net 约 230KB/s，两者整包 SHA-256 均与官方 digest 一致；
     * ghfast.top 仅 22KB/s、ghproxy.cc 证书过期、其余镜像解析失败/超时，不纳入）。
     */
    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    /**
     * 静态清单兜底：GitHub API 不可达（被墙/限流）时改读仓库根目录的 version.json。
     * 2026-10 实测三条地址均可用；清单在每次发版时同步更新。
     */
    private val MANIFEST_URLS = listOf(
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/version.json",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$OWNER/$REPO/main/version.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/$OWNER/$REPO/main/version.json",
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        // v0.318：有效线路 10 秒内必有响应；10 秒拿不到就判定该线路不可用，立即换镜像
        // （连接与读取都收紧到 10 秒，避免被墙的 GitHub 直连把等待拖到十几秒）
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 检查更新专用短超时客户端：API 和清单都在 10 秒内出结果，避免界面长时间卡在"检查中" */
    private val checkClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 下载线路测速客户端（v0.320）：每个候选线路拉取 256KB 样例，
     * 4 秒内测不出速度就视为该线路不可用。并发测速，避免逐条等待。
     */
    private val probeClient = client.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private const val PROBE_BYTES = 256 * 1024L

    /** 待安装的新版本 */
    data class UpdateInfo(
        val versionName: String,
        val apkUrl: String,
        val sizeBytes: Long,
        val sha256: String?,
        val changelog: String,
    )

    sealed class CheckResult {
        data class NewVersion(val info: UpdateInfo) : CheckResult()
        data object UpToDate : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        @SerialName("body") val body: String = "",
        @SerialName("assets") val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        @SerialName("name") val name: String = "",
        @SerialName("size") val size: Long = 0L,
        @SerialName("browser_download_url") val downloadUrl: String = "",
        @SerialName("digest") val digest: String? = null,
    )

    /** 静态清单结构（仓库根目录 version.json，发版时更新） */
    @Serializable
    private data class ManifestDto(
        val versionName: String = "",
        val sizeBytes: Long = 0L,
        val sha256: String? = null,
        val changelog: String = "",
        val apkUrl: String = "",
    )

    /** 当前安装包版本名（如 "0.314"） */
    fun localVersionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

    /** 查询最新版本：优先 GitHub API，不可用时改用静态清单兜底 */
    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val apiResult = checkViaApi(context)
        if (apiResult !is CheckResult.Error) return@withContext apiResult
        // API 被墙/限流/异常 → 读静态清单，避免用户完全检查不到新版
        when (val manifestResult = checkViaManifest(context)) {
            is CheckResult.Error ->
                CheckResult.Error("${apiResult.message}；静态清单也不可用（${manifestResult.message}）")
            else -> manifestResult
        }
    }

    private fun checkViaApi(context: Context): CheckResult {
        val request = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", UA)
            .build()
        return try {
            checkClient.newCall(request).execute().use { resp ->
                when {
                    resp.code == 404 -> return CheckResult.Error("更新服务不可用（仓库未公开或地址变更）")
                    resp.code == 403 || resp.code == 429 ->
                        return CheckResult.Error("检查太频繁（GitHub 限流），请稍后再试")
                    !resp.isSuccessful ->
                        return CheckResult.Error("检查更新失败（HTTP ${resp.code}）")
                }
                val dto = json.decodeFromString<ReleaseDto>(resp.body?.string().orEmpty())
                val asset = dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    ?: return CheckResult.Error("最新版本没有找到 APK 安装包")
                buildResult(
                    context = context,
                    remoteVersion = dto.tagName.trim().removePrefix("v").removePrefix("V"),
                    apkUrl = asset.downloadUrl,
                    sizeBytes = asset.size,
                    // GitHub 资产摘要格式为 "sha256:xxxx"，旧接口可能没有该字段
                    sha256 = asset.digest?.substringAfter("sha256:", "")?.takeIf { it.isNotBlank() },
                    changelog = dto.body.trim(),
                )
            }
        } catch (e: Exception) {
            CheckResult.Error("无法连接更新服务器：${e.message ?: "网络错误"}")
        }
    }

    /** 依次尝试静态清单地址（raw 直连 → 镜像），第一个解析成功的生效 */
    private fun checkViaManifest(context: Context): CheckResult {
        var lastError = "没有可用的清单地址"
        for (url in MANIFEST_URLS) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", UA).build()
                checkClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        lastError = "HTTP ${resp.code}"
                        return@use
                    }
                    val dto = json.decodeFromString<ManifestDto>(resp.body?.string().orEmpty())
                    if (dto.apkUrl.isBlank()) {
                        lastError = "清单缺少 apkUrl"
                        return@use
                    }
                    return buildResult(
                        context = context,
                        remoteVersion = dto.versionName.trim().removePrefix("v").removePrefix("V"),
                        apkUrl = dto.apkUrl.trim(),
                        sizeBytes = dto.sizeBytes,
                        sha256 = dto.sha256?.trim()?.takeIf { it.isNotBlank() },
                        changelog = dto.changelog.trim(),
                    )
                }
            } catch (e: Exception) {
                lastError = e.message ?: "网络错误"
            }
        }
        return CheckResult.Error(lastError)
    }

    /** 远端版本与本地比较，决定「有新版本」还是「已是最新」 */
    private fun buildResult(
        context: Context,
        remoteVersion: String,
        apkUrl: String,
        sizeBytes: Long,
        sha256: String?,
        changelog: String,
    ): CheckResult {
        if (remoteVersion.isBlank()) return CheckResult.Error("无法识别最新版本号")
        if (!isNewer(remoteVersion, localVersionName(context))) return CheckResult.UpToDate
        return CheckResult.NewVersion(
            UpdateInfo(
                versionName = remoteVersion,
                apkUrl = apkUrl,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                changelog = changelog,
            )
        )
    }

    /** 候选下载线路（直连 + 镜像）；顺序即默认回退顺序 */
    private fun channelsFor(info: UpdateInfo): List<String> = buildList {
        add(info.apkUrl)
        // 只给 GitHub 原始地址套镜像前缀（清单里若已写镜像直链则不再二次拼接）
        if (info.apkUrl.startsWith("https://github.com/")) {
            MIRROR_PREFIXES.forEach { add(it + info.apkUrl) }
        }
    }.distinct()

    /**
     * 下载线路优选（v0.320）：并发测速后按速度从高到低返回全部候选线路。
     * 测速失败/超时的线路排到最后（仍保留，作为兜底回退）；
     * 全部失败时返回原顺序，由 [download] 正常逐条尝试。
     */
    suspend fun rankChannels(info: UpdateInfo): List<String> = withContext(Dispatchers.IO) {
        val channels = channelsFor(info)
        if (channels.size <= 1) return@withContext channels
        val scored = coroutineScope {
            channels.map { url ->
                async {
                    url to runCatching { probeSpeed(url) }.getOrDefault(-1L)
                }
            }.awaitAll()
        }
        scored.sortedWith(compareByDescending<Pair<String, Long>> { it.second }).map { it.first }
    }

    /** 拉取一小段做测速，返回 bytes/秒；失败返回 -1 */
    private fun probeSpeed(url: String): Long {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
            .build()
        // 从发起请求开始计时（含 DNS/TLS/重定向），更接近用户实际感受到的起速
        val started = SystemClock.elapsedRealtime()
        return probeClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return -1L
            if (resp.header("Content-Type").orEmpty().contains("text/html", true)) return -1L
            val body = resp.body ?: return -1L
            val input = body.byteStream()
            val buf = ByteArray(32 * 1024)
            var read = 0L
            while (read < PROBE_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                read += n
            }
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            // 少于 32KB 说明不是正常安装包（错误页/限流），视为不可用
            if (read < 32 * 1024) -1L else read * 1000 / elapsed
        }
    }

    /** 线路展示名（用于下载进度里显示当前实际使用的线路） */
    private fun channelLabel(url: String): String = when {
        url.startsWith("https://github.com/") -> "GitHub 直连"
        url.startsWith("https://gh-proxy.com/") -> "gh-proxy.com"
        url.startsWith("https://ghproxy.net/") -> "ghproxy.net"
        else -> url.substringAfter("://").substringBefore("/")
    }

    /**
     * 下载安装包到 cacheDir/updates：优先使用 [rankChannels] 优选后的线路顺序，
     * 某条线路失败时自动尝试下一条；[onChannel] 回传当前线路名供界面展示。
     * 每次尝试都会重新校验 SHA-256（有摘要时），校验不过视为该通道失败。
     * 支持协程取消（取消时中止底层 HTTP 请求，抛出 CancellationException）。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        preferredChannels: List<String>? = null,
        onChannel: (String) -> Unit = {},
    ): File {
        // 前台服务 + 唤醒锁：息屏/切后台时继续下载（v0.319）
        DownloadKeepAlive.begin(context)
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // 只保留本次的安装包，避免历史版本堆积
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "update-${info.versionName}.apk")

            val channels = preferredChannels?.takeIf { it.isNotEmpty() } ?: channelsFor(info)
            var lastError: Exception? = null
            for (url in channels) {
                try {
                    onChannel(channelLabel(url))
                    onProgress(0L, info.sizeBytes)
                    downloadOnce(url, target, info.sizeBytes, onProgress)
                    if (info.sha256 != null && !sha256(target).equals(info.sha256, ignoreCase = true)) {
                        throw IOException("安装包校验失败")
                    }
                    return target
                } catch (e: CancellationException) {
                    target.delete()
                    throw e
                } catch (e: Exception) {
                    target.delete()
                    lastError = e
                }
            }
            throw IOException("所有下载通道均失败（${lastError?.message ?: "网络错误"}），可打开发布页手动下载")
        } finally {
            DownloadKeepAlive.end()
        }
    }

    /** 单通道下载；失败抛出异常，由 [download] 决定是否换镜像 */
    private suspend fun downloadOnce(
        url: String,
        target: File,
        sizeHint: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = suspendCancellableCoroutine { cont ->
        val call = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()
        )
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        if (!cont.isCancelled) {
                            cont.resumeWithException(IOException("HTTP ${resp.code}"))
                        }
                        return
                    }
                    try {
                        val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: sizeHint
                        resp.body?.byteStream()?.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    done += n
                                    onProgress(done, total)
                                }
                            }
                        }
                        if (!cont.isCancelled) cont.resume(Unit)
                    } catch (t: Throwable) {
                        target.delete()
                        if (!cont.isCancelled) cont.resumeWithException(t)
                    }
                }
            }
        })
    }

    /** 拉起系统安装器（覆盖安装，签名必须与已安装版本一致） */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Android 8+ 需要用户为本应用开启「安装未知应用」 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openReleasesPage(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 版本名按数字段比较（"0.315" > "0.314"） */
    private fun isNewer(remote: String, local: String): Boolean {
        val a = versionParts(remote)
        val b = versionParts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun versionParts(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V").split('.').map { part ->
            part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
