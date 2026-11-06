package com.kleinops.gitmount.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * 服务配置页（主界面与「仓库」并排的按钮进入）。
 * 只放服务本身的运行参数：服务协议（WebDAV / FTP）、端口、访问账号（用户名 / 密码）。
 * 网络与后台类选项（仅本机访问、常驻通知、后台隐藏、自启动等）在右上角「设置」页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceConfigScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val server by vm.serverState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 用 settings 的具体字段作为 remember key：settings 从默认值加载为真实值时会重置一次，
    // 之后用户在本页的编辑不会被 DataStore 的再次发射覆盖。
    var mode by remember(settings.mode) {
        mutableStateOf(runCatching { ServerMode.valueOf(settings.mode) }.getOrDefault(ServerMode.WEBDAV))
    }
    var ftpPort by remember(settings.ftpPort) { mutableStateOf(settings.ftpPort.toString()) }
    var webdavPort by remember(settings.webdavPort) { mutableStateOf(settings.webdavPort.toString()) }
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var password by remember(settings.password) { mutableStateOf(settings.password) }
    var showPassword by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("服务配置") },
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

            // ---- 服务协议 ----
            SectionCard(title = "服务协议") {
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
                            if (mode == ServerMode.FTP) "建议 2121（1024 以上免 root）。FTP 与 WebDAV 端口分别记忆"
                            else "建议 8080。FTP 与 WebDAV 端口分别记忆",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            // ---- 访问认证（两种协议共用）----
            SectionCard(title = "访问账号") {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("访问用户名") },
                )
                Spacer(Modifier.height(8.dp))
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
                    supportingText = {
                        Text(
                            "仅用于局域网访问认证，与 GitHub 令牌无关",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    // 端口占用预检：保存设置前先试 bind 一下当前协议端口，
                    // 被占用时给出明确提示，避免启动后才发现端口被占。
                    val checkPort = if (mode == ServerMode.FTP) {
                        ftpPort.toIntOrNull()
                    } else {
                        webdavPort.toIntOrNull()
                    }
                    if (checkPort == null || checkPort !in 1..65535) {
                        scope.launch { snackbar.showSnackbar("端口必须是 1~65535 的整数") }
                        return@Button
                    }
                    // 服务正在运行时，当前运行端口被自己的服务占用属于正常情况，
                    // 只有「改成了别的端口」才需要检查新端口是否可用。
                    // 端口预检按「仅本机访问」设置决定监听地址，该设置在右上角设置页维护。
                    val runningPort = if (server.running) server.port else 0
                    if (checkPort != runningPort &&
                        !com.kleinops.gitmount.service.NetUtils.portAvailable(
                            checkPort,
                            if (settings.loopbackOnly) "127.0.0.1" else null,
                        )
                    ) {
                        scope.launch { snackbar.showSnackbar("端口 $checkPort 已被占用，请换一个端口") }
                        return@Button
                    }
                    // 影响服务运行参数的改动：模式 / 端口 / 账号 / 密码。
                    // 保存后若服务正在运行，自动用新设置重启，不再要求用户手动停止再启动。
                    val savedPort = if (mode == ServerMode.FTP) ftpPort.toIntOrNull() else webdavPort.toIntOrNull()
                    val oldPort = if (runCatching { ServerMode.valueOf(settings.mode) }
                            .getOrDefault(ServerMode.WEBDAV) == ServerMode.FTP
                    ) {
                        settings.ftpPort
                    } else {
                        settings.webdavPort
                    }
                    val restartNeeded = server.running &&
                        (mode.name != settings.mode ||
                            savedPort != oldPort ||
                            username.trim() != settings.username ||
                            password != settings.password)
                    vm.saveServiceConfig(
                        mode = mode,
                        ftpPort = ftpPort.toIntOrNull() ?: 2121,
                        webdavPort = webdavPort.toIntOrNull() ?: 8080,
                        username = username.trim(),
                        password = password,
                        // 落盘完成后：需要重启则重启，然后返回主界面（主界面响应式展示最新状态）
                        onSaved = {
                            if (restartNeeded) {
                                LogBus.info("服务配置已变更，自动重启服务使新设置生效")
                                MountService.restart(context)
                            }
                            onBack()
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存配置") }

            Spacer(Modifier.height(24.dp))
        }
    }
}