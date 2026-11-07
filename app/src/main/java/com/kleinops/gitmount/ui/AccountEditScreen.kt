package com.kleinops.gitmount.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kleinops.gitmount.data.AccountConfig
import com.kleinops.gitmount.data.RepoBrief
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
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

    // ---- 仓库绑定（列表选择）----
    var bindingRepo by remember(account?.id) { mutableStateOf<RepoBrief?>(null) }
    var unbind by remember(account?.id) { mutableStateOf(false) }
    // 「新建仓库」面板（页面内展开，非弹窗）
    var showNewRepo by remember(account?.id) { mutableStateOf(false) }
    var newRepoName by remember(account?.id) { mutableStateOf("") }
    var newRepoPrivate by remember(account?.id) { mutableStateOf(true) }
    var creatingRepo by remember(account?.id) { mutableStateOf(false) }

    val effectToken = token.trim().ifBlank { shared }
    val isWorking = scanState is ScanState.Working || creatingRepo
    val currentOwner = account?.owner ?: ""
    val currentRepo = account?.repoName ?: ""
    val currentBinding = bindingRepo?.let { it.fullName }
        ?: if (currentOwner.isNotBlank() && currentRepo.isNotBlank()) "$currentOwner/$currentRepo" else ""

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

            // ---- 分区二：绑定仓库（列表选择，点击即绑定）----
            SectionCard(title = "绑定仓库") {
                if (unbind) {
                    Text(
                        "已选择解绑：保存后该挂载点不再绑定任何仓库。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                } else if (currentBinding.isNotBlank()) {
                    Text(
                        "当前绑定：$currentBinding",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            unbind = false
                            if (effectToken.isBlank()) return@OutlinedButton
                            vm.scanRepos(effectToken)
                        },
                        enabled = effectToken.isNotBlank() && !isWorking,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (scanState is ScanState.Working) {
                            CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("扫描中…")
                        } else {
                            Text("扫描我的仓库")
                        }
                    }
                    OutlinedButton(
                        onClick = { showNewRepo = !showNewRepo },
                        enabled = effectToken.isNotBlank() && !isWorking,
                        modifier = Modifier.weight(1f),
                    ) { Text("新建仓库") }
                }
                Spacer(Modifier.height(8.dp))
                if (currentBinding.isNotBlank()) {
                    TextButton(
                        onClick = { unbind = !unbind; if (unbind) bindingRepo = null },
                    ) { Text(if (unbind) "取消解绑" else "解绑仓库") }
                    Spacer(Modifier.height(4.dp))
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
                        Spacer(Modifier.height(6.dp))
                        if (s.repos.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                s.repos.forEach { r ->
                                    val full = r.fullName
                                    val isCurrent = bindingRepo?.fullName == full ||
                                        (bindingRepo == null && !unbind && full.equals(
                                            currentBinding,
                                            ignoreCase = true,
                                        ))
                                    // 选中态颜色平滑过渡（MD3 动效）
                                    val rowColor by animateColorAsState(
                                        targetValue = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                                        label = "repoRowColor",
                                    )
                                    Surface(
                                        onClick = {
                                            bindingRepo = r
                                            unbind = false
                                        },
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
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> Unit
                }

                // 「新建仓库」面板（页面内展开，带展开/收起动效）
                AnimatedVisibility(
                    visible = showNewRepo,
                    enter = expandVertically(
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                    ) + fadeIn(tween(240)),
                    exit = shrinkVertically(
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                    ) + fadeOut(tween(200)),
                ) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newRepoName,
                        onValueChange = { newRepoName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("新仓库名称") },
                        placeholder = { Text("例如 my-repo") },
                    )
                    Spacer(Modifier.height(4.dp))
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
                    Spacer(Modifier.height(6.dp))
                    Button(
                        enabled = !creatingRepo && newRepoName.trim().isNotBlank(),
                        onClick = {
                            creatingRepo = true
                            vm.createRepoOnly(effectToken, newRepoName.trim(), newRepoPrivate) { brief ->
                                creatingRepo = false
                                showNewRepo = false
                                newRepoName = ""
                                if (brief != null) {
                                    bindingRepo = brief
                                    unbind = false
                                    LogBus.info("仓库 ${brief.fullName} 创建成功，已选中")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (creatingRepo) {
                            CircularProgressIndicator(modifier = Modifier.width(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("创建中…")
                        } else {
                            Text("创建并绑定")
                        }
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