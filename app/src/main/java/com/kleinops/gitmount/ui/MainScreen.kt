package com.kleinops.gitmount.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Cloud
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kleinops.gitmount.R
import com.kleinops.gitmount.data.ServerMode
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

    var showRepoSetup by remember { mutableStateOf(false) }
    var showServerSetup by remember { mutableStateOf(false) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }

    // 已配置仓库时，进入主界面自动查一次容量（后续可手动刷新）
    LaunchedEffect(settings.isConfigured) {
        if (settings.isConfigured) vm.loadRepoInfo()
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
            showRepoSetup = false
            vm.clearSetupMessage()
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
                    IconButton(onClick = { showRepoSetup = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "仓库配置")
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
                        "${runCatching { ServerMode.valueOf(settings.mode) }.getOrDefault(ServerMode.WEBDAV).label} 服务"
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
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (server.loopbackOnly) {
                                "当前仅本机可访问（只监听 127.0.0.1）。如需局域网访问，请在服务设置里关闭“仅本机访问”。"
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
                                "尚未配置仓库。请先点击右上角设置完成 GitHub 配置。"
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
                                        scope.launch { snackbar.showSnackbar("请先完成仓库配置") }
                                        showRepoSetup = true
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
                    // 第二行：仓库配置 / 服务设置均分宽度，每个都带图标，风格与启动按钮一致
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { showRepoSetup = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("仓库配置", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = { showServerSetup = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("服务设置", maxLines = 1)
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

            // 运行日志
            item {
                SectionCard(
                    title = "运行日志",
                    trailing = {
                        TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
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

    if (showRepoSetup) {
        RepoSetupDialog(
            vm = vm,
            initialToken = settings.token,
            initialOwner = settings.owner,
            initialRepo = settings.repoName,
            initialBranch = settings.branch,
            isWorking = setupState is SetupState.Working,
            scanState = scanState,
            setupState = setupState,
            onDismiss = {
                showRepoSetup = false
                vm.clearSetupMessage()
                vm.clearScanState()
            },
        )
    }

    if (showServerSetup) {
        ServerSetupDialog(
            vm = vm,
            initialMode = runCatching { ServerMode.valueOf(settings.mode) }.getOrDefault(ServerMode.WEBDAV),
            initialFtpPort = settings.ftpPort.toString(),
            initialWebdavPort = settings.webdavPort.toString(),
            initialUsername = settings.username,
            initialPassword = settings.password,
            initialLoopbackOnly = settings.loopbackOnly,
            initialHideNotification = settings.hideNotification,
            initialAutoStart = settings.autoStart,
            onDismiss = { showServerSetup = false },
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

/** 仓库配置对话框：令牌 → 扫描仓库 → 绑定已有 / 创建新仓库 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoSetupDialog(
    vm: MainViewModel,
    initialToken: String,
    initialOwner: String,
    initialRepo: String,
    initialBranch: String,
    isWorking: Boolean,
    scanState: ScanState,
    setupState: SetupState,
    onDismiss: () -> Unit,
) {
    var token by remember { mutableStateOf(initialToken) }
    var owner by remember { mutableStateOf(initialOwner) }
    var repo by remember { mutableStateOf(initialRepo) }
    var branch by remember { mutableStateOf(initialBranch.ifBlank { "main" }) }
    var showToken by remember { mutableStateOf(false) }

    // 判定：owner/repo 与某个已配置/扫描到的仓库匹配 → 绑定已有仓库；否则创建新仓库。
    // 注意要兼容两种情况：
    //  1) 已保存过配置，用户只是重开对话框改设置 → 默认按“绑定”处理，避免误显示“创建”。
    //  2) 通过“扫描我的仓库”选中 → 按“绑定”处理。
    val selectedRepo = (scanState as? ScanState.Success)
        ?.repos
        ?.firstOrNull { it.fullName.equals("${owner.trim()}/${repo.trim()}", ignoreCase = true) }
    val keepsExisting = initialRepo.isNotBlank() &&
        repo.trim().equals(initialRepo.trim(), ignoreCase = true) &&
        owner.trim().equals(initialOwner.trim(), ignoreCase = true)
    val isBinding = selectedRepo != null || keepsExisting

    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        title = { Text("仓库配置") },
        text = {
            // 注意：AlertDialog 的 text 槽位高度有界，直接放 LazyColumn 会因约束冲突崩溃。
            // 这里用带高度上限的可滚动 Column 替代。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "GitHub 访问令牌 *",
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
                    supportingText = {
                        Text(
                            "需 repo 权限（classic 令牌）或 Contents 读写权限（fine-grained 令牌）",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )

                // 扫描并选择已有仓库
                TextButton(
                    onClick = { vm.scanRepos(token) },
                    enabled = !isWorking && scanState !is ScanState.Working,
                ) {
                    if (scanState is ScanState.Working) {
                        CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("扫描中…")
                    } else {
                        Text("扫描我的仓库（绑定已有）")
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
                            "账号 ${s.login}，共 ${s.repos.size} 个仓库。点击下方仓库即可选中绑定；也可以在下方面板输入新仓库名来创建。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (s.repos.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                s.repos.forEach { r ->
                                    val full = r.fullName
                                    val isSel = full.equals("${owner.trim()}/${repo.trim()}", ignoreCase = true)
                                    // 列表项：用 Surface 呈现可点击行，选中态高亮 + 对勾，而非一竖排按钮。
                                    Surface(
                                        onClick = {
                                            owner = full.substringBefore('/')
                                            repo = r.name
                                            branch = r.defaultBranch
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.small,
                                        color = if (isSel) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                if (isSel) "✓ " else "  ",
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
                                                    "默认分支 ${r.defaultBranch}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ScanState.Idle -> Unit
                }

                Spacer(Modifier.height(4.dp))
                Text("仓库（绑定已有或创建新仓库）", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("所有者（创建时可留空，默认令牌账号）") },
                )
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("仓库名称 *") },
                    placeholder = { Text("gitmount-data") },
                )
                OutlinedTextField(
                    value = branch,
                    onValueChange = { branch = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("分支（绑定已有时自动填入）") },
                )
                Text(
                    if (isBinding) "将绑定已有私有仓库：${owner.trim()}/${repo.trim()}"
                    else "将创建新的私有仓库：${owner.trim()}/${repo.trim()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isBinding) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )

                // 初始化失败：直接显示在对话框内部，避免被对话框遮挡看不到
                if (setupState is SetupState.Error) {
                    Text(
                        "保存失败：${(setupState as SetupState.Error).message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isWorking,
                onClick = {
                    vm.setupRepo(token, owner, repo, branch, createNew = !isBinding)
                },
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        isWorking -> "处理中…"
                        isBinding -> "绑定并保存"
                        else -> "创建并保存"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWorking) { Text("取消") }
        },
    )
}

/** 服务设置对话框：协议 / 端口 / 访问账号 / 仅本机访问 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSetupDialog(
    vm: MainViewModel,
    initialMode: ServerMode,
    initialFtpPort: String,
    initialWebdavPort: String,
    initialUsername: String,
    initialPassword: String,
    initialLoopbackOnly: Boolean,
    initialHideNotification: Boolean,
    initialAutoStart: Boolean,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var ftpPort by remember { mutableStateOf(initialFtpPort) }
    var webdavPort by remember { mutableStateOf(initialWebdavPort) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    var loopbackOnly by remember { mutableStateOf(initialLoopbackOnly) }
    var hideNotification by remember { mutableStateOf(initialHideNotification) }
    var autoStart by remember { mutableStateOf(initialAutoStart) }
    var showPassword by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 打开电池优化白名单：
    // 1) 优先 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS——直接弹针对本应用的
    //    “允许/不允许”系统对话框（最直接有效，需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）；
    // 2) 失败降级到“全部应用”列表页；
    // 3) 再失败用 Toast 明确提示，绝不静默吞掉（用户之前反馈“点了没反应”就是静默吞异常导致）。
    fun openBatterySettings() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}"),
                )
            )
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.settings_bg_battery_err),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("服务设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("协议", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ServerMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(index, ServerMode.entries.size),
                        ) {
                            Text(m.label)
                        }
                    }
                }

                OutlinedTextField(
                    value = if (mode == ServerMode.FTP) ftpPort else webdavPort,
                    onValueChange = {
                        if (mode == ServerMode.FTP) ftpPort = it else webdavPort = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(
                            if (mode == ServerMode.FTP) "建议 2121（1024 以上免 root）" else "建议 8080",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("访问用户名（可选）") },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("访问密码（可选）") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            "仅用于局域网访问认证，可与 GitHub 令牌不同",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )

                // 仅本机访问开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅本机访问", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "开启后只监听 127.0.0.1，局域网内其他设备无法访问，更安全",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = loopbackOnly,
                        onCheckedChange = { loopbackOnly = it },
                    )
                }

                // 后台运行：隐藏常驻通知 + 电池优化白名单引导
                Spacer(Modifier.height(4.dp))
                Text("后台运行", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_bg_hide_notif), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_bg_hide_notif_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = hideNotification,
                        onCheckedChange = { hideNotification = it },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_bg_battery), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_bg_battery_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { openBatterySettings() },
                    ) {
                        Text(stringResource(R.string.settings_bg_battery_btn))
                    }
                }

                // 打开应用时自动启动服务
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_bg_auto_start), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_bg_auto_start_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoStart,
                        onCheckedChange = { autoStart = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    vm.saveServerSettings(
                        mode = mode,
                        ftpPort = ftpPort.toIntOrNull() ?: 2121,
                        webdavPort = webdavPort.toIntOrNull() ?: 8080,
                        username = username.trim(),
                        password = password,
                        loopbackOnly = loopbackOnly,
                        hideNotification = hideNotification,
                        autoStart = autoStart,
                    )
                    onDismiss()
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}