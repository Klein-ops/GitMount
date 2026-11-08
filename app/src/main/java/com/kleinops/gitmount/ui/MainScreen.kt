package com.kleinops.gitmount.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kleinops.gitmount.data.AppSettings
import com.kleinops.gitmount.data.ServerMode
import com.kleinops.gitmount.service.MountPointManager
import com.kleinops.gitmount.service.MountService
import com.kleinops.gitmount.service.ServerController
import com.kleinops.gitmount.ui.components.ClickableInfoRow
import com.kleinops.gitmount.ui.components.EmptyHint
import com.kleinops.gitmount.ui.components.InfoRow
import com.kleinops.gitmount.ui.components.LogRow
import com.kleinops.gitmount.ui.components.SectionCard
import com.kleinops.gitmount.ui.components.StatusDot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val settings by vm.settings.collectAsStateWithLifecycle()
    // 高频/独立状态已全部下沉到各自卡片子组合内订阅（ServerCard / LogCard / RateLimitCard），
    // 服务运行时的会话数、流量统计、日志更新都只重启对应卡片，不会牵动整页重组

    var showSettings by remember { mutableStateOf(false) }
    var showMountPointList by remember { mutableStateOf(false) }

    // 进入后台（UI 不可见）时释放可重建的 UI 缓存，回前台自动恢复；
    // 服务端（FTP/WebDAV 监听、挂载点 repo 缓存）不受影响。
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentIsConfigured by rememberUpdatedState(settings.isConfigured)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.releaseUiMemory()
                Lifecycle.Event.ON_START -> if (currentIsConfigured) vm.loadRateLimit()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 页面导航（主界面 / 挂载点页 / 设置页），用 AnimatedContent 做切换动效：
    // 进入二级页从右滑入，返回时从左侧退回，整体淡入淡出
    AnimatedContent(
        targetState = when {
            showMountPointList -> 1
            showSettings -> 2
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
        label = "mainNav",
    ) { page ->
        when (page) {
            // 挂载点管理页（多挂载点：新建一步到位 / 编辑完整配置 / 启用开关 / 删除）
            1 -> {
                // 系统返回键：回到主界面而不是退出应用
                BackHandler { showMountPointList = false }
                MountPointListScreen(vm = vm, onBack = { showMountPointList = false })
            }
            // 设置页独立成页面（网络与后台类选项），返回后回到主界面
            2 -> {
                BackHandler { showSettings = false }
                SettingsScreen(vm = vm, onBack = { showSettings = false })
            }
            // ---- 主界面 ----
            else -> {

    // 已配置仓库时，自动查询一次 GitHub API 本小时剩余调用余额（换令牌后也重查）
    LaunchedEffect(settings.owner, settings.repoName, settings.branch, settings.token) {
        if (settings.isConfigured) vm.loadRateLimit()
    }

    // 打开应用自动启动服务（设置里可开关）。
    // 只在本次进程首次满足条件时触发一次，避免“用户手动停止后 running=false 又自动拉起”
    // 的死循环——autoStartTriggered 置位后，即使之后 running 变化也不再触发。
    var autoStartTriggered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings.isConfigured, settings.autoStart) {
        if (settings.isConfigured && settings.autoStart && !autoStartTriggered) {
            autoStartTriggered = true
            // 快照读取服务状态（不订阅，避免引入到顶层重组的订阅关系）
            val st = ServerController.state.value
            if (!st.running && !st.starting) {
                MountService.start(context)
            }
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
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
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

            // 运行状态 + 控制（全局服务：启动/停止作用于全部已启用挂载点）
            // 独立子组合：服务运行时的会话数/流量统计只重组本卡片，不牵动整页
            item {
                ServerCard(
                    vm = vm,
                    settings = settings,
                    scope = scope,
                    snackbar = snackbar,
                    onOpenMountPoints = { showMountPointList = true },
                )
            }

            // GitHub API 剩余调用余额（贴心功能：让用户知道这个小时还能调用多少次接口）
            // 独立子组合：余额查询状态的变化只重组本卡片，不牵动整页
            item {
                RateLimitCard(vm = vm)
            }

            // 运行日志
            // 独立子组合：服务运行时的日志流更新只重组本卡片，不牵动整页
            item {
                LogCard(vm = vm)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

/**
 * 服务状态卡片（运行状态 / 地址 / 会话流量 / 启停控制）。
 * 独立订阅 serverState：服务运行时的会话数、流量统计变化只重组本卡片，
 * 不牵动整页重组（性能优化）。
 */
@Composable
private fun ServerCard(
    vm: MainViewModel,
    settings: AppSettings,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    onOpenMountPoints: () -> Unit,
) {
    val context = LocalContext.current
    val server by vm.serverState.collectAsStateWithLifecycle()

    SectionCard(
        title = "服务",
        trailing = { StatusDot(running = server.running) },
    ) {
        if (server.starting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("正在启动…", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (server.running) {
            // 复制到剪贴板的工具函数
            fun copyToClipboard(text: String, label: String) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("GitMount 地址", text))
                scope.launch { snackbar.showSnackbar("$label 已复制到剪贴板") }
            }
            // 简略展示：全部已启用挂载点的状态 + 仓库 + 可复制地址（一行一个）。
            // 每个挂载点地址按各自实际绑定地址展示：仅本机的显示 127.0.0.1，否则显示局域网 IP。
            val shown = settings.mountPoints.filter { it.isUsable(settings.sharedToken) }
            shown.forEachIndexed { idx, mp ->
                val mode = runCatching { ServerMode.valueOf(mp.mode) }.getOrDefault(ServerMode.WEBDAV)
                val mpLoopback = mp.effectiveLoopback(settings.loopbackOnly)
                val baseIp = if (mpLoopback) "127.0.0.1" else (server.lanIp ?: "127.0.0.1")
                val addr = if (mode == ServerMode.WEBDAV) "http://$baseIp:${mp.port}/" else "ftp://$baseIp:${mp.port}/"
                val activeNow = MountPointManager.isActive(mp.id)
                InfoRow(
                    label = mp.name,
                    value = "${mode.label}:${mp.port} · ${if (activeNow) "活跃" else "休眠"}",
                    mono = true,
                )
                InfoRow("仓库", mp.repoLabel, mono = true)
                ClickableInfoRow(
                    label = "地址",
                    value = addr,
                    mono = true,
                    onClick = { copyToClipboard(addr, "${mp.name} 地址") },
                )
                if (idx < shown.lastIndex) Spacer(Modifier.height(6.dp))
            }
            if (server.activeSessions > 0 || server.totalBytes > 0) {
                InfoRow("活跃会话", server.activeSessions.toString(), mono = true)
                InfoRow("已传流量", formatBytes(server.totalBytes), mono = true)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (settings.loopbackOnly) {
                    "已开启「强制仅本机访问」：所有挂载点只监听 127.0.0.1。如需局域网访问，请到设置里关闭全局开关，再按需开启各挂载点。"
                } else if (shown.any { it.effectiveLoopback(false) }) {
                    "部分挂载点仅本机可访问（只监听 127.0.0.1），其余可局域网访问；各挂载点可单独开关。"
                } else {
                    "点击地址即可复制。局域网内其他设备用局域网地址访问；手机本机用 127.0.0.1 地址更稳定。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (settings.isConfigured) {
                    "服务未启动。点击下方按钮启动服务，将一次为全部已启用挂载点提供访问。"
                } else {
                    "尚未配置挂载点。请先点击下方「挂载点」按钮创建挂载点并绑定 GitHub 仓库。"
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
        // 启动 / 停止独占整行，全局作用：一次拉起/停止全部已启用挂载点的服务
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!server.running) {
                Button(
                    onClick = {
                        if (!settings.isConfigured) {
                            scope.launch { snackbar.showSnackbar("请先在「挂载点」页创建挂载点并绑定仓库") }
                            onOpenMountPoints()
                        } else {
                            MountService.start(context)
                        }
                    },
                    enabled = !server.starting,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("启动服务")
                }
            } else {
                FilledTonalButton(
                    onClick = { MountService.stop(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止服务")
                }
            }
        }
        // 第二行：挂载点（仓库绑定/协议/端口/启用开关都在挂载点页一步到位）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onOpenMountPoints,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("挂载点", maxLines = 1)
            }
        }
    }
}

/** 分享日志：把 FileLogger 当天的日志文件用系统分享面板发出去（排查问题一键导出） */
private fun shareLogs(context: Context) {
    val file = com.kleinops.gitmount.service.FileLogger.shareableLogFile()
    if (file == null) {
        android.widget.Toast.makeText(context, "暂无日志可分享", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "GitMount 日志 ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 GitMount 日志"))
    }.onFailure {
        android.widget.Toast.makeText(context, "分享失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * GitHub API 余额卡片。
 * 独立订阅 rateLimitState：查询状态的变化只重组本卡片，不牵动整页（性能优化）。
 */
@Composable
private fun RateLimitCard(vm: MainViewModel) {
    val rateLimitState by vm.rateLimitState.collectAsStateWithLifecycle()
    SectionCard(title = "GitHub API") {
        when (val rl = rateLimitState) {
            is RateLimitState.Idle -> Text(
                "未查询",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is RateLimitState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在查询本小时剩余调用次数…", style = MaterialTheme.typography.bodySmall)
            }
            is RateLimitState.Error -> Column {
                Text(
                    "余额查询失败：${rl.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { vm.loadRateLimit() }) { Text("重试") }
            }
            is RateLimitState.Success -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "本小时剩余 ${rl.remaining} / ${rl.limit} 次",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "GitHub REST API 调用额度（每小时重置；查询本身不扣额度）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.loadRateLimit() }) { Text("刷新") }
            }
        }
    }
}

/**
 * 运行日志卡片。
 * 独立订阅日志流：服务运行时的日志更新只重组本卡片，避免整页被高频日志拖动（性能优化）。
 * 默认折叠只显示最近 5 条；点击日志区域展开显示最近 30 条，再点收起。
 */
@Composable
private fun LogCard(vm: MainViewModel) {
    val context = LocalContext.current
    val logs by vm.logs.collectAsStateWithLifecycle()
    // 默认折叠（展开态不跨进程保存，进程重启后回到折叠，符合"默认折叠"预期）
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = "运行日志",
        trailing = {
            Row {
                TextButton(onClick = { shareLogs(context) }) { Text("分享") }
                TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
            }
        },
    ) {
        if (logs.isEmpty()) {
            EmptyHint("暂无日志")
        } else {
            val shown = if (expanded) logs.take(30) else logs.take(5)
            Column(modifier = Modifier.fillMaxWidth()) {
                shown.forEach { LogRow(it) }
                // 展开/收起统一用全宽 tonal 按钮：大触摸目标 + 图标，折叠/展开状态一眼可辨
                if (logs.size > 5 || expanded) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.width(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (expanded) "收起日志"
                            else "展开全部日志（共 ${logs.size} 条）"
                        )
                    }
                }
            }
        }
    }
}
