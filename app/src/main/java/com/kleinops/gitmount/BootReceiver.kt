package com.kleinops.gitmount

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kleinops.gitmount.data.SettingsRepository
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 开机自启动：监听 BOOT_COMPLETED，按设置自动拉起 FTP / WebDAV 服务。
 *
 * 说明：
 * - 仅当「自启动」开关开启、且已配置仓库和访问账号时才尝试启动。
 * - 开机后网络可能尚未就绪（尤其 Wi-Fi），这里延迟 15 秒再启动，给系统
 *   连接网络留出时间；若仍失败会写入日志，用户在 App 内可手动启动。
 * - Android 8+ 对后台启动前台服务有限制，但 BOOT_COMPLETED 广播属于官方
 *   豁免场景，可正常 startForegroundService。
 * - 国产 ROM 可能限制开机自启，需要用户把 App 加入系统自启动白名单。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        LogBus.info("收到开机广播，准备按设置自启服务")

        // goAsync + 后台线程：onReceive 在主线程且 10 秒内必须返回，
        // DataStore 读取 + 15 秒延迟启动都放到后台线程执行。
        val pending = goAsync()
        Thread {
            try {
                val settings = runCatching {
                    runBlocking { SettingsRepository(context.applicationContext).settings.first() }
                }.getOrNull() ?: return@Thread
                if (!settings.autoStart) return@Thread
                if (!settings.isConfigured) return@Thread

                // 开机后网络可能尚未就绪（尤其 Wi-Fi），延迟 15 秒再启动
                try {
                    Thread.sleep(15_000)
                } catch (_: InterruptedException) {
                }
                // 全局启动：一次拉起全部已启用挂载点的服务（mode/port 由各挂载点配置决定）
                MountService.start(context.applicationContext)
                LogBus.info("开机自启动：已请求启动全部已启用挂载点的服务")
            } catch (e: Exception) {
                LogBus.error("开机自启动失败：${e.message}")
            } finally {
                pending.finish()
            }
        }.apply {
            name = "boot-autostart"
            isDaemon = true
        }.start()
    }
}