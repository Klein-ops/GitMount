package com.kleinops.gitmount.service

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 文件日志：把 LogBus 的日志同步落盘到应用外部私有目录
 * /storage/emulated/0/Android/data/<包名>/files/logs/gitmount_yyyy-MM-dd.log
 *
 * - 按天滚动文件，超过 KEEP_DAYS 天的旧日志自动清理
 * - 写盘走单线程执行器，避免 FTP/HTTP 多线程并发写坏文件，也不阻塞业务线程
 * - 供用户测试后直接读取日志排查问题（无需连 adb）
 */
object FileLogger {

    private const val LOG_DIR = "logs"
    private const val FILE_PREFIX = "gitmount_"
    private const val KEEP_DAYS = 3
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "file-logger").apply { isDaemon = true }
    }

    @Volatile
    private var logDir: File? = null

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 必须在 Application.onCreate 里初始化（需要 Context 定位外部私有目录） */
    fun init(context: Context) {
        if (logDir != null) return
        val base = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            // /storage/emulated/0/Android/data/<pkg>/files/logs
            File(context.getExternalFilesDir(null), LOG_DIR)
        } else {
            // 外部存储不可用时退回应用内部私有目录（fallback）
            File(context.filesDir, LOG_DIR)
        }
        base.mkdirs()
        logDir = base
        cleanupOldLogs(base)
    }

    /** 异步追加一行日志；文件超过上限或跨天时自动滚动 */
    fun append(level: String, message: String) {
        val dir = logDir ?: return
        val line = "${timeFormat.format(Date())} [$level] $message"
        executor.execute {
            try {
                val today = dayFormat.format(Date())
                val file = File(dir, "$FILE_PREFIX$today.log")
                if (file.length() > MAX_FILE_BYTES) {
                    rotate(file, today)
                }
                FileWriter(file, true).use { it.write(line + "\n") }
            } catch (_: Exception) {
                // 日志写盘失败不影响业务，静默忽略
            }
        }
    }

    /** 当日文件超长时改名归档，新日志从新文件开始 */
    private fun rotate(file: File, today: String) {
        var i = 1
        while (File(file.parentFile, "$FILE_PREFIX$today.$i.log").exists()) i++
        file.renameTo(File(file.parentFile, "$FILE_PREFIX$today.$i.log"))
    }

    /** 删除超过保留天数的旧日志 */
    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600_000L
        dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }?.forEach { f ->
            try {
                val dateStr = f.name.removePrefix(FILE_PREFIX).substringBefore('.')
                val ts = dayFormat.parse(dateStr)?.time ?: 0L
                if (ts < cutoff) f.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 返回当前可分享的最新日志文件（今天的日志文件，若不存在则尝试昨天）。
     * 供主界面「分享日志」一键导出排查问题。
     */
    fun shareableLogFile(): File? {
        val dir = logDir ?: return null
        val today = File(dir, "$FILE_PREFIX${dayFormat.format(Date())}.log")
        if (today.exists() && today.length() > 0) return today
        // 今天还没有日志（例如刚开机未触发过），退回最近一天
        return dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) }
            ?.maxByOrNull { it.lastModified() }
            ?.takeIf { it.length() > 0 }
    }
}