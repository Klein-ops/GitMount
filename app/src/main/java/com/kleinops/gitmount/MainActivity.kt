package com.kleinops.gitmount

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kleinops.gitmount.data.SettingsRepository
import com.kleinops.gitmount.ui.MainScreen
import com.kleinops.gitmount.ui.theme.GitMountTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 未授权时前台服务仍可运行，只是通知不可见（Android 13+）
            if (!granted) {
                com.kleinops.gitmount.service.LogBus.warn("通知权限被拒绝，服务状态将无法在通知栏展示")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        applyRecentsVisibilityFromSettings()

        setContent {
            GitMountTheme {
                MainScreen()
            }
        }
    }

    /**
     * “后台隐藏”设置：从设置读取 hideFromRecents，动态设置最近任务可见性。
     * 用 onResume 时机（每次回到前台都会应用），用户改完设置返回主界面即生效。
     */
    override fun onResume() {
        super.onResume()
        applyRecentsVisibilityFromSettings()
    }

    private fun applyRecentsVisibilityFromSettings() {
        val settingsRepo = SettingsRepository(applicationContext)
        lifecycleScope.launch {
            val hide = runCatching { settingsRepo.settings.first().hideFromRecents }.getOrDefault(false)
            runCatching {
                val am = getSystemService(ActivityManager::class.java)
                am.appTasks.firstOrNull()?.setExcludeFromRecents(hide)
            }.onFailure {
                com.kleinops.gitmount.service.LogBus.warn("设置最近任务隐藏失败：${it.message}")
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}