package com.shangyin.app.data.sync

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 云同步客户端。
 * 每个用户用自己的 WebDAV 网盘（坚果云等）做云端存储，
 * 数据按 {base}/ShangYinSync/backup.json 存放，账号天然隔离。
 */
object CloudSyncClient {

    private const val SYNC_DIR = "ShangYinSync"
    private const val BACKUP_FILE = "backup.json"
    private const val TIMEOUT = 30L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
        .build()

    /** 同步结果 */
    sealed class SyncResult {
        data object Success : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    /** 拼接备份文件完整 URL（自动补末尾斜杠） */
    private fun backupUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base/$SYNC_DIR/$BACKUP_FILE"
    }

    private fun dirUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return "$base/$SYNC_DIR"
    }

    private fun authHeader(user: String, pass: String): String {
        val cred = "$user:$pass"
        return "Basic " + Base64.encodeToString(cred.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** 把 HTTP 错误码/异常翻译成用户能看懂的话 */
    private fun friendlyError(code: Int, action: String): String = when (code) {
        401 -> "账号或应用密码错误"
        403 -> "没有 WebDAV 权限（坚果云需在账户信息→安全选项里添加应用密码）"
        404 -> "云端还没有备份文件"
        else -> "${action}失败（HTTP $code）"
    }

    /** 测试连接：PROPFIND 基础目录，207 = 成功 */
    fun testConnection(url: String, user: String, pass: String): SyncResult {
        val base = url.trimEnd('/')
        val req = Request.Builder()
            .url("$base/")
            .header("Authorization", authHeader(user, pass))
            .header("Depth", "0")
            .method("PROPFIND", ByteArray(0).toRequestBody("application/xml".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful || resp.code == 207 -> SyncResult.Success
                    else -> SyncResult.Error(friendlyError(resp.code, "连接"))
                }
            }
        } catch (e: Exception) {
            SyncResult.Error("连接失败：${e.message ?: "网络错误"}")
        }
    }

    /** 上传备份 JSON（自动创建同步目录，覆盖云端旧备份） */
    fun upload(url: String, user: String, pass: String, json: String): SyncResult {
        val auth = authHeader(user, pass)
        // 1. 确保目录存在（已存在返回 405/301，视为成功）
        val mkcol = Request.Builder()
            .url(dirUrl(url))
            .header("Authorization", auth)
            .method("MKCOL", ByteArray(0).toRequestBody(null))
            .build()
        try {
            client.newCall(mkcol).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 405 && resp.code != 301 && resp.code != 409) {
                    return SyncResult.Error(friendlyError(resp.code, "创建云端目录"))
                }
            }
        } catch (e: Exception) {
            return SyncResult.Error("创建云端目录失败：${e.message ?: "网络错误"}")
        }

        // 2. PUT 备份文件
        val put = Request.Builder()
            .url(backupUrl(url))
            .header("Authorization", auth)
            .put(json.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(put).execute().use { resp ->
                if (resp.isSuccessful) SyncResult.Success
                else SyncResult.Error(friendlyError(resp.code, "上传"))
            }
        } catch (e: Exception) {
            SyncResult.Error("上传失败：${e.message ?: "网络错误"}")
        }
    }

    /** 下载云端备份；neverBackedUp = 云端 404（从未备份） */
    fun download(url: String, user: String, pass: String): Result<String> {
        val req = Request.Builder()
            .url(backupUrl(url))
            .header("Authorization", authHeader(user, pass))
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> Result.failure(Exception("云端还没有备份，请先在其他设备上传"))
                    resp.isSuccessful -> Result.success(resp.body?.string().orEmpty())
                    else -> Result.failure(Exception(friendlyError(resp.code, "下载")))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("下载失败：${e.message ?: "网络错误"}"))
        }
    }
}
