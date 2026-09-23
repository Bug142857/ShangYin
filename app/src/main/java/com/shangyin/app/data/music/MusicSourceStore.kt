package com.shangyin.app.data.music

import com.shangyin.app.ui.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 音乐音源管理：导入 / 启用 / 初始化 LX 自定义音源脚本。
 *
 * 音源脚本（洛雪音源）只负责把"某平台某首歌"解析成可播放直链（action=musicUrl），
 * 搜索/歌单/歌词数据走 App 内置接口（[MusicApis]）。
 * 推荐音源来源：https://github.com/pdone/lx-music-source
 */
object MusicSourceStore {

    /** 内置推荐音源（一键导入用），raw 为 GitHub 原始地址，导入失败时自动走加速镜像 */
    data class Recommended(val name: String, val rawUrl: String)

    val RECOMMENDED = listOf(
        Recommended("六音音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/sixyin/latest.js"),
        Recommended("Huibq 音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/huibq/latest.js"),
        Recommended("Flower 音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/flower/latest.js"),
        Recommended("LX 音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/lx/latest.js"),
        Recommended("长青音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/changqing/latest.js"),
        Recommended("幻音音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/huanyin/latest.js"),
        Recommended("ikun 音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/ikun/latest.js"),
        Recommended("Grass 音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/grass/latest.js"),
        Recommended("聚合 API 音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/juhe/latest.js"),
        Recommended("QDY 音源", "https://raw.githubusercontent.com/pdone/lx-music-source/main/qdy/latest.js")
    )

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _scripts = MutableStateFlow(SettingsStore.getMusicSources())
    val scripts: StateFlow<List<MusicSourceScript>> = _scripts.asStateFlow()

    /** 重新从 SP 读一遍（配置恢复/导入导出后刷新 UI） */
    fun refresh() {
        _scripts.value = SettingsStore.getMusicSources()
    }

    fun all(): List<MusicSourceScript> = SettingsStore.getMusicSources()

    /** 支持某平台且已启用的音源（按导入顺序） */
    fun enabledFor(platform: MusicPlatform): List<MusicSourceScript> =
        all().filter { it.enabled && it.supports(platform) }

    /** 是否有任何已启用音源声明支持该平台 */
    fun hasSourceFor(platform: MusicPlatform): Boolean = enabledFor(platform).isNotEmpty()

    private fun save(list: List<MusicSourceScript>) {
        SettingsStore.setMusicSources(list)
        _scripts.value = list
    }

    // ---------------- 导入 ----------------

    /** 导入音源脚本（[sourceUrl] 非空时记录来源地址；[content] 非空时直接用本地内容） */
    suspend fun import(content: String, name: String = "", sourceUrl: String = ""): Result<String> =
        withContext(Dispatchers.IO) {
            val text = content.trim()
            if (text.length < 50) return@withContext Result.failure(Exception("脚本内容为空或过短"))
            // ⚠️ 不能用关键字判断脚本有效性：混淆过的音源（六音等）连 `globalThis.lx` 都是加密字符串，
            // 早期版本按关键字校验会把好脚本误判成"不是有效的音源脚本"。这里只拦明显不是脚本的内容
            // （比如下载到网页/404 页面），真正的有效性交给下面的脚本初始化来判定。
            val head = text.take(300).lowercase()
            if (head.contains("<!doctype") || head.contains("<html")) {
                return@withContext Result.failure(Exception("下载到的是网页而不是脚本，导入地址可能已失效"))
            }
            val meta = parseMeta(text)
            val script = MusicSourceScript(
                id = UUID.randomUUID().toString().take(8),
                name = meta["name"]?.takeIf { it.isNotBlank() } ?: name.ifBlank { "未命名音源" },
                version = meta["version"].orEmpty(),
                author = meta["author"].orEmpty(),
                homepage = meta["homepage"].orEmpty(),
                description = meta["description"].orEmpty(),
                url = sourceUrl,
                content = text,
                enabled = true
            )
            val existing = all()
            if (existing.any { it.name == script.name }) {
                return@withContext Result.failure(Exception("已存在同名音源：${script.name}"))
            }
            val result = LxSourceEngine.initScript(script)
            val stored = script.copy(
                support = result.support ?: emptyMap(),
                lastError = result.error
            )
            save(existing + stored)
            if (result.error != null) Result.failure(Exception(result.error))
            else Result.success(stored.name)
        }

    /** 从网络地址导入（GitHub 原始地址失败时自动换加速镜像重试） */
    suspend fun importFromUrl(url: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return@withContext Result.failure(Exception("地址为空"))
        val candidates = buildList {
            add(trimmed)
            if (trimmed.contains("raw.githubusercontent.com")) {
                MIRRORS.forEach { add(it + trimmed) }
            }
        }
        var lastError: Exception? = null
        for (candidate in candidates) {
            val text = try {
                download(candidate)
            } catch (e: Exception) {
                lastError = Exception(e.message ?: "下载失败")
                ""
            }
            if (text.isBlank()) {
                if (lastError == null) lastError = Exception("下载内容为空")
            } else {
                // 内容拿到了就定结果：初始化失败也不再换镜像重试（那是脚本问题，不是网络问题）
                return@withContext import(text, sourceUrl = trimmed)
            }
        }
        Result.failure(lastError ?: Exception("下载失败"))
    }

    /** 从本地文件导入（[fileName] 仅用于失败时提示） */
    suspend fun importFromFile(fileName: String, content: String): Result<String> =
        import(content, name = fileName.substringBeforeLast('.'), sourceUrl = "local://$fileName")

    // ---------------- 初始化 / 启停 ----------------

    /** 初始化脚本并落库它声明的平台与音质（返回失败原因，成功为 null） */
    suspend fun initOne(script: MusicSourceScript): String? {
        val result = LxSourceEngine.initScript(script)
        val list = all().map {
            if (it.id == script.id) it.copy(support = result.support ?: emptyMap(), lastError = result.error)
            else it
        }
        save(list)
        return result.error
    }

    /** 把所有"启用但还没拿到平台信息 / 上次初始化失败"的脚本跑一遍（进音乐页与播放前调用） */
    suspend fun ensureInitialized() {
        all().filter { it.enabled && (it.support.isEmpty() || it.lastError != null) }
            .forEach { initOne(it) }
    }

    suspend fun setEnabled(script: MusicSourceScript, enabled: Boolean) {
        save(all().map { if (it.id == script.id) it.copy(enabled = enabled) else it })
        if (enabled) {
            initOne(script.copy(enabled = true))
        } else {
            LxSourceEngine.release(script.id)
        }
    }

    suspend fun delete(script: MusicSourceScript) {
        LxSourceEngine.release(script.id)
        save(all().filterNot { it.id == script.id })
    }

    // ---------------- 内部 ----------------

    private fun download(url: String): String {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    /** 解析脚本头部注释块里的 @name / @version / @author / @homepage / @description */
    private fun parseMeta(text: String): Map<String, String> {
        val head = text.take(3000)
        fun field(key: String): String? =
            Regex("@$key\\s*:?\\s*(.+)").find(head)?.groupValues?.getOrNull(1)
                ?.trim()?.trimEnd('*', '/')?.trim()?.takeIf { it.isNotEmpty() }
        return mapOf(
            "name" to field("name").orEmpty(),
            "version" to field("version").orEmpty(),
            "author" to field("author").orEmpty(),
            "homepage" to field("homepage").orEmpty(),
            "description" to field("description").orEmpty()
        )
    }

    /** GitHub 原始地址的加速镜像（用户网络直连 GitHub 不稳时兜底） */
    private val MIRRORS = listOf(
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
        "https://gh-proxy.org/"
    )
}
