package com.kleinops.gitmount.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kleinops.gitmount.data.AppSettings
import com.kleinops.gitmount.data.GitHubApiException
import com.kleinops.gitmount.data.GitHubClient
import com.kleinops.gitmount.data.LogEntry
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.data.SettingsRepository
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.ServerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 仓库初始化向导状态 */
sealed interface SetupState {
    data object Idle : SetupState
    data object Working : SetupState
    data class Success(val message: String) : SetupState
    data class Error(val message: String) : SetupState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val serverState: StateFlow<ServerController.State> = ServerController.state
    val logs: StateFlow<List<LogEntry>> = LogBus.logs

    private val _setupState = MutableStateFlow<SetupState>(SetupState.Idle)
    val setupState: StateFlow<SetupState> = _setupState

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    /** 校验 token 并返回登录名（不落盘） */
    fun verifyToken(token: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            _testing.value = true
            val result = runCatching { GitHubClient(token).getUser() }
            _testing.value = false
            if (result.isSuccess) {
                LogBus.info("Token 校验通过，账号 ${result.getOrNull()}")
            } else {
                LogBus.error("Token 校验失败：${friendly(result.exceptionOrNull())}")
            }
            onResult(result)
        }
    }

    /**
     * 一键初始化：
     * 1) 校验 token
     * 2) 仓库不存在则创建私有仓库
     * 3) 保存配置
     */
    fun setupRepo(token: String, ownerInput: String, repoInput: String, branch: String) {
        if (token.isBlank()) {
            _setupState.value = SetupState.Error("请填写 GitHub 访问令牌")
            return
        }
        if (repoInput.isBlank()) {
            _setupState.value = SetupState.Error("请填写仓库名称")
            return
        }
        _setupState.value = SetupState.Working
        viewModelScope.launch {
            try {
                val client = GitHubClient(token.trim())
                val login = client.getUser()
                if (login.isBlank()) throw IllegalStateException("令牌无效")
                LogBus.info("Token 校验通过，账号 $login")

                val owner = ownerInput.ifBlank { login }.trim()
                val repo = repoInput.trim()

                var resolvedBranch = client.getDefaultBranch(owner, repo)
                if (resolvedBranch == null) {
                    LogBus.info("仓库 $owner/$repo 不存在，正在创建私有仓库…")
                    client.createPrivateRepo(repo)
                    LogBus.info("私有仓库创建成功")
                    // 关键：GitHub 账号的默认分支可能是 main 也可能是 master，
                    // 不能想当然用 "main"，必须回读仓库真实的默认分支。
                    resolvedBranch = awaitDefaultBranch(client, owner, repo)
                        ?: branch.ifBlank { "main" }
                } else {
                    LogBus.info("仓库 $owner/$repo 已存在，默认分支 $resolvedBranch")
                }

                settingsRepo.saveRepo(token.trim(), owner, repo, resolvedBranch)
                _setupState.value = SetupState.Success("配置完成：$owner/$repo（$resolvedBranch）")
            } catch (e: Exception) {
                val msg = friendly(e)
                LogBus.error("初始化失败：$msg")
                _setupState.value = SetupState.Error(msg)
            }
        }
    }

    fun saveServerSettings(mode: ServerMode, ftpPort: Int, webdavPort: Int, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            LogBus.warn("访问账号或密码为空，服务启动后无法登录")
        }
        viewModelScope.launch {
            settingsRepo.saveServer(mode.name, ftpPort, webdavPort, username, password)
            LogBus.info("服务设置已保存：${mode.label} / 端口 ${if (mode == ServerMode.FTP) ftpPort else webdavPort}")
        }
    }

    fun clearSetupMessage() {
        _setupState.value = SetupState.Idle
    }

    fun clearLogs() = LogBus.clear()

    /**
     * 新建仓库后立即读取默认分支时，GitHub 可能尚未完成初始化（短暂 404）。
     * 这里做有限次重试，避免把分支名存错导致后续所有读写失败。
     */
    private suspend fun awaitDefaultBranch(
        client: GitHubClient,
        owner: String,
        repo: String,
    ): String? {
        repeat(5) { attempt ->
            val branch = client.getDefaultBranch(owner, repo)
            if (branch != null) return branch
            if (attempt < 4) kotlinx.coroutines.delay(600)
        }
        return null
    }

    private fun friendly(e: Throwable?): String {
        if (e == null) return "未知错误"
        return when (e) {
            is GitHubApiException -> when (e.code) {
                401 -> "令牌无效或已过期（401）"
                403 -> "权限不足或触发速率限制（403）"
                404 -> "仓库不存在或无权访问（404）"
                422 -> "参数不合法（422）：${e.message}"
                else -> "GitHub 返回 ${e.code}：${e.message}"
            }
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}