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

/**
 * 单个账户配置（多账户动态激活架构）。
 * 每账户独立持有：GitHub 令牌、绑定仓库、服务协议、端口、局域网访问账号密码。
 * token 字段存的是 Keystore 加密后的密文（见 TokenCrypto）。
 */
data class AccountConfig(
    val id: String,
    val name: String,
    val token: String,
    val owner: String,
    val repoName: String,
    val branch: String = "main",
    val mode: String = "WEBDAV",
    val ftpPort: Int = 2121,
    val webdavPort: Int = 8080,
    val username: String = "gitmount",
    val password: String = "",
) {
    val isConfigured: Boolean
        get() = token.isNotBlank() && owner.isNotBlank() && repoName.isNotBlank()

    val repoLabel: String
        get() = if (owner.isBlank() && repoName.isBlank()) "(未绑定仓库)" else "$owner/$repoName"

    /** 当前生效的端口（按协议） */
    val port: Int
        get() = if (mode == "FTP") ftpPort else webdavPort
}

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