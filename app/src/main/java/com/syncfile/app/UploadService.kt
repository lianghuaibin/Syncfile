package com.syncfile.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 前台服务：遍历所选文件夹（含子目录）并增量上传到服务器。
 * 增量规则：服务器上存在同名相对路径、且大小与修改时间一致的文件直接跳过。
 */
class UploadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val cancelled = AtomicBoolean(false)

    private lateinit var treeUri: Uri
    private lateinit var client: UploadClient
    /** 服务器上的目标子目录，空字符串表示保存到服务器根目录 */
    private lateinit var targetDir: String

    private val powerManager: PowerManager?
        get() = getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled.set(true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY // 已在同步中

        val uriString = intent?.getStringExtra(EXTRA_URI)
        val server = intent?.getStringExtra(EXTRA_SERVER) ?: return START_NOT_STICKY
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        targetDir = normalizeTarget(intent?.getStringExtra(EXTRA_TARGET))
        if (uriString == null) return START_NOT_STICKY

        treeUri = Uri.parse(uriString)
        client = UploadClient(server, token)

        startForegroundNow("准备中…")
        SyncState.start()
        cancelled.set(false)
        job = scope.launch { runSync() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runSync() {
        try {
            val root = DocumentFile.fromTreeUri(this, treeUri)
            if (root == null || !root.canRead()) {
                SyncState.log("错误：无法读取所选文件夹，请重新选择")
                finish("失败")
                return
            }

            SyncState.log("正在扫描文件列表…")
            val files = ArrayList<Pair<DocumentFile, String>>() // (文件, 相对路径)
            collectFiles(root, "", files)
            if (files.isEmpty()) {
                SyncState.log("所选文件夹为空，无事可做")
                finish("完成")
                return
            }
            // 保留所选文件夹名本身：Syncfile/<目标目录>/<所选文件夹名>/<相对路径>
            val rootName = (root.name ?: "folder").trim().ifEmpty { "folder" }
            val prefix = if (targetDir.isEmpty()) rootName else "$targetDir/$rootName"
            SyncState.log("共 ${files.size} 个文件，上传到 $prefix/，开始上传")

            val skipped = AtomicInteger()
            val failed = AtomicInteger()
            val completed = AtomicInteger()
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "syncfile:upload"
            )?.apply { acquire(60 * 60 * 1000L) }
            try {
                // 并发上传：同时传 CONCURRENCY 个文件，其余在信号量上排队
                coroutineScope {
                    val semaphore = Semaphore(CONCURRENCY)
                    for ((file, relPath) in files) {
                        launch {
                            semaphore.withPermit {
                                if (cancelled.get()) return@withPermit
                                val remotePath = "$prefix/$relPath"
                                SyncState.update(
                                    completed.get(), files.size, remotePath,
                                    skipped.get(), failed.get()
                                )
                                try {
                                    if (uploadOne(file, remotePath)) skipped.incrementAndGet()
                                } catch (e: Exception) {
                                    failed.incrementAndGet()
                                    SyncState.log("失败: $remotePath (${e.message})")
                                }
                                val done = completed.incrementAndGet()
                                if (done % 10 == 0 || done == files.size) {
                                    updateNotification("$done/${files.size}  $remotePath")
                                    SyncState.update(
                                        done, files.size, remotePath,
                                        skipped.get(), failed.get()
                                    )
                                }
                            }
                        }
                    }
                }
            } finally {
                wakeLock?.takeIf { it.isHeld }?.release()
            }

            val msg = if (cancelled.get()) "已取消" else "完成"
            SyncState.log(
                "同步$msg：上传 ${files.size - skipped.get() - failed.get()} 个，" +
                    "跳过 ${skipped.get()} 个，失败 ${failed.get()} 个"
            )
            finish(msg)
        } catch (e: Exception) {
            SyncState.log("错误：${e.message}")
            finish("失败")
        }
    }

    /** 清洗目标目录：去掉首尾斜杠、压缩重复斜杠、拒绝 ".."，多级目录用 / 分隔 */
    private fun normalizeTarget(raw: String?): String {
        val cleaned = (raw ?: "").trim()
            .replace("\\", "/")
            .split("/")
            .filter { it.isNotEmpty() && it != "." }
        if (cleaned.any { it == ".." }) return ""
        return cleaned.joinToString("/")
    }

    /** @return true 表示服务器已存在而跳过 */
    private suspend fun uploadOne(file: DocumentFile, remotePath: String): Boolean {
        val size = file.length()
        val mtime = file.lastModified()
        for (attempt in 1..MAX_RETRIES) {
            try {
                val remote = client.check(remotePath, size, mtime)
                // 已存在且大小、修改时间一致（±2 秒容差）才跳过；内容有变化则重新上传
                val sameMtime = mtime <= 0 || remote.mtime <= 0 ||
                    Math.abs(remote.mtime - mtime) < 2000
                if (remote.exists && remote.size == size && sameMtime) {
                    SyncState.log("跳过: $remotePath")
                    return true
                }
                if (remote.exists) SyncState.log("有变化，重新上传: $remotePath")
                val stream = contentResolver.openInputStream(file.uri)
                    ?: throw IOException("无法打开文件")
                stream.use { client.upload(remotePath, size, mtime, it) }
                SyncState.log("已上传: $remotePath")
                return false
            } catch (e: Exception) {
                if (attempt == MAX_RETRIES) throw e
                SyncState.log("重试 $attempt/$MAX_RETRIES: $remotePath (${e.message})")
                delay(attempt * 2000L)
                if (cancelled.get()) throw IOException("已取消")
            }
        }
        return false
    }

    private fun collectFiles(dir: DocumentFile, prefix: String, out: MutableList<Pair<DocumentFile, String>>) {
        for (child in dir.listFiles()) {
            if (cancelled.get()) return
            val name = child.name ?: continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                collectFiles(child, path, out)
            } else {
                out.add(child to path)
            }
        }
    }

    private suspend fun finish(text: String) = withContext(Dispatchers.Main) {
        SyncState.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification("同步$text"))
        stopSelf()
    }

    private fun startForegroundNow(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "文件同步", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val p = SyncState.progress.value
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Syncfile 正在上传")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(SyncState.running.value)
        if (p != null && p.total > 0) {
            builder.setProgress(p.total, p.done, false)
        }
        return builder.build()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_SERVER = "server"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_TARGET = "target"
        const val ACTION_STOP = "stop"

        private const val CHANNEL_ID = "sync"
        private const val NOTIFY_ID = 1
        private const val MAX_RETRIES = 3

        /** 并发上传的文件数 */
        private const val CONCURRENCY = 4

        fun start(context: Context, uri: Uri, server: String, token: String?, target: String? = null) {
            val intent = Intent(context, UploadService::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_SERVER, server)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_TARGET, target)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, UploadService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
