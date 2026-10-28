package com.kleinops.gitmount.service

import com.kleinops.gitmount.data.LogEntry
import com.kleinops.gitmount.data.ServerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 内存日志总线：保留最近 300 条，供 UI 与排查使用 */
object LogBus {
    private const val MAX = 300
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun info(msg: String) = add("INFO", msg)
    fun warn(msg: String) = add("WARN", msg)
    fun error(msg: String) = add("ERROR", msg)

    fun add(level: String, msg: String) {
        val entry = LogEntry(timeFormat.format(Date()), level, msg)
        // 最新的在最前，便于直接浏览
        _logs.value = (listOf(entry) + _logs.value).take(MAX)
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
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = State()
    }
}