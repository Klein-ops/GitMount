package com.kleinops.gitmount.service.http

import com.kleinops.gitmount.service.ServerController
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
import java.util.concurrent.atomic.AtomicBoolean

/** 请求体超过上限（在读取 body 前抛出，由服务器层转 413） */
class RequestTooLargeException(val limit: Long) : IOException("请求体超过 ${limit / 1024 / 1024}MB 上限")

/** 极简 HTTP/1.1 服务器：单线程池 + 逐请求分发，专为 DAV 客户端（文件管理器）设计 */
class HttpServer(
    private val port: Int,
    private val handler: (HttpExchange) -> Unit,
    /** 绑定地址；null/0.0.0.0 = 监听所有网卡（局域网），127.0.0.1 = 仅本机 */
    private val bindAddress: String? = null,
    /** 请求体上限：超过时在读 body 之前直接回 413，避免超大上传白白占用内存/带宽
     *  与 GitRepository.MAX_FILE_SIZE（40MB 本地拦截线）保持一致 */
    private val maxBodyBytes: Long = 40L * 1000 * 1000,
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
    /**
     * 工作线程池：newCachedThreadPool 默认空闲 60s 才回收线程，
     * 而每个线程有独立的栈（默认 1MB 虚拟内存 + 实际栈页），
     * 空闲时段这些线程只是白占内存。这里把 keepAlive 压到 8s，
     * 服务空闲几秒后线程自动退场，内存回落到基线。
     */
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        0, Int.MAX_VALUE, 8L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
    ) { r -> Thread(r, "dav-http").apply { isDaemon = true } }

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
        ServerController.sessionOpened()
        try {
            socket.soTimeout = 60_000
            BufferedInputStream(socket.getInputStream()).use { input ->
                BufferedOutputStream(socket.getOutputStream()).use { output ->
                    var keepAlive = true
                    while (keepAlive && running.get() && !socket.isClosed) {
                        val exchange = try {
                            parseRequest(input)
                        } catch (e: RequestTooLargeException) {
                            // 超大上传：回 413 再断开，让客户端立刻知道被拒绝
                            try {
                                val err = HttpExchange("GET", "/", emptyMap(), ByteArray(0), false)
                                err.status = 413
                                err.bodyBytes = ("请求体超过 ${e.limit / 1024 / 1024}MB 上限").toByteArray()
                                err.writeTo(output)
                                output.flush()
                            } catch (_: IOException) {
                            }
                            break
                        } catch (e: IOException) {
                            break
                        } ?: break
                        keepAlive = exchange.keepAlive
                        try {
                            handler(exchange)
                            exchange.writeTo(output)
                            // 流量打点：请求体=上传，响应体=下载（含目录列表/元数据等）
                            ServerController.addTraffic(exchange.body.size.toLong(), exchange.bodyBytes.size.toLong())
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
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            ServerController.sessionClosed()
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

        val cl = headers["content-length"]?.toLongOrNull() ?: 0L
        // 关键：在【读取 body 之前】就拦截超大上传。
        // 超大文件本来就会被 GitRepository.MAX_FILE_SIZE 拒绝，提前在这里挡掉，
        // 可以完全避免把百 MB 的字节读进内存、也绝不触发 GitHub API 调用。
        if (cl > maxBodyBytes) {
            throw RequestTooLargeException(maxBodyBytes)
        }
        val body = if (cl > 0) {
            // 已通过上限检查（cl <= 100MB < Int.MAX_VALUE），toInt() 安全
            // 直接按 Content-Length 预分配精确数组并读满，避免 ByteArrayOutputStream
            // 的 toByteArray() 再复制一份（78MB 上传峰值 = 2×78MB，默认堆必炸）。
            val bytes = ByteArray(cl.toInt())
            var offset = 0
            var remaining = cl
            while (remaining > 0) {
                val n = input.read(bytes, offset, minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
                if (n < 0) throw IOException("eof in body")
                offset += n
                remaining -= n
            }
            bytes
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