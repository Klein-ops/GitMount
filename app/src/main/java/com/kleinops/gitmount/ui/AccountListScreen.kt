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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.service.AccountManager
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * 账户管理页：账户是"配置单元"（每个账户独立绑定仓库/协议/端口/访问账号）。
 * 服务端为**全部**账户监听端口（动态激活），此页直接管理所有账户的完整配置
 * （名称 / 协议 / 端口 / 访问账号 / 独立令牌），无需再切到「服务配置」页编辑。
 * 顶部同时提供「共享 GitHub 令牌」：所有账户默认共用同一个 GitHub API 令牌，
 * 每个账户仍可单独填写独立令牌（留空即继承共享）。
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
    val activeId = settings.activeAccountId

    // 共享令牌本地编辑态（settings.sharedToken 变化时重置，避免 DataStore 重新发射覆盖用户输入）
    var sharedToken by remember(settings.sharedToken) { mutableStateOf(settings.sharedToken) }
    var showSharedToken by remember { mutableStateOf(false) }

    var showCreate by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AccountConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<AccountConfig?>(null) }

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
                    "所有账户默认共用同一个 GitHub API 令牌。在此设置后，账户令牌留空即自动继承；" +
                        "需要单独令牌的账户可在下方编辑里单独填写。",
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
                        "还没有账户。点右上角 + 新建一个账户，然后在「仓库」里绑定 GitHub 仓库。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    accounts.forEach { acc ->
                        AccountRow(
                            account = acc,
                            isActive = acc.id == activeId,
                            activeNow = AccountManager.isActive(acc.id),
                            onSelect = { vm.switchAccount(acc.id) },
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
                "说明：服务启动后会为每个已配置的账户监听其端口；账户平时处于休眠，" +
                    "只有对应端口有登录请求时才临时激活（首次加载目录约 1~2 秒），空闲后自动回收。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showCreate) {
        AccountNameDialog(
            title = "新建账户",
            initial = "",
            onConfirm = { vm.createAccount(it); showCreate = false },
            onDismiss = { showCreate = false },
        )
    }

    // 编辑账户完整配置（名称 / 协议 / 端口 / 访问账号 / 独立令牌）
    editTarget?.let { acc ->
        AccountEditDialog(
            account = acc,
            sharedToken = settings.sharedToken.takeIf { it.isNotBlank() },
            onSave = { updated ->
                val old = acc
                val changedService = old.mode != updated.mode ||
                    old.ftpPort != updated.ftpPort ||
                    old.webdavPort != updated.webdavPort ||
                    old.username != updated.username ||
                    old.password != updated.password
                vm.updateAccount(updated) {
                    LogBus.info("账户「${updated.name}」配置已保存")
                    if (server.running && changedService) {
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
    isActive: Boolean,
    activeNow: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Check, contentDescription = "当前账户", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(14.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (activeNow) "活跃" else "休眠",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (activeNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
                Text(
                    "${account.repoLabel} ｜ ${account.mode} : ${account.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isActive) {
                OutlinedButton(onClick = onSelect) { Text("切换") }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun AccountNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("账户名称") },
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
                    placeholder = { Text("留空 = 使用上方共享令牌") },
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
                            else if (token.isBlank()) "当前无可用的 GitHub 令牌，服务无法启动"
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