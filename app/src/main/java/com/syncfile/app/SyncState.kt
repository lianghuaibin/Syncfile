package com.syncfile.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上传进度与日志的共享状态。UploadService 写入，MainActivity 订阅展示。
 */
object SyncState {

    data class Progress(
        val done: Int,
        val total: Int,
        val currentFile: String,
        val skipped: Int,
        val failed: Int,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    val running = MutableStateFlow(false)

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(text: String) {
        val line = "[${timeFmt.format(Date())}] $text"
        synchronized(this) {
            val list = _logs.value.toMutableList()
            list.add(line)
            if (list.size > 400) list.subList(0, list.size - 400).clear()
            _logs.value = list
        }
    }

    fun update(done: Int, total: Int, currentFile: String, skipped: Int, failed: Int) {
        _progress.value = Progress(done, total, currentFile, skipped, failed)
    }

    fun start() {
        _logs.value = emptyList()
        _progress.value = null
        running.value = true
    }

    fun stop() {
        running.value = false
    }
}
