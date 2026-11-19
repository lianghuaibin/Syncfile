package com.syncfile.app

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import android.widget.LinearLayout

/**
 * 单屏界面：选择文件夹 -> 填服务器地址 -> 开始/停止同步，实时显示进度和日志。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var folderLabel: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvLog: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnSync: Button
    private lateinit var etServer: EditText
    private lateinit var etToken: EditText
    private lateinit var etTarget: EditText

    private var treeUri: Uri? = null

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        folderLabel = findViewById(R.id.folderLabel)
        tvProgress = findViewById(R.id.tvProgress)
        tvLog = findViewById(R.id.tvLog)
        progressBar = findViewById(R.id.progressBar)
        btnSync = findViewById(R.id.btnSync)
        etServer = findViewById(R.id.etServer)
        etToken = findViewById(R.id.etToken)
        etTarget = findViewById(R.id.etTarget)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString(KEY_URI, null)?.let {
            val uri = Uri.parse(it)
            if (checkUriPermission(uri)) {
                treeUri = uri
                folderLabel.text = DocumentFile.fromTreeUri(this, uri)?.name ?: it
            }
        }
        etServer.setText(prefs.getString(KEY_SERVER, ""))
        etToken.setText(prefs.getString(KEY_TOKEN, ""))
        // 目标目录默认为手机型号（如 "K70"），用户改过则用改过的
        etTarget.setText(prefs.getString(KEY_TARGET, null) ?: defaultTarget())

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickFolder.launch(null) }
        btnSync.setOnClickListener { onSyncClicked() }

        renderHistory()
        observeState()
    }

    private fun onFolderPicked(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        treeUri = uri
        folderLabel.text = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URI, uri.toString()).apply()
        renderHistory()
    }

    private fun onSyncClicked() {
        if (SyncState.running.value) {
            UploadService.stop(this)
            return
        }
        val server = etServer.text.toString().trim()
        if (server.isEmpty()) {
            Toast.makeText(this, "请先填写服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            Toast.makeText(this, "服务器地址需以 http:// 开头", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = treeUri ?: run {
            Toast.makeText(this, "请先选择要同步的文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestIgnoreBatteryOptimization()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_TOKEN, etToken.text.toString().trim())
            .putString(KEY_TARGET, etTarget.text.toString().trim())
            .apply()
        saveHistory(uri)
        UploadService.start(this, uri, server, etToken.text.toString().trim(), etTarget.text.toString().trim())
    }

    private fun observeState() {
        lifecycleScope.launch {
            SyncState.progress.collectLatest { p ->
                if (p == null) {
                    tvProgress.text = ""
                    progressBar.progress = 0
                } else {
                    tvProgress.text =
                        "${p.done}/${p.total}  跳过 ${p.skipped}  失败 ${p.failed}\n${p.currentFile}"
                    progressBar.progress = if (p.total == 0) 0 else p.done * 100 / p.total
                }
            }
        }
        lifecycleScope.launch {
            // 日志最多 400 行，且限频渲染（每 300ms 最多刷一次），上万文件也不卡 UI
            var lastRender = 0L
            SyncState.logs.collect { lines ->
                val now = System.currentTimeMillis()
                if (now - lastRender > 300) {
                    lastRender = now
                    tvLog.text = lines.joinToString("\n")
                }
            }
        }
        lifecycleScope.launch {
            SyncState.running.collectLatest { running ->
                btnSync.text = getString(if (running) R.string.stop_sync else R.string.start_sync)
            }
        }
    }

    private fun checkUriPermission(uri: Uri): Boolean = try {
        contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    } catch (e: Exception) {
        false
    }

    /** 手机型号名（如 "K70"），清理掉不适合做目录名的字符 */
    private fun defaultTarget(): String =
        Build.MODEL.trim().replace(Regex("[/\\\\\\u0000]"), "-").ifEmpty { "Android" }

    /** 申请忽略电池优化，避免息屏后 Doze 模式掐断网络导致上传中断 */
    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // 部分定制 ROM 不支持该入口，忽略即可（用户可手动去设置里关）
        }
    }

    // ---------- 同步历史 ----------

    private fun historyList(): MutableList<String> {
        val arr = JSONArray(
            getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HISTORY, "[]") ?: "[]"
        )
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.optString(i))
        return list
    }

    /** 记录本次上传的文件夹，最近 5 个，去重后置顶 */
    private fun saveHistory(uri: Uri) {
        val list = historyList()
        list.remove(uri.toString())
        list.add(0, uri.toString())
        while (list.size > 5) list.removeAt(list.size - 1)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_HISTORY, JSONArray(list).toString())
            .apply()
    }

    /** 展示历史文件夹，点按一条 = 选中它并立即开始上传 */
    private fun renderHistory() {
        val container = findViewById<LinearLayout>(R.id.historyContainer)
        container.removeAllViews()
        val entries = historyList().mapNotNull { s ->
            val uri = Uri.parse(s)
            if (!checkUriPermission(uri)) return@mapNotNull null // 授权已失效的不再显示
            uri to (DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString())
        }
        findViewById<TextView>(R.id.historyTitle).visibility =
            if (entries.isEmpty()) TextView.GONE else TextView.VISIBLE
        for ((uri, name) in entries) {
            val tv = TextView(this).apply {
                text = "📁 $name"
                textSize = 15f
                setPadding(24, 20, 24, 20)
                setBackgroundResource(R.drawable.log_background)
                setOnClickListener {
                    selectFolder(uri)   // 选中该历史文件夹
                    onSyncClicked()     // 用当前服务器/令牌/目标设置直接开始上传
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            container.addView(tv, lp)
        }
    }

    /** 选中一个文件夹作为当前同步目标（历史记录点按时调用） */
    private fun selectFolder(uri: Uri) {
        treeUri = uri
        folderLabel.text = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URI, uri.toString()).apply()
    }

    companion object {
        private const val PREFS = "syncfile"
        private const val KEY_URI = "tree_uri"
        private const val KEY_SERVER = "server"
        private const val KEY_TOKEN = "token"
        private const val KEY_TARGET = "target"
        private const val KEY_HISTORY = "history"
    }
}
