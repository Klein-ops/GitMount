package com.kleinops.gitmount.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kleinops.gitmount.MainActivity
import com.kleinops.gitmount.R
import com.kleinops.gitmount.data.GitHubClient
import com.kleinops.gitmount.data.GitRepository
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.data.SettingsRepository
import com.kleinops.gitmount.service.http.HttpServer
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
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: HttpServer? = null
    private var ftpServer: FtpServer? = null
    private var startJob: Job? = null
    private lateinit var settingsRepo: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 若本服务是通过 startForegroundService 拉起的，必须先 startForeground
                // 才能安全退出，否则系统会抛 "did not then call Service.startForeground()"。
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_stopped), null))
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
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting), null))

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

                val client = GitHubClient(settings.token)
                val repo = GitRepository(client, settings.owner, settings.repoName, settings.branch)

                // 启动前做一次真实连通性校验，避免“显示已启动但实际不可用”
                val probes = repo.visibleList("/")
                LogBus.info("仓库连接正常，根目录 ${probes.size} 项")

                val credentials = { settings.username to settings.password }
                val lanIp = NetUtils.lanIpv4()

                val actualPort = when (mode) {
                    ServerMode.WEBDAV -> {
                        val handler = WebDavHandler(repo) { credentials() }
                        val server = HttpServer(port) { exchange -> handler.handle(exchange) }
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
                    )
                )
                updateNotification(
                    getString(R.string.notif_running, mode.label, actualPort),
                    endpointText(mode, lanIp, actualPort),
                )
            } catch (e: Exception) {
                LogBus.error("启动失败：${e.message}")
                updateNotification(getString(R.string.notif_stopped), e.message)
                // 先清理（reset 会清空状态），再把错误写回去，否则 UI 永远看不到失败原因
                shutdownServers()
                ServerController.setState(
                    ServerController.State(
                        running = false,
                        starting = false,
                        mode = mode,
                        port = port,
                        error = e.message ?: "未知错误",
                    )
                )
                stopSelf()
            }
        }
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_server_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_server_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String?): Notification {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    private fun updateNotification(title: String, content: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(title, content))
    }
}