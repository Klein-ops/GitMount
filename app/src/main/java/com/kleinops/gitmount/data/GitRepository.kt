package com.kleinops.gitmount.data

import com.kleinops.gitmount.service.LogBus
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 虚拟文件系统中的一个节点 */
data class VfsNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val sha: String,
)

/**
 * 把 GitHub 仓库包装成一个“文件系统”。
 *
 * 设计要点：
 * 1. 路径统一为以 "/" 开头、不以 "/" 结尾、无 ".." 的内部形式，对外（FTP/WebDAV）可自由转换。
 * 2. 目录列表做短 TTL 缓存，避免文件管理器反复刷新打爆 GitHub 5000 次/小时配额。
 * 3. GitHub 没有真正的空目录，删除一个目录的最后一项时，用 .gitkeep 占位保住目录结构。
 * 4. 写操作之后主动失效相关缓存，保证同一会话内读到最新数据。
 */
class GitRepository(
    private val client: GitHubClient,
    private val owner: String,
    private val repo: String,
    private val branch: String,
) {

    companion object {
        const val KEEP_FILE = ".gitkeep"

        /** 目录列表缓存有效期：30s（文件管理器反复刷新列表是配额消耗大头） */
        private const val CACHE_TTL_MS = 30_000L

        /**
         * 单文件上限：GitHub Contents API 限制为 100MB（解码后）。
         * 本地先拦截，避免把超大文件发到 GitHub 再被拒绝。
         */
        const val MAX_FILE_SIZE = 100L * 1024 * 1024

        /** 文件内容缓存仅用于 ≤1MB 的小文件 */
        private const val FILE_CACHE_TTL_MS = 60_000L
        private const val FILE_CACHE_MAX_ENTRIES = 64
        private const val FILE_CACHE_MAX_BYTES = 24L * 1024 * 1024

        /**
         * 空闲回收阈值：距最后一次文件系统访问超过该时长，认为服务处于空闲，
         * 清空全部缓存把内存还给系统（下次访问自然重建）。
         */
        const val IDLE_RECLAIM_MS = 5L * 60 * 1000
    }

    private class CacheEntry(val nodes: List<VfsNode>, val at: Long)

    private class FileCacheEntry(val sha: String, val bytes: ByteArray, val at: Long)

    private val dirCache = ConcurrentHashMap<String, CacheEntry>()
    private val fileCache = ConcurrentHashMap<String, FileCacheEntry>()

    /** 文件缓存总字节数（写入时累加、清空时归零，用于限制内存占用） */
    private val fileCacheBytes = java.util.concurrent.atomic.AtomicLong(0)

    /** 最后一次文件系统访问时间（供空闲回收判断；写操作也视为活动） */
    @Volatile
    private var lastActiveAt: Long = System.currentTimeMillis()

    /**
     * 全局写锁：GitHub Contents API 的单文件写入本质是“读最新 sha → 提交”两个步骤，
     * 多客户端并发覆盖同一文件会 409；且大文件写入本身吃内存（base64 请求体），
     * 并发的多个大文件写入会瞬间打爆默认堆。这里把写操作全部串行化，
     * 一次只允许一个写请求进入 GitHub 提交阶段，从根上消除并发叠加。
     */
    private val writeLock = java.util.concurrent.locks.ReentrantLock()

    /** 每次对外操作前调用：超过空闲阈值则清空缓存，把内存还给系统 */
    private fun reclaimIfIdle() {
        val now = System.currentTimeMillis()
        if (now - lastActiveAt > IDLE_RECLAIM_MS) {
            dirCache.clear()
            fileCache.clear()
            fileCacheBytes.set(0)
        }
        lastActiveAt = now
    }

    /**
     * 规范化路径：把 ".." 与 "." 真正折叠掉，而不是直接拒绝。
     *
     * 折叠（而非拒绝）的原因：FTP 的 CDUP / 客户端的 "cd .." 必须能用。
     * 越界安全由折叠规则本身保证——".." 在根目录上无操作，无法跳出根。
     */
    fun normalize(path: String): String {
        val raw = path.replace('\\', '/')
        val out = ArrayDeque<String>()
        for (part in raw.split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(part)
            }
        }
        return if (out.isEmpty()) "/" else "/" + out.joinToString("/")
    }

    /** 内部形式 -> GitHub 相对路径（根为 ""） */
    private fun toGitPath(normalized: String): String =
        if (normalized == "/") "" else normalized.removePrefix("/")

    /** 父目录（根目录的父仍是根） */
    fun parentOf(normalized: String): String {
        if (normalized == "/") return "/"
        val idx = normalized.lastIndexOf('/')
        return if (idx <= 0) "/" else normalized.substring(0, idx)
    }

    fun nameOf(normalized: String): String =
        if (normalized == "/") "/" else normalized.substringAfterLast('/')

    fun invalidate(path: String? = null) {
        if (path == null) {
            dirCache.clear()
            fileCache.clear()
            fileCacheBytes.set(0)
        } else {
            dirCache.remove(normalize(path))
            evictFile(normalize(path))
        }
    }

    /** 按内部路径移除文件内容缓存（同时扣减字节计数） */
    private fun evictFile(norm: String) {
        fileCache.remove(norm)?.let { fileCacheBytes.addAndGet(-it.bytes.size.toLong()) }
    }

    /** 写入文件内容缓存（只缓存 ≤1MB 小文件；超出条目或字节上限时整体清空，简单可靠） */
    private fun putFileCache(norm: String, sha: String, bytes: ByteArray) {
        if (sha.isBlank() || bytes.isEmpty() || bytes.size > 1024 * 1024) return
        if (fileCache.size >= FILE_CACHE_MAX_ENTRIES ||
            fileCacheBytes.get() + bytes.size > FILE_CACHE_MAX_BYTES
        ) {
            fileCache.clear()
            fileCacheBytes.set(0)
        }
        fileCache[norm] = FileCacheEntry(sha, bytes, System.currentTimeMillis())
        fileCacheBytes.addAndGet(bytes.size.toLong())
    }

    /** 列目录（带 TTL 缓存） */
    fun list(path: String, useCache: Boolean = true): List<VfsNode> {
        reclaimIfIdle()
        val norm = normalize(path)
        val now = System.currentTimeMillis()
        if (useCache) {
            dirCache[norm]?.let { if (now - it.at < CACHE_TTL_MS) return it.nodes }
        }
        val nodes = try {
            client.listDir(owner, repo, toGitPath(norm), branch).map { e ->
                VfsNode(
                    name = e.name,
                    path = if (norm == "/") "/${e.name}" else "$norm/${e.name}",
                    isDirectory = e.type == "dir",
                    size = e.size,
                    sha = e.sha,
                )
            }
            // 注意：这里保留 .gitkeep（删除目录时需要用它定位占位文件），
            // 对外展示统一走 visibleList() 过滤。
        } catch (e: GitHubApiException) {
            // 仓库刚创建、分支尚无内容时 GitHub 返回 404，按空目录处理
            if (e.code == 404) emptyList() else throw e
        }
        dirCache[norm] = CacheEntry(nodes, now)
        return nodes
    }

    /** 对客户端可见的列表（隐藏 .gitkeep 占位文件） */
    fun visibleList(path: String): List<VfsNode> =
        list(path).filter { it.name != KEEP_FILE }

    /** 查询节点；不存在返回 null */
    fun stat(path: String, useCache: Boolean = true): VfsNode? {
        val norm = normalize(path)
        if (norm == "/") return VfsNode("/", "/", true, 0, "")
        val parent = parentOf(norm)
        val name = nameOf(norm)
        // 先查父目录列表，命中即可判定类型，避免多余请求。
        // 父路径若是文件（而非目录），list 会抛异常，这里按“路径不存在”处理。
        val siblings = try {
            list(parent, useCache)
        } catch (e: IOException) {
            return null
        }
        siblings.firstOrNull { it.name == name }?.let { return it }
        // 父目录列表是 Contents API 的权威结果：列表里没有即为不存在。
        // 不再额外调 getFileMeta 兜底——所有写操作都会失效父目录缓存，30s TTL 内列表必然最新；
        // 删掉这步可显著降低配额消耗（文件管理器会对每个文件高频 stat）。
        return null
    }

    fun exists(path: String): Boolean = stat(path) != null

    fun isDirectory(path: String): Boolean = stat(path)?.isDirectory == true

    /** 读取文件字节（带内容缓存：sha 是内容哈希，命中即内容一致，安全） */
    fun read(path: String): ByteArray {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能读取目录")
        return try {
            val cached = fileCache[norm]
            if (cached != null && System.currentTimeMillis() - cached.at < FILE_CACHE_TTL_MS) {
                return cached.bytes
            }
            val (bytes, meta) = client.getFile(owner, repo, toGitPath(norm), branch)
            putFileCache(norm, meta.sha, bytes)
            bytes
        } catch (e: GitHubApiException) {
            if (e.code == 404) throw FileNotFoundException("文件不存在：$norm") else throw e
        }
    }

    /** 读取文件并同时拿到 sha（上传前做乐观锁用；内容同样走缓存） */
    fun readWithMeta(path: String): Pair<ByteArray, FileMeta> {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能读取目录")
        val cached = fileCache[norm]
        if (cached != null && System.currentTimeMillis() - cached.at < FILE_CACHE_TTL_MS) {
            return cached.bytes to FileMeta(cached.sha, cached.bytes.size.toLong())
        }
        val (bytes, meta) = client.getFile(owner, repo, toGitPath(norm), branch)
        putFileCache(norm, meta.sha, bytes)
        return bytes to meta
    }

    /**
     * 写入文件（自动判断新建还是覆盖）。
     * 覆盖时总是先取最新 sha，避免客户端缓存过期导致 409 冲突。
     */
    fun write(path: String, data: ByteArray, commitMessage: String? = null): VfsNode {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能写入根目录")
        if (data.size > MAX_FILE_SIZE) {
            throw IOException("文件超过 ${MAX_FILE_SIZE / 1024 / 1024}MB 上限，GitHub Contents API 不支持")
        }
        // 串行化写阶段：并发大文件上传会叠加内存峰值并触发 GitHub 409，
        // 见 writeLock 注释。ReentrantLock 可重入，move/copy 递归调用 write 不会死锁。
        writeLock.lock()
        return try {
            ensureParentDirs(norm)
            val gitPath = toGitPath(norm)
            val existing = client.getFileMeta(owner, repo, gitPath, branch)
            val msg = commitMessage ?: if (existing == null) "上传 ${nameOf(norm)}" else "更新 ${nameOf(norm)}"
            val meta = client.putFile(owner, repo, gitPath, data, existing?.sha, msg, branch)
            invalidate(parentOf(norm))
            invalidate(norm)
            VfsNode(nameOf(norm), norm, false, data.size.toLong(), meta.sha)
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * 流式写入文件（创建或覆盖）：直接读输入流产线转发给 GitHub，不把整个文件驻留内存。
     *
     * 用于 FTP 上传：数据连接收到的字节边收边 base64 发往 GitHub（chunked），
     * FTP 客户端的进度会被 GitHub 公网真实上传速度拖住，
     * 消除"客户端 100% 但 GitHub 才刚开始传"的假完成。
     *
     * @param input 上传数据流；调用方需配置 soTimeout，read 空闲超时（SocketTimeoutException）
     *              视为数据已发完（与 readLimited 的 DATA_IDLE_TIMEOUT 语义一致）。
     */
    fun writeStream(path: String, input: java.io.InputStream, commitMessage: String? = null): VfsNode {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能写入根目录")
        // 与 write 同一把锁：串行化写阶段，避免并发上传叠加内存峰值 / GitHub 409
        writeLock.lock()
        return try {
            ensureParentDirs(norm)
            val gitPath = toGitPath(norm)
            val existing = client.getFileMeta(owner, repo, gitPath, branch)
            val msg = commitMessage ?: if (existing == null) "上传 ${nameOf(norm)}" else "更新 ${nameOf(norm)}"
            val meta = client.putFileStream(owner, repo, gitPath, input, existing?.sha, msg, branch)
            invalidate(parentOf(norm))
            invalidate(norm)
            VfsNode(nameOf(norm), norm, false, meta.size, meta.sha)
        } finally {
            writeLock.unlock()
        }
    }

    /** 创建目录：GitHub 无空目录，用 .gitkeep 占位 */
    fun createDirectory(path: String) {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") return
        if (isDirectory(norm)) return
        if (exists(norm)) throw IOException("已存在同名文件：$norm")
        // 先确保父目录链存在（parentOf(norm) 才是 norm 的父目录）
        ensureParentDirs(norm)
        val keepPath = toGitPath(norm) + "/" + KEEP_FILE
        val existing = client.getFileMeta(owner, repo, keepPath, branch)
        if (existing == null) {
            client.putFile(
                owner, repo, keepPath, ByteArray(0),
                null, "创建目录 ${nameOf(norm)}", branch
            )
        }
        invalidate(parentOf(norm))
        invalidate(norm)
    }

    /**
     * 递归创建父目录链。
     *
     * 注意：必须把 parent 本身递归传下去（ensureParentDirs(parent)），
     * 早先写成 ensureParentDirs("$parent/x") 会让 parentOf 始终返回同一个 parent
     * 导致无限递归 StackOverflow。
     */
    private fun ensureParentDirs(filePath: String) {
        val parent = parentOf(normalize(filePath))
        if (parent == "/") return
        if (isDirectory(parent)) return
        ensureParentDirs(parent)
        val keepPath = toGitPath(parent) + "/" + KEEP_FILE
        if (client.getFileMeta(owner, repo, keepPath, branch) == null) {
            client.putFile(owner, repo, keepPath, ByteArray(0), null, "创建目录 ${nameOf(parent)}", branch)
        }
        invalidate(parentOf(parent))
        invalidate(parent)
    }

    /** 删除文件或目录（目录递归删除） */
    fun delete(path: String) {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能删除根目录")
        val node = stat(norm) ?: throw FileNotFoundException("不存在：$norm")

        if (!node.isDirectory) {
            client.deleteFile(owner, repo, toGitPath(norm), node.sha, "删除 ${node.name}", branch)
            invalidate(parentOf(norm))
            invalidate(norm)
            return
        }
        deleteDirectoryRecursive(norm)
        invalidate(parentOf(norm))
        invalidate(norm)
    }

    private fun deleteDirectoryRecursive(normDir: String) {
        // list() 不含 .gitkeep 隐藏逻辑，占位文件也会被一并删掉，符合预期
        val children = list(normDir, useCache = false)
        for (child in children) {
            if (child.isDirectory) {
                deleteDirectoryRecursive(child.path)
            } else {
                client.deleteFile(owner, repo, toGitPath(child.path), child.sha, "删除 ${child.name}", branch)
            }
        }
        invalidate(normDir)
    }

    /** 移动/重命名（GitHub 无原生 move，走读-写-删） */
    fun move(fromPath: String, toPath: String) {
        val from = normalize(fromPath)
        val to = normalize(toPath)
        if (from == "/" || to == "/") throw IOException("不能移动根目录")
        if (from == to) return
        if (exists(to)) throw IOException("目标已存在：$to")
        val node = stat(from) ?: throw FileNotFoundException("源不存在：$from")
        LogBus.info("FTP/WebDAV 移动开始 → $from -> $to")

        if (node.isDirectory) {
            createDirectory(to)
            val children = list(from, useCache = false)
            LogBus.info("移动目录 $from：共 ${children.size} 项")
            for (child in children) {
                if (child.name == KEEP_FILE) continue
                move(child.path, "$to/${child.name}")
            }
            delete(from)
        } else {
            LogBus.info("移动文件（${node.size} 字节）→ 下载源...")
            val data = read(from)
            LogBus.info("源已下载（${data.size} 字节）→ 上传目标...")
            write(to, data, "移动 ${node.name}")
            LogBus.info("目标已上传 → 删除源...")
            delete(from)
        }
        LogBus.info("移动完成 → $from -> $to")
    }

    /** 供 UI 展示的修改时间（GitHub Contents API 不提供文件 mtime，统一用当前时间占位） */
    fun displayModified(): Date = Date()

    fun formatTimestamp(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)

    /** RFC 1123 时间格式（WebDAV getlastmodified） */
    fun rfc1123(date: Date): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).format(date)

    /** ISO 8601 时间格式（WebDAV creationdate） */
    fun iso8601(date: Date): String = "yyyy-MM-dd'T'HH:mm:ss'Z'".let { fmt ->
        SimpleDateFormat(fmt, Locale.US).format(date)
    }

    /** 复制文件或目录（与 move 逻辑相同但保留源） */
    fun copy(fromPath: String, toPath: String) {
        val from = normalize(fromPath)
        val to = normalize(toPath)
        if (from == "/" || to == "/") throw IOException("不能复制根目录")
        if (from == to) throw IOException("源和目标相同")
        if (exists(to)) throw IOException("目标已存在：$to")
        val node = stat(from) ?: throw FileNotFoundException("源不存在：$from")

        if (node.isDirectory) {
            createDirectory(to)
            for (child in list(from, useCache = false)) {
                if (child.name == KEEP_FILE) continue
                copy(child.path, "$to/${child.name}")
            }
        } else {
            val data = read(from)
            write(to, data, "复制 ${node.name}")
        }
    }

    /** 查询仓库容量信息（size 包含提交历史，单位 KB；私有仓库配额 500MB，公开仓库无配额） */
    fun repoInfo(): RepoInfo? = client.getRepoInfo(owner, repo)

    /**
     * 清空提交历史（孤儿提交重写，**文件全部保留**）。
     *
     * 原理：保留当前分支最新 commit 的 tree（即全部文件的目录索引），
     * 创建一个无父提交（parents=[]）指向同一棵树，再把分支强制移动到该提交。
     * 效果：文件一个不少，历史从 N 个 commit 变成 1 个；旧 commit 变为不可达对象，
     * 由 GitHub 后台 GC 回收，容量数字会延迟下降（几小时~几天不等）。
     *
     * 只需 3 次 API 调用，不备份、不重传文件。
     */
    fun clearHistory(commitMessage: String = "初始化（历史已清空）") {
        // 1) 读 HEAD commit 与其根 tree（一次调用）
        val (_, treeSha) = client.getHeadTree(owner, repo, branch)
        if (treeSha.isBlank()) throw IOException("无法获取仓库文件树")
        // 2) 创建孤儿提交（无父提交，复用 tree → 文件保留）
        val newCommit = client.createOrphanCommit(owner, repo, treeSha, commitMessage)
        // 3) 强制移动分支引用到新提交（旧历史不可达）
        client.updateBranchRef(owner, repo, branch, newCommit)
        // 本会话缓存全部失效（sha 全变了）
        invalidate()
        LogBus.info("历史已清空：$owner/$repo 分支 $branch 现有 ${newCommit.take(7)}")
    }

    /** 仓库总配额参考（GitHub 建议单仓库 < 1GB） */
    fun usageHint(): String = "GitHub 建议私有仓库保持在 1GB 以内"
}