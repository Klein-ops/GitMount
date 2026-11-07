package com.kleinops.gitmount.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kleinops.gitmount.data.AccountConfig
import com.kleinops.gitmount.data.RepoBrief
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.service.AccountManager
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * 账户管理页：每个账户是一个独立配置单元（仓库/协议/端口/访问账号/令牌），
 * 每账户一个【启用开关】（Switch）控制是否参与服务，不再有"切换当前账户"。
 * 服务启停是全局操作（主界面「启动/停止服务」），账户开关决定是否监听其端口。
 * 新建/编辑账户一步到位：名称 / 令牌 / 扫描绑定仓库 / 协议 / 端口 / 访问账号一次填完。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val server by vm.serverState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val accounts = settings.accounts
    val shared = settings.sharedToken

    var sharedToken by remember(settings.sharedToken) { mutableStateOf(settings.sharedToken) }
    var showSharedToken by remember { mutableStateOf(false) }

    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AccountConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<AccountConfig?>(null) }

    // 账户开关切换：落盘后若服务在运行，自动重启让新开关生效（停用的账户不再监听）
    fun onToggle(acc: AccountConfig, enabled: Boolean) {
        vm.setAccountEnabled(acc.id, enabled) {
            if (server.running && !server.starting) {
                LogBus.info("账户开关已变更，重启服务使其生效")
                MountService.restart(context)
            }
            scope.launch { snackbar.showSnackbar("账户「${acc.name}」已${if (enabled) "启用" else "停用"}") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("账户") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建账户")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ---- 共享 GitHub 令牌（所有账户默认共用，可改）----
            SectionCard(title = "共享 GitHub 令牌") {
                Text(
                    "所有账户默认共用同一个 GitHub API 令牌。账户令牌留空即自动继承；" +
                        "需要单独令牌的账户可在编辑里单独填写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = sharedToken,
                    onValueChange = { sharedToken = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("GitHub 访问令牌（全部账户共用）") },
                    placeholder = { Text("ghp_… 或 github_pat_…") },
                    visualTransformation = if (showSharedToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showSharedToken = !showSharedToken }) {
                            Icon(
                                if (showSharedToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showSharedToken) "隐藏令牌" else "显示令牌",
                            )
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.saveSharedToken(sharedToken) {
                            LogBus.info("共享 GitHub 令牌已更新，重启服务使新令牌生效")
                            if (server.running) MountService.restart(context)
                            scope.launch { snackbar.showSnackbar("共享令牌已保存") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存共享令牌") }
            }

            // ---- 账户列表 ----
            SectionCard(title = "账户列表") {
                if (accounts.isEmpty()) {
                    Text(
                        "还没有账户。点右上角 + 新建账户：名称 / 令牌 / 仓库 / 协议 / 端口 / 访问账号一次填完。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    accounts.forEach { acc ->
                        AccountRow(
                            account = acc,
                            enabled = acc.enabled,
                            activeNow = AccountManager.isActive(acc.id),
                            onToggle = { onToggle(acc, it) },
                            onEdit = { editTarget = acc },
                            onDelete = { deleteTarget = acc },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { showCreate = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建账户")
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "说明：服务启动是全局操作（主界面「启动服务」），一次为所有【已启用】的账户监听端口。" +
                    "账户平时处于休眠，只有对应端口有登录请求时才临时激活，空闲后自动回收。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // 新建账户：一步到位对话框
    if (showCreate) {
        AccountCreateDialog(
            initialToken = shared,
            scanState = vm.scanState.collectAsStateWithLifecycle().value,
            setupState = vm.setupState.collectAsStateWithLifecycle().value,
            onScan = { vm.scanRepos(it) },
            onSave = { draft ->
                vm.createAccount(draft) {
                    scope.launch { snackbar.showSnackbar("账户「${draft.name}」已创建") }
                }
                vm.clearSetupMessage()
                showCreate = false
            },
            onDismiss = {
                vm.clearScanState()
                vm.clearSetupMessage()
                showCreate = false
            },
        )
    }

    // 编辑账户完整配置（名称 / 协议 / 端口 / 访问账号 / 独立令牌 / 仓库令牌）
    editTarget?.let { acc ->
        AccountEditDialog(
            account = acc,
            sharedToken = shared.takeIf { it.isNotBlank() },
            onSave = { updated ->
                val changedService = acc.mode != updated.mode ||
                    acc.ftpPort != updated.ftpPort ||
                    acc.webdavPort != updated.webdavPort ||
                    acc.username != updated.username ||
                    acc.password != updated.password ||
                    acc.token != updated.token
                vm.updateAccount(updated) {
                    if (server.running && !server.starting && changedService) {
                        LogBus.info("账户配置已变更，重启服务使新设置生效")
                        MountService.restart(context)
                    }
                    scope.launch { snackbar.showSnackbar("账户「${updated.name}」已保存") }
                }
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }

    // 删除账户
    deleteTarget?.let { acc ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除账户？") },
            text = { Text("将删除账户「${acc.name}」及其仓库绑定、端口与访问账号配置。此操作不可撤销。") },
            confirmButton = {
                Button(onClick = { vm.deleteAccount(acc.id); deleteTarget = null }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AccountRow(
    account: AccountConfig,
    enabled: Boolean,
    activeNow: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    account.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        !enabled -> "已停用"
                        activeNow -> "活跃"
                        else -> "休眠"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.outline
                        activeNow -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                "${account.repoLabel} ｜ ${account.mode} : ${account.port}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除")
        }
    }
}

/**
 * 新建账户对话框（一步到位）：名称 / 令牌(默认继承共享，可改) / 扫描绑定仓库 /
 * 协议 / 端口 / 访问账号一次填完，保存即创建完整账户，无需再挨个设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountCreateDialog(
    initialToken: String,
    scanState: ScanState,
    setupState: SetupState,
    onScan: (String) -> Unit,
    onSave: (AccountConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(initialToken) }
    var showToken by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(ServerMode.WEBDAV) }
    var ftpPort by remember { mutableStateOf("2121") }
    var webdavPort by remember { mutableStateOf("8080") }
    var username by remember { mutableStateOf("gitmount") }
    var password by remember { mutableStateOf("") }
    var selectedRepo by remember { mutableStateOf<RepoBrief?>(null) }
    val working = setupState is SetupState.Working || scanState is ScanState.Working

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("新建账户") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("账户名称") },
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("GitHub 访问令牌（默认继承共享）") },
                    placeholder = { Text("留空 = 使用顶部共享令牌") },
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

                // 选择绑定仓库
                Text(
                    "绑定仓库",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = { onScan(token) },
                    enabled = !working && token.isNotBlank(),
                ) {
                    if (scanState is ScanState.Working) {
                        CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("扫描中…")
                    } else {
                        Text("扫描我的仓库")
                    }
                }
                when (scanState) {
                    is ScanState.Error -> Text(
                        scanState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is ScanState.Success -> {
                        if (scanState.repos.isEmpty()) {
                            Text(
                                "该令牌下没有可绑定的仓库",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            scanState.repos.take(20).forEach { repo ->
                                val full = repo.fullName
                                OutlinedButton(
                                    onClick = { selectedRepo = repo },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (selectedRepo?.fullName == full) "✓ " else "",
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            full + if (repo.isPrivate) "（私有）" else "（公开）",
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> Unit
                }
                if (selectedRepo != null) {
                    Text(
                        "已选：${selectedRepo?.fullName}（分支 ${selectedRepo?.defaultBranch}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                // 协议 / 端口 / 访问账号
                Text(
                    "服务协议",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ServerMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(index, ServerMode.entries.size),
                        ) { Text(m.label) }
                    }
                }
                OutlinedTextField(
                    value = if (mode == ServerMode.FTP) ftpPort else webdavPort,
                    onValueChange = { if (mode == ServerMode.FTP) ftpPort = it else webdavPort = it },
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
                    label = { Text("访问用户名（默认 gitmount）") },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("访问密码") },
                    visualTransformation = PasswordVisualTransformation(),
                )

                (setupState as? SetupState.Error)?.let { err ->
                    Text(
                        err.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !working,
                onClick = {
                    val n = name.trim()
                    if (n.isBlank()) return@Button
                    onSave(
                        AccountConfig(
                            id = "",
                            name = n,
                            token = token.trim(),
                            owner = selectedRepo?.fullName?.substringBefore('/') ?: "",
                            repoName = selectedRepo?.fullName?.substringAfter('/') ?: "",
                            branch = selectedRepo?.defaultBranch ?: "main",
                            mode = mode.name,
                            ftpPort = ftpPort.toIntOrNull() ?: 2121,
                            webdavPort = webdavPort.toIntOrNull() ?: 8080,
                            username = username.trim().ifBlank { "gitmount" },
                            password = password,
                            enabled = true,
                        )
                    )
                },
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("取消") }
        },
    )
}

/**
 * 账户完整配置编辑对话框：名称 / 协议 / 端口 / 访问账号 / 独立令牌（留空=继承共享）。
 * 保存后由调用方统一处理服务重启。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditDialog(
    account: AccountConfig,
    sharedToken: String?,
    onSave: (AccountConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(account.id) { mutableStateOf(account.name) }
    var mode by remember(account.id) {
        mutableStateOf(runCatching { ServerMode.valueOf(account.mode) }.getOrDefault(ServerMode.WEBDAV))
    }
    var ftpPort by remember(account.id) { mutableStateOf(account.ftpPort.toString()) }
    var webdavPort by remember(account.id) { mutableStateOf(account.webdavPort.toString()) }
    var username by remember(account.id) { mutableStateOf(account.username) }
    var password by remember(account.id) { mutableStateOf(account.password) }
    var token by remember(account.id) { mutableStateOf(account.token) }
    var showPassword by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑账户") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("账户名称") },
                )

                Text(
                    "服务协议",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ServerMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(index, ServerMode.entries.size),
                        ) { Text(m.label) }
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
                    label = { Text("访问用户名") },
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("访问密码") },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("独立 GitHub 令牌（可留空）") },
                    placeholder = { Text("留空 = 使用共享令牌") },
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
                            if (token.isBlank() && sharedToken != null) "当前使用共享令牌（已配置）"
                            else if (token.isBlank()) "当前无可用的 GitHub 令牌，服务无法为该账户启动"
                            else "使用本账户独立令牌",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        account.copy(
                            name = name.trim().ifBlank { account.name },
                            mode = mode.name,
                            ftpPort = ftpPort.toIntOrNull() ?: account.ftpPort,
                            webdavPort = webdavPort.toIntOrNull() ?: account.webdavPort,
                            username = username.trim().ifBlank { "gitmount" },
                            password = password,
                            token = token.trim(),
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}