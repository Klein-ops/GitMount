package com.kleinops.gitmount.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kleinops.gitmount.MainActivity
import com.kleinops.gitmount.R
import com.kleinops.gitmount.data.GitHubApiException
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.data.SettingsRepository
import com.kleinops.gitmount.service.http.HttpServer
import java.net.BindException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 前台服务：持有 FTP / WebDAV 服务器实例，保证息屏后仍可访问。
 *
 * 生命周期：START_STICKY（被系统杀掉后尝试恢复，恢复失败则停留在停止态）。
 * 通知：常驻通知展示端口与地址，附带“停止”操作。
 */
class MountService : Service() {

    companion object {
        private const val CHANNEL_ID = "gitmount_server"
        private const val CHANNEL_SILENT_ID = "gitmount_server_silent"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.kleinops.gitmount.action.START"
        const val ACTION_STOP = "com.kleinops.gitmount.action.STOP"
        const val ACTION_RESTART = "com.kleinops.gitmount.action.RESTART"

        /**
         * 启动流程整体超时：从设置读取 → 端口预检 → GitHub 连通性校验 → 拉起服务器，
         * 任何一步卡住（DataStore 读、GitHub 网络、系统冻结恢复）都必须在有限时间内
         * 收敛成明确失败并清理，绝不永久悬挂"正在启动"（实测曾出现卡 3.5 小时）。
         * 120s 足以覆盖 15s 连接 + OkHttp 读超时 + 余量；超时即视为失败。
         */
        private const val START_TIMEOUT_MS = 120_000L

        /** DataStore 读取超时：本地文件操作正常毫秒级，兜底防卡死 */
        private const val SETTINGS_READ_TIMEOUT_MS = 10_000L

        /** 由 UI 调用：启动服务（多账户架构下启动全部账户的监听；mode/port 仅保留兼容） */
        fun start(context: Context, mode: ServerMode = ServerMode.WEBDAV, port: Int = 0) {
            // 记录用户意图：期望服务保持运行（进程被杀后据此自动恢复）
            persistIntent(context, enabled = true)
            val intent = Intent(context, MountService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 由 UI 调用：用最新保存的设置重启服务（模式/端口/账号/仓库变更后立即生效） */
        fun restart(context: Context) {
            // 重启同样意味着用户期望服务运行，确保意图标记为 true
            persistIntent(context, enabled = true)
            val intent = Intent(context, MountService::class.java).apply {
                action = ACTION_RESTART
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            // 用户手动停止：清除"期望运行"意图，即便进程被杀也不再自动恢复
            persistIntent(context, enabled = false)
            val intent = Intent(context, MountService::class.java).apply {
                action = ACTION_STOP
            }
            // Android 8+ 应用退到后台后 startService 会抛 IllegalStateException，
            // 这里统一走 startForegroundService；服务内 ACTION_STOP 分支会先 startForeground 再退出。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 持久化"用户期望服务运行"的意图（独立于服务生命周期的协程落盘，
         * 避免被服务 onDestroy 的 scope.cancel 取消导致标记丢失）。
         */
        private fun persistIntent(context: Context, enabled: Boolean) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    SettingsRepository(context.applicationContext).setServiceEnabled(enabled)
                }.onFailure {
                    LogBus.warn("写入服务运行意图失败：${it.message}")
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 每个账户一个服务器实例：accountId -> HttpServer / FtpServer（空壳监听，认证后才激活仓库） */
    private val httpServers = mutableMapOf<String, HttpServer>()
    private val ftpServers = mutableMapOf<String, FtpServer>()
    private var startJob: Job? = null
    private lateinit var settingsRepo: SettingsRepository

    /** true = 隐藏前台服务常驻通知（用静默渠道），启动后从设置读取 */
    @Volatile
    private var hiddenNotification = false

    /** 网络变化监听：Wi-Fi 断开/重连、IP 变更时自动刷新通知里的端点地址 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
        createChannel()
        registerNetworkCallback()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        shutdownServers()
        scope.cancel()
        super.onDestroy()
    }

    /** 注册网络监听：仅关心「当前是否有可用网络」与 IP 变化（用于刷新通知端点） */
    private fun registerNetworkCallback() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshEndpointAfterNetworkChange()
                }

                override fun onLost(network: Network) {
                    refreshEndpointAfterNetworkChange()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    refreshEndpointAfterNetworkChange()
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure {
            LogBus.warn("注册网络变化监听失败：${it.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(cb)
        }
    }

    /** 网络变化后：若服务在运行，重新计算局域网 IP 并刷新通知（不重启服务、不影响已有连接） */
    private fun refreshEndpointAfterNetworkChange() {
        val st = ServerController.state.value
        if (!st.running || st.loopbackOnly) return
        val newIp = NetUtils.lanIpv4()
        val oldIp = st.lanIp
        LogBus.info("网络状态变化，IP: ${oldIp ?: "无"} → ${newIp ?: "无"}")
        if (newIp != oldIp) {
            ServerController.update { it.copy(lanIp = newIp) }
            if (newIp != null) {
                updateNotification(
                    getString(R.string.notif_running, st.mode.label, st.port),
                    endpointText(st.mode, newIp, st.port),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 通知栏"停止"按钮直接发 ACTION_STOP（不经 MountService.stop()），
                // 这里同样清除"期望运行"意图，否则用户点通知栏停止后进程被杀仍会自动恢复。
                persistIntent(this, enabled = false)
                // 若本服务是通过 startForegroundService 拉起的，必须先 startForeground
                // 才能安全退出，否则系统会抛 "did not then call Service.startForeground()"。
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_stopped), null, hiddenNotification))
                shutdownServers()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // 用最新保存的设置重启（模式/端口/账号/仓库变更后立即生效，
                // 不再要求用户手动停止再启动）。
                LogBus.info("收到重启指令，按最新设置重启服务…")
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting), null, hiddenNotification))
                restartServers()
                return START_STICKY
            }
            ACTION_START -> {
                // 多账户动态激活：启动全部账户的监听（空壳，认证后才激活仓库）
                startServers()
            }
            else -> {
                // 系统按 START_STICKY 重建服务（进程被杀后 intent 为 null 或 action 未知）。
                // 只有"用户上次手动开过服务（持久化意图为 true）且配置完整"才自动按最新设置恢复；
                // 用户从未开启或已手动停止 → 保持停止，不做无意义的重启（避免异常循环）。
                LogBus.info("服务进程被系统回收，检查用户意图决定是否自动恢复…")
                scope.launch {
                    // DataStore 读取同样加超时，防止恢复流程悬挂
                    val settings = withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
                        runCatching { settingsRepo.settings.first() }.getOrNull()
                    }
                    val shouldResume = settings != null &&
                        settings.serviceEnabled &&
                        settings.isConfigured &&
                        settings.username.isNotBlank() &&
                        settings.password.isNotBlank()
                    if (shouldResume) {
                        LogBus.info("检测到用户曾开启服务，自动按最新配置恢复")
                        // 系统重建的前台服务也先补一次 startForeground，避免恢复期间被判定非前台
                        startForeground(
                            NOTIF_ID,
                            buildNotification(getString(R.string.notif_starting), null, hiddenNotification),
                        )
                        startServers()
                    } else {
                        LogBus.warn("无服务运行意图（用户未开启或已停止），保持停止")
                        stopSelf()
                    }
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 系统内存压力回调：应用不在前台时，系统会频繁回调此方法要求降内存。
     * 这里清空仓库文件缓存（目录/文件缓存都有短 TTL，重建成本可忽略），
     * 并主动 GC 一次（System.gc 只是建议，但能帮助 ART 尽快回收弱引用与临时对象）。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            AccountManager.reclaimIdle()
            LogBus.info("系统内存压力回调（level=$level），已回收空闲账户缓存")
        }
    }

    /**
     * 按最新保存的设置重启服务（先停旧服务器实例，再用新设置拉起）。
     * 与 ACTION_STOP 不同：这里不退出服务、不摘除前台，避免重启瞬间
     * 前台服务出现"非前台间隙"被系统判定违规。
     */
    private fun restartServers() {
        shutdownServers(keepForeground = true)
        scope.launch {
            // DataStore 读取同样加超时，避免重启流程悬挂
            val settings = withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
                settingsRepo.settings.first()
            }
            if (settings == null) {
                LogBus.warn("重启失败：读取配置超时")
                return@launch
            }
            if (!settings.isConfigured) {
                LogBus.warn("重启失败：尚未配置 GitHub 仓库信息")
                return@launch
            }
            startServers()
        }
    }

    private fun startServers() {
        if (ServerController.state.value.running || ServerController.state.value.starting) {
            LogBus.warn("服务已在运行，忽略重复启动")
            return
        }
        ServerController.update { it.copy(starting = true, error = null) }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting), null, hiddenNotification))

        startJob?.cancel()
        startJob = scope.launch {
            try {
                LogBus.info("开始启动多账户服务…")
                val completed = withTimeoutOrNull(START_TIMEOUT_MS) {
                    // DataStore 读取单独超时
                    val settings = withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
                        settingsRepo.settings.first()
                    } ?: throw IllegalStateException("读取配置超时，请重试")
                    hiddenNotification = settings.hideNotification

                    val accounts = settings.accounts.filter { it.isConfigured }
                    if (accounts.isEmpty()) {
                        throw IllegalStateException("尚未配置任何账户，请先在 App 中添加账户并绑定仓库")
                    }

                    // 启动前网络预检：无可用网络时立即失败，不发起 GitHub 请求
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val active = cm.activeNetwork
                    val hasInternet = active != null && (
                        cm.getNetworkCapabilities(active)
                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    )
                    if (!hasInternet) {
                        throw IOException("当前无可用网络，无法连接 GitHub，请联网后重试")
                    }

                    // 端口冲突检测：相同协议端口不能有两个账户同时绑定（用户明确要求）
                    val usedPorts = mutableMapOf<Pair<ServerMode, Int>, String>()
                    accounts.forEach { a ->
                        val am = runCatching { ServerMode.valueOf(a.mode) }.getOrDefault(ServerMode.WEBDAV)
                        val key = am to a.port
                        usedPorts[key]?.let { other ->
                            throw BindException("端口 ${a.port} 已被账户「$other」占用，请为账户「${a.name}」换一个端口")
                        }
                        usedPorts[key] = a.name
                    }

                    val bindAddr = if (settings.loopbackOnly) "127.0.0.1" else null
                    val lanIp = if (settings.loopbackOnly) null else NetUtils.lanIpv4()

                    // 为每个账户起空壳监听（不建仓库；认证通过后经 AccountManager 懒激活）
                    accounts.forEach { a ->
                        val am = runCatching { ServerMode.valueOf(a.mode) }.getOrDefault(ServerMode.WEBDAV)
                        val credentials = { a.username to a.password }
                        when (am) {
                            ServerMode.WEBDAV -> {
                                val handler = WebDavHandler(
                                    accountId = a.id,
                                    repoProvider = { AccountManager.getRepoBlocking(a) },
                                    authUser = credentials,
                                )
                                val server = HttpServer(a.webdavPort, { exchange -> handler.handle(exchange) }, bindAddr)
                                server.start()
                                httpServers[a.id] = server
                                // 登记监听端口：FTP PASV 选数据端口时避让，防止数据端口撞上账户监听端口
                                AccountManager.registerPort(server.boundPort)
                                LogBus.info("账户「${a.name}」WebDAV 监听 ${server.boundPort}（休眠，认证后激活）")
                            }
                            ServerMode.FTP -> {
                                val server = FtpServer(
                                    port = a.ftpPort,
                                    accountId = a.id,
                                    repoProvider = { AccountManager.getRepoBlocking(a) },
                                    credentials = credentials,
                                    logger = { msg -> LogBus.info(msg) },
                                    bindAddress = bindAddr,
                                )
                                server.start()
                                ftpServers[a.id] = server
                                AccountManager.registerPort(server.boundPort)
                                LogBus.info("账户「${a.name}」FTP 监听 ${server.boundPort}（休眠，认证后激活）")
                            }
                        }
                    }

                    // 状态与通知：以激活账户为主展示
                    val main = settings.activeAccount ?: accounts.first()
                    val mainMode = runCatching { ServerMode.valueOf(main.mode) }.getOrDefault(ServerMode.WEBDAV)
                    ServerController.setState(
                        ServerController.State(
                            running = true,
                            starting = false,
                            mode = mainMode,
                            port = main.port,
                            lanIp = lanIp,
                            username = main.username,
                            password = main.password,
                            loopbackOnly = settings.loopbackOnly,
                        )
                    )
                    updateNotification(
                        getString(R.string.notif_running, mainMode.label, main.port),
                        endpointText(mainMode, lanIp, main.port),
                    )
                }
                // 整个启动流程超时兜底
                if (completed == null) {
                    throw IllegalStateException("启动超时（${START_TIMEOUT_MS / 1000} 秒），请检查网络后重试")
                }
            } catch (e: Exception) {
                val reason = startFailureReason(e)
                LogBus.error("启动失败：$reason")
                updateNotification(getString(R.string.notif_stopped), reason)
                shutdownServers()
                ServerController.setState(
                    ServerController.State(
                        running = false,
                        starting = false,
                        error = reason,
                    )
                )
                stopSelf()
            }
        }
    }

    /**
     * 把启动失败按原因分类翻译成用户能看懂的话，不再让 BindException /
     * ConnectException 等底层异常原文直接冒到通知/主界面。
     */
    private fun startFailureReason(e: Exception): String = when (e) {
        is BindException -> e.message ?: "端口被占用，请换一个端口"
        is GitHubApiException -> when (e.code) {
            401 -> "GitHub 令牌无效或已过期（401），请到仓库设置重新填写"
            403 -> "GitHub 拒绝访问（403）：可能触发了速率限制或令牌权限不足"
            404 -> "仓库不存在或无权限访问（404），请检查仓库设置"
            else -> "GitHub 返回 ${e.code}：${e.message}"
        }
        is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "无法连接 GitHub（网络不通或超时），请检查网络后重试"
        is IllegalStateException -> e.message ?: "配置不完整，请检查设置"
        else -> e.message ?: "未知错误"
    }

    private fun endpointText(mode: ServerMode, ip: String?, port: Int): String? {
        if (ip == null) return null
        return if (mode == ServerMode.WEBDAV) "http://$ip:$port/" else "ftp://$ip:$port/"
    }

    private fun shutdownServers(keepForeground: Boolean = false) {
        httpServers.values.forEach { s ->
            runCatching { s.stop() }.onFailure { LogBus.warn("停止 WebDAV 异常：${it.message}") }
        }
        httpServers.clear()
        ftpServers.values.forEach { s ->
            runCatching { s.stop() }.onFailure { LogBus.warn("停止 FTP 异常：${it.message}") }
        }
        ftpServers.clear()
        // 释放全部账户仓库缓存（含其全部缓存），避免停止服务后仍被引用
        AccountManager.releaseAll()
        if (ServerController.state.value.running || ServerController.state.value.starting) {
            LogBus.info("服务已停止")
        }
        ServerController.reset()
        // 重启场景（keepForeground=true）保留前台通知，避免出现"非前台间隙"
        if (!keepForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_server_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_server_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)

            // 静默渠道：隐藏通知时使用（Android 8+ 最低优先级，不响铃不震动、不常驻状态栏）
            val silent = NotificationChannel(
                CHANNEL_SILENT_ID,
                getString(R.string.channel_silent_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.channel_silent_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(silent)
        }
    }

    private fun buildNotification(title: String, content: String?, hidden: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getForegroundService(
            this,
            1,
            Intent(this, MountService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // 隐藏通知：用静默渠道，标题内容简化（前台服务通知系统强制存在，但用 MIN 渠道几乎不可见）
        val builder = if (hidden) {
            NotificationCompat.Builder(this, CHANNEL_SILENT_ID)
        } else {
            NotificationCompat.Builder(this, CHANNEL_ID)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (hidden) getString(R.string.app_name) else title)
            .setContentText(if (hidden) null else content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setPriority(if (hidden) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    private fun updateNotification(title: String, content: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(title, content, hiddenNotification))
    }
}