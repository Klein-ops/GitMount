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
    ) {
        /** 供文件管理器填写的完整地址 */
        val endpoint: String?
            get() = when {
                !running || lanIp == null -> null
                mode == ServerMode.WEBDAV -> "http://$lanIp:$port/"
                else -> "ftp://$lanIp:$port/"
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

    fun reset() {
        _state.value = State()
    }
}