package com.kleinops.gitmount.service

import com.kleinops.gitmount.data.GitRepository
import com.kleinops.gitmount.data.VfsNode
import com.kleinops.gitmount.service.LogBus
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
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
 *
 * 多挂载点动态激活：repo 通过 repoProvider 懒获取（MountPointManager 缓存），
 * 认证通过前不碰仓库；认证通过后第一次仓库操作才激活挂载点并缓存到会话。
 * 会话全程占用挂载点活跃标记（beginOp），大文件传输期间不会被空闲回收误伤。
 */
class FtpServer(
    private val port: Int,
    private val mountPointId: String,
    private val repoProvider: () -> GitRepository,
    private val credentials: () -> Pair<String, String>,
    private val logger: (String) -> Unit = {},
    /** 绑定地址；null = 监听所有网卡（局域网），127.0.0.1 = 仅本机 */
    private val bindAddress: String? = null,
) {
    /** 挂载点仓库（懒获取，缓存在会话级，避免每命令走 provider 的同步锁） */
    private var sessionRepo: GitRepository? = null
    private val repo: GitRepository
        get() = sessionRepo ?: repoProvider().also { sessionRepo = it }
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    /**
     * 会话线程池：默认 cachedThreadPool 空闲 60s 才回收，
     * FTP 客户端断开后线程仍占栈内存。压到 8s 让空闲会话线程尽快退场。
     */
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        0, Int.MAX_VALUE, 8L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
    ) { r -> Thread(r, "ftp-session").apply { isDaemon = true } }

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
                    pool.execute {
                        ServerController.sessionOpened()
                        // 会话级活跃占用：连接建立→断开全程占用挂载点标记，
                        // 防止长连接/传输期间该挂载点被空闲回收仓库缓存
                        MountPointManager.beginOp(mountPointId)
                        try {
                            FtpSession(socket).run()
                        } finally {
                            MountPointManager.endOp(mountPointId)
                            ServerController.sessionClosed()
                        }
                    }
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
        // 空闲命令连接超时：客户端长时间不发命令（或静默挂机）时回收线程，
        // 避免长期占着会话线程。注意 FTP 数据连接（传输中）不受影响。
        private val COMMAND_IDLE_TIMEOUT_MS = 60_000
        /** 数据连接等待客户端连入的超时（PASV 后客户端不连则回收会话线程，避免挂死） */
        private val DATA_CONNECT_TIMEOUT_MS = 30_000
        /** 数据连接空闲超时：传输中超过该时长无新数据，视为客户端已发完（在等 226），
         *  终止 read() 对 EOF 的无限等待，解除“传到最后卡住”的死锁 */
        private val DATA_IDLE_TIMEOUT_MS = 60_000

        private val input: BufferedReader by lazy {
            try {
                socket.soTimeout = COMMAND_IDLE_TIMEOUT_MS
            } catch (_: Exception) {
            }
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
            // 用户名与密码都留空 = 匿名模式：接受任意 USER/PASS（RFC 959 匿名惯例）。
            // 只有其中一项留空属于半配置状态，无法判断意图，明确拒绝并提示。
            val anonymous = user.isBlank() && password.isBlank()
            if (!anonymous && (user.isBlank() || password.isBlank())) {
                reply(530, "账号密码不完整：用户名与密码需同时填写，或同时留空使用匿名登录")
                return
            }
            if (anonymous || (pendingUser == user && pass == password)) {
                authenticated = true
                // 认证通过：立即激活该挂载点（建 repo + 连通性探测）。
                // 失败时提示挂载点不可用，但保持已认证（仓库操作时再报），
                // 避免客户端反复 USER/PASS 重试。
                try {
                    repoProvider()
                    reply(230, "登录成功")
                } catch (e: Exception) {
                    reply(230, "登录成功（仓库暂不可用：${e.message}）")
                }
            } else {
                // 登录失败：低频但重要，记一条 warn 便于排查（认证错误/半配置）
                LogBus.warn("FTP 登录失败：用户 ${pendingUser ?: "(未填)"} 认证未通过")
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
            val ps = openPassiveSocket()
            if (ps == null) {
                reply(425, "无法建立被动数据连接（端口资源不足）")
                return
            }
            passiveServer = ps
            val p1 = ps.localPort / 256
            val p2 = ps.localPort % 256
            val ip = localIp().replace('.', ',')
            reply(227, "进入被动模式 ($ip,$p1,$p2)")
        }

        private fun openEpsv() {
            closePassive()
            val ps = openPassiveSocket()
            if (ps == null) {
                reply(425, "无法建立被动数据连接（端口资源不足）")
                return
            }
            passiveServer = ps
            reply(229, "进入扩展被动模式 (|||${ps.localPort}|)")
        }

        /**
         * 打开被动数据监听端口。
         * 避开所有挂载点的监听端口（MountPointManager.occupiedPorts），防止 FTP
         * 随机数据端口撞上其他挂载点的 WebDAV/FTP 监听端口导致连接被劫持。
         * 最多尝试若干次，仍失败则返回 null 由上层回 425。
         */
        private fun openPassiveSocket(): ServerSocket? {
            val occupied = MountPointManager.occupiedPorts()
            repeat(10) {
                val ps = ServerSocket(0, 1, socket.localAddress)
                val p = ps.localPort
                if (p !in occupied) return ps
                runCatching { ps.close() }
            }
            return null
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
                    // PASV 后客户端迟迟不连数据端口时，accept 超时避免会话线程挂死
                    ps.soTimeout = DATA_CONNECT_TIMEOUT_MS
                    val s = ps.accept()
                    closePassive()
                    // 关键：数据连接加空闲超时。部分客户端传完数据不关闭连接（等 226），
                    // 若服务端一直等 EOF 就会死锁，表现为“传到最后卡住”。超时即判定传输结束。
                    s.soTimeout = DATA_IDLE_TIMEOUT_MS
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
                        soTimeout = DATA_IDLE_TIMEOUT_MS
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
                ServerController.addTraffic(0, sb.length.toLong())
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
                // 流式下载：LFS 大文件（指针对应 Releases 资产）边下载边发给客户端，
                // 不整读进内存（大文件 RETR 不 OOM）；小文件走整读（openReadStream 内部处理）。
                val stream = repo.openReadStream(path)
                stream.use { input ->
                    // REST 断点续传：跳过 restartOffset 字节再发送
                    var skip = restartOffset
                    val skipBuf = ByteArray(64 * 1024)
                    while (skip > 0) {
                        val n = input.read(skipBuf, 0, minOf(skip, skipBuf.size.toLong()).toInt())
                        if (n < 0) break
                        skip -= n
                    }
                    val buf = ByteArray(128 * 1024)
                    val out = dataSocket.getOutputStream()
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    out.flush()
                    restartOffset = 0
                    ServerController.addTraffic(0, total)
                    dataSocket.close()
                    reply(226, "下载完成")
                }
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
                if (append && repo.exists(path)) {
                    // LFS 大文件不支持 APPE（断点续传会读到指针文本当普通文件处理，破坏指针）
                    if (repo.isLfsPointer(path)) {
                        LogBus.warn("FTP APPE 拒绝：$path 是 LFS 大文件（GitHub Releases 不支持服务端追加）")
                        dataSocket.close()
                        reply(552, "大文件不支持断点续传（APPE）")
                        return
                    }
                    // APPE / 断点续传：需要先读已有内容再合并，只能内存路径。
                    // （GitHub Contents API 不支持服务端追加，仍保持原语义）
                    val incoming = readLimited(dataSocket.getInputStream(), GitRepository.MAX_LFS_SIZE)
                        ?: run {
                            dataSocket.close()
                            reply(552, "文件超过 ${GitRepository.MAX_LFS_SIZE / 1000 / 1000 / 1000}GB 上限")
                            return
                        }
                    LogBus.info("FTP 收到上传数据 ${incoming.size} 字节（APPE）→ $path")
                    val finalBytes = repo.read(path) + incoming
                    repo.write(path, finalBytes)
                    LogBus.info("FTP 上传已写入 GitHub：$path（${finalBytes.size} 字节）")
                    dataSocket.close()
                    ServerController.addTraffic(incoming.size.toLong(), 0)
                    reply(226, "上传完成")
                } else {
                    // 常规 STOR：流式转发——数据连接收到的字节边收边 base64 推给 GitHub
                    // （chunked），FTP 客户端的进度被 GitHub 公网真实上传速度拖住，
                    // 不再出现“客户端 100% 但 GitHub 才开始传”的假完成。
                    LogBus.info("FTP 流式上传开始 → $path")
                    val node = repo.writeStream(path, dataSocket.getInputStream())
                    LogBus.info("FTP 上传已写入 GitHub：$path（${node.size} 字节）")
                    dataSocket.close()
                    ServerController.addTraffic(node.size, 0)
                    reply(226, "上传完成")
                }
            } catch (e: com.kleinops.gitmount.data.FileTooLargeException) {
                // 本地拦截：不用等 GitHub 返回 422，直接拒绝并告知上限
                LogBus.warn("FTP 上传被本地拦截（超限）：$path → ${e.message}")
                try {
                    dataSocket.close()
                } catch (_: IOException) {
                }
                reply(552, "文件超过 ${GitRepository.MAX_LFS_SIZE / 1000 / 1000 / 1000}GB 上限，已本地拒绝")
            } catch (e: OutOfMemoryError) {
                LogBus.error("上传内存不足：${e.message}")
                try {
                    dataSocket.close()
                } catch (_: IOException) {
                }
                reply(552, "内存不足，请关闭其他应用后重试")
            } catch (e: Exception) {
                LogBus.error("FTP 写入失败：${path} → ${e.message}")
                try {
                    dataSocket.close()
                } catch (_: IOException) {
                }
                reply(550, "写入失败：${e.message}")
            }
        }

        /** 流式读取数据连接，最多读 limit 字节；超过则返回 null（由调用方中断并回 552） */
        private fun readLimited(input: InputStream, limit: Long): ByteArray? {
            val readBuf = ByteArray(64 * 1024)
            // 自管理字节数组 + 固定 1MB 增量扩容：
            // - 相比 ByteArrayOutputStream 的翻倍扩容（64MB→128MB 时新旧并存峰值 192MB），
            //   这里峰值 ≈ 数据量 + 1MB，78MB 文件在默认堆下不会 OOM；
            // - 固定增量保证扩容次数少（78MB 约 78 次），均摊复制成本可接受。
            var data = ByteArray(0)
            var size = 0
            while (true) {
                val n = try {
                    input.read(readBuf)
                } catch (e: java.net.SocketTimeoutException) {
                    // 客户端发完数据但未关闭数据连接（在等 226）时，空闲超时判定传输结束。
                    // 若不超时判定，服务端会永久阻塞在 read() 等待 EOF，出现“传到最后卡住”。
                    LogBus.warn("FTP 数据连接空闲超时，按传输结束处理（已收 ${size} 字节）")
                    break
                } catch (e: IOException) {
                    return java.util.Arrays.copyOf(data, size)
                }
                if (n < 0) break
                if (size.toLong() + n > limit) return null
                if (size + n > data.size) {
                    val need = size + n
                    val newCap = need.coerceAtLeast(data.size + 1024 * 1024)
                    data = java.util.Arrays.copyOf(data, newCap)
                }
                System.arraycopy(readBuf, 0, data, size, n)
                size += n
            }
            return java.util.Arrays.copyOf(data, size)
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