package com.kleinops.gitmount.service

import com.kleinops.gitmount.data.MountPointConfig
import com.kleinops.gitmount.data.GitHubClient
import com.kleinops.gitmount.data.GitRepository
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 多挂载点动态激活管理器（服务层单例）。
 *
 * 沉睡挂载点不持有 GitRepository（不占缓存/写锁/连接），只在认证通过后的
 * 第一个仓库操作时懒创建（首次 0.5~2s，含一次 GitHub 连通性探测），
 * 空闲超时后释放回到沉睡。端口监听由 MountService 为每挂载点独立维持。
 */
object MountPointManager {

    /**
     * 空闲回收阈值：挂载点无活跃操作超过该时长即释放仓库缓存。
     * 8 分钟：介于用户要求的 5~10 分钟之间；大文件传输由 activeOps
     * 全程占用保护，不会被该阈值误杀（FTP 会话 beginOp/endOp 严格配对）。
     */
    private const val IDLE_RECLAIM_MS = 8 * 60 * 1000L

    private class ActiveEntry {
        @Volatile var repo: GitRepository? = null
        @Volatile var lastAccess: Long = System.currentTimeMillis()
        @Volatile var activeOps: Int = 0
        fun touch() { lastAccess = System.currentTimeMillis() }
        fun isIdle(now: Long): Boolean = activeOps == 0 && now - lastAccess >= IDLE_RECLAIM_MS
    }

    private val entries = ConcurrentHashMap<String, ActiveEntry>()
    private val createLock = Object()

    /**
     * 大文件上传临时落盘目录（FTP 数据连接无长度，需先落盘统计总字节数再上传）。
     * 由 MountService 在 onCreate 设置（应用 cacheDir）；未配置时 FTP 大文件上传会明确报错。
     */
    @Volatile
    var spoolDir: java.io.File? = null

    /** 挂载点最近活跃刷新（WebDavHandler / FtpServer 在认证通过/每次操作后调用） */
    fun touch(mountPointId: String) {
        entries[mountPointId]?.touch()
    }

    /**
     * 同步获取挂载点仓库对象（工作线程调用）。首次创建并探测（0.5~2s），
     * 之后命中缓存开销可忽略。双检锁保证每挂载点单实例，FTP 与 WebDAV
     * 共享同一把写锁的语义保持不变。
     * @param sharedToken 全局共享令牌：挂载点独立令牌为空时用它（见 MountPointConfig.effectiveToken）
     */
    fun getRepoBlocking(mountPoint: MountPointConfig, sharedToken: String = ""): GitRepository {
        val id = mountPoint.id
        entries[id]?.repo?.let { entries[id]?.touch(); return it }
        synchronized(createLock) {
            entries[id]?.repo?.let { entries[id]?.touch(); return it }
            val entry = entries.getOrPut(id) { ActiveEntry() }
            entry.activeOps++
            try {
                val token = mountPoint.effectiveToken(sharedToken)
                val repo = GitRepository(GitHubClient(token), mountPoint.owner, mountPoint.repoName, mountPoint.branch, spoolDir)
                repo.visibleList("/") // 连通性探测：确保仓库真实可读
                entry.repo = repo
                entry.touch()
                LogBus.info("挂载点 ${mountPoint.name} 已激活（${mountPoint.owner}/${mountPoint.repoName}）")
                return repo
            } catch (e: Exception) {
                LogBus.error("挂载点 ${mountPoint.name} 激活失败：${e.message}")
                throw e
            } finally {
                entry.activeOps--
            }
        }
    }

    /** 挂载点登录/操作时刷新活跃时间并保持 repo 不过期（供 FTP 会话调用） */
    fun markActive(mountPointId: String) {
        entries[mountPointId]?.let { it.activeOps++; it.touch(); it.activeOps-- }
    }

    /**
     * 会话级活跃占用：FTP 控制连接从建立到断开全程占用（beginOp/endOp 配对）。
     * 占用期间该挂载点不会被空闲回收（isIdle 要求 activeOps==0），
     * 避免长连接/大文件传输中途被回收仓库缓存。
     */
    fun beginOp(mountPointId: String) {
        val entry = entries.getOrPut(mountPointId) { ActiveEntry() }
        entry.activeOps++
        entry.touch()
    }

    /** 与 [beginOp] 配对：会话结束释放占用 */
    fun endOp(mountPointId: String) {
        entries[mountPointId]?.let { entry ->
            if (entry.activeOps > 0) {
                entry.activeOps--
                entry.touch()
            }
        }
    }

    /**
     * 释放指定挂载点仓库缓存（切换/删除挂载点时调用）。
     * 若该挂载点正在执行操作（activeOps>0），强制释放会把进行中的 repo 操作弄挂，
     * 因此仅在无活跃操作时释放。
     */
    fun release(mountPointId: String) {
        val entry = entries.remove(mountPointId) ?: return
        if (entry.activeOps == 0) {
            entry.repo?.forceRelease()
            entry.repo = null
            LogBus.info("挂载点 $mountPointId 已释放缓存（回到沉睡）")
        } else {
            // 有活跃操作：先放开引用，等操作结束由空闲回收兜底
            entry.repo?.let { r -> Thread { runCatching { r.forceRelease() } }.start() }
            LogBus.info("挂载点 $mountPointId 有活跃操作，延迟释放缓存")
        }
    }

    /** 全局回收：释放所有空闲挂载点的仓库缓存（供 MountService 周期调用） */
    fun reclaimIdle() {
        val now = System.currentTimeMillis()
        entries.forEach { (id, entry) ->
            if (entry.isIdle(now)) {
                entries.remove(id)
                entry.repo?.forceRelease()
                entry.repo = null
                LogBus.info("挂载点 $id 空闲超时，已回收仓库缓存")
            }
        }
    }

    /** 释放全部（服务停止时调用） */
    fun releaseAll() {
        entries.forEach { (id, _) -> release(id) }
        entries.clear()
        registeredPorts.clear()
    }

    /** 当前已激活（持有 repo）的挂载点数——用于日志与调试 */
    fun activeCount(): Int = entries.values.count { it.repo != null }

    /** 指定挂载点当前是否已激活（持有 repo 缓存）——供 UI 展示"活跃/休眠"状态 */
    fun isActive(mountPointId: String): Boolean = entries[mountPointId]?.repo != null

    // ---------- 挂载点监听端口登记（供 FTP PASV 选端口时避让） ----------

    /** 全局已登记的被挂载点监听占用的端口（FTP 数据端口避让用），线程安全 */
    private val registeredPorts = ConcurrentHashMap.newKeySet<Int>()

    /** 登记挂载点监听端口（MountService 启动挂载点时调用） */
    fun registerPort(port: Int) {
        if (port > 0) registeredPorts.add(port)
    }

    /** 注销挂载点监听端口（MountService 停止挂载点时调用） */
    fun unregisterPort(port: Int) {
        registeredPorts.remove(port)
    }

    /** 当前所有被挂载点监听占用的端口快照 */
    fun occupiedPorts(): Set<Int> = registeredPorts.toSet()

    /** 校验仓库可读（预检用）：失败抛 IOException */
    @Throws(IOException::class)
    fun probe(mountPoint: MountPointConfig) {
        getRepoBlocking(mountPoint)
    }
}