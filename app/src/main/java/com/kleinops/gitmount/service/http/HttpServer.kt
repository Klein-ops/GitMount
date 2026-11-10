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
    /** 请求体上限：超过时在读 body 之前直接回 413，避免超大上传把整个 body 读进内存导致 OOM。
     *  与 GitRepository.MAX_FILE_SIZE（已取消 40MB 拦截，仅保留 100MB GitHub 上限兜底）保持一致 */
    private val maxBodyBytes: Long = 100L * 1000 * 1000,
) {
    private val serverSocket = ServerSocket().apply {
        // 必须显式 reuseAddress：增量同步会"停旧监听→立即用同端口重新 bind"，
        // 未设置时刚 close 的 listener 端口可能处于 TIME_WAIT 导致 Address already in use。
        // （FtpServer 一直有 reuseAddress=true，这里补齐保持一致）
        reuseAddress = true
        if (bindAddress == null) {
            bind(java.net.InetSocketAddress(port))
        } else {
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
                            // 先回响应：客户端（尤其失败路径）收到响应后即停止发送，
                            // 再清理剩余请求体；keep-alive 下残留 body 会污染下一个请求的解析。
                            exchange.writeTo(output)
                            val drained = exchange.drainBody()
                            // 流量打点：请求体=上传（流式上传用实际写入字节数），响应体=下载
                            ServerController.addTraffic(exchange.requestBytes, exchange.bodyBytes.size.toLong())
                            if (!drained) break
                        } catch (e: Exception) {
                            // 处理器抛异常时回 500
                            try {
                                val err = HttpExchange(exchange.method, exchange.path, emptyMap(), ByteArray(0), false)
                                err.status = 500
                                err.bodyBytes = (e.message ?: "Internal Server Error").toByteArray()
                                err.writeTo(output)
                            } catch (_: IOException) {
                            }
                            // 异常时剩余请求体可能未消费，连接状态不可靠，断开避免污染下一个请求
                            break
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
        // 超大上传（> GitHub 兜底上限 100MB）：直接 413，不读 body、响应后断开连接。
        if (cl > maxBodyBytes) {
            throw RequestTooLargeException(maxBodyBytes)
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

        // 小请求体（≤1MB，如 MKCOL/PROPFIND/MOVE 的少量 XML）直接读入缓存，
        // 方便大多数 handler 无脑用 exchange.body；大上传（PUT）由 handler 走
        // exchange.bodyStream 流式消费（writeStream），绝不把百 MB 请求体整读进内存。
        return if (cl in 1..(1024 * 1024)) {
            val bytes = ByteArray(cl.toInt())
            var offset = 0
            while (offset < cl) {
                val n = input.read(bytes, offset, (cl - offset).toInt())
                if (n < 0) throw IOException("eof in body")
                offset += n
            }
            HttpExchange(method, decodedPath, headers, bytes, keepAlive, chunked,
                contentLength = cl, bodyStream = null)
        } else {
            HttpExchange(method, decodedPath, headers, ByteArray(0), keepAlive, chunked,
                contentLength = if (cl > 0) cl else 0L,
                bodyStream = if (cl > 0) HttpBodyStream(LimitedInputStream(input, cl)) else null)
        }
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
    /** 请求体总长度（Content-Length）；无 body 时为 0 */
    val contentLength: Long = 0L,
    /**
     * 大请求体的原始流（≤1MB 的小请求体已被读入 [body]，此字段为 null）。
     *
     * 供 handler 做流式消费（WebDAV PUT → repo.writeStream），
     * 避免把百 MB 请求体整读进内存导致 OOM。
     * 该流已包上 [HttpBodyStream]：SocketTimeoutException 会被转成 IOException
     * （HTTP 语义下“客户端暂停”不应被当作“发完了”），并限定最多读到 contentLength 字节，
     * keep-alive 下不会越界吃掉下一个请求的字节。
     */
    val bodyStream: InputStream? = null,
) {
    var status: Int = 200
    var responseHeaders: MutableMap<String, String> = mutableMapOf()
    var bodyBytes: ByteArray = ByteArray(0)

    /** 实际上传字节数（流量统计用）：大文件流式上传时由 handler 写入实际值 */
    var requestBytes: Long = body.size.toLong()

    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    fun respond(status: Int, contentType: String? = null, body: ByteArray = ByteArray(0), extra: Map<String, String> = emptyMap()) {
        this.status = status
        if (contentType != null) responseHeaders["Content-Type"] = contentType
        responseHeaders.putAll(extra)
        bodyBytes = body
    }

    /** 丢弃请求体剩余字节（keep-alive 必须清空，否则残留 body 会污染下一个请求的解析）。
     *  返回是否可继续复用连接：读不完（连接已坏）返回 false，由外层断开。 */
    fun drainBody(): Boolean {
        val s = bodyStream ?: return true
        return try {
            val buf = ByteArray(8192)
            while (s.read(buf) >= 0) { /* 丢弃 */ }
            true
        } catch (_: IOException) {
            false
        }
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

/** 限定最多读取 limit 字节，之后返回 EOF；防止 keep-alive 下越界读走下一个请求的字节。 */
private class LimitedInputStream(private val underlying: InputStream, private val limit: Long) : InputStream() {
    private var remaining = limit

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = underlying.read()
        if (b < 0) return -1
        remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val want = minOf(len.toLong(), remaining).toInt()
        val n = underlying.read(b, off, want)
        if (n < 0) return -1
        remaining -= n
        return n
    }

    override fun close() = underlying.close()
}

/**
 * 把 SocketTimeoutException 转成普通 IOException。
 * 流式上传（StreamingBase64RequestBody）会把 SocketTimeoutException 当作
 * “客户端发完数据但未关闭连接”（FTP 语义）；对 HTTP 而言这是错误的——
 * HTTP 客户端发完 body 必定等待响应，长时间无数据只可能是暂停/断连，
 * 应作为失败处理（断开/500），绝不能静默截断文件。
 */
private class HttpBodyStream(stream: InputStream) : java.io.FilterInputStream(stream) {
    override fun read(): Int = try {
        super.read()
    } catch (e: java.net.SocketTimeoutException) {
        throw IOException("请求体读取超时：${e.message}")
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int = try {
        super.read(b, off, len)
    } catch (e: java.net.SocketTimeoutException) {
        throw IOException("请求体读取超时：${e.message}")
    }
}