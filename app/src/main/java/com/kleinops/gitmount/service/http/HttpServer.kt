package com.kleinops.gitmount.service.http

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 极简 HTTP/1.1 服务器：单线程池 + 逐请求分发，专为 DAV 客户端（文件管理器）设计 */
class HttpServer(
    private val port: Int,
    private val handler: (HttpExchange) -> Unit,
    /** 绑定地址；null/0.0.0.0 = 监听所有网卡（局域网），127.0.0.1 = 仅本机 */
    private val bindAddress: String? = null,
) {
    private val serverSocket = if (bindAddress == null) {
        ServerSocket(port)
    } else {
        ServerSocket().apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(bindAddress, port))
        }
    }
    private val running = AtomicBoolean(true)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "dav-http").apply { isDaemon = true }
    }

    val boundPort: Int get() = serverSocket.localPort

    fun start() {
        Thread {
            while (running.get()) {
                try {
                    val socket = serverSocket.accept()
                    pool.execute { handle(socket) }
                } catch (e: IOException) {
                    if (running.get()) e.printStackTrace()
                }
            }
        }.apply { name = "dav-accept"; isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket.close()
        } catch (_: IOException) {
        }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 120_000
        BufferedInputStream(socket.getInputStream()).use { input ->
            BufferedOutputStream(socket.getOutputStream()).use { output ->
                var keepAlive = true
                while (keepAlive && running.get() && !socket.isClosed) {
                    val exchange = try {
                        parseRequest(input)
                    } catch (e: IOException) {
                        break
                    } ?: break
                    keepAlive = exchange.keepAlive
                    try {
                        handler(exchange)
                        exchange.writeTo(output)
                    } catch (e: Exception) {
                        // 处理器抛异常时回 500
                        try {
                            val err = HttpExchange(exchange.method, exchange.path, emptyMap(), ByteArray(0), false)
                            err.status = 500
                            err.bodyBytes = (e.message ?: "Internal Server Error").toByteArray()
                            err.writeTo(output)
                        } catch (_: IOException) {
                        }
                    }
                    output.flush()
                    if (!exchange.keepAlive) break
                }
            }
        }
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    /** 解析请求头；请求行/头部解析失败抛 IOException */
    private fun parseRequest(input: InputStream): HttpExchange? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) throw IOException("bad request line")
        val method = parts[0].uppercase(Locale.US)
        val rawPath = parts[1]

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("eof in headers")
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
            }
        }

        val cl = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (cl > 0) {
            val bytes = ByteArrayOutputStream(cl)
            val buf = ByteArray(8192)
            var remaining = cl
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n < 0) throw IOException("eof in body")
                bytes.write(buf, 0, n)
                remaining -= n
            }
            bytes.toByteArray()
        } else {
            ByteArray(0)
        }

        val decodedPath = try {
            // 注意：不能用 URLDecoder.decode——它会把路径里的 '+' 变成空格，
            // 导致名为 "a+b.txt" 的文件无法访问。这里只还原 %XX 转义。
            percentDecode(rawPath.split("?")[0])
        } catch (e: Exception) {
            rawPath
        }
        val keepAlive = headers["connection"]?.equals("keep-alive", true) != false
        val chunked = headers["transfer-encoding"]?.contains("chunked", true) == true

        return HttpExchange(method, decodedPath, headers, body, keepAlive, chunked)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c == -1) return null
        while (c != -1) {
            // read() 返回 Int，必须与 Char 的码值比较
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }
}

/**
 * 只还原 %XX 转义，保留 '+' 原义。
 * URLDecoder 会把 '+' 解成空格，对文件路径而言是错误行为。
 */
internal fun percentDecode(input: String): String {
    if (!input.contains('%')) return input
    val out = ByteArrayOutputStream(input.length)
    var i = 0
    while (i < input.length) {
        val c = input[i]
        if (c == '%' && i + 2 < input.length) {
            val hex = input.substring(i + 1, i + 3)
            val value = hex.toIntOrNull(16)
            if (value != null) {
                out.write(value)
                i += 3
                continue
            }
        }
        out.write(c.toString().toByteArray(StandardCharsets.UTF_8))
        i++
    }
    return String(out.toByteArray(), StandardCharsets.UTF_8)
}

/** 一次 HTTP 交换：请求字段 + 响应字段 */
class HttpExchange(
    val method: String,
    val path: String, // 已 URL 解码的路径
    val headers: Map<String, String>,
    val body: ByteArray,
    val keepAlive: Boolean,
    /** 请求体使用 Transfer-Encoding: chunked；本服务器不解析分块体，需由上层拒绝 */
    val chunked: Boolean = false,
) {
    var status: Int = 200
    var responseHeaders: MutableMap<String, String> = mutableMapOf()
    var bodyBytes: ByteArray = ByteArray(0)

    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    fun respond(status: Int, contentType: String? = null, body: ByteArray = ByteArray(0), extra: Map<String, String> = emptyMap()) {
        this.status = status
        if (contentType != null) responseHeaders["Content-Type"] = contentType
        responseHeaders.putAll(extra)
        bodyBytes = body
    }

    /** 将响应写到输出流 */
    fun writeTo(out: OutputStream) {
        val reason = reasonPhrase(status)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        responseHeaders.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        if (responseHeaders["Content-Length"] == null) {
            head.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        }
        if (responseHeaders["Connection"] == null) {
            head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
    }

    companion object {
        fun reasonPhrase(code: Int): String = when (code) {
            100 -> "Continue"; 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
            207 -> "Multi-Status"; 301 -> "Moved Permanently"; 304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            411 -> "Length Required"; 412 -> "Precondition Failed"
            413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"
            423 -> "Locked"; 500 -> "Internal Server Error"; 501 -> "Not Implemented"
            503 -> "Service Unavailable"; else -> "Unknown"
        }
    }
}