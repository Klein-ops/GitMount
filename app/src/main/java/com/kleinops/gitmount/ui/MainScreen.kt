package com.kleinops.gitmount.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.service.MountService
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

    var showSetup by remember { mutableStateOf(false) }

    // 初始化结果反馈
    LaunchedEffect(setupState) {
        when (val s = setupState) {
            is SetupState.Success -> {
                snackbar.showSnackbar(s.message)
                showSetup = false
                vm.clearSetupMessage()
            }
            is SetupState.Error -> {
                snackbar.showSnackbar("失败：${s.message}")
                vm.clearSetupMessage()
            }
            else -> Unit
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
                    IconButton(onClick = { showSetup = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "配置")
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
                    title = if (server.mode == ServerMode.WEBDAV) "WebDAV 服务" else "FTP 服务",
                    trailing = { StatusDot(running = server.running) },
                ) {
                    if (server.starting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("正在启动…", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (server.running) {
                        val endpoint = server.endpoint ?: "未知地址"
                        InfoRow("访问地址", endpoint, mono = true)
                        InfoRow("协议", server.mode.label)
                        InfoRow("端口", server.port.toString(), mono = true)
                        InfoRow("用户名", server.username, mono = true)
                        InfoRow("密码", "••••••", mono = true)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "在同局域网的电脑/手机上添加该地址即可读写仓库文件。",
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!server.running) {
                            Button(
                                onClick = {
                                    val port = if (settings.mode == "FTP") settings.ftpPort else settings.webdavPort
                                    val mode = runCatching { ServerMode.valueOf(settings.mode) }
                                        .getOrDefault(ServerMode.WEBDAV)
                                    if (!settings.isConfigured) {
                                        scope.launch { snackbar.showSnackbar("请先完成仓库配置") }
                                        showSetup = true
                                    } else {
                                        MountService.start(context, mode, port)
                                    }
                                },
                                enabled = !server.starting,
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.width(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("启动服务")
                            }
                        } else {
                            OutlinedButton(onClick = { MountService.stop(context) }) {
                                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.width(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("停止服务")
                            }
                        }
                        FilledTonalButton(onClick = { showSetup = true }) {
                            Text("配置")
                        }
                    }
                }
            }

            // 仓库信息
            item {
                SectionCard(title = "仓库") {
                    if (settings.isConfigured) {
                        InfoRow("仓库", "${settings.owner}/${settings.repoName}", mono = true)
                        InfoRow("分支", settings.branch, mono = true)
                        InfoRow("令牌", "已加密保存于系统密钥库")
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

    if (showSetup) {
        SetupDialog(
            vm = vm,
            initialMode = runCatching { ServerMode.valueOf(settings.mode) }.getOrDefault(ServerMode.WEBDAV),
            initialToken = settings.token,
            initialOwner = settings.owner,
            initialRepo = settings.repoName,
            initialBranch = settings.branch,
            initialFtpPort = settings.ftpPort.toString(),
            initialWebdavPort = settings.webdavPort.toString(),
            initialUsername = settings.username,
            initialPassword = settings.password,
            isWorking = setupState is SetupState.Working,
            onDismiss = {
                showSetup = false
                vm.clearSetupMessage()
            },
        )
    }
}

/** 配置对话框：仓库设置 + 服务设置 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupDialog(
    vm: MainViewModel,
    initialMode: ServerMode,
    initialToken: String,
    initialOwner: String,
    initialRepo: String,
    initialBranch: String,
    initialFtpPort: String,
    initialWebdavPort: String,
    initialUsername: String,
    initialPassword: String,
    isWorking: Boolean,
    onDismiss: () -> Unit,
) {
    var token by remember { mutableStateOf(initialToken) }
    var owner by remember { mutableStateOf(initialOwner) }
    var repo by remember { mutableStateOf(initialRepo) }
    var branch by remember { mutableStateOf(initialBranch.ifBlank { "main" }) }
    var mode by remember { mutableStateOf(initialMode) }
    var ftpPort by remember { mutableStateOf(initialFtpPort) }
    var webdavPort by remember { mutableStateOf(initialWebdavPort) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        title = { Text("配置") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "GitHub 访问令牌",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                item {
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("ghp_… 或 github_pat_…") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = {
                            Text(
                                "需 repo 权限（classic 令牌）或 Contents 读写权限（fine-grained 令牌）",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                item {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "隐藏令牌" else "显示令牌")
                    }
                }
                item {
                    OutlinedTextField(
                        value = repo,
                        onValueChange = { repo = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("仓库名称") },
                        placeholder = { Text("gitmount-data") },
                    )
                }
                item {
                    OutlinedTextField(
                        value = owner,
                        onValueChange = { owner = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("所有者（留空=令牌对应账号）") },
                    )
                }
                item {
                    OutlinedTextField(
                        value = branch,
                        onValueChange = { branch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("分支") },
                    )
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    Text("服务设置", style = MaterialTheme.typography.labelLarge)
                }
                item {
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
                }
                item {
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
                }
                item {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("访问用户名") },
                    )
                }
                item {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("访问密码") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        supportingText = {
                            Text(
                                "仅用于局域网访问认证，可与 GitHub 令牌不同",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                item {
                    Text(
                        "保存服务设置后，请到主界面启动服务；仓库信息需点击下方“保存并初始化”。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isWorking,
                onClick = {
                    vm.saveServerSettings(
                        mode = mode,
                        ftpPort = ftpPort.toIntOrNull() ?: 2121,
                        webdavPort = webdavPort.toIntOrNull() ?: 8080,
                        username = username.trim(),
                        password = password,
                    )
                    vm.setupRepo(token, owner, repo, branch)
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
                Text(if (isWorking) "处理中…" else "保存并初始化")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWorking) { Text("取消") }
        },
    )
}