package com.kleinops.gitmount.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kleinops.gitmount.data.AccountConfig
import com.kleinops.gitmount.data.RepoBrief
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.ui.components.InfoRow
import com.kleinops.gitmount.ui.components.SectionCard

/**
 * 账户配置页（全屏页面，非弹窗）：新建（account=null）与编辑（account!=null）共用。
 * 三大分区：基本信息 / 绑定仓库（列表选择，旧版样式）/ 服务配置。
 * 底部大按钮保存；保存后服务运行中自动重启使新配置生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    vm: MainViewModel,
    account: AccountConfig?, // null = 新建账户
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val server by vm.serverState.collectAsStateWithLifecycle()
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    val repoOpState by vm.repoOpState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val isNew = account == null
    val shared = settings.sharedToken

    // ---- 基本信息 ----
    var name by remember(account?.id) { mutableStateOf(account?.name ?: "") }
    var enabled by remember(account?.id) { mutableStateOf(account?.enabled ?: true) }
    var token by remember(account?.id) { mutableStateOf(account?.token ?: "") }
    var showToken by remember { mutableStateOf(false) }

    // ---- 服务配置 ----
    var mode by remember(account?.id) {
        mutableStateOf(
            runCatching { ServerMode.valueOf(account?.mode ?: "WEBDAV") }.getOrDefault(ServerMode.WEBDAV)
        )
    }
    var ftpPort by remember(account?.id) { mutableStateOf((account?.ftpPort ?: 2121).toString()) }
    var webdavPort by remember(account?.id) { mutableStateOf((account?.webdavPort ?: 8080).toString()) }
    var username by remember(account?.id) { mutableStateOf(account?.username ?: "gitmount") }
    var password by remember(account?.id) { mutableStateOf(account?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }

    // ---- 仓库绑定（选择放到独立页面，配置页只做展示 + 容量/清空历史）----
    var bindingRepo by remember(account?.id) { mutableStateOf<RepoBrief?>(null) }
    var unbind by remember(account?.id) { mutableStateOf(false) }
    // 独立「选择仓库」页面开关
    var showRepoSelect by remember(account?.id) { mutableStateOf(false) }
    // 清空提交历史确认对话框
    var showClearHistoryConfirm by remember(account?.id) { mutableStateOf(false) }

    val effectToken = token.trim().ifBlank { shared }
    val currentOwner = account?.owner ?: ""
    val currentRepo = account?.repoName ?: ""
    val currentBinding = bindingRepo?.let { it.fullName }
        ?: if (currentOwner.isNotBlank() && currentRepo.isNotBlank()) "$currentOwner/$currentRepo" else ""

    // 容量/清空历史针对的仓库 = 配置页当前所选（bindingRepo 优先，未选则用挂载点旧绑定）
    val repoOwner = bindingRepo?.fullName?.substringBefore('/') ?: currentOwner
    val repoName = bindingRepo?.fullName?.substringAfter('/') ?: currentRepo
    val repoBranch = bindingRepo?.defaultBranch ?: (account?.branch ?: "main")
    val hasRepo = currentBinding.isNotBlank() && !unbind

    // 已绑定仓库且令牌有效时自动查询容量（换仓库/换令牌自动重查）
    LaunchedEffect(repoOwner, repoName, effectToken) {
        if (hasRepo && effectToken.isNotBlank()) {
            vm.loadRepoInfo(effectToken, repoOwner, repoName)
        }
    }

    // 配置页 ↔ 选择仓库页：用 AnimatedContent 做进出手势动效（与主界面/挂载点页一致）
    AnimatedContent(
        targetState = showRepoSelect,
        transitionSpec = {
            if (targetState) {
                (slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(300)))
            } else {
                (slideInHorizontally(tween(300)) { -it / 3 } + fadeIn(tween(300)))
                    .togetherWith(slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)))
            }
        },
        label = "repoSelectNav",
    ) { showSelect ->
        if (showSelect) {
            RepoSelectScreen(
                vm = vm,
                effectToken = effectToken,
                bindingRepo = bindingRepo,
                currentBinding = currentBinding,
                onPick = { r ->
                    bindingRepo = r
                    unbind = false
                    showRepoSelect = false
                },
                onBack = { showRepoSelect = false },
            )
        } else {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建挂载点" else "编辑挂载点") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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

            // ---- 分区一：基本信息 ----
            SectionCard(title = "基本信息") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("挂载点名称") },
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用该挂载点", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "停用后服务跳过它、不监听其端口",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("GitHub 访问令牌") },
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
                            if (token.isBlank() && shared.isNotBlank()) "当前使用共享令牌（已配置）"
                            else if (token.isBlank()) "未配置令牌，服务无法为该挂载点启动"
                            else "使用本挂载点独立令牌",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            // ---- 分区二：仓库（展示 + 容量 + 清空历史，选择走独立页面）----
            SectionCard(title = "仓库") {
                if (!hasRepo) {
                    Text(
                        "尚未绑定仓库。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (unbind) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已选择解绑：保存后该挂载点不再绑定任何仓库。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showRepoSelect = true },
                        enabled = effectToken.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("选择仓库") }
                    if (unbind) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { unbind = false }) { Text("取消解绑") }
                    }
                } else {
                    InfoRow("仓库", currentBinding, mono = true)
                    InfoRow("分支", repoBranch, mono = true)

                    Spacer(Modifier.height(8.dp))
                    // ---- 容量展示（同步恢复旧版容量卡片）----
                    when (val op = repoOpState) {
                        is RepoOpState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在查询容量…", style = MaterialTheme.typography.bodySmall)
                        }
                        is RepoOpState.Error -> Text(
                            "容量查询失败：${op.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        is RepoOpState.ClearingHistory -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在清空提交历史…", style = MaterialTheme.typography.bodySmall)
                        }
                        is RepoOpState.Success -> {
                            val info = op.info
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
                        RepoOpState.Idle -> Text(
                            "未查询容量（点击下方刷新）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    val busy = repoOpState is RepoOpState.Loading || repoOpState is RepoOpState.ClearingHistory
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showRepoSelect = true },
                            enabled = effectToken.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("选择仓库") }
                        OutlinedButton(
                            onClick = { vm.loadRepoInfo(effectToken, repoOwner, repoName) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("刷新容量") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showClearHistoryConfirm = true },
                            enabled = repoOpState !is RepoOpState.ClearingHistory,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (repoOpState is RepoOpState.ClearingHistory) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("清空中…")
                            } else {
                                Text("清空提交历史")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                unbind = true
                                bindingRepo = null
                                vm.clearRepoOpState()
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("解绑") }
                    }
                }
            }

            // ---- 分区三：服务配置 ----
            SectionCard(title = "服务配置") {
                Text(
                    "协议",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ServerMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(index, ServerMode.entries.size),
                        ) { Text(m.label) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val portValue = if (mode == ServerMode.FTP) ftpPort else webdavPort
                OutlinedTextField(
                    value = portValue,
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
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("访问用户名") },
                )
                Spacer(Modifier.height(10.dp))
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
                // 认证状态提示：留空 = 匿名模式；只填一项 = 半配置（登录会被拒绝）
                val bothBlank = username.isBlank() && password.isBlank()
                val halfFilled = username.isBlank() != password.isBlank()
                AnimatedVisibility(
                    visible = bothBlank || halfFilled,
                    enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(160, easing = FastOutSlowInEasing)) + fadeOut(tween(160)),
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                bothBlank -> "已启用匿名模式：无需用户名密码即可访问（仅建议在可信局域网使用）"
                                else -> "用户名与密码需同时填写，或同时留空使用匿名模式"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (halfFilled) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val n = name.trim()
                    if (n.isBlank()) return@Button
                    val base = AccountConfig(
                        id = account?.id ?: "",
                        name = n,
                        token = token.trim(),
                        owner = bindingRepo?.fullName?.substringBefore('/')
                            ?: if (unbind) "" else currentOwner,
                        repoName = bindingRepo?.fullName?.substringAfter('/')
                            ?: if (unbind) "" else currentRepo,
                        branch = bindingRepo?.defaultBranch
                            ?: if (unbind) "main" else (account?.branch ?: "main"),
                        mode = mode.name,
                        ftpPort = ftpPort.toIntOrNull() ?: (account?.ftpPort ?: 2121),
                        webdavPort = webdavPort.toIntOrNull() ?: (account?.webdavPort ?: 8080),
                        username = username.trim(),
                        password = password,
                        enabled = enabled,
                    )
                    if (isNew) {
                        vm.createAccount(base) {
                            LogBus.info("挂载点「$n」已创建")
                            onSaved()
                        }
                    } else {
                        val serviceChanged = account!!.mode != base.mode ||
                            account.ftpPort != base.ftpPort ||
                            account.webdavPort != base.webdavPort ||
                            account.username != base.username ||
                            account.password != base.password ||
                            account.token != base.token ||
                            account.owner != base.owner ||
                            account.repoName != base.repoName ||
                            account.branch != base.branch
                        vm.updateAccount(base) {
                            if (server.running && !server.starting && serviceChanged) {
                                LogBus.info("挂载点配置已变更，重启服务使新设置生效")
                                MountService.restart(context)
                            }
                            onSaved()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (isNew) "创建挂载点" else "保存") }

            Spacer(Modifier.height(24.dp))
        }
    }
        }
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text("清空提交历史？") },
            text = {
                Text(
                    "将把仓库 $currentBinding 的全部提交历史压缩为 1 个（文件全部保留）。" +
                        "此操作不可撤销；容量数字由 GitHub 后台延迟回收，不会立即下降。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistoryConfirm = false
                        vm.clearRepoHistory(effectToken, repoOwner, repoName, repoBranch)
                    },
                ) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 选择仓库全屏页：从 Token 名下扫描并选择要绑定的仓库，或就地新建仓库。
 * 与配置页分离——仓库多时扫描列表 + 新建面板不会挤占日常配置空间；
 * 选完即返回配置页，容量/清空历史等操作仍在配置页的「仓库」卡片里做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoSelectScreen(
    vm: MainViewModel,
    effectToken: String,
    bindingRepo: RepoBrief?,
    currentBinding: String,
    onPick: (RepoBrief) -> Unit,
    onBack: () -> Unit,
) {
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    // 「新建仓库」面板（页面内展开，非弹窗）
    var showNewRepo by remember { mutableStateOf(false) }
    var newRepoName by remember { mutableStateOf("") }
    var newRepoPrivate by remember { mutableStateOf(true) }
    var creatingRepo by remember { mutableStateOf(false) }

    // 删除仓库：三级确认（①确认删除？→ ②再次确认？→ ③输入完整仓库名）
    // deleteStep：null=未删除，1=第一次确认，2=第二次确认，3=输入完整名
    var deleteTarget by remember { mutableStateOf<RepoBrief?>(null) }
    var deleteStep by remember { mutableStateOf(0) }
    var deleteInput by remember { mutableStateOf("") }
    var deletingRepo by remember { mutableStateOf(false) }

    // 进入页面自动扫描一次（5 分钟内带缓存，不会重复请求）
    LaunchedEffect(effectToken) {
        if (scanState !is ScanState.Success) vm.scanRepos(effectToken)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择仓库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 扫描 / 新建按钮行
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.scanRepos(effectToken) },
                    enabled = effectToken.isNotBlank() && scanState !is ScanState.Working,
                    modifier = Modifier.weight(1f),
                ) {
                    if (scanState is ScanState.Working) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("扫描中…")
                    } else {
                        Text("刷新仓库列表")
                    }
                }
                OutlinedButton(
                    onClick = { showNewRepo = !showNewRepo },
                    enabled = effectToken.isNotBlank() && scanState !is ScanState.Working,
                    modifier = Modifier.weight(1f),
                ) { Text(if (showNewRepo) "收起新建" else "新建仓库") }
            }

            // 「新建仓库」面板（带展开/收起动效）
            AnimatedVisibility(
                visible = showNewRepo,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                ) + fadeIn(tween(240)),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ) + fadeOut(tween(200)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("新仓库名称") },
                        placeholder = { Text("例如 my-repo") },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (newRepoPrivate) "私有仓库" else "公开仓库", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "私有：仅自己和受邀协作者可见",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = newRepoPrivate, onCheckedChange = { newRepoPrivate = it })
                    }
                    Button(
                        enabled = !creatingRepo && newRepoName.trim().isNotBlank(),
                        onClick = {
                            creatingRepo = true
                            vm.createRepoOnly(effectToken, newRepoName.trim(), newRepoPrivate) { brief ->
                                creatingRepo = false
                                if (brief != null) {
                                    onPick(brief)
                                    LogBus.info("仓库 ${brief.fullName} 创建成功，已选中")
                                } else {
                                    showNewRepo = false
                                    newRepoName = ""
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (creatingRepo) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("创建中…")
                        } else {
                            Text("创建并绑定")
                        }
                    }
                }
            }

            // 仓库列表
            when (val s = scanState) {
                is ScanState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在扫描仓库…", style = MaterialTheme.typography.bodySmall)
                }
                is ScanState.Error -> Column {
                    Text(
                        "扫描失败：${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { vm.scanRepos(effectToken) }) { Text("重试") }
                }
                is ScanState.Success -> {
                    Text(
                        "账号 ${s.login}，共 ${s.repos.size} 个仓库。点击仓库即绑定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (s.repos.isEmpty()) {
                        Text(
                            "该账号下没有可用的仓库。可以点上方「新建仓库」。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            s.repos.forEach { r ->
                                val full = r.fullName
                                val isCurrent = bindingRepo?.fullName == full ||
                                    (bindingRepo == null && full.equals(currentBinding, ignoreCase = true))
                                // 选中态颜色平滑过渡（MD3 动效）
                                val rowColor by animateColorAsState(
                                    targetValue = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                                    label = "repoRowColor",
                                )
                                Surface(
                                    onClick = { onPick(r) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small,
                                    color = rowColor,
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
                                                "默认分支 ${r.defaultBranch}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                deleteTarget = r
                                                deleteStep = 1
                                                deleteInput = ""
                                            },
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "删除仓库 $full",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    // ---- 删除仓库三级确认 ----
    deleteTarget?.let { target ->
        val fullName = target.fullName
        when (deleteStep) {
            // 第 1 级：首次确认
            1 -> AlertDialog(
                onDismissRequest = { deleteTarget = null; deleteStep = 0; deleteInput = "" },
                title = { Text("删除仓库 $fullName？") },
                text = { Text("这将永久删除 GitHub 上的仓库 $fullName，包括其中全部文件、提交历史与设置。此操作不可撤销！") },
                confirmButton = {
                    Button(
                        onClick = { deleteStep = 2 },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("继续") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null; deleteStep = 0; deleteInput = "" }) { Text("取消") }
                },
            )
            // 第 2 级：二次确认
            2 -> AlertDialog(
                onDismissRequest = { deleteTarget = null; deleteStep = 0; deleteInput = "" },
                title = { Text("再次确认删除？") },
                text = { Text("你确定要删除仓库 $fullName 吗？仓库一旦删除无法恢复，GitHub 不会保留任何备份。") },
                confirmButton = {
                    Button(
                        onClick = { deleteStep = 3 },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("我确定要删除") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null; deleteStep = 0; deleteInput = "" }) { Text("取消") }
                },
            )
            // 第 3 级：输入完整仓库名校验
            3 -> AlertDialog(
                onDismissRequest = { deleteTarget = null; deleteStep = 0; deleteInput = "" },
                title = { Text("输入仓库全名以确认") },
                text = {
                    Column {
                        Text("请输入仓库完整名称以确认删除（不可撤销）：")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deleteInput,
                            onValueChange = { deleteInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("仓库全名") },
                            placeholder = { Text(fullName) },
                        )
                        if (deletingRepo) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在删除…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (deletingRepo) return@Button
                            if (deleteInput.trim() != fullName) return@Button
                            deletingRepo = true
                            val owner = fullName.substringBefore('/')
                            val repo = fullName.substringAfter('/')
                            vm.deleteRepo(effectToken, owner, repo) { result ->
                                deletingRepo = false
                                deleteTarget = null
                                deleteStep = 0
                                deleteInput = ""
                                result.onFailure {
                                    LogBus.error("删除仓库失败：${it.message}")
                                }
                            }
                        },
                        enabled = deleteInput.trim() == fullName && !deletingRepo,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(if (deletingRepo) "删除中…" else "永久删除") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { deleteTarget = null; deleteStep = 0; deleteInput = "" },
                        enabled = !deletingRepo,
                    ) { Text("取消") }
                },
            )
        }
    }
}
