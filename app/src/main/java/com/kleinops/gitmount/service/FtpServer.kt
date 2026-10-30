package com.kleinops.gitmount.service

import com.kleinops.gitmount.data.GitRepository
import com.kleinops.gitmount.data.VfsNode
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手写 FTP 服务器（RFC 959 子集 + RFC 2428 EPSV）。
 *
 * 支持命令：USER PASS QUIT FEAT OPTS SYST PWD CWD CDUP TYPE PASV EPSV PORT
 *          LIST NLST SIZE MDTM RETR STOR APPE DELE MKD RMD RNFR RNTO NOOP REST
 *
 * 设计取舍：
 * - 只做明文 FTP（不实现 FTPS）：Android 上 TLS 与 FTP 双通道组合复杂，
 *   且该服务默认只监听局域网，配合账号密码已能满足个人使用。
 * - 数据连接支持 PASV/EPSV（客户端主流）与 PORT（主动模式）。
 * - 所有文件操作统一走 GitRepository，与 WebDAV 共享同一套语义。
 */
class FtpServer(
    private val port: Int,
    private val repo: GitRepository,
    private val credentials: () -> Pair<String, String>,
    private val logger: (String) -> Unit = {},
    /** 绑定地址；null = 监听所有网卡（局域网），127.0.0.1 = 仅本机 */
    private val bindAddress: String? = null,
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ftp-session").apply { isDaemon = true }
    }

    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        if (bindAddress == null) {
            ss.bind(InetSocketAddress(port))
        } else {
            ss.bind(InetSocketAddress(bindAddress, port))
        }
        serverSocket = ss
        running.set(true)
        Thread {
            while (running.get()) {
                try {
                    val socket = ss.accept()
                    pool.execute { FtpSession(socket).run() }
                } catch (e: IOException) {
                    if (running.get()) logger("FTP accept 异常: ${e.message}")
                }
            }
        }.apply { name = "ftp-accept"; isDaemon = true }.start()
        logger("FTP 服务已启动，端口 $port")
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        pool.shutdownNow()
        logger("FTP 服务已停止")
    }

    /** 单个客户端会话 */
    private inner class FtpSession(private val socket: Socket) {
        private val input: BufferedReader by lazy {
            BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        }
        private val output: OutputStream by lazy { socket.getOutputStream() }

        private var authenticated = false
        private var pendingUser: String? = null
        private var cwd = "/"

        private var passiveServer: ServerSocket? = null
        private var activeAddress: InetAddress? = null
        private var activePort: Int = 0
        private var renameFrom: String? = null
        private var restartOffset: Long = 0

        fun run() {
            reply(220, "GitMount FTP 服务就绪")
            try {
                while (running.get()) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) continue
                    val idx = line.indexOf(' ')
                    val cmd = (if (idx < 0) line else line.substring(0, idx)).uppercase(Locale.US)
                    val arg = if (idx < 0) "" else line.substring(idx + 1)
                    if (!dispatch(cmd, arg)) break
                }
            } catch (e: IOException) {
                // 客户端断开，正常退出
            } finally {
                closePassive()
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }

        private fun dispatch(cmd: String, arg: String): Boolean {
            val preAuthAllowed = setOf("USER", "PASS", "QUIT", "FEAT", "OPTS", "AUTH", "SYST", "NOOP")
            if (!authenticated && cmd !in preAuthAllowed) {
                reply(530, "请先登录")
                return true
            }
            return when (cmd) {
                "USER" -> {
                    pendingUser = arg
                    reply(331, "需要密码")
                    true
                }
                "PASS" -> {
                    doLogin(arg)
                    true
                }
                "QUIT" -> {
                    reply(221, "再见")
                    false
                }
                "NOOP" -> {
                    reply(200, "OK")
                    true
                }
                "FEAT" -> {
                    writeRaw("211-Features:\r\n")
                    writeRaw(" UTF8\r\n")
                    writeRaw(" SIZE\r\n")
                    writeRaw(" MDTM\r\n")
                    writeRaw(" EPSV\r\n")
                    writeRaw(" PASV\r\n")
                    writeRaw(" REST STREAM\r\n")
                    writeRaw("211 End\r\n")
                    true
                }
                "OPTS" -> {
                    reply(200, "OK")
                    true
                }
                "AUTH" -> {
                    reply(502, "不支持加密认证")
                    true
                }
                "SYST" -> {
                    reply(215, "UNIX Type: L8")
                    true
                }
                "TYPE" -> {
                    reply(200, "切换到 $arg 模式")
                    true
                }
                "PWD", "XPWD" -> {
                    reply(257, "\"$cwd\"")
                    true
                }
                "CWD" -> {
                    doCwd(arg)
                    true
                }
                "CDUP" -> {
                    doCwd("..")
                    true
                }
                "PASV" -> {
                    openPassive()
                    true
                }
                "EPSV" -> {
                    openEpsv()
                    true
                }
                "PORT" -> {
                    openActive(arg)
                    true
                }
                "LIST" -> {
                    doList(arg, detailed = true)
                    true
                }
                "NLST" -> {
                    doList(arg, detailed = false)
                    true
                }
                "SIZE" -> {
                    doSize(arg)
                    true
                }
                "MDTM" -> {
                    doMdtm(arg)
                    true
                }
                "REST" -> {
                    restartOffset = arg.trim().toLongOrNull() ?: 0
                    reply(350, "从 $restartOffset 继续")
                    true
                }
                "RETR" -> {
                    doRetr(arg)
                    true
                }
                "STOR" -> {
                    doStor(arg, append = false)
                    true
                }
                "APPE" -> {
                    doStor(arg, append = true)
                    true
                }
                "DELE" -> {
                    doDelete(arg)
                    true
                }
                "MKD", "XMKD" -> {
                    doMkd(arg)
                    true
                }
                "RMD", "XRMD" -> {
                    doRmd(arg)
                    true
                }
                "RNFR" -> {
                    renameFrom = resolve(arg)
                    reply(350, "请提供新名称")
                    true
                }
                "RNTO" -> {
                    doRnto(arg)
                    true
                }
                else -> {
                    reply(502, "不支持的命令 $cmd")
                    true
                }
            }
        }

        // ---------- 认证 ----------

        private fun doLogin(pass: String) {
            val (user, password) = credentials()
            if (user.isBlank() || password.isBlank()) {
                reply(530, "服务端未配置账号密码，请在 App 内设置")
                return
            }
            if (pendingUser == user && pass == password) {
                authenticated = true
                reply(230, "登录成功")
            } else {
                reply(530, "用户名或密码错误")
            }
        }

        // ---------- 路径 ----------

        /** 把 FTP 参数路径解析成绝对内部路径 */
        private fun resolve(arg: String): String {
            val raw = if (arg.isBlank()) cwd else arg
            return try {
                val base = if (raw.startsWith("/")) "" else cwd.trimEnd('/')
                repo.normalize(base + "/" + raw)
            } catch (e: IOException) {
                cwd
            }
        }

        private fun doCwd(arg: String) {
            val target = resolve(arg)
            val node = try {
                repo.stat(target)
            } catch (e: Exception) {
                null
            }
            if (node != null && node.isDirectory) {
                cwd = target
                reply(250, "目录已切换")
            } else {
                reply(550, "目录不存在")
            }
        }

        // ---------- 数据连接 ----------

        private fun localIp(): String {
            // 仅监听回环时，PASV 数据连接地址也必须通告为 127.0.0.1，
            // 否则客户端会去连接局域网 IP 导致数据连接失败。
            if (bindAddress == "127.0.0.1") return "127.0.0.1"
            return try {
                val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
                var candidate: String? = null
                for (iface in ifaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.contains(':')) continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                        if (candidate == null) candidate = ip
                    }
                }
                candidate ?: socket.localAddress.hostAddress ?: "127.0.0.1"
            } catch (e: Exception) {
                socket.localAddress?.hostAddress ?: "127.0.0.1"
            }
        }

        private fun openPassive() {
            closePassive()
            val ps = ServerSocket(0, 1, socket.localAddress)
            passiveServer = ps
            val p1 = ps.localPort / 256
            val p2 = ps.localPort % 256
            val ip = localIp().replace('.', ',')
            reply(227, "进入被动模式 ($ip,$p1,$p2)")
        }

        private fun openEpsv() {
            closePassive()
            val ps = ServerSocket(0, 1, socket.localAddress)
            passiveServer = ps
            reply(229, "进入扩展被动模式 (|||${ps.localPort}|)")
        }

        private fun openActive(arg: String) {
            val parts = arg.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size != 6) {
                reply(501, "PORT 参数错误")
                return
            }
            val ip = "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}"
            activeAddress = InetAddress.getByName(ip)
            activePort = parts[4] * 256 + parts[5]
            reply(200, "已记录主动模式地址")
        }

        /** 建立数据连接：被动模式等服务端 accept，主动模式由服务端连回客户端 */
        private fun openDataSocket(): Socket? {
            val ps = passiveServer
            if (ps != null) {
                return try {
                    val s = ps.accept()
                    closePassive()
                    s
                } catch (e: IOException) {
                    closePassive()
                    null
                }
            }
            val addr = activeAddress
            if (addr != null && activePort > 0) {
                return try {
                    Socket().apply {
                        connect(InetSocketAddress(addr, activePort), 5000)
                    }
                } catch (e: IOException) {
                    null
                }
            }
            reply(425, "未建立数据连接，请先 PASV 或 PORT")
            return null
        }

        private fun closePassive() {
            try {
                passiveServer?.close()
            } catch (_: IOException) {
            }
            passiveServer = null
        }

        // ---------- 目录与文件 ----------

        private fun doList(arg: String, detailed: Boolean) {
            val target = resolve(arg)
            val node = try {
                repo.stat(target)
            } catch (e: Exception) {
                null
            }
            if (node == null) {
                reply(550, "路径不存在")
                return
            }
            val entries: List<VfsNode> = if (node.isDirectory) {
                repo.visibleList(target)
            } else {
                listOf(node)
            }

            reply(150, "开始传输列表")
            val dataSocket = openDataSocket() ?: return
            try {
                val out = dataSocket.getOutputStream()
                val sb = StringBuilder()
                val time = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date())
                for (e in entries) {
                    if (detailed) {
                        sb.append(if (e.isDirectory) "drwxr-xr-x" else "-rw-r--r--")
                        sb.append(" 1 ftp ftp ")
                        sb.append(String.format(Locale.US, "%12d", e.size))
                        sb.append(' ')
                        sb.append(time)
                        sb.append(' ')
                        sb.append(e.name)
                        sb.append("\r\n")
                    } else {
                        sb.append(e.name).append("\r\n")
                    }
                }
                out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
                out.flush()
                dataSocket.close()
                reply(226, "传输完成")
            } catch (e: IOException) {
                reply(426, "连接中断：${e.message}")
            }
        }

        private fun doSize(arg: String) {
            val path = resolve(arg)
            val node = try {
                repo.stat(path)
            } catch (e: Exception) {
                null
            }
            if (node == null || node.isDirectory) {
                reply(550, "无法获取大小")
            } else {
                reply(213, node.size.toString())
            }
        }

        private fun doMdtm(arg: String) {
            val path = resolve(arg)
            val node = try {
                repo.stat(path)
            } catch (e: Exception) {
                null
            }
            if (node == null) {
                reply(550, "文件不存在")
            } else {
                reply(213, SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date()))
            }
        }

        private fun doRetr(arg: String) {
            val path = resolve(arg)
            reply(150, "开始下载")
            val dataSocket = openDataSocket() ?: return
            try {
                var bytes = repo.read(path)
                if (restartOffset in 1 until bytes.size.toLong()) {
                    bytes = bytes.copyOfRange(restartOffset.toInt(), bytes.size)
                }
                restartOffset = 0
                val out = dataSocket.getOutputStream()
                out.write(bytes)
                out.flush()
                dataSocket.close()
                reply(226, "下载完成")
            } catch (e: Exception) {
                try {
                    dataSocket.close()
                } catch (_: IOException) {
                }
                reply(550, "读取失败：${e.message}")
            }
        }

        private fun doStor(arg: String, append: Boolean) {
            val path = resolve(arg)
            if (path == "/") {
                reply(550, "非法目标")
                return
            }
            reply(150, "请发送数据")
            val dataSocket = openDataSocket() ?: return
            try {
                val incoming = dataSocket.getInputStream().readBytes()
                val finalBytes = if (append && repo.exists(path)) {
                    repo.read(path) + incoming
                } else {
                    incoming
                }
                repo.write(path, finalBytes)
                dataSocket.close()
                reply(226, "上传完成")
            } catch (e: Exception) {
                try {
                    dataSocket.close()
                } catch (_: IOException) {
                }
                reply(550, "写入失败：${e.message}")
            }
        }

        private fun doDelete(arg: String) {
            val path = resolve(arg)
            try {
                repo.delete(path)
                reply(250, "已删除")
            } catch (e: Exception) {
                reply(550, "删除失败：${e.message}")
            }
        }

        private fun doMkd(arg: String) {
            val path = resolve(arg)
            try {
                repo.createDirectory(path)
                reply(257, "\"$path\" 已创建")
            } catch (e: Exception) {
                reply(550, "创建失败：${e.message}")
            }
        }

        private fun doRmd(arg: String) {
            val path = resolve(arg)
            if (path == "/") {
                reply(550, "不能删除根目录")
                return
            }
            try {
                repo.delete(path)
                reply(250, "目录已删除")
            } catch (e: Exception) {
                reply(550, "删除失败：${e.message}")
            }
        }

        private fun doRnto(arg: String) {
            val from = renameFrom
            if (from == null) {
                reply(503, "请先发送 RNFR")
                return
            }
            val to = resolve(arg)
            try {
                repo.move(from, to)
                renameFrom = null
                reply(250, "重命名完成")
            } catch (e: Exception) {
                reply(550, "重命名失败：${e.message}")
            }
        }

        // ---------- 输出 ----------

        private fun reply(code: Int, message: String) {
            writeRaw("$code $message\r\n")
        }

        private fun writeRaw(text: String) {
            try {
                output.write(text.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            } catch (_: IOException) {
            }
        }
    }
}