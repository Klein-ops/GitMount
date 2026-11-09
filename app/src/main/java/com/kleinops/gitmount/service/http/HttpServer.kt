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
     *  与 GitRepository.MAX_LFS_SIZE（2GB 大文件通道上限）保持一致 */
    private val maxBodyBytes: Long = 2L * 1000 * 1000 * 1000,
    /** 大请求体落盘目录（WebDAV PUT 大文件时把 body 写临时文件而非读入内存，防 OOM）。
     *  为 null 时超过 bodySpoolThreshold 的请求体直接回 413。由 MountService 传入应用 cacheDir。 */
    private val spoolDir: java.io.File? = null,
    /** 请求体超过该字节数改落盘（避免大上传 OOM）；未配置 spoolDir 时此阈值无效 */
    private val bodySpoolThreshold: Long = 5L * 1024 * 1024,
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
                            exchange.writeTo(output)
                            // 流量打点：请求体=上传，响应体=下载（含目录列表/元数据等）。
                            // 大文件落盘上传用 contentLength（body 恒空）；流式响应用 Content-Length。
                            val reqLen = if (exchange.bodyFile != null) exchange.contentLength
                            else exchange.body.size.toLong()
                            val respLen = exchange.responseHeaders["Content-Length"]
                                ?.toLongOrNull()
                                ?: exchange.bodyBytes.size.toLong()
                            ServerController.addTraffic(reqLen, respLen)
                        } catch (e: Exception) {
                            // 处理器抛异常时回 500
                            try {
                                val err = HttpExchange(exchange.method, exchange.path, emptyMap(), ByteArray(0), false)
                                err.status = 500
                                err.bodyBytes = (e.message ?: "Internal Server Error").toByteArray()
                                err.writeTo(output)
                            } catch (_: IOException) {
                            }
                        } finally {
                            // 大请求体落盘的临时文件用完即删（无论成功失败）
                            exchange.bodyFile?.let { f ->
                                runCatching { f.delete() }
                                exchange.bodyFile = null
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
        // 超过 2GB LFS 上限直接 413（绝不可能上传成功，提前挡掉）。
        if (cl > maxBodyBytes) {
            throw RequestTooLargeException(maxBodyBytes)
        }

        // 请求体两种承载方式：
        // 1. 小 body（≤ bodySpoolThreshold 或未配置 spoolDir）：读入内存（保持既有行为，≤5MB 峰值可控）；
        // 2. 大 body（> bodySpoolThreshold 且有 spoolDir）：流式落盘临时文件，Handler 用
        //    FileInputStream 读取再直传 GitHub。彻底避免 WebDAV PUT 大文件把几百 MB 读进内存 OOM。
        val bodyFile: java.io.File?
        val bodyBytes: ByteArray
        if (cl > bodySpoolThreshold && spoolDir != null) {
            if (!spoolDir.exists()) spoolDir.mkdirs()
            val tmp = java.io.File.createTempFile("gm-body-", ".tmp", spoolDir)
            try {
                java.io.FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var remaining = cl
                    while (remaining > 0) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) throw IOException("eof in body")
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                }
                bodyFile = tmp
                bodyBytes = ByteArray(0)
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        } else {
            // 没有可用的落盘目录：超过内存承载阈值（bodySpoolThreshold）的直接 413，
            // 避免 ByteArray(cl.toInt()) 在 cl > Int.MAX_VALUE 时溢出成负数崩溃（NegativeArraySizeException）。
            if (cl > bodySpoolThreshold) {
                throw RequestTooLargeException(bodySpoolThreshold)
            }
            bodyFile = null
            bodyBytes = if (cl > 0) {
                // 已通过上限检查，cl <= maxBodyBytes；但 cl 可能 > Int.MAX_VALUE，需分段读。
                // 落盘阈值已挡住大 body，这里 cl ≤ bodySpoolThreshold ≤ Int 范围，toInt() 安全。
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

        return HttpExchange(method, decodedPath, headers, bodyBytes, keepAlive, chunked, bodyFile, cl)
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
    /**
     * 大请求体落盘的临时文件（WebDAV PUT 大文件时使用；由 HttpServer 在请求处理后删除）。
     * 非空时代替 body 承载请求体内容，Handler 用 FileInputStream 流式读取。
     */
    var bodyFile: java.io.File? = null,
    /** 请求体实际字节数（body 或 bodyFile 的内容总长） */
    var contentLength: Long = 0L,
) {
    var status: Int = 200
    var responseHeaders: MutableMap<String, String> = mutableMapOf()
    var bodyBytes: ByteArray = ByteArray(0)

    /**
     * 流式响应体（供大文件 GET 使用）：非空时优先于 bodyBytes 写出。
     * 由 HttpServer 在写出后负责关闭；Handler 不要再手动关闭。
     */
    var responseStream: java.io.InputStream? = null

    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    fun respond(status: Int, contentType: String? = null, body: ByteArray = ByteArray(0), extra: Map<String, String> = emptyMap()) {
        this.status = status
        if (contentType != null) responseHeaders["Content-Type"] = contentType
        responseHeaders.putAll(extra)
        bodyBytes = body
    }

    /** 将响应写到输出流（支持 bodyBytes 与 responseStream 两种模式） */
    fun writeTo(out: OutputStream) {
        val reason = reasonPhrase(status)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        responseHeaders.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        if (responseHeaders["Content-Length"] == null) {
            val len = responseStream?.available()?.toLong() ?: bodyBytes.size.toLong()
            head.append("Content-Length: ").append(len).append("\r\n")
        }
        if (responseHeaders["Connection"] == null) {
            head.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
        val stream = responseStream
        if (stream != null) {
            // 流式响应：128KB 缓冲边读边写；完成后关闭流（释放 OkHttp Response 连接）
            try {
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            } finally {
                try {
                    stream.close()
                } catch (_: IOException) {
                }
                responseStream = null
            }
        } else if (bodyBytes.isNotEmpty()) {
            out.write(bodyBytes)
        }
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