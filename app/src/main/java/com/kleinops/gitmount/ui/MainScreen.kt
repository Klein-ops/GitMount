package com.kleinops.gitmount.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.ui.components.ClickableInfoRow
import com.kleinops.gitmount.ui.components.EmptyHint
import com.kleinops.gitmount.ui.components.InfoRow
import com.kleinops.gitmount.ui.components.LogRow
import com.kleinops.gitmount.ui.components.SectionCard
import com.kleinops.gitmount.ui.components.StatusDot
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val server by vm.serverState.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val setupState by vm.setupState.collectAsStateWithLifecycle()
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    val repoOpState by vm.repoOpState.collectAsStateWithLifecycle()
    val rateLimitState by vm.rateLimitState.collectAsStateWithLifecycle()

    var showRepoList by remember { mutableStateOf(false) }
    var showCreateRepo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showServiceConfig by remember { mutableStateOf(false) }
    var showAccountList by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    /** 从仓库列表跳转新建时带入的令牌（可能尚未保存到 DataStore） */
    var createRepoToken by remember { mutableStateOf("") }

    // 账户管理页（多账户：新建/切换/重命名/删除）
    if (showAccountList) {
        AccountListScreen(vm = vm, onBack = { showAccountList = false })
        return
    }

    // 服务配置页独立成页面（协议 / 端口 / 访问账号），返回后回到主界面
    if (showServiceConfig) {
        ServiceConfigScreen(vm = vm, onBack = { showServiceConfig = false })
        return
    }

    // 设置页独立成页面（网络与后台类选项），返回后回到主界面
    if (showSettings) {
        SettingsScreen(vm = vm, onBack = { showSettings = false })
        return
    }

    // 已配置仓库时，进入主界面自动查一次容量（后续可手动刷新）；
    // 监听仓库身份字段（owner/repoName/branch/token）变化，绑定/切换仓库后自动重查，
    // 否则新仓库的公开/私有和容量不会自动更新，需要手动再刷新一遍。
    LaunchedEffect(settings.owner, settings.repoName, settings.branch, settings.token) {
        if (settings.isConfigured) vm.loadRepoInfo()
    }

    // 已配置仓库时，自动查询一次 GitHub API 本小时剩余调用余额（换仓库/换令牌后也重查）
    LaunchedEffect(settings.owner, settings.repoName, settings.branch, settings.token) {
        if (settings.isConfigured) vm.loadRateLimit()
    }

    // 打开应用自动启动服务（设置里可开关）。
    // 只在本次进程首次满足条件时触发一次，避免“用户手动停止后 running=false 又自动拉起”
    // 的死循环——autoStartTriggered 置位后，即使之后 running 变化也不再触发。
    var autoStartTriggered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.isConfigured, settings.autoStart) {
        if (settings.isConfigured && settings.autoStart && !autoStartTriggered) {
            autoStartTriggered = true
            if (!server.running && !server.starting) {
                val mode = runCatching { ServerMode.valueOf(settings.mode) }
                    .getOrDefault(ServerMode.WEBDAV)
                val port = if (mode == ServerMode.FTP) settings.ftpPort else settings.webdavPort
                MountService.start(context, mode, port)
            }
        }
    }

    // 初始化成功：关闭仓库对话框并用 Snackbar 提示。
    // 注意：必须先关对话框再 showSnackbar——showSnackbar 是挂起函数，
    // 若先弹 Snackbar 会一直挂起到播完，AlertDialog 又盖住它，导致对话框迟迟不退出。
    // 初始化失败不在此处理——错误会直接显示在配置对话框内部，
    // 避免 AlertDialog 遮挡底部 Snackbar 导致用户看不到具体原因。
    LaunchedEffect(setupState) {
        if (setupState is SetupState.Success) {
            val message = (setupState as SetupState.Success).message
            showRepoList = false
            showCreateRepo = false
            vm.clearSetupMessage()
            // 换绑/新建仓库成功后：若服务正在运行，自动用新仓库重启，
            // 否则服务仍持有旧仓库对象，访问的还是旧仓库内容（需手动重启才生效）。
            if (server.running && !server.starting) {
                LogBus.info("仓库配置已变更，自动重启服务使新仓库生效")
                MountService.restart(context)
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GitMount", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "GitHub 仓库挂载为 FTP / WebDAV",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // 运行状态 + 控制
            item {
                SectionCard(
                    title = if (settings.isConfigured) {
                        val accName = settings.activeAccount?.name ?: ""
                        "${runCatching { ServerMode.valueOf(settings.mode) }.getOrDefault(ServerMode.WEBDAV).label} 服务" +
                            (accName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                    } else {
                        "服务"
                    },
                    trailing = { StatusDot(running = server.running) },
                ) {
                    if (server.starting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("正在启动…", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (server.running) {
                        // 复制到剪贴板的工具函数
                        fun copyToClipboard(text: String, label: String) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("GitMount 地址", text))
                            scope.launch { snackbar.showSnackbar("$label 已复制到剪贴板") }
                        }
                        if (server.loopbackOnly) {
                            // 仅本机模式：只展示回环地址
                            ClickableInfoRow(
                                label = "本机地址",
                                value = server.loopbackEndpoint ?: "未知",
                                mono = true,
                                onClick = { server.loopbackEndpoint?.let { copyToClipboard(it, "本机地址") } },
                            )
                        } else {
                            ClickableInfoRow(
                                label = "局域网地址",
                                value = server.endpoint ?: "未知",
                                mono = true,
                                onClick = { server.endpoint?.let { copyToClipboard(it, "局域网地址") } },
                            )
                            ClickableInfoRow(
                                label = "本机地址",
                                value = server.loopbackEndpoint ?: "未知",
                                mono = true,
                                onClick = { server.loopbackEndpoint?.let { copyToClipboard(it, "本机地址") } },
                            )
                        }
                        InfoRow("协议", server.mode.label)
                        InfoRow("端口", server.port.toString(), mono = true)
                        InfoRow("用户名", server.username, mono = true)
                        InfoRow("密码", "••••••", mono = true)
                        if (server.activeSessions > 0 || server.totalBytes > 0) {
                            InfoRow("活跃会话", server.activeSessions.toString(), mono = true)
                            InfoRow("已传流量", formatBytes(server.totalBytes), mono = true)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (server.loopbackOnly) {
                                "当前仅本机可访问（只监听 127.0.0.1）。如需局域网访问，请在右上角设置里关闭“仅本机访问”。"
                            } else {
                                "点击地址即可复制。局域网内其他设备用局域网地址访问；手机本机用 127.0.0.1 地址更稳定。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            if (settings.isConfigured) {
                                "服务未启动。点击下方按钮开始提供服务。"
                            } else {
                                "尚未配置仓库。请先点击下方「仓库」按钮绑定 GitHub 仓库。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (server.error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "上次启动失败：${server.error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    // 第一行：启动 / 停止独占整行，避免与下方按钮挤压导致文字换行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!server.running) {
                            Button(
                                onClick = {
                                    val port = if (settings.mode == "FTP") settings.ftpPort else settings.webdavPort
                                    val mode = runCatching { ServerMode.valueOf(settings.mode) }
                                        .getOrDefault(ServerMode.WEBDAV)
                                    if (!settings.isConfigured) {
                                        scope.launch { snackbar.showSnackbar("请先绑定一个仓库") }
                                        showRepoList = true
                                        vm.scanRepos(settings.token.ifBlank { "" })
                                    } else {
                                        MountService.start(context, mode, port)
                                    }
                                },
                                enabled = !server.starting,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.width(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("启动服务")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { MountService.stop(context) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.width(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("停止服务")
                            }
                        }
                    }
                    // 第二行：仓库 / 服务配置 / 账户，各自打开对应页面
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                showRepoList = true
                                vm.scanRepos(settings.token.ifBlank { "" })
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("仓库", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = { showServiceConfig = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("服务配置", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = { showAccountList = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("账户", maxLines = 1)
                        }
                    }
                }
            }

            // 仓库信息 + 容量
            item {
                SectionCard(title = "仓库") {
                    if (settings.isConfigured) {
                        InfoRow("仓库", "${settings.owner}/${settings.repoName}", mono = true)
                        InfoRow("分支", settings.branch, mono = true)
                        InfoRow("令牌", "已加密保存于系统密钥库")
                        Spacer(Modifier.height(10.dp))

                        // ---- 容量展示 ----
                        when (val op = repoOpState) {
                            is RepoOpState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在查询容量…", style = MaterialTheme.typography.bodySmall)
                            }
                            is RepoOpState.Error -> Text(
                                "容量查询失败：${op.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            is RepoOpState.ClearingHistory -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在清空提交历史…", style = MaterialTheme.typography.bodySmall)
                            }
                            is RepoOpState.Success -> {
                                val info = op.info
                                // 本地写入时直接在用后查询，命中缓存避免重复调用
                                // 公开仓库 GitHub 无存储配额上限，只展示已用容量；私有仓库才展示
                                // 剩余容量/占用比例/进度条/超限提示（配额 500MB）。
                                InfoRow("仓库类型", if (info.isPrivate) "私有" else "公开", mono = true)
                                InfoRow("已用容量", info.usedHuman, mono = true)
                                if (info.isPrivate) {
                                    InfoRow("剩余容量", info.remainingHuman, mono = true)
                                    InfoRow(
                                        "占用比例",
                                        "${info.percentUsed}% / 500MB",
                                        mono = true,
                                        valueColor = if (info.percentUsed >= 90) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { info.percentUsed / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (info.exceeded) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "已超出仓库容量上限！请清理文件或清空提交历史。",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                } else {
                                    Text(
                                        "公开仓库无存储配额上限",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            RepoOpState.Idle -> Unit
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { vm.loadRepoInfo() },
                                enabled = repoOpState !is RepoOpState.Loading &&
                                    repoOpState !is RepoOpState.ClearingHistory,
                            ) {
                                Text("刷新容量")
                            }
                            OutlinedButton(
                                onClick = { showClearHistoryConfirm = true },
                                enabled = repoOpState !is RepoOpState.ClearingHistory,
                            ) {
                                if (repoOpState is RepoOpState.ClearingHistory) {
                                    CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("清空中…")
                                } else {
                                    Text("清空提交历史")
                                }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "未配置仓库",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // GitHub API 剩余调用余额（贴心功能：让用户知道这个小时还能调用多少次接口）
            item {
                SectionCard(title = "GitHub API") {
                    when (val rl = rateLimitState) {
                        is RateLimitState.Idle -> Text(
                            "未查询",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is RateLimitState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在查询本小时剩余调用次数…", style = MaterialTheme.typography.bodySmall)
                        }
                        is RateLimitState.Error -> Column {
                            Text(
                                "余额查询失败：${rl.message}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { vm.loadRateLimit() }) { Text("重试") }
                        }
                        is RateLimitState.Success -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "本小时剩余 ${rl.remaining} / ${rl.limit} 次",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "GitHub REST API 调用额度（每小时重置；查询本身不扣额度）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.loadRateLimit() }) { Text("刷新") }
                        }
                    }
                }
            }

            // 运行日志
            item {
                SectionCard(
                    title = "运行日志",
                    trailing = {
                        Row {
                            TextButton(onClick = { shareLogs(context) }) { Text("分享") }
                            TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
                        }
                    },
                ) {
                    if (logs.isEmpty()) {
                        EmptyHint("暂无日志")
                    } else {
                        Column {
                            logs.take(30).forEach { LogRow(it) }
                        }
                        if (logs.size > 30) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "仅显示最近 30 条，共 ${logs.size} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showRepoList) {
        RepoListDialog(
            vm = vm,
            initialToken = settings.token,
            currentOwner = settings.owner,
            currentRepo = settings.repoName,
            scanState = scanState,
            setupState = setupState,
            onBind = { token, owner, name, branch ->
                vm.bindRepo(token, owner, name, branch)
            },
            onCreateNew = { token ->
                createRepoToken = token
                showCreateRepo = true
            },
            onRefresh = { token -> vm.scanRepos(token) },
            onDismiss = {
                showRepoList = false
                vm.clearSetupMessage()
                vm.clearScanState()
            },
        )
    }

    if (showCreateRepo) {
        CreateRepoDialog(
            vm = vm,
            initialToken = createRepoToken.ifBlank { settings.token },
            isWorking = setupState is SetupState.Working,
            setupState = setupState,
            onDismiss = {
                showCreateRepo = false
                vm.clearSetupMessage()
            },
        )
    }

    // 清空历史完成/失败提示（检测 ClearingHistory → Success/Error 的状态跳变）
    val prevRepoOp = remember { mutableStateOf<RepoOpState>(RepoOpState.Idle) }
    LaunchedEffect(repoOpState) {
        val prev = prevRepoOp.value
        if (prev is RepoOpState.ClearingHistory && repoOpState is RepoOpState.Success) {
            snackbar.showSnackbar("提交历史已清空：文件全部保留，容量将由 GitHub 后台回收后下降")
        } else if (prev is RepoOpState.ClearingHistory && repoOpState is RepoOpState.Error) {
            snackbar.showSnackbar("清空失败：${(repoOpState as RepoOpState.Error).message}")
        }
        prevRepoOp.value = repoOpState
    }

    // 清空历史强确认对话框（明确说明文件保留、容量延迟回收、不可撤销）
    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("清空提交历史？") },
            text = {
                Text(
                    "将把当前分支的历史重写为单个提交（孤儿提交重写）。\n\n" +
                        "• 仓库内所有文件都会保留，不会丢失\n" +
                        "• 提交历史将变成 1 条，旧历史变为不可达对象\n" +
                        "• GitHub 后台回收旧对象需要时间，容量数字可能延迟下降（几小时~几天）\n\n" +
                        "此操作不可撤销，确定继续？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistoryConfirm = false
                        vm.clearRepoHistory()
                    },
                ) { Text("确定清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 仓库列表对话框：输入令牌 → 扫描 → 点选仓库即绑定。
 * 与旧版「仓库配置」的区别：不再手动填 owner/仓库名，全部从列表点选；
 * 新建走独立入口（onCreateNew 带出当前令牌）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoListDialog(
    vm: MainViewModel,
    initialToken: String,
    currentOwner: String,
    currentRepo: String,
    scanState: ScanState,
    setupState: SetupState,
    onBind: (token: String, owner: String, name: String, branch: String) -> Unit,
    onCreateNew: (token: String) -> Unit,
    onRefresh: (token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var token by remember { mutableStateOf(initialToken) }
    var showToken by remember { mutableStateOf(false) }
    val working = setupState is SetupState.Working

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("仓库") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "GitHub 访问令牌",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("ghp_… 或 github_pat_…") },
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showToken) "隐藏令牌" else "显示令牌",
                            )
                        }
                    },
                )

                TextButton(
                    onClick = { onRefresh(token) },
                    enabled = !working && scanState !is ScanState.Working,
                ) {
                    if (scanState is ScanState.Working) {
                        CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("扫描中…")
                    } else {
                        Text("扫描我的仓库")
                    }
                }

                when (val s = scanState) {
                    is ScanState.Working -> Unit
                    is ScanState.Error -> Text(
                        "扫描失败：${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is ScanState.Success -> {
                        Text(
                            "账号 ${s.login}，共 ${s.repos.size} 个仓库。点击仓库即绑定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (s.repos.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                s.repos.forEach { r ->
                                    val full = r.fullName
                                    val isCurrent = full.equals(
                                        "${currentOwner.trim()}/${currentRepo.trim()}",
                                        ignoreCase = true,
                                    )
                                    Surface(
                                        onClick = {
                                            onBind(
                                                token.trim(),
                                                full.substringBefore('/'),
                                                r.name,
                                                r.defaultBranch,
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.small,
                                        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                if (isCurrent) "✓ " else "  ",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    (if (r.isPrivate) "🔒 " else "") + full,
                                                    maxLines = 1,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                Text(
                                                    "默认分支 ${r.defaultBranch}" +
                                                        if (isCurrent) "（当前）" else "",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                "没有可绑定的仓库，可点下方「新建仓库」创建",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    ScanState.Idle -> Text(
                        "点击上方「扫描我的仓库」列出账号名下的仓库，点选即绑定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (setupState is SetupState.Error) {
                    Text(
                        "操作失败：${(setupState as SetupState.Error).message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Button(
                    onClick = { onCreateNew(token.trim()) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新建仓库")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("完成") }
        },
    )
}

/**
 * 新建仓库对话框：填仓库名 + 选择公开/私有，创建成功后自动绑定。
 * 走与「绑定已有仓库」完全相同的保存链路（createAndBindRepo）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateRepoDialog(
    vm: MainViewModel,
    initialToken: String,
    isWorking: Boolean,
    setupState: SetupState,
    onDismiss: () -> Unit,
) {
    var token by remember { mutableStateOf(initialToken) }
    var name by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }
    var showToken by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        title = { Text("新建仓库") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "GitHub 访问令牌",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("ghp_… 或 github_pat_…") },
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showToken) "隐藏令牌" else "显示令牌",
                            )
                        }
                    },
                )

                Text("仓库名称", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("如 gitmount-data") },
                )

                Text("可见性", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = isPrivate,
                        onClick = { isPrivate = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("私有") }
                    SegmentedButton(
                        selected = !isPrivate,
                        onClick = { isPrivate = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("公开") }
                }
                Text(
                    if (isPrivate) "私有仓库：仅自己和受邀协作者可见，免费额度 500MB"
                    else "公开仓库：所有人可见，无存储配额上限",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (setupState is SetupState.Error) {
                    Text(
                        "创建失败：${(setupState as SetupState.Error).message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isWorking && name.isNotBlank() && token.isNotBlank(),
                onClick = { vm.createAndBindRepo(token.trim(), name.trim(), isPrivate) },
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isWorking) "创建中…" else "创建并绑定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWorking) { Text("取消") }
        },
    )
}

/** 字节数人类可读化（B / KB / MB / GB，保留 1 位小数） */
private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/** 分享日志：把 FileLogger 当天的日志文件用系统分享面板发出去（排查问题一键导出） */
private fun shareLogs(context: Context) {
    val file = com.kleinops.gitmount.service.FileLogger.shareableLogFile()
    if (file == null) {
        android.widget.Toast.makeText(context, "暂无日志可分享", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GitMount 日志 ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 GitMount 日志"))
    }.onFailure {
        android.widget.Toast.makeText(context, "分享失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}
