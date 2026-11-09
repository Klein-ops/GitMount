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
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.service.ServerController
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** GitHub API 本小时剩余调用余额查询状态 */
sealed interface RateLimitState {
    data object Idle : RateLimitState
    data object Loading : RateLimitState
    data class Success(val limit: Int, val remaining: Int) : RateLimitState
    data class Error(val message: String) : RateLimitState
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

    private val _rateLimitState = MutableStateFlow<RateLimitState>(RateLimitState.Idle)
    val rateLimitState: StateFlow<RateLimitState> = _rateLimitState

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

    /**
     * 查询仓库容量信息（size 包含提交历史；私有仓库配额 500MB，公开仓库无配额）。
     * 显式参数版：多挂载点架构下由挂载点编辑页传入当前挂载点的生效令牌与仓库；
     * 无参调用保持旧行为（查当前激活/首个挂载点绑定仓库）。
     */
    fun loadRepoInfo(
        token: String = settings.value.token,
        owner: String = settings.value.owner,
        repo: String = settings.value.repoName,
    ) {
        val t = token.trim()
        val o = owner.trim()
        val r = repo.trim()
        if (t.isBlank() || o.isBlank() || r.isBlank()) {
            _repoOpState.value = RepoOpState.Error("尚未配置仓库")
            return
        }
        _repoOpState.value = RepoOpState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = GitHubClient(t).getRepoInfo(o, r)
                    ?: throw IOException("仓库不存在或无权访问")
                _repoOpState.value = RepoOpState.Success(info)
            } catch (e: Exception) {
                _repoOpState.value = RepoOpState.Error(friendly(e))
            }
        }
    }

    /**
     * 查询 GitHub API 本小时剩余调用余额（GET /rate_limit 不计入配额）。
     * 已配置 token 时自动查询一次；结果用于主界面显示，让用户知道这个小时
     * 还能调用多少次接口，避免临近上限时上传/下载突然 403。
     */
    fun loadRateLimit() {
        val token = settings.value.token
        if (token.isBlank()) {
            _rateLimitState.value = RateLimitState.Error("未配置令牌，无法查询")
            return
        }
        _rateLimitState.value = RateLimitState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (limit, remaining) = GitHubClient(token).rateLimit()
                _rateLimitState.value = RateLimitState.Success(limit, remaining)
            } catch (e: Exception) {
                _rateLimitState.value = RateLimitState.Error(friendly(e))
            }
        }
    }

    /**
     * 清空提交历史（孤儿提交重写：文件全部保留，历史从 N 个 commit 变成 1 个）。
     * 容量数字由 GitHub 后台 GC 延迟回收，不会立即下降。
     * 显式参数版：多挂载点架构下由挂载点编辑页传入当前挂载点的生效令牌与仓库；
     * 无参调用保持旧行为（对当前激活/首个挂载点绑定仓库操作）。
     */
    fun clearRepoHistory(
        token: String = settings.value.token,
        owner: String = settings.value.owner,
        repo: String = settings.value.repoName,
        branch: String = settings.value.branch,
    ) {
        val t = token.trim()
        val o = owner.trim()
        val r = repo.trim()
        val b = branch.ifBlank { "main" }
        if (t.isBlank() || o.isBlank() || r.isBlank()) {
            _repoOpState.value = RepoOpState.Error("尚未配置仓库")
            return
        }
        _repoOpState.value = RepoOpState.ClearingHistory
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repo = GitRepository(GitHubClient(t), o, r, b)
                repo.clearHistory()
                // 清完历史立即重新拉取容量（此时 size 可能仍含旧的不可达对象，如实展示）
                val info = GitHubClient(t).getRepoInfo(o, r)
                _repoOpState.value = if (info != null) RepoOpState.Success(info) else RepoOpState.Error("历史已清空，但容量信息暂时不可用")
            } catch (e: Exception) {
                _repoOpState.value = RepoOpState.Error(friendly(e))
            }
        }
    }

    /**
     * 绑定已有仓库到指定挂载点（仓库必须已存在，走一套统一的保存逻辑）。
     * @param token 用户输入的令牌（尚未保存时也能直接用）
     * @param targetMountPointId 目标挂载点 id；null = 旧行为（激活挂载点/默认挂载点）
     */
    fun bindRepo(token: String, owner: String, repoName: String, branch: String = "", targetMountPointId: String? = null) {
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
                settingsRepo.saveRepo(t, o, r, actualBranch, targetMountPointId)
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
     * 新建仓库并立即绑定到指定挂载点（创建完成后走与绑定完全相同的保存逻辑）。
     * @param token 用户输入的令牌（尚未保存时也能直接用）
     * @param isPrivate true = 私有，false = 公开
     * @param targetMountPointId 目标挂载点 id；null = 旧行为（激活挂载点/默认挂载点）
     */
    fun createAndBindRepo(token: String, name: String, isPrivate: Boolean, targetMountPointId: String? = null) {
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
                settingsRepo.saveRepo(t, login, repoName, resolvedBranch, targetMountPointId)
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

    /**
     * 仅创建仓库并刷新扫描列表（不绑定挂载点）。
     * 用于挂载点创建对话框内的"新建仓库"：创建成功后刷新列表并自动选中新仓库，
     * 随后随挂载点一起落盘，实现"创建挂载点一步到位"。
     */
    fun createRepoOnly(token: String, name: String, isPrivate: Boolean, onResult: (RepoBrief?) -> Unit = {}) {
        val t = token.trim()
        val repoName = name.trim()
        if (t.isBlank() || repoName.isBlank()) {
            onResult(null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = GitHubClient(t)
                val login = client.getUser()
                if (login.isBlank()) throw IllegalStateException("令牌无效")
                LogBus.info("正在创建${if (isPrivate) "私有" else "公开"}仓库 $login/$repoName …")
                client.createRepo(repoName, isPrivate)
                awaitDefaultBranch(client, login, repoName)
                // 绕过缓存强制刷新扫描列表，让新仓库立即可选
                scanCache = null
                val repos = client.listUserRepos()
                scanCache = ScanCache(t, login, repos, System.currentTimeMillis())
                _scanState.value = ScanState.Success(login, repos)
                LogBus.info("仓库 $login/$repoName 创建成功，已刷新列表")
                onResult(repos.find { it.fullName == "$login/$repoName" })
            } catch (e: Exception) {
                val msg = friendly(e)
                LogBus.error("创建仓库失败：$msg")
                _scanState.value = ScanState.Error(msg)
                onResult(null)
            }
        }
    }

    /**
     * 删除仓库（不可逆）：从 GitHub 永久删除仓库及其全部文件与提交历史。
     * 危险操作：调用方必须已通过两道确认弹窗 + 输入完整仓库名校验。
     * 删除成功后绕过缓存刷新扫描列表，让列表立即移除该仓库。
     */
    fun deleteRepo(token: String, owner: String, repo: String, onResult: (Result<String>) -> Unit = {}) {
        val t = token.trim()
        val o = owner.trim()
        val r = repo.trim()
        if (t.isBlank() || o.isBlank() || r.isBlank()) {
            onResult(Result.failure(IllegalStateException("令牌或仓库信息不完整")))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = GitHubClient(t)
                client.deleteRepo(o, r)
                // 删除仓库后联动解绑：所有绑定该仓库的挂载点清空仓库字段，
                // 避免挂载点继续指向已删除的仓库导致服务激活失败/连不上
                val unboundIds = try {
                    settingsRepo.unbindRepoFromMountPoints(o, r)
                } catch (e: Exception) {
                    LogBus.warn("删除仓库后自动解绑挂载点失败：${e.message}")
                    emptyList()
                }
                if (unboundIds.isNotEmpty()) {
                    LogBus.warn("仓库 $o/$r 已删除，自动解绑挂载点：${unboundIds.joinToString()}")
                    unboundIds.forEach { id ->
                        // 服务在运行时同步释放该挂载点的监听与旧仓库缓存
                        if (serverState.value.running && !serverState.value.starting) {
                            MountService.sync(getApplication(), id)
                        }
                    }
                }
                // 绕过缓存强制刷新列表，让被删仓库立即可见地消失
                scanCache = null
                val login = client.getUser()
                val repos = runCatching { client.listUserRepos() }.getOrElse { emptyList() }
                scanCache = ScanCache(t, login, repos, System.currentTimeMillis())
                _scanState.value = ScanState.Success(login, repos)
                LogBus.warn("仓库 $o/$r 已永久删除")
                onResult(Result.success("$o/$r"))
            } catch (e: Exception) {
                val msg = friendly(e)
                LogBus.error("删除仓库失败：$msg")
                onResult(Result.failure(e))
            }
        }
    }

    /** 保存服务配置页的表单（服务协议 / 端口 / 访问账号），落盘完成后回调 */
    fun saveServiceConfig(
        mode: ServerMode,
        ftpPort: Int,
        webdavPort: Int,
        username: String,
        password: String,
        /** 落盘完成后回调（调用方据此再触发重启/返回，避免读到旧配置） */
        onSaved: () -> Unit = {},
    ) {
        if (username.isBlank() != password.isBlank()) {
            LogBus.warn("访问账号不完整：用户名与密码需同时填写，或同时留空使用匿名登录")
        }
        viewModelScope.launch {
            settingsRepo.saveServiceConfig(
                mode.name, ftpPort, webdavPort, username, password,
            )
            LogBus.info(
                "服务配置已保存：${mode.label} / 端口 ${if (mode == ServerMode.FTP) ftpPort else webdavPort} / 账号 $username"
            )
            onSaved()
        }
    }

    /** 保存右上角设置页的网络与后台选项，落盘完成后回调 */
    fun saveBackgroundSettings(
        loopbackOnly: Boolean,
        hideNotification: Boolean,
        autoStart: Boolean,
        hideFromRecents: Boolean,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            settingsRepo.saveBackgroundSettings(
                loopbackOnly, hideNotification, autoStart, hideFromRecents,
            )
            LogBus.info(
                "后台设置已保存：${if (loopbackOnly) "仅本机" else "局域网"} / " +
                    "${if (hideNotification) "隐藏通知" else "常驻通知"} / " +
                    "${if (hideFromRecents) "后台隐藏" else "任务列表可见"} / " +
                    "${if (autoStart) "自启动开" else "自启动关"}"
            )
            onSaved()
        }
    }

    fun clearSetupMessage() {
        _setupState.value = SetupState.Idle
    }

    // ---------- 多挂载点管理 ----------

    /**
     * 新建挂载点（一步到位：完整配置一次落盘，无需再切到其他页挨个设置）。
     * 挂载点名 / 独立令牌（留空继承共享）/ 仓库绑定 / 协议 / 端口 / 访问账号一次填完。
     */
    fun createMountPoint(
        mountPoint: com.kleinops.gitmount.data.MountPointConfig,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            settingsRepo.saveMountPoint(
                mountPoint.copy(id = mountPoint.id.ifBlank { "mp_" + java.util.UUID.randomUUID().toString().take(8) }),
                activate = false,
            )
            LogBus.info("已新建挂载点「${mountPoint.name}」")
            onSaved()
        }
    }

    /** 切换挂载点启用开关（挂载点页 Switch）——停用后服务重启即跳过该挂载点 */
    fun setMountPointEnabled(mountPointId: String, enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val mountPoints = settings.value.mountPoints
            mountPoints.find { it.id == mountPointId }?.let { mp ->
                settingsRepo.saveMountPoint(mp.copy(enabled = enabled), activate = false)
                LogBus.info(
                    if (enabled) "挂载点「${mp.name}」已启用" else "挂载点「${mp.name}」已停用（重启服务后不再监听其端口）"
                )
                onSaved()
            }
        }
    }

    /**
     * 切换挂载点的"仅本机访问"开关（挂载点页卡片 Switch）。
     * 全局「强制仅本机访问」开启时此开关被禁用（一律仅本机）；
     * 全局关闭时按本开关（该挂载点是否只监听 127.0.0.1）生效。
     */
    fun setMountPointLoopback(mountPointId: String, loopback: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val mountPoints = settings.value.mountPoints
            mountPoints.find { it.id == mountPointId }?.let { mp ->
                settingsRepo.saveMountPoint(mp.copy(loopbackOnly = loopback), activate = false)
                LogBus.info(
                    "挂载点「${mp.name}」${if (loopback) "已设为仅本机访问" else "已允许局域网访问"}"
                )
                onSaved()
            }
        }
    }

    /** 重命名挂载点 */
    fun renameMountPoint(id: String, name: String) {
        val n = name.trim().ifBlank { return }
        viewModelScope.launch {
            val mountPoints = settings.value.mountPoints
            mountPoints.find { it.id == id }?.let { mp ->
                settingsRepo.saveMountPoint(mp.copy(name = n), activate = false)
                LogBus.info("挂载点已重命名为「$n」")
            }
        }
    }

    /**
     * 保存挂载点的完整配置（名称 / 协议 / 端口 / 访问账号 / 独立令牌 / 仓库绑定）。
     * 挂载点页直接编辑，无需先"切换激活挂载点"再去其他页面改。
     * @param onSaved 落盘完成后回调（UI 据此决定是否重启服务 / 返回）
     */
    fun updateMountPoint(
        mountPoint: com.kleinops.gitmount.data.MountPointConfig,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            settingsRepo.saveMountPoint(mountPoint, activate = false)
            LogBus.info("挂载点「${mountPoint.name}」配置已保存")
            onSaved()
        }
    }

    /** 保存全局共享 GitHub 令牌（所有挂载点默认共用；挂载点独立令牌留空即继承） */
    fun saveSharedToken(token: String, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsRepo.saveSharedToken(token.trim())
            LogBus.info(if (token.isBlank()) "已清除共享 GitHub 令牌" else "已更新共享 GitHub 令牌")
            onSaved()
        }
    }

    /** 删除挂载点（若删的是激活挂载点，激活回退到剩余第一个） */
    fun deleteMountPoint(id: String, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsRepo.deleteMountPoint(id)
            LogBus.info("已删除挂载点")
            onSaved()
        }
    }

    fun clearScanState() {
        _scanState.value = ScanState.Idle
    }

    /**
     * 释放可重建的 UI 内存（应用进入后台 / 系统内存吃紧时调用）。
     * 只清掉一次性展示状态与扫描缓存，不动设置、令牌、服务状态——
     * 这些由 DataStore / 服务 / 文件日志持久化，回到前台会自动重新加载。
     */
    fun releaseUiMemory() {
        scanCache = null
        _setupState.value = SetupState.Idle
        _scanState.value = ScanState.Idle
        _repoOpState.value = RepoOpState.Idle
        _rateLimitState.value = RateLimitState.Idle
        LogBus.info("已释放后台 UI 缓存（扫描/余额/瞬时状态）")
    }

    fun clearLogs() = LogBus.clear()

    // ---------- 备份 / 恢复 ----------

    /** 导出备份：生成备份 JSON（可口令加密），内容通过回调返回 */
    fun exportBackup(encrypt: Boolean, password: String, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { settingsRepo.exportBackup(encrypt, password) }
            if (result.isSuccess) {
                LogBus.info("配置备份已生成（${if (encrypt) "已加密" else "明文"}）")
            } else {
                LogBus.error("备份失败：${result.exceptionOrNull()?.message}")
            }
            // 回调切回主线程（onResult 里会更新 Compose 状态）
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /** 导入备份：解析并恢复配置；失败抛异常信息给 UI 提示 */
    fun importBackup(content: String, password: String, onResult: (Result<Int>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { settingsRepo.importBackup(content, password) }
            if (result.isSuccess) {
                LogBus.info("配置已从备份恢复：${result.getOrNull()} 个挂载点")
            } else {
                LogBus.error("恢复失败：${result.exceptionOrNull()?.message}")
            }
            // 回调切回主线程（onResult 里会更新 Compose 状态）
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

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