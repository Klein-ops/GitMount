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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kleinops.gitmount.R
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.ui.components.SectionCard

/**
 * 设置页（右上角齿轮）：只放网络与后台类选项。
 * 服务本身的运行参数（协议 / 端口 / 访问账号）已拆到「服务配置」页
 * （主界面与「仓库」并排的按钮进入），见 ServiceConfigScreen。
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