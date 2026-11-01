package com.kleinops.gitmount.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kleinops.gitmount.data.AppSettings
import com.kleinops.gitmount.data.GitHubApiException
import com.kleinops.gitmount.data.GitHubClient
import com.kleinops.gitmount.data.GitRepository
import com.kleinops.gitmount.data.LogEntry
import com.kleinops.gitmount.data.RepoBrief
import com.kleinops.gitmount.data.RepoInfo
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.data.SettingsRepository
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.ServerController
import java.io.IOException
import kotlinx.coroutines.Dispatchers
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

/** 扫描令牌名下仓库的状态 */
sealed interface ScanState {
    data object Idle : ScanState
    data object Working : ScanState
    data class Success(val login: String, val repos: List<RepoBrief>) : ScanState
    data class Error(val message: String) : ScanState
}

/** 仓库容量/历史清理状态 */
sealed interface RepoOpState {
    data object Idle : RepoOpState
    data object Loading : RepoOpState
    data object ClearingHistory : RepoOpState
    data class Success(val info: RepoInfo) : RepoOpState
    data class Error(val message: String) : RepoOpState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val serverState: StateFlow<ServerController.State> = ServerController.state
    val logs: StateFlow<List<LogEntry>> = LogBus.logs

    private val _setupState = MutableStateFlow<SetupState>(SetupState.Idle)
    val setupState: StateFlow<SetupState> = _setupState

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    private val _repoOpState = MutableStateFlow<RepoOpState>(RepoOpState.Idle)
    val repoOpState: StateFlow<RepoOpState> = _repoOpState

    /** 扫描仓库结果缓存：同一 token 5 分钟内不重复请求（避免反复开关对话框打爆配额） */
    private data class ScanCache(val token: String, val login: String, val repos: List<RepoBrief>, val at: Long)
    private var scanCache: ScanCache? = null
    private val SCAN_CACHE_TTL_MS = 5L * 60 * 1000

    fun clearRepoOpState() {
        _repoOpState.value = RepoOpState.Idle
    }

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    /** 校验 token 并返回登录名（不落盘） */
    fun verifyToken(token: String, onResult: (Result<String>) -> Unit) {
        // GitHubClient 全部是同步阻塞调用，必须切到 IO 线程，
        // 否则在默认主线程执行会抛 NetworkOnMainThreadException。
        viewModelScope.launch(Dispatchers.IO) {
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

    /** 校验 token 并扫描该账号名下的仓库（用于选择“绑定已有仓库”） */
    fun scanRepos(token: String) {
        val t = token.trim()
        if (t.isBlank()) {
            _scanState.value = ScanState.Error("请填写 GitHub 访问令牌")
            return
        }
        // 同一 token 5 分钟内直接复用上次扫描结果，避免反复开关对话框打爆配额
        scanCache?.takeIf {
            it.token == t && System.currentTimeMillis() - it.at < SCAN_CACHE_TTL_MS
        }?.let {
            _scanState.value = ScanState.Success(it.login, it.repos)
            return
        }
        _scanState.value = ScanState.Working
        // OkHttp 同步调用必须在 IO 线程执行，避免 NetworkOnMainThreadException
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = GitHubClient(t)
                val login = client.getUser()
                if (login.isBlank()) throw IllegalStateException("令牌无效")
                LogBus.info("Token 校验通过，账号 $login")
                val repos = client.listUserRepos()
                scanCache = ScanCache(t, login, repos, System.currentTimeMillis())
                _scanState.value = ScanState.Success(login, repos)
            } catch (e: Exception) {
                val msg = friendly(e)
                LogBus.error("扫描仓库失败：$msg")
                _scanState.value = ScanState.Error(msg)
            }
        }
    }

    /** 查询仓库容量信息（size 包含提交历史；私有仓库配额 500MB，公开仓库无配额） */
    fun loadRepoInfo() {
        val s = settings.value
        if (s.token.isBlank() || s.owner.isBlank() || s.repoName.isBlank()) {
            _repoOpState.value = RepoOpState.Error("尚未配置仓库")
            return
        }
        _repoOpState.value = RepoOpState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = GitHubClient(s.token).getRepoInfo(s.owner, s.repoName)
                    ?: throw IOException("仓库不存在或无权访问")
                _repoOpState.value = RepoOpState.Success(info)
            } catch (e: Exception) {
                _repoOpState.value = RepoOpState.Error(friendly(e))
            }
        }
    }

    /**
     * 清空提交历史（孤儿提交重写：文件全部保留，历史从 N 个 commit 变成 1 个）。
     * 容量数字由 GitHub 后台 GC 延迟回收，不会立即下降。
     */
    fun clearRepoHistory() {
        val s = settings.value
        if (s.token.isBlank() || s.owner.isBlank() || s.repoName.isBlank()) {
            _repoOpState.value = RepoOpState.Error("尚未配置仓库")
            return
        }
        _repoOpState.value = RepoOpState.ClearingHistory
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repo = GitRepository(GitHubClient(s.token), s.owner, s.repoName, s.branch)
                repo.clearHistory()
                // 清完历史立即重新拉取容量（此时 size 可能仍含旧的不可达对象，如实展示）
                val info = GitHubClient(s.token).getRepoInfo(s.owner, s.repoName)
                _repoOpState.value = if (info != null) RepoOpState.Success(info) else RepoOpState.Error("历史已清空，但容量信息暂时不可用")
            } catch (e: Exception) {
                _repoOpState.value = RepoOpState.Error(friendly(e))
            }
        }
    }

    /**
     * 绑定已有仓库（仓库必须已存在，走一套统一的保存逻辑）。
     * @param token 用户输入的令牌（尚未保存时也能直接用）
     */
    fun bindRepo(token: String, owner: String, repoName: String, branch: String = "") {
        val t = token.trim()
        val o = owner.trim()
        val r = repoName.trim()
        if (t.isBlank()) {
            _setupState.value = SetupState.Error("请先填写 GitHub 访问令牌")
            return
        }
        if (o.isBlank() || r.isBlank()) {
            _setupState.value = SetupState.Error("仓库信息不完整")
            return
        }
        _setupState.value = SetupState.Working
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 用 token 校验一次并拿到真实默认分支，避免存错分支导致后续全部读写失败
                val actualBranch = branch.ifBlank {
                    val b = GitHubClient(t).getDefaultBranch(o, r)
                    b ?: throw GitHubApiException(404, "仓库 $o/$r 不存在或无权访问")
                }
                settingsRepo.saveRepo(t, o, r, actualBranch)
                LogBus.info("已绑定仓库 $o/$r（$actualBranch）")
                _setupState.value = SetupState.Success("已绑定：$o/$r（$actualBranch）")
            } catch (e: Exception) {
                val msg = friendly(e)
                LogBus.error("绑定失败：$msg")
                _setupState.value = SetupState.Error(msg)
            }
        }
    }

    /**
     * 新建仓库并立即绑定（创建完成后走与绑定完全相同的保存逻辑）。
     * @param token 用户输入的令牌（尚未保存时也能直接用）
     * @param isPrivate true = 私有，false = 公开
     */
    fun createAndBindRepo(token: String, name: String, isPrivate: Boolean) {
        val t = token.trim()
        val repoName = name.trim()
        if (t.isBlank()) {
            _setupState.value = SetupState.Error("请先填写 GitHub 访问令牌")
            return
        }
        if (repoName.isBlank()) {
            _setupState.value = SetupState.Error("请填写仓库名称")
            return
        }
        _setupState.value = SetupState.Working
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = GitHubClient(t)
                val login = client.getUser()
                if (login.isBlank()) throw IllegalStateException("令牌无效")
                LogBus.info("正在创建${if (isPrivate) "私有" else "公开"}仓库 $login/$repoName …")
                client.createRepo(repoName, isPrivate)
                LogBus.info("仓库创建成功，读取默认分支…")
                // 新建仓库后 GitHub 可能短暂 404（auto_init 尚未完成），重试几次
                val resolvedBranch = awaitDefaultBranch(client, login, repoName) ?: "main"
                settingsRepo.saveRepo(t, login, repoName, resolvedBranch)
                // 创建后立刻刷新列表，让新仓库出现在仓库列表里
                runCatching { client.listUserRepos() }.getOrNull()?.let { repos ->
                    scanCache = ScanCache(t, login, repos, System.currentTimeMillis())
                    _scanState.value = ScanState.Success(login, repos)
                }
                _setupState.value = SetupState.Success(
                    "已创建并绑定：$login/$repoName（${if (isPrivate) "私有" else "公开"}，$resolvedBranch）"
                )
            } catch (e: Exception) {
                val msg = friendly(e)
                LogBus.error("创建仓库失败：$msg")
                _setupState.value = SetupState.Error(msg)
            }
        }
    }

    fun saveServerSettings(
        mode: ServerMode,
        ftpPort: Int,
        webdavPort: Int,
        username: String,
        password: String,
        loopbackOnly: Boolean,
        hideNotification: Boolean,
        autoStart: Boolean,
        hideFromRecents: Boolean,
    ) {
        if (username.isBlank() || password.isBlank()) {
            LogBus.warn("访问账号或密码为空，服务启动后无法登录")
        }
        viewModelScope.launch {
            settingsRepo.saveServer(
                mode.name, ftpPort, webdavPort, username, password,
                loopbackOnly, hideNotification, autoStart, hideFromRecents,
            )
            LogBus.info(
                "服务设置已保存：${mode.label} / 端口 ${if (mode == ServerMode.FTP) ftpPort else webdavPort} / " +
                    "${if (loopbackOnly) "仅本机" else "局域网"} / " +
                    "${if (hideNotification) "隐藏通知" else "常驻通知"} / " +
                    "${if (hideFromRecents) "后台隐藏" else "任务列表可见"} / " +
                    "${if (autoStart) "自启动开" else "自启动关"}"
            )
        }
    }

    fun clearSetupMessage() {
        _setupState.value = SetupState.Idle
    }

    fun clearScanState() {
        _scanState.value = ScanState.Idle
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