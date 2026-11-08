package com.kleinops.gitmount.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kleinops.gitmount.R
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.ui.components.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（右上角齿轮）：只放网络与后台类选项。
 * 服务本身的运行参数（协议 / 端口 / 访问账号 / 独立令牌）在「挂载点」页编辑
 * （主界面「挂载点」按钮进入，见 MountPointListScreen）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val server by vm.serverState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var loopbackOnly by remember(settings.loopbackOnly) { mutableStateOf(settings.loopbackOnly) }
    var hideNotification by remember(settings.hideNotification) { mutableStateOf(settings.hideNotification) }
    var hideFromRecents by remember(settings.hideFromRecents) { mutableStateOf(settings.hideFromRecents) }
    var autoStart by remember(settings.autoStart) { mutableStateOf(settings.autoStart) }

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

    // ---- 备份 / 恢复 ----
    val scope = rememberCoroutineScope()
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    // 备份加密开关：默认勾选加密
    var backupEncrypt by remember { mutableStateOf(true) }
    var backupPassword by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var pendingBackupContent by remember { mutableStateOf<String?>(null) }
    var pendingRestoreContent by remember { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // 备份：生成内容后让用户选保存位置（SAF 创建文档）
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = pendingBackupContent ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        pendingBackupContent = null
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                toast(if (ok) "备份已保存" else "备份保存失败")
            }
        }
    }

    // 恢复：选择备份文件并读取内容
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (content == null || content.isBlank()) {
                    toast("读取备份文件失败")
                    return@withContext
                }
                if (content.trimStart().startsWith("{")) {
                    // 明文 JSON 备份：直接恢复
                    vm.importBackup(content, "") { result ->
                        result.onSuccess { n -> toast("已恢复 $n 个挂载点") }
                            .onFailure { toast("恢复失败：${it.message}") }
                    }
                } else {
                    // 加密备份：需要口令
                    pendingRestoreContent = content
                    restorePassword = ""
                    showRestorePasswordDialog = true
                }
            }
        }
    }

    Scaffold(
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

            // ---- 网络与后台 ----
            SectionCard(title = "网络与后台") {
                SettingSwitchRow(
                    title = "强制仅本机访问",
                    desc = "开启后所有挂载点一律只监听 127.0.0.1（覆盖每个挂载点的单独开关），局域网内其他设备无法访问",
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
                    // 仅本机访问 / 常驻通知会直接影响运行中的服务与通知，需要时自动重启；
                    // 后台隐藏由 MainActivity.onResume 动态生效，自启动下次打开 App 生效，均无需重启。
                    val restartNeeded = server.running &&
                        (loopbackOnly != settings.loopbackOnly ||
                            hideNotification != settings.hideNotification)
                    vm.saveBackgroundSettings(
                        loopbackOnly = loopbackOnly,
                        hideNotification = hideNotification,
                        autoStart = autoStart,
                        hideFromRecents = hideFromRecents,
                        onSaved = {
                            if (restartNeeded) {
                                LogBus.info("网络与后台设置已变更，自动重启服务使新设置生效")
                                MountService.restart(context)
                            }
                            onBack()
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存设置") }

            // ---- 备份 / 恢复 ----
            SectionCard(title = "备份与恢复") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("配置备份", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "导出全部挂载点与设置到文件；可加密（默认）或明文",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { showBackupDialog = true }) {
                        Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("备份")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("恢复配置", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "从备份文件恢复挂载点与设置（明文或加密均可）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("恢复")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- 备份对话框：加密开关（默认开）+ 口令输入 ----
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("备份配置") },
            text = {
                Column {
                    Text(
                        "将导出全部挂载点、GitHub 令牌与设置。加密备份需要设置口令；明文备份令牌直接可读，请谨慎选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    // 加密开关（默认勾选）
                    SettingSwitchRow(
                        title = "加密备份",
                        desc = "使用 AES-256 加密，恢复时需要输入同一口令",
                        checked = backupEncrypt,
                        onCheckedChange = { backupEncrypt = it },
                    )
                    if (backupEncrypt) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = { backupPassword = it },
                            label = { Text("备份口令") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val password = if (backupEncrypt) backupPassword else ""
                        if (backupEncrypt && password.isBlank()) {
                            toast("请输入备份口令")
                            return@Button
                        }
                        showBackupDialog = false
                        vm.exportBackup(backupEncrypt, password) { result ->
                            result.onSuccess { content ->
                                pendingBackupContent = content
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                backupLauncher.launch("gitmount-backup-$stamp.json")
                            }.onFailure { toast("备份失败：${it.message}") }
                        }
                    }
                ) { Text("导出") }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) { Text("取消") }
            },
        )
    }

    // ---- 恢复口令对话框：加密备份需要输入口令 ----
    if (showRestorePasswordDialog) {
        AlertDialog(
            onDismissRequest = { showRestorePasswordDialog = false },
            title = { Text("输入备份口令") },
            text = {
                Column {
                    Text(
                        "该备份已加密，请输入备份时设置的口令",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text("备份口令") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val content = pendingRestoreContent ?: return@Button
                        if (restorePassword.isBlank()) {
                            toast("请输入备份口令")
                            return@Button
                        }
                        showRestorePasswordDialog = false
                        pendingRestoreContent = null
                        vm.importBackup(content, restorePassword) { result ->
                            result.onSuccess { n -> toast("已恢复 $n 个挂载点") }
                                .onFailure { toast("恢复失败：${it.message}") }
                        }
                    }
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showRestorePasswordDialog = false }) { Text("取消") }
            },
        )
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