package com.kleinops.gitmount.service

import com.kleinops.gitmount.data.AccountConfig
import com.kleinops.gitmount.data.GitHubClient
import com.kleinops.gitmount.data.GitRepository
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 多账户动态激活管理器（服务层单例）。
 *
 * 沉睡账户不持有 GitRepository（不占缓存/写锁/连接），只在认证通过后的
 * 第一个仓库操作时懒创建（首次 0.5~2s，含一次 GitHub 连通性探测），
 * 空闲超时后释放回到沉睡。端口监听由 MountService 为每账户独立维持。
 */
object AccountManager {

    /** 空闲回收阈值：账户无活跃操作超过该时长即释放仓库缓存 */
    private const val IDLE_RECLAIM_MS = 15 * 60 * 1000L

    private class ActiveEntry {
        @Volatile var repo: GitRepository? = null
        @Volatile var lastAccess: Long = System.currentTimeMillis()
        @Volatile var activeOps: Int = 0
        fun touch() { lastAccess = System.currentTimeMillis() }
        fun isIdle(now: Long): Boolean = activeOps == 0 && now - lastAccess >= IDLE_RECLAIM_MS
    }

    private val entries = ConcurrentHashMap<String, ActiveEntry>()
    private val createLock = Object()

    /** 账户最近活跃刷新（WebDavHandler / FtpServer 在认证通过/每次操作后调用） */
    fun touch(accountId: String) {
        entries[accountId]?.touch()
    }

    /**
     * 同步获取账户仓库对象（工作线程调用）。首次创建并探测（0.5~2s），
     * 之后命中缓存开销可忽略。双检锁保证每账户单实例，FTP 与 WebDAV
     * 共享同一把写锁的语义保持不变。
     */
    fun getRepoBlocking(account: AccountConfig): GitRepository {
        val id = account.id
        entries[id]?.repo?.let { entries[id]?.touch(); return it }
        synchronized(createLock) {
            entries[id]?.repo?.let { entries[id]?.touch(); return it }
            val entry = entries.getOrPut(id) { ActiveEntry() }
            entry.activeOps++
            try {
                val repo = GitRepository(GitHubClient(account.token), account.owner, account.repoName, account.branch)
                repo.visibleList("/") // 连通性探测：确保仓库真实可读
                entry.repo = repo
                entry.touch()
                LogBus.info("账户 ${account.name} 已激活（${account.owner}/${account.repoName}）")
                return repo
            } catch (e: Exception) {
                LogBus.error("账户 ${account.name} 激活失败：${e.message}")
                throw e
            } finally {
                entry.activeOps--
            }
        }
    }

    /** 账户登录/操作时刷新活跃时间并保持 repo 不过期（供 FTP 会话调用） */
    fun markActive(accountId: String) {
        entries[accountId]?.let { it.activeOps++; it.touch(); it.activeOps-- }
    }

    /**
     * 会话级活跃占用：FTP 控制连接从建立到断开全程占用（beginOp/endOp 配对）。
     * 占用期间该账户不会被空闲回收（isIdle 要求 activeOps==0），
     * 避免长连接/大文件传输中途被回收仓库缓存。
     */
    fun beginOp(accountId: String) {
        val entry = entries.getOrPut(accountId) { ActiveEntry() }
        entry.activeOps++
        entry.touch()
    }

    /** 与 [beginOp] 配对：会话结束释放占用 */
    fun endOp(accountId: String) {
        entries[accountId]?.let { entry ->
            if (entry.activeOps > 0) {
                entry.activeOps--
                entry.touch()
            }
        }
    }

    /**
     * 释放指定账户仓库缓存（切换/删除账户时调用）。
     * 若该账户正在执行操作（activeOps>0），强制释放会把进行中的 repo 操作弄挂，
     * 因此仅在无活跃操作时释放。
     */
    fun release(accountId: String) {
        val entry = entries.remove(accountId) ?: return
        if (entry.activeOps == 0) {
            entry.repo?.forceRelease()
            entry.repo = null
            LogBus.info("账户 $accountId 已释放缓存（回到沉睡）")
        } else {
            // 有活跃操作：先放开引用，等操作结束由空闲回收兜底
            entry.repo?.let { r -> Thread { runCatching { r.forceRelease() } }.start() }
            LogBus.info("账户 $accountId 有活跃操作，延迟释放缓存")
        }
    }

    /** 全局回收：释放所有空闲账户的仓库缓存（供 MountService 周期调用） */
    fun reclaimIdle() {
        val now = System.currentTimeMillis()
        entries.forEach { (id, entry) ->
            if (entry.isIdle(now)) {
                entries.remove(id)
                entry.repo?.forceRelease()
                entry.repo = null
                LogBus.info("账户 $id 空闲超时，已回收仓库缓存")
            }
        }
    }

    /** 释放全部（服务停止时调用） */
    fun releaseAll() {
        entries.forEach { (id, _) -> release(id) }
        entries.clear()
    }

    /** 当前已激活（持有 repo）的账户数——用于日志与调试 */
    fun activeCount(): Int = entries.values.count { it.repo != null }

    /** 校验仓库可读（预检用）：失败抛 IOException */
    @Throws(IOException::class)
    fun probe(account: AccountConfig) {
        getRepoBlocking(account)
    }
}