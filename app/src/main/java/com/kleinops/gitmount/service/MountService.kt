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
import com.kleinops.gitmount.data.GitHubClient
import com.kleinops.gitmount.data.GitRepository
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
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_PORT = "extra_port"

        /** 由 UI 调用：启动服务 */
        fun start(context: Context, mode: ServerMode, port: Int) {
            val intent = Intent(context, MountService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: HttpServer? = null
    private var ftpServer: FtpServer? = null
    private var startJob: Job? = null
    private lateinit var settingsRepo: SettingsRepository

    /** 当前持有的仓库对象：供 onTrimMemory 释放缓存（空引用时直接跳过） */
    @Volatile
    private var repo: GitRepository? = null

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
                // 若本服务是通过 startForegroundService 拉起的，必须先 startForeground
                // 才能安全退出，否则系统会抛 "did not then call Service.startForeground()"。
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_stopped), null, hiddenNotification))
                shutdownServers()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val mode = intent.getStringExtra(EXTRA_MODE)
                    ?.let { runCatching { ServerMode.valueOf(it) }.getOrNull() }
                    ?: ServerMode.WEBDAV
                val port = intent.getIntExtra(EXTRA_PORT, defaultPort(mode))
                startServers(mode, port)
            }
            else -> {
                // 系统重建服务：没有配置就不重启，避免异常循环
                LogBus.warn("服务被系统重建，等待用户重新启动")
                stopSelf()
                return START_NOT_STICKY
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
        val r = repo
        if (r != null && level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            r.forceRelease()
            LogBus.info("系统内存压力回调（level=$level），已释放仓库缓存")
        }
    }

    override fun onDestroy() {
        shutdownServers()
        scope.cancel()
        super.onDestroy()
    }

    private fun defaultPort(mode: ServerMode): Int =
        if (mode == ServerMode.FTP) 2121 else 8080

    private fun startServers(mode: ServerMode, port: Int) {
        if (ServerController.state.value.running || ServerController.state.value.starting) {
            LogBus.warn("服务已在运行，忽略重复启动")
            return
        }
        ServerController.update { it.copy(starting = true, error = null) }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting), null, hiddenNotification))

        startJob?.cancel()
        startJob = scope.launch {
            try {
                val settings = settingsRepo.settings.first()
                if (!settings.isConfigured) {
                    throw IllegalStateException("尚未配置 GitHub 仓库信息")
                }
                if (settings.username.isBlank() || settings.password.isBlank()) {
                    throw IllegalStateException("请先设置访问用用户名与密码")
                }
                hiddenNotification = settings.hideNotification

                // 启动前先做端口占用预检：被占用时直接给出明确提示，
                // 不再等到 bind 失败后靠异常文本猜测（旧实现在这步会卡在
                // "无法解析的异常信息"，用户不知道是端口问题）。
                val bindAddr = if (settings.loopbackOnly) "127.0.0.1" else null
                if (!NetUtils.portAvailable(port, bindAddr)) {
                    throw BindException("端口 $port 已被占用，请换一个端口或在设置里修改")
                }

                val client = GitHubClient(settings.token)
                val repo = GitRepository(client, settings.owner, settings.repoName, settings.branch)
                this@MountService.repo = repo

                // 启动前做一次真实连通性校验，避免“显示已启动但实际不可用”
                val probes = repo.visibleList("/")
                LogBus.info("仓库连接正常，根目录 ${probes.size} 项")

                val credentials = { settings.username to settings.password }
                // loopbackOnly=true 时只监听 127.0.0.1，不暴露局域网
                val lanIp = if (settings.loopbackOnly) null else NetUtils.lanIpv4()

                val actualPort = when (mode) {
                    ServerMode.WEBDAV -> {
                        val handler = WebDavHandler(repo) { credentials() }
                        val server = HttpServer(port, { exchange -> handler.handle(exchange) }, bindAddr)
                        server.start()
                        httpServer = server
                        LogBus.info("WebDAV 服务已启动：http://$lanIp:${server.boundPort}/")
                        server.boundPort
                    }
                    ServerMode.FTP -> {
                        val server = FtpServer(
                            port = port,
                            repo = repo,
                            credentials = credentials,
                            logger = { msg -> LogBus.info(msg) },
                            bindAddress = bindAddr,
                        )
                        server.start()
                        ftpServer = server
                        LogBus.info("FTP 服务已启动：ftp://$lanIp:${server.boundPort}/")
                        server.boundPort
                    }
                }

                ServerController.setState(
                    ServerController.State(
                        running = true,
                        starting = false,
                        mode = mode,
                        port = actualPort,
                        lanIp = lanIp,
                        username = settings.username,
                        password = settings.password,
                        loopbackOnly = settings.loopbackOnly,
                    )
                )
                updateNotification(
                    getString(R.string.notif_running, mode.label, actualPort),
                    endpointText(mode, lanIp, actualPort),
                )
            } catch (e: Exception) {
                val reason = startFailureReason(e)
                LogBus.error("启动失败：$reason")
                updateNotification(getString(R.string.notif_stopped), reason)
                // 先清理（reset 会清空状态），再把错误写回去，否则 UI 永远看不到失败原因
                shutdownServers()
                ServerController.setState(
                    ServerController.State(
                        running = false,
                        starting = false,
                        mode = mode,
                        port = port,
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

    private fun shutdownServers() {
        try {
            httpServer?.stop()
        } catch (e: Exception) {
            LogBus.warn("停止 WebDAV 异常：${e.message}")
        }
        httpServer = null
        try {
            ftpServer?.stop()
        } catch (e: Exception) {
            LogBus.warn("停止 FTP 异常：${e.message}")
        }
        ftpServer = null
        // 释放仓库对象（含其全部缓存），避免停止服务后仍被 Service 持有
        repo?.forceRelease()
        repo = null
        if (ServerController.state.value.running || ServerController.state.value.starting) {
            LogBus.info("服务已停止")
        }
        ServerController.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
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