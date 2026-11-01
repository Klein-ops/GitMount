package com.kleinops.gitmount.service

import com.kleinops.gitmount.data.LogEntry
import com.kleinops.gitmount.data.ServerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 内存日志总线：保留最近 300 条，供 UI 与排查使用 */
object LogBus {
    private const val MAX = 300
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    /**
     * SimpleDateFormat 非线程安全，而日志会从 FTP 会话线程、HTTP 工作线程等多处写入，
     * 因此用 ThreadLocal 隔离，避免时间串错乱。
     */
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun info(msg: String) = add("INFO", msg)
    fun warn(msg: String) = add("WARN", msg)
    fun error(msg: String) = add("ERROR", msg)

    fun add(level: String, msg: String) {
        val time = timeFormat.get()?.format(Date()) ?: "--:--:--"
        val entry = LogEntry(time, level, msg)
        // 用 update 做原子读-改-写；直接赋值在并发下会丢失其他线程刚写入的条目
        _logs.update { current -> (listOf(entry) + current).take(MAX) }
        // 同步落盘（异步写），供用户直接读取日志文件排查问题
        FileLogger.append(level, msg)
    }

    fun clear() {
        _logs.value = emptyList()
    }
}

/** 服务运行时状态（UI 与前台服务共享） */
object ServerController {

    data class State(
        val running: Boolean = false,
        val starting: Boolean = false,
        val mode: ServerMode = ServerMode.WEBDAV,
        val port: Int = 0,
        val lanIp: String? = null,
        val username: String = "",
        val password: String = "",
        val error: String? = null,
        /** true = 仅监听本机回环，不暴露局域网 */
        val loopbackOnly: Boolean = false,
        /** 当前活跃会话数（FTP 控制连接 + WebDAV 连接） */
        val activeSessions: Int = 0,
        /** 累计通过本服务传输的字节数（上传 + 下载，含目录列表/元数据请求体） */
        val totalBytes: Long = 0L,
        /** 累计上传字节数 */
        val uploadedBytes: Long = 0L,
        /** 累计下载字节数 */
        val downloadedBytes: Long = 0L,
    ) {
        /** 局域网访问地址（仅非回环模式时存在） */
        val endpoint: String?
            get() = when {
                !running || lanIp == null || loopbackOnly -> null
                mode == ServerMode.WEBDAV -> "http://$lanIp:$port/"
                else -> "ftp://$lanIp:$port/"
            }

        /** 本机回环访问地址（loopback 模式下地址一致，仅 127.0.0.1） */
        val loopbackEndpoint: String?
            get() = when {
                !running -> null
                mode == ServerMode.WEBDAV -> "http://127.0.0.1:$port/"
                else -> "ftp://127.0.0.1:$port/"
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun setState(newState: State) {
        _state.value = newState
    }

    fun update(block: (State) -> State) {
        _state.update(block)
    }

    // ---------- 会话与流量统计（FTP/WebDAV 服务端打点） ----------

    /** 会话开始（FTP 控制连接或 WebDAV 连接建立） */
    fun sessionOpened() = update { it.copy(activeSessions = it.activeSessions + 1) }

    /** 会话结束 */
    fun sessionClosed() = update { it.copy(activeSessions = (it.activeSessions - 1).coerceAtLeast(0)) }

    /** 累计传输字节（上传/下载分开计，同时累加总量） */
    fun addTraffic(uploaded: Long, downloaded: Long) {
        val u = uploaded.coerceAtLeast(0)
        val d = downloaded.coerceAtLeast(0)
        val total = u + d
        if (total == 0L) return
        update {
            it.copy(
                totalBytes = it.totalBytes + total,
                uploadedBytes = it.uploadedBytes + u,
                downloadedBytes = it.downloadedBytes + d,
            )
        }
    }

    fun reset() {
        _state.value = State()
    }
}