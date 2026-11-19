package com.syncfile.app

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 与接收服务器通信的 HTTP 客户端。
 *
 * 协议（与 server.py 对应）：
 *   POST /check   {"path","size","mtime"} -> {"exists":bool,"size":long,"mtime":long}
 *   POST /upload?path=..&mtime=..         body=原始文件字节 -> {"ok":true}
 * 令牌通过请求头 X-Token 携带（服务器未设置令牌时忽略）。
 */
class UploadClient(baseUrlIn: String, private val token: String?) {

    private val baseUrl: String = baseUrlIn.trim().trimEnd('/')

    class CheckResult(val exists: Boolean, val size: Long, val mtime: Long)

    private fun open(path: String): HttpURLConnection {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        if (!token.isNullOrBlank()) conn.setRequestProperty("X-Token", token)
        return conn
    }

    /** 查询服务器上是否已存在该文件（大小、修改时间一致视为已存在） */
    @Throws(IOException::class)
    fun check(relPath: String, size: Long, mtime: Long): CheckResult {
        val body = JSONObject().put("path", relPath).put("size", size).put("mtime", mtime)
        val conn = open("/check")
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(body.toString().toByteArray(Charsets.UTF_8).size)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw IOException("check 失败: HTTP $code ${readError(conn)}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            return CheckResult(obj.optBoolean("exists"), obj.optLong("size"), obj.optLong("mtime"))
        } finally {
            conn.disconnect()
        }
    }

    /** 流式上传文件内容（不在内存中缓存整个文件） */
    @Throws(IOException::class)
    fun upload(relPath: String, size: Long, mtime: Long, input: InputStream) {
        val enc = URLEncoder.encode(relPath, "UTF-8")
        val conn = open("/upload?path=$enc&mtime=$mtime")
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(size)
            input.copyTo(conn.outputStream, DEFAULT_BUFFER_SIZE * 16)
            conn.outputStream.flush()
            val code = conn.responseCode
            if (code != 200) throw IOException("upload 失败: HTTP $code ${readError(conn)}")
        } finally {
            conn.disconnect()
        }
    }

    private fun readError(conn: HttpURLConnection): String = try {
        conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) ?: ""
    } catch (e: Exception) {
        ""
    }

    companion object {
        private const val TAG = "UploadClient"
    }
}
