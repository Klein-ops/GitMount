package com.kleinops.gitmount

import android.app.Application
import com.kleinops.gitmount.service.FileLogger
import com.kleinops.gitmount.service.LogBus
import com.kleinops.gitmount.service.MountPointManager
import com.kleinops.gitmount.service.ServiceSelfHeal

class GitMountApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 注入应用上下文（挂载点管理器需要：大文件上传临时目录等）
        MountPointManager.appContext = applicationContext
        // 初始化文件日志（写到应用外部私有目录，供排查问题直接读取）
        FileLogger.init(this)
        LogBus.info("GitMount 应用启动，文件日志已初始化")
        // 服务自愈：进程被系统回收（START_STICKY 重建）后主动检查是否需要恢复服务。
        // 国产 ROM 可能把 onStartCommand 的重新分发拖延数小时，导致后台自启动
        // 一直卡在"正在启动"——这里不再干等系统调度，进程一重建就按持久化意图拉起。
        ServiceSelfHeal.schedule(this)
    }
}