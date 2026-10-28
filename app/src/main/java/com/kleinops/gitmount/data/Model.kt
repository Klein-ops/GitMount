package com.kleinops.gitmount.data

/** 服务协议类型 */
enum class ServerMode(val label: String) {
    WEBDAV("WebDAV"),
    FTP("FTP"),
}

/** GitHub 仓库配置（token 仅存内存与加固后的 DataStore，绝不写入 git 仓库） */
data class RepoConfig(
    val token: String,
    val owner: String,
    val repoName: String,
    val branch: String = "main",
)

/** 服务运行状态 */
data class ServerStatus(
    val running: Boolean = false,
    val mode: ServerMode = ServerMode.WEBDAV,
    val host: String? = null,
    val port: Int = 0,
    val username: String = "gitmount",
    val password: String = "",
    val rootUrl: String? = null,
)

/** GitHub 目录条目 */
data class GitEntry(
    val name: String,
    val path: String,
    val type: String, // "file" | "dir"
    val size: Long,
    val sha: String,
)

/** UI 事件日志 */
data class LogEntry(
    val time: String,
    val level: String, // INFO / WARN / ERROR
    val message: String,
)