package com.kleinops.gitmount.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kleinops.gitmount.data.AccountConfig
import com.kleinops.gitmount.service.AccountManager
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.ui.components.SectionCard

/**
 * 账户列表页：共享令牌 + 账户列表。
 * 新建 / 编辑账户进入全屏 [AccountEditScreen]，本页不再用弹窗堆配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountListScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    val accounts = settings.accounts

    var sharedToken by remember(settings.sharedToken) { mutableStateOf(settings.sharedToken) }
    var showSharedToken by remember { mutableStateOf(false) }

    // 页面导航：新建 / 编辑走向全屏配置页，用 AnimatedContent 做进出手势动效
    var creating by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AccountConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<AccountConfig?>(null) }

    // 页面层级：0=账户列表，1=新建挂载点，2=编辑挂载点
    // 用 targetState > initialState 判断前进/后退：前进右侧滑入，后退左侧退回
    AnimatedContent(
        targetState = when {
            creating -> 1
            editTarget != null -> 2
            else -> 0
        },
        transitionSpec = {
            if (targetState > initialState) {
                (slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(300)))
            } else {
                (slideInHorizontally(tween(300)) { -it / 3 } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)))
            }
        },
        label = "accountListNav",
    ) { page ->
        when (page) {
            1 -> AccountEditScreen(
                vm = vm,
                account = null,
                onBack = { creating = false },
                onSaved = { creating = false },
            )
            2 -> editTarget?.let { acc ->
                AccountEditScreen(
                    vm = vm,
                    account = acc,
                    onBack = { editTarget = null },
                    onSaved = { editTarget = null },
                )
            }
            else -> AccountListContent(
                vm = vm,
                accounts = accounts,
                sharedToken = sharedToken,
                showSharedToken = showSharedToken,
                onSharedTokenChange = { sharedToken = it },
                onToggleSharedToken = { showSharedToken = !showSharedToken },
                onBack = onBack,
                onCreate = { creating = true },
                onEdit = { editTarget = it },
                onDelete = { deleteTarget = it },
            )
        }
    }

    // 删除账户确认
    deleteTarget?.let { acc ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除挂载点？") },
            text = { Text("将删除挂载点「${acc.name}」及其仓库绑定、端口与访问账号配置。此操作不可撤销。") },
            confirmButton = {
                Button(onClick = { vm.deleteAccount(acc.id); deleteTarget = null }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 账户列表页主体内容（与原 AccountListScreen 的主 Scaffold 一致，抽出以便 AnimatedContent 切换） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountListContent(
    vm: MainViewModel,
    accounts: List<AccountConfig>,
    sharedToken: String,
    showSharedToken: Boolean,
    onSharedTokenChange: (String) -> Unit,
    onToggleSharedToken: () -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (AccountConfig) -> Unit,
    onDelete: (AccountConfig) -> Unit,
) {
    val server by vm.serverState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 账户开关切换：落盘后若服务在运行，自动重启让新开关生效
    fun onToggle(acc: AccountConfig, enabled: Boolean) {
        vm.setAccountEnabled(acc.id, enabled) {
            if (server.running && !server.starting) {
                LogBus.info("挂载点开关已变更，重启服务使其生效")
                MountService.restart(context)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("挂载点") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onCreate) {
                        Icon(Icons.Filled.Add, contentDescription = "新建挂载点")
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

            // ---- 共享 GitHub 令牌 ----
            SectionCard(title = "共享 GitHub 令牌") {
                Text(
                    "所有挂载点默认共用同一个令牌；挂载点令牌留空即自动继承。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = sharedToken,
                    onValueChange = onSharedTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("GitHub 访问令牌（全部挂载点共用）") },
                    placeholder = { Text("ghp_… 或 github_pat_…") },
                    visualTransformation = if (showSharedToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = onToggleSharedToken) {
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
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存共享令牌") }
            }

            // ---- 账户列表 ----
            SectionCard(title = "挂载点列表") {
                if (accounts.isEmpty()) {
                    Text(
                        "还没有挂载点。点右上角 + 新建。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        accounts.forEach { acc ->
                            AccountRow(
                                account = acc,
                                enabled = acc.enabled,
                                activeNow = AccountManager.isActive(acc.id),
                                onToggle = { onToggle(acc, it) },
                                onClick = { onEdit(acc) },
                                onDelete = { onDelete(acc) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建挂载点")
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "说明：服务启动是全局操作（主界面「启动服务」），一次为所有已启用挂载点监听端口。" +
                    "点挂载点行进入完整配置页（仓库 / 协议 / 端口 / 账号 / 令牌）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountConfig,
    enabled: Boolean,
    activeNow: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}