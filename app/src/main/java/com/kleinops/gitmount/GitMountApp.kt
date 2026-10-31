package com.kleinops.gitmount

import android.app.Application
import com.kleinops.gitmount.service.FileLogger
import com.kleinops.gitmount.service.LogBus

class GitMountApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化文件日志（写到应用外部私有目录，供排查问题直接读取）
        FileLogger.init(this)
        LogBus.info("GitMount 应用启动，文件日志已初始化")
    }
}