package com.kleinops.gitmount.service

import android.util.Base64
import com.kleinops.gitmount.data.GitRepository
import com.kleinops.gitmount.data.VfsNode
import com.kleinops.gitmount.service.http.HttpExchange
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * WebDAV 协议处理器：把 HttpServer 的请求路由到 GitRepository。
 *
 * 支持的 DAV 能力：
 * - PROPFIND（Depth 0/1，返回 207 Multi-Status）
 * - GET / HEAD（下载）
 * - PUT（新建/覆盖文件）
 * - DELETE（删文件/递归删目录）
 * - MKCOL（建目录，.gitkeep 占位）
 * - MOVE / COPY（读-写-删）
 * - LOCK / UNLOCK（乐观响应，保证 Windows 资源管理器等客户端可用）
 *
 * 认证：HTTP Basic（用户名/密码在设置页配置，空密码一律拒绝）。
 */
class WebDavHandler(
    private val repo: GitRepository,
    private val authUser: () -> Pair<String, String>,
) {

    fun handle(exchange: HttpExchange) {
        if (!checkAuth(exchange)) return

        when (exchange.method) {
            "OPTIONS" -> handleOptions(exchange)
            "PROPFIND" -> handlePropfind(exchange)
            "GET", "HEAD" -> handleGet(exchange)
            "PUT" -> handlePut(exchange)
            "DELETE" -> handleDelete(exchange)
            "MKCOL" -> handleMkcol(exchange)
            "MOVE" -> handleMove(exchange)
            "COPY" -> handleCopy(exchange)
            "LOCK" -> handleLock(exchange)
            "UNLOCK" -> handleUnlock(exchange)
            else -> exchange.respond(405)
        }
    }

    // ---------- 认证 ----------

    private fun checkAuth(exchange: HttpExchange): Boolean {
        // 每次请求都重新读取凭据（用户在设置页改密码后立即生效）
        val currentUser = authUser().first
        val currentPass = authUser().second
        if (currentUser.isBlank() || currentPass.isBlank()) {
            exchange.respond(401, extra = mapOf("WWW-Authenticate" to "Basic realm=\"GitMount\""))
            return false
        }
        val auth = exchange.header("authorization") ?: ""
        val expected = "Basic " + Base64.encodeToString(
            "$currentUser:$currentPass".toByteArray(), Base64.NO_WRAP
        )
        if (auth != expected) {
            exchange.respond(401, extra = mapOf("WWW-Authenticate" to "Basic realm=\"GitMount\""))
            return false
        }
        return true
    }

    // ---------- 基础 ----------

    private fun handleOptions(exchange: HttpExchange) {
        exchange.respond(
            200,
            extra = mapOf(
                "DAV" to "1, 2",
                "Allow" to "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL, MOVE, COPY, LOCK, UNLOCK",
                "MS-Author-Via" to "DAV",
            )
        )
    }

    private fun handleLock(exchange: HttpExchange) {
        val token = "opaquelocktoken:" + UUID.randomUUID()
        // 注意：这里返回的是 XML 文档本身，绝不能再经过 xmlEncode，
        // 否则尖括号会被转义成 &lt; 导致客户端无法解析。
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
            "<D:locktype><D:write/></D:locktype>" +
            "<D:lockscope><D:exclusive/></D:lockscope>" +
            "<D:depth>infinity</D:depth>" +
            "<D:owner>GitMount</D:owner>" +
            "<D:locktoken><D:href>$token</D:href></D:locktoken>" +
            "</D:activelock></D:lockdiscovery></D:prop>"
        exchange.respond(
            200,
            contentType = "application/xml; charset=utf-8",
            body = body.toByteArray(StandardCharsets.UTF_8),
            extra = mapOf("Lock-Token" to "<$token>"),
        )
    }

    private fun handleUnlock(exchange: HttpExchange) {
        exchange.respond(204)
    }

    // ---------- PROPFIND ----------

    private fun handlePropfind(exchange: HttpExchange) {
        val depth = exchange.header("depth") ?: "1"
        if (depth.equals("infinity", true)) {
            // 有限实现：拒绝无限深度（大多数客户端只发 0/1）
            exchange.respond(403)
            return
        }
        val path = repo.normalize(exchange.path)
        val node = repo.stat(path)
        if (node == null) {
            exchange.respond(404)
            return
        }

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">")

        val nodesToReport = mutableListOf<Pair<String, VfsNode>>()
        nodesToReport.add(path to node)
        if (depth != "0" && node.isDirectory) {
            repo.visibleList(path).forEach { nodesToReport.add(it.path to it) }
        }

        for ((p, n) in nodesToReport) {
            appendPropfindResponse(sb, p, n)
        }
        sb.append("</D:multistatus>")

        exchange.respond(
            207,
            contentType = "application/xml; charset=utf-8",
            body = sb.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun appendPropfindResponse(sb: StringBuilder, path: String, node: VfsNode) {
        val href = if (path == "/") "/" else {
            val base = path.split("/").joinToString("/") { urlEncodeSegment(it) }
            if (node.isDirectory) "$base/" else base
        }
        val isDir = node.isDirectory
        val modified = repo.displayModified()
        sb.append("<D:response>")
        sb.append("<D:href>").append(xmlEncode(href)).append("</D:href>")
        sb.append("<D:propstat><D:prop>")
        sb.append("<D:displayname>").append(xmlEncode(node.name)).append("</D:displayname>")
        sb.append("<D:resourcetype>")
        if (isDir) sb.append("<D:collection/>")
        sb.append("</D:resourcetype>")
        sb.append("<D:getcontentlength>").append(node.size).append("</D:getcontentlength>")
        sb.append("<D:getlastmodified>").append(repo.rfc1123(modified)).append("</D:getlastmodified>")
        sb.append("<D:creationdate>").append(repo.iso8601(modified)).append("</D:creationdate>")
        sb.append("<D:getetag>").append("\"${node.sha}\"").append("</D:getetag>")
        sb.append("<D:getcontenttype>").append(if (isDir) "httpd/unix-directory" else mimeType(node.name)).append("</D:getcontenttype>")
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
        sb.append("</D:response>")
    }

    // ---------- GET / HEAD ----------

    private fun handleGet(exchange: HttpExchange) {
        val path = repo.normalize(exchange.path)
        val node = repo.stat(path)
        if (node == null) {
            exchange.respond(404)
            return
        }
        if (node.isDirectory) {
            exchange.respond(404)
            return
        }
        val bytes = try {
            repo.read(path)
        } catch (e: Exception) {
            exchange.respond(500, body = e.message?.toByteArray() ?: ByteArray(0))
            return
        }
        exchange.respond(
            200,
            contentType = mimeType(node.name),
            body = bytes,
            extra = mapOf(
                "Content-Disposition" to "inline",
                "ETag" to "\"${node.sha}\"",
            ),
        )
        // HEAD 由 HttpServer 以 GET 相同逻辑执行，但避免发送 body 由上层判断
        if (exchange.method == "HEAD") {
            exchange.bodyBytes = ByteArray(0)
            // 修正 Content-Length 为文件大小
            exchange.responseHeaders["Content-Length"] = bytes.size.toString()
        }
    }

    // ---------- PUT ----------

    private fun handlePut(exchange: HttpExchange) {
        val path = repo.normalize(exchange.path)
        // 分块传输的请求体未被解析，继续写会得到空文件（静默数据损坏），必须明确拒绝。
        if (exchange.chunked) {
            exchange.respond(411)
            return
        }
        val parentExists = repo.stat(repo.parentOf(path))?.isDirectory == true
        if (!parentExists) {
            exchange.respond(409)
            return
        }
        val contentLength = exchange.header("content-length")?.toLongOrNull() ?: 0L
        if (contentLength > GitRepository.MAX_FILE_SIZE) {
            exchange.respond(413)
            return
        }
        try {
            val node = repo.write(path, exchange.body)
            exchange.respond(
                201,
                extra = mapOf(
                    "ETag" to "\"${node.sha}\"",
                    "Location" to exchange.path,
                ),
            )
        } catch (e: Exception) {
            exchange.respond(409, body = e.message?.toByteArray() ?: ByteArray(0))
        }
    }

    // ---------- DELETE ----------

    private fun handleDelete(exchange: HttpExchange) {
        val path = repo.normalize(exchange.path)
        if (path == "/") {
            exchange.respond(403)
            return
        }
        try {
            repo.delete(path)
            exchange.respond(204)
        } catch (e: Exception) {
            exchange.respond(404, body = e.message?.toByteArray() ?: ByteArray(0))
        }
    }

    // ---------- MKCOL ----------

    private fun handleMkcol(exchange: HttpExchange) {
        // MKCOL 带 body 属于扩展（RFC 5689），我们只支持空 body 创建目录
        if (exchange.body.isNotEmpty()) {
            exchange.respond(415)
            return
        }
        val path = repo.normalize(exchange.path)
        try {
            repo.createDirectory(path)
            exchange.respond(201)
        } catch (e: Exception) {
            exchange.respond(405, body = e.message?.toByteArray() ?: ByteArray(0))
        }
    }

    // ---------- MOVE / COPY ----------

    private fun handleMove(exchange: HttpExchange) {
        val destination = exchange.header("destination") ?: run {
            exchange.respond(400)
            return
        }
        val destPath = extractDestinationPath(destination)
        if (destPath == null) {
            exchange.respond(400)
            return
        }
        try {
            repo.move(repo.normalize(exchange.path), destPath)
            exchange.respond(201)
        } catch (e: Exception) {
            exchange.respond(409, body = e.message?.toByteArray() ?: ByteArray(0))
        }
    }

    private fun handleCopy(exchange: HttpExchange) {
        val destination = exchange.header("destination") ?: run {
            exchange.respond(400)
            return
        }
        val destPath = extractDestinationPath(destination)
        if (destPath == null) {
            exchange.respond(400)
            return
        }
        try {
            repo.copy(repo.normalize(exchange.path), destPath)
            exchange.respond(201)
        } catch (e: Exception) {
            exchange.respond(409, body = e.message?.toByteArray() ?: ByteArray(0))
        }
    }

    /** 从 Destination 头解析路径（http://host:port/a/b -> /a/b） */
    private fun extractDestinationPath(destination: String): String? {
        val stripped = destination.substringBefore('?').substringBefore('#')
        val slashIdx = stripped.indexOf('/', stripped.indexOf("://") + 3)
        if (slashIdx < 0) return "/"
        // 只还原 %XX，保留 '+'（URLDecoder 会把 '+' 变成空格）
        return com.kleinops.gitmount.service.http.percentDecode(stripped.substring(slashIdx))
    }

    // ---------- 工具 ----------

    private fun xmlEncode(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                // 注意：" / ' 用拼接生成，避免写入工具对实体做反转义
                '"' -> append('&').append("quot;")
                '\'' -> append('&').append("apos;")
                else -> append(c)
            }
        }
    }

    private fun urlEncodeSegment(seg: String): String =
        java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

    private fun mimeType(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") -> "text/plain; charset=utf-8"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".xml") || lower.endsWith(".html") || lower.endsWith(".htm") -> "text/xml; charset=utf-8"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".zip") || lower.endsWith(".gz") || lower.endsWith(".tar") -> "application/zip"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".mp4") -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}