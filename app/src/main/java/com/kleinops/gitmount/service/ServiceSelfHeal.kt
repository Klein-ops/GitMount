package com.kleinops.gitmount.service

import android.content.Context
import com.kleinops.gitmount.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 服务自愈：进程被系统回收后（START_STICKY 重建），国产 ROM（如 MIUI）可能把
 * onStartCommand 的重新分发拖延数小时，导致「后台自启动一直卡在正在启动」。
 * 实测证据：进程被杀 → Application 立即重建，但 onStartCommand 迟了整整 1 小时
 * 才被系统调用；一旦执行，1 秒内即启动成功。所以不能干等系统调度。
 *
 * 本对象在 Application 启动后主动自检：只要用户持久化意图（serviceEnabled=true）
 * 且已配置可用挂载点，就立即拉起服务，把恢复时间从「数小时」缩短到「几秒」。
 *
 * 重试策略：Android 12+ 应用处于后台时启动前台服务可能被系统拒绝
 * （ForegroundServiceStartNotAllowedException，MountService.start 已捕获）。
 * 因此首次失败后每隔一段时间重试，捕捉系统放行窗口（用户解锁/打开应用/
 * 系统调度恢复等），直到服务真正启动或放弃。
 *
 * 安全边界：
 * - 尊重 serviceEnabled：用户手动停止后（写 false）每次重试都会重新检查并退出；
 * - 防重入：服务已在 running/starting 时不重复拉起；
 * - 不崩溃：所有启动异常由 MountService.start 收敛为日志。
 */
object ServiceSelfHeal {

    /** 首次自检延迟：等 Application / DataStore 完全就绪后执行 */
    private const val FIRST_DELAY_MS = 3_000L

    /** 失败重试间隔 */
    private const val RETRY_DELAY_MS = 30_000L

    /** 最大尝试次数（首检 1 次 + 重试 9 次，共约 4.5 分钟窗口；超过即等待系统调度兜底） */
    private const val MAX_ATTEMPTS = 10

    /** DataStore 读取超时兜底（本地文件正常毫秒级） */
    private const val SETTINGS_READ_TIMEOUT_MS = 5_000L

    /** 触发自检（Application.onCreate 调用；进程重建后内存状态全新，天然只执行一次） */
    fun schedule(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(FIRST_DELAY_MS)
            repeat(MAX_ATTEMPTS) { attempt ->
                val st = ServerController.state.value
                // 系统抢先 dispatch / 用户在前台打开应用已拉起：无需自愈
                if (st.running || st.starting) {
                    LogBus.info("自检恢复：服务已处于运行/启动中，无需恢复")
                    return@launch
                }
                if (!shouldResume(app)) return@launch // 无运行意图或无可用挂载点：静默退出
                if (attempt > 0) {
                    LogBus.info("自检恢复：第 ${attempt + 1} 次尝试拉起服务")
                }
                MountService.start(app)
                // 留出启动时间窗口；下轮检查是否已成功 / 是否被用户停止
                delay(RETRY_DELAY_MS)
            }
            LogBus.warn("自检恢复：多次尝试后仍未启动（系统可能限制后台启动），等待系统调度兜底")
        }
    }

    /** 检查持久化意图：用户期望服务运行 + 至少一个可用挂载点 */
    private suspend fun shouldResume(app: Context): Boolean {
        val settings = withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) {
            runCatching { SettingsRepository(app).settings.first() }.getOrNull()
        } ?: run {
            LogBus.warn("自检恢复跳过：读取配置超时")
            return false
        }
        // 用户从未开启或已手动停止：保持停止，绝不自动拉起（避免与停止意图冲突）
        if (!settings.serviceEnabled) return false
        // 没有任何已启用且可用的挂载点：拉起了也起不来
        if (!settings.isConfigured) return false
        return true
    }
}