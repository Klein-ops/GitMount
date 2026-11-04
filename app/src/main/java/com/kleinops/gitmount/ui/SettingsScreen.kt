package com.kleinops.gitmount.ui

import android.content.Intent
import android.provider.Settings
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
import com.kleinops.gitmount.R
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * 设置页（独立页面，替代原先的「服务设置」对话框）。
 *
 * 设计原则：FTP 与 WebDAV **共用**的选项（协议切换、仅本机、隐藏通知、
 * 后台隐藏、自启动、访问账号）全部放在这里，不再分散到协议各自的配置页；
 * 端口因为两种协议默认值不同，仍按当前协议呈现一个输入框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
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
    var loopbackOnly by remember(settings.loopbackOnly) { mutableStateOf(settings.loopbackOnly) }
    var hideNotification by remember(settings.hideNotification) { mutableStateOf(settings.hideNotification) }
    var hideFromRecents by remember(settings.hideFromRecents) { mutableStateOf(settings.hideFromRecents) }
    var autoStart by remember(settings.autoStart) { mutableStateOf(settings.autoStart) }
    var showPassword by remember { mutableStateOf(false) }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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

            // ---- 协议 ----
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

            // ---- 网络与后台（两种协议共用）----
            SectionCard(title = "网络与后台") {
                SettingSwitchRow(
                    title = "仅本机访问",
                    desc = "开启后只监听 127.0.0.1，局域网内其他设备无法访问，更安全",
                    checked = loopbackOnly,
                    onCheckedChange = { loopbackOnly = it },
                )
                Spacer(Modifier.height(6.dp))
                SettingSwitchRow(
                    title = stringResourceSafe(R.string.settings_bg_hide_notif),
                    desc = stringResourceSafe(R.string.settings_bg_hide_notif_desc),
                    // 开关语义：打开 = 显示常驻通知（!hideNotification）
                    checked = !hideNotification,
                    onCheckedChange = { show -> hideNotification = !show },
                )
                Spacer(Modifier.height(6.dp))
                SettingSwitchRow(
                    title = stringResourceSafe(R.string.settings_bg_hide_from_recents),
                    desc = stringResourceSafe(R.string.settings_bg_hide_from_recents_desc),
                    checked = hideFromRecents,
                    onCheckedChange = { hideFromRecents = it },
                )
                Spacer(Modifier.height(6.dp))
                SettingSwitchRow(
                    title = stringResourceSafe(R.string.settings_bg_auto_start),
                    desc = stringResourceSafe(R.string.settings_bg_auto_start_desc),
                    checked = autoStart,
                    onCheckedChange = { autoStart = it },
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResourceSafe(R.string.settings_bg_battery),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResourceSafe(R.string.settings_bg_battery_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { openBatterySettings() }) {
                        Text(stringResourceSafe(R.string.settings_bg_battery_btn))
                    }
                }
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
                    val runningPort = if (server.running) server.port else 0
                    if (checkPort != runningPort &&
                        !com.kleinops.gitmount.service.NetUtils.portAvailable(checkPort, if (loopbackOnly) "127.0.0.1" else null)
                    ) {
                        scope.launch { snackbar.showSnackbar("端口 $checkPort 已被占用，请换一个端口") }
                        return@Button
                    }
                    // 影响服务运行参数的改动：模式 / 端口 / 仅本机 / 账号 / 密码 / 通知显示。
                    // 保存后若服务正在运行，自动用新设置重启，不再要求用户手动停止再启动。
                    val savedPort = if (mode == ServerMode.FTP) ftpPort.toIntOrNull() else webdavPort.toIntOrNull()
                    val oldPort = if (runCatching { ServerMode.valueOf(settings.mode) }.getOrDefault(ServerMode.WEBDAV) == ServerMode.FTP) {
                        settings.ftpPort
                    } else {
                        settings.webdavPort
                    }
                    val restartNeeded = server.running &&
                        (mode.name != settings.mode ||
                            savedPort != oldPort ||
                            loopbackOnly != settings.loopbackOnly ||
                            username.trim() != settings.username ||
                            password != settings.password ||
                            hideNotification != settings.hideNotification)
                    vm.saveServerSettings(
                        mode = mode,
                        ftpPort = ftpPort.toIntOrNull() ?: 2121,
                        webdavPort = webdavPort.toIntOrNull() ?: 8080,
                        username = username.trim(),
                        password = password,
                        loopbackOnly = loopbackOnly,
                        hideNotification = hideNotification,
                        autoStart = autoStart,
                        hideFromRecents = hideFromRecents,
                        // 落盘完成后：需要重启则重启，然后返回主界面（主界面响应式展示最新状态）
                        onSaved = {
                            if (restartNeeded) {
                                LogBus.info("服务设置已变更，自动重启服务使新设置生效")
                                MountService.restart(context)
                            }
                            onBack()
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存设置") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 单行「标题 + 说明 + 开关」，设置页大量复用 */
@Composable
private fun SettingSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 直接取字符串资源（本页部分文案从 strings.xml 复用） */
@Composable
private fun stringResourceSafe(id: Int): String = androidx.compose.ui.res.stringResource(id)
