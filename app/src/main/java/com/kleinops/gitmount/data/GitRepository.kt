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
    /**
     * 大文件上传临时落盘目录（FTP 场景数据连接无总长度，需先把剩余流
     * 写入临时文件统计字节数，再以已知 Content-Length 上传 GitHub）。
     * 由 MountService 传入 cacheDir；为 null 时未知长度大文件上传直接报错。
     */
    private val spoolDir: java.io.File? = null,
) {

    companion object {
        const val KEEP_FILE = ".gitkeep"

        /** 目录列表缓存有效期：30s（文件管理器反复刷新列表是配额消耗大头） */
        private const val CACHE_TTL_MS = 30_000L

        /**
         * 单文件上限：本地拦截阈值。
         *
         * 实测（2026-11-01，Klein-ops/api-streaming-test）：GitHub Contents API
         * 原始字节上限约 40MiB（41.5MB 成功、40MiB-1 = 41,943,039B 已 422 拒绝），
         * 远低于文档宣称的 100MB。这里取 40,000,000B（40MB）做保守本地拦截：
         * 超过走 Releases 大文件通道，不再发给 Contents API。
         */
        const val MAX_FILE_SIZE = 40L * 1000 * 1000

        /**
         * LFS 通道单文件上限：GitHub Releases 单资产上限 2GB。
         * 超过 40MB 的文件改走 Releases（仓库里只留指针），超过 2GB 仍拒绝。
         */
        const val MAX_LFS_SIZE = 2L * 1000 * 1000 * 1000

        /** LFS 指针文件魔数（首行），用于识别指针与普通小文件的边界 */
        const val LFS_MAGIC = "gitmount-lfs-v1"

        /** LFS 桶的固定 tag（每仓库一个，容纳全部大文件资产） */
        const val LFS_RELEASE_TAG = "gitmount-lfs"

        /** 文件内容缓存仅用于 ≤1MB 的小文件 */
        private const val FILE_CACHE_TTL_MS = 60_000L
        private const val FILE_CACHE_MAX_ENTRIES = 64
        private const val FILE_CACHE_MAX_BYTES = 24L * 1024 * 1024

        /**
         * 空闲回收阈值：距最后一次文件系统访问超过该时长，认为服务处于空闲，
         * 清空全部缓存把内存还给系统（下次访问自然重建）。
         */
        const val IDLE_RECLAIM_MS = 5L * 60 * 1000

        /** 指针大小可解析的信任上限：超过该大小的文件几乎不可能是指针，跳过内容读取省配额 */
        private const val POINTER_MAX_BYTES = 8 * 1024
    }

    /** LFS 指针内容（解析自仓库里的指针文件） */
    data class LfsPointer(
        val assetId: Long,
        val size: Long,
        val sha256: String,
        val uploadedAt: Long,
        /** 资产文件名（直链下载需要：github.com/.../releases/download/{tag}/{file}）；
         * 旧版指针无此字段，解析后为 ""，下载自动 fallback 到 API 通道。 */
        val fileName: String = "",
    ) {
        fun toText(): String = buildString {
            appendLine(LFS_MAGIC)
            append("asset_id=").append(assetId).append('\n')
            append("size=").append(size).append('\n')
            append("sha256=").append(sha256).append('\n')
            append("uploaded_at=").append(uploadedAt).append('\n')
            append("file_name=").append(fileName).append('\n')
        }

        companion object {
            fun parse(text: String): LfsPointer? {
                val lines = text.lines()
                if (lines.firstOrNull() != LFS_MAGIC) return null
                var assetId = -1L
                var size = -1L
                var sha256 = ""
                var uploadedAt = 0L
                var fileName = ""
                for (line in lines.drop(1)) {
                    val idx = line.indexOf('=')
                    if (idx <= 0) continue
                    val key = line.substring(0, idx)
                    val value = line.substring(idx + 1)
                    when (key) {
                        "asset_id" -> assetId = value.toLongOrNull() ?: -1L
                        "size" -> size = value.toLongOrNull() ?: -1L
                        "sha256" -> sha256 = value.trim()
                        "uploaded_at" -> uploadedAt = value.toLongOrNull() ?: 0L
                        "file_name" -> fileName = value.trim()
                    }
                }
                if (assetId <= 0 || size < 0) return null
                return LfsPointer(assetId, size, sha256, uploadedAt, fileName)
            }
        }
    }

    private class CacheEntry(val nodes: List<VfsNode>, val at: Long)

    private class FileCacheEntry(val sha: String, val bytes: ByteArray, val at: Long)

    private val dirCache = ConcurrentHashMap<String, CacheEntry>()
    private val fileCache = ConcurrentHashMap<String, FileCacheEntry>()

    /** 指针解析结果缓存条目（pointer 为 null = 已确认非指针） */
    private class PointerEntry(val pointer: LfsPointer?)

    /** 指针解析结果缓存：norm path -> PointerEntry。
     * 旧版只缓存 size、解析结果每次都要重新读文件内容，导致 stat/list 对指针文件
     * 反复 GET 打爆配额。这里缓存完整解析结果（含空结果），invalidate 时清除。
     * 注意：ConcurrentHashMap 禁止 null value，必须用包装类容纳 null 指针。 */
    private val pointerCache = ConcurrentHashMap<String, PointerEntry>()

    /** LFS 桶 release id 缓存（首次获取后缓存，避免每次上传/删除都查接口） */
    @Volatile
    private var lfsReleaseId: Long = -1L

    /** 桶存在性检查标志（false=未检查；避免无 LFS 桶的仓库 list 时对每个小文件读内容解析指针打爆配额） */
    @Volatile
    private var lfsBucketChecked = false
    @Volatile
    private var hasLfsBucket = false

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
            pointerCache.clear()
            fileCacheBytes.set(0)
        }
        lastActiveAt = now
    }

    /**
     * 无条件清空全部缓存（供系统内存压力回调 onTrimMemory 调用）。
     * 与 reclaimIfIdle 的区别：不判断空闲时长、不更新 lastActiveAt，
     * 系统明确要求释放内存时立即归还，下次访问自然重建。
     * 目录缓存 30s TTL、文件缓存 60s TTL，重建成本可忽略。
     */
    fun forceRelease() {
        dirCache.clear()
        fileCache.clear()
        pointerCache.clear()
        fileCacheBytes.set(0)
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
            pointerCache.clear()
            fileCacheBytes.set(0)
        } else {
            dirCache.remove(normalize(path))
            evictFile(normalize(path))
            pointerCache.remove(normalize(path))
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

    // ---------- LFS（GitHub Releases 大文件通道） ----------

    /**
     * 生成唯一的 LFS 资产名（同 release 内必须唯一，重复会 422）。
     * 指针文件里存的是 asset_id，资产名只用于人类识别，因此用时间+随机数即可，
     * 绝不能依赖"路径扁平化名"——不同路径（/a/b.txt 与 /a_b.txt）会撞名。
     */
    private fun newLfsAssetName(): String =
        "gm-${System.currentTimeMillis()}-${(100000..999999).random()}"

    /** 获取（必要时创建）LFS 桶 release id；结果缓存到实例字段 */
    private fun ensureLfsRelease(): Long {
        val cached = lfsReleaseId
        if (cached > 0) return cached
        val id = client.getOrCreateLfsRelease(owner, repo, LFS_RELEASE_TAG)
        lfsReleaseId = id
        lfsBucketChecked = true
        hasLfsBucket = true
        return id
    }

    /** 仓库是否已有 LFS 桶（惰性检查一次并缓存结果） */
    private fun hasLfsBucket(): Boolean {
        if (lfsBucketChecked) return hasLfsBucket
        val id = try {
            client.getLfsReleaseIfExists(owner, repo, LFS_RELEASE_TAG)
        } catch (e: Exception) {
            null
        }
        lfsBucketChecked = true
        hasLfsBucket = id != null
        if (id != null) lfsReleaseId = id
        return hasLfsBucket
    }

    /**
     * 迁移旧版 draft LFS 桶为发布态（下载直链的前提）。
     * 挂载激活时由 MountPointManager 调用；内部吞掉全部异常，失败不影响挂载。
     */
    fun publishLfsReleaseIfDraft() {
        try {
            client.publishLfsReleaseIfDraft(owner, repo, LFS_RELEASE_TAG)
        } catch (e: Exception) {
            LogBus.warn("LFS 桶发布迁移失败（忽略，API 通道仍可用）：${e.message}")
        }
    }

    /**
     * 读取指针文件内容并尝试解析为 LFS 指针；结果（含 null=非指针）缓存到 pointerCache。
     * 只对 ≤ POINTER_MAX_BYTES 的小文件尝试解析（指针必然极小）。
     */
    private fun tryReadPointer(norm: String): LfsPointer? {
        pointerCache[norm]?.let { return it.pointer }
        val bytes = try {
            read(norm)
        } catch (e: Exception) {
            return null
        }
        if (bytes.size > POINTER_MAX_BYTES) return null
        val ptr = LfsPointer.parse(String(bytes, Charsets.UTF_8))
        pointerCache[norm] = PointerEntry(ptr)
        return ptr
    }

    /** 该路径是否为大文件（LFS 指针，真实内容在 Releases 桶） */
    fun isLfsPointer(path: String): Boolean = tryReadPointer(normalize(path)) != null

    /**
     * 写 LFS 指针文件到仓库（Contents 通道，同路径同文件名）。
     * 覆盖时先取最新 sha 做乐观锁；返回指针文件的 VfsNode（对外 size 显示真实大小）。
     */
    private fun writePointerFile(
        norm: String,
        pointer: LfsPointer,
        existingSha: String?,
        commitMessage: String?,
    ): VfsNode {
        val text = pointer.toText().toByteArray(Charsets.UTF_8)
        val meta = client.putFile(owner, repo, toGitPath(norm), text, existingSha, commitMessage ?: "上传 ${nameOf(norm)}", branch)
        invalidate(parentOf(norm))
        invalidate(norm)
        return VfsNode(nameOf(norm), norm, false, pointer.size, meta.sha)
    }

    /**
     * 打开文件内容读取流（供 WebDAV GET 流式下载；FTP RETR 也用）。
     * 若目标是指针文件（大文件在 Releases），返回 Releases 资产下载流（由调用方关闭，
     * 关闭时释放 OkHttp Response）；普通文件读入内存返回 ByteArrayInputStream。
     * 下载路径不持有任何锁（只有上传/删除需要 writeLock），绝不与写操作互相卡死。
     */
    fun openReadStream(path: String): java.io.InputStream {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能读取目录")
        val pointer = tryReadPointer(norm)
        if (pointer != null) {
            LogBus.info("LFS 下载开始：$norm ← release asset ${pointer.assetId}（${pointer.size} 字节）")
            return client.openLfsAssetStream(owner, repo, LFS_RELEASE_TAG, pointer)
        }
        return java.io.ByteArrayInputStream(read(norm))
    }

    /**
     * 流式读取文件内容到 output（边下载边写，不驻留内存）。
     * 若目标是指针文件（大文件在 Releases），改为从 Releases 资产流式下载；
     * 普通小文件直接读入内存写下游。
     * @return 实际写出的字节数
     */
    fun readStream(path: String, output: java.io.OutputStream): Long {
        val stream = openReadStream(path)
        stream.use { input ->
            val buf = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                total += n
            }
            output.flush()
            return total
        }
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
                var size = e.size
                // 仅当仓库确实存在 LFS 桶时才尝试把小文件解析为指针（否则不读内容，
                // 避免普通小文件仓库 list 时每文件多打一次 GET 耗尽配额）
                if (e.type != "dir" && e.size <= POINTER_MAX_BYTES && hasLfsBucket()) {
                    val childPath = if (norm == "/") "/${e.name}" else "$norm/${e.name}"
                    val cached = pointerCache[childPath]
                    if (cached != null) {
                        cached.pointer?.let { size = it.size }
                    } else {
                        tryReadPointer(childPath)?.let { size = it.size }
                    }
                }
                VfsNode(
                    name = e.name,
                    path = if (norm == "/") "/${e.name}" else "$norm/${e.name}",
                    isDirectory = e.type == "dir",
                    size = size,
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
     *
     * ≤40MB 走 GitHub Contents API（历史通道）；
     * >40MB 走 GitHub Releases 大文件通道：先把内容上传到 LFS 桶拿 assetId，
     * 再在仓库写同路径指针文件（对外透明，大小显示真实字节数）。
     * 覆盖时总是先取最新 sha，避免客户端缓存过期导致 409 冲突。
     */
    fun write(path: String, data: ByteArray, commitMessage: String? = null): VfsNode {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能写入根目录")
        if (data.size > MAX_LFS_SIZE) throw FileTooLargeException(MAX_LFS_SIZE)
        // 串行化写阶段：并发大文件上传会叠加内存峰值并触发 GitHub 409，
        // 见 writeLock 注释。ReentrantLock 可重入，move/copy 递归调用 write 不会死锁。
        writeLock.lock()
        return try {
            ensureParentDirs(norm)
            val gitPath = toGitPath(norm)
            val existing = client.getFileMeta(owner, repo, gitPath, branch)
            if (data.size > MAX_FILE_SIZE) {
                // 大文件通道：Releases 资产 + 指针
                deleteOldAssetIfAny(norm)
                val releaseId = ensureLfsRelease()
                val asset = client.uploadLfsAsset(
                    owner, repo, releaseId, newLfsAssetName(),
                    data.inputStream(), MAX_LFS_SIZE, data.size.toLong(),
                )
                val ptr = LfsPointer(asset.assetId, asset.size, asset.sha256, System.currentTimeMillis(), asset.fileName)
                writePointerFile(norm, ptr, existing?.sha, commitMessage)
            } else {
                writeContents(norm, data, existing, commitMessage)
            }
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * 流式写入文件（创建或覆盖）。
     *
     * 核心约束（2026-11-09 实测）：GitHub 的 uploads.github.com 与 Contents API
     * **都拒绝 Transfer-Encoding: chunked**，强制要求请求带已知 Content-Length。
     * 因此本方法按以下规则分流：
     * - knownLength != null 且 > MAX_FILE_SIZE：直接流式走 Releases 通道（固定长度上传资产 + 写指针），
     *   不整读内存（WebDAV PUT 场景：HTTP Content-Length 已知）。
     * - knownLength != null 且 ≤ MAX_FILE_SIZE：读入内存走 Contents putFile（固定长度，≤40MB 峰值可控）。
     * - knownLength == null（FTP 数据连接无长度）：先把流完整落盘到 spool 临时文件统计总字节数，
     *   再按总长分流（≤40MB 读内存走 Contents；>40MB 用 FileInputStream + 已知长度走 Releases）。
     *   内存占用恒定（128KB 缓冲 + 小文件 ≤40MB），彻底移除旧版 readProbe 探测 40MB+1 的峰值。
     *
     * @param input 上传数据流；调用方需配置 soTimeout，read 空闲超时（SocketTimeoutException）
     *              视为数据已发完（与 readLimited 的 DATA_IDLE_TIMEOUT 语义一致）。
     * @param knownLength 数据流总长度；未知传 null（FTP 场景由内部 spool 统计）
     */
    fun writeStream(
        path: String,
        input: java.io.InputStream,
        commitMessage: String? = null,
        knownLength: Long? = null,
    ): VfsNode {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能写入根目录")
        if (knownLength != null && knownLength > MAX_LFS_SIZE) throw FileTooLargeException(MAX_LFS_SIZE)
        // 与 write 同一把锁：串行化写阶段，避免并发上传叠加内存峰值 / GitHub 409
        writeLock.lock()
        return try {
            ensureParentDirs(norm)
            val gitPath = toGitPath(norm)
            val existing = client.getFileMeta(owner, repo, gitPath, branch)
            // 已知长度的大文件：全流式直传 Releases（固定 Content-Length，无探测、无整读内存）
            if (knownLength != null && knownLength > MAX_FILE_SIZE) {
                return writeLfsStream(norm, input, knownLength, existing, commitMessage)
            }
            // 长度未知（FTP 数据连接）：先落盘统计总长度，再按长度分流。
            // 不再用 readProbe 探测 40MB+1 字节到内存——那正是旧版 FTP 上传内存膨胀的根源。
            if (knownLength == null) {
                val (tmp, totalBytes) = spoolStream(input)
                try {
                    if (totalBytes > MAX_LFS_SIZE) throw FileTooLargeException(MAX_LFS_SIZE)
                    if (totalBytes > MAX_FILE_SIZE) {
                        java.io.FileInputStream(tmp).use { fileIn ->
                            return writeLfsStream(norm, fileIn, totalBytes, existing, commitMessage)
                        }
                    } else {
                        return writeContents(norm, tmp.readBytes(), existing, commitMessage)
                    }
                } finally {
                    tmp.delete()
                }
            }
            // 已知长度的小文件（≤40MB）：读入内存走 Contents（固定长度）
            writeContents(norm, readAll(input, knownLength ?: 0L), existing, commitMessage)
        } finally {
            writeLock.unlock()
        }
    }

    /** 小文件写 Contents 通道（固定长度）：先删旧资产（若有指针），再 putFile */
    private fun writeContents(
        norm: String,
        data: ByteArray,
        existing: FileMeta?,
        commitMessage: String?,
    ): VfsNode {
        // 小文件覆盖大文件：旧文件若是指针（真实内容在 Releases），先删其资产
        deleteOldAssetIfAny(norm)
        val msg = commitMessage ?: if (existing == null) "上传 ${nameOf(norm)}" else "更新 ${nameOf(norm)}"
        val meta = client.putFile(owner, repo, toGitPath(norm), data, existing?.sha, msg, branch)
        invalidate(parentOf(norm))
        invalidate(norm)
        return VfsNode(nameOf(norm), norm, false, data.size.toLong(), meta.sha)
    }

    /**
     * LFS 大文件公共上传流程：删除旧资产（若有）→ ensure 桶 → 已知长度流式上传
     * → 写指针文件。长度已知（GitHub 拒绝 chunked，必须带固定 Content-Length）。
     */
    private fun writeLfsStream(
        norm: String,
        input: java.io.InputStream,
        totalBytes: Long,
        existing: FileMeta?,
        commitMessage: String?,
    ): VfsNode {
        deleteOldAssetIfAny(norm)
        val releaseId = ensureLfsRelease()
        val asset = client.uploadLfsAsset(owner, repo, releaseId, newLfsAssetName(), input, MAX_LFS_SIZE, totalBytes)
        val ptr = LfsPointer(asset.assetId, asset.size, asset.sha256, System.currentTimeMillis(), asset.fileName)
        return writePointerFile(norm, ptr, existing?.sha, commitMessage)
    }

    /**
     * 覆盖写入前清理旧 LFS 资产：读旧指针（若有）拿精确 asset_id 删除，
     * 绝不按名猜（防撞名误删）；404 = 资产已被外部清理，忽略。
     */
    private fun deleteOldAssetIfAny(norm: String) {
        val oldPointer = tryReadPointer(norm)
        if (oldPointer != null && oldPointer.assetId > 0) {
            try {
                client.deleteLfsAsset(owner, repo, oldPointer.assetId)
            } catch (e: GitHubApiException) {
                if (e.code != 404) throw e
            }
        }
    }

    /**
     * 把输入流完整落盘到临时文件，返回 (临时文件, 写入总字节数)。
     *
     * 用于 FTP 大文件上传：数据连接无总长度 → 先写满临时文件统计字节，
     * 再以已知 Content-Length 上传 GitHub（GitHub 拒绝 chunked）。
     * 内存占用恒定（128KB 缓冲），彻底解决旧版 readProbe 探测大文件到内存的膨胀问题。
     * 自动校验不超过 MAX_LFS_SIZE；失败时清理临时文件。
     */
    private fun spoolStream(input: java.io.InputStream): Pair<java.io.File, Long> {
        val dir = spoolDir
            ?: throw IOException("大文件上传需要临时目录（FTP 模式需配置应用缓存目录）")
        if (!dir.exists()) dir.mkdirs()
        val tmp = java.io.File.createTempFile("gm-upload-", ".tmp", dir)
        try {
            var total = 0L
            java.io.FileOutputStream(tmp).use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = try {
                        input.read(buf)
                    } catch (e: java.net.SocketTimeoutException) {
                        break // 客户端发完数据但未关闭数据连接（在等 226），视为传输结束
                    }
                    if (n < 0) break
                    total += n
                    if (total > MAX_LFS_SIZE) {
                        throw FileTooLargeException(MAX_LFS_SIZE)
                    }
                    out.write(buf, 0, n)
                }
            }
            return tmp to total
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** 从输入流读取至多 limit 字节；limit>0 时精确读满 limit 即停（不等待 EOF，防 keep-alive 卡死） */
    private fun readAll(input: java.io.InputStream, limit: Long): ByteArray {
        if (limit <= 0) return ByteArray(0)
        val readBuf = ByteArray(64 * 1024)
        var data = ByteArray(0)
        var size = 0
        while (size.toLong() < limit) {
            val want = minOf(readBuf.size.toLong(), limit - size).toInt()
            val n = try {
                input.read(readBuf, 0, want)
            } catch (e: java.net.SocketTimeoutException) {
                break
            } catch (e: java.io.IOException) {
                break
            }
            if (n < 0) break
            size += n
            if (size > data.size) {
                val need = size
                val newCap = need.coerceAtLeast(data.size + 1024 * 1024)
                data = java.util.Arrays.copyOf(data, newCap)
            }
            System.arraycopy(readBuf, 0, data, size - n, n)
        }
        return java.util.Arrays.copyOf(data, size)
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

    /**
     * 删除文件或目录（目录递归删除）。
     *
     * 删除同样纳入全局写锁：与上传/覆盖互斥。删除文件夹要逐个文件调
     * GitHub Contents API、跨秒级时长，若不持锁，期间其他连接（或同连接
     * 交错操作）改写文件会让删除请求里的 sha 过期，触发 422 乐观锁冲突
     * （"is at X but expected Y"）。持锁后本服务内的写互斥，再配一次冲突重试兜底。
     */
    fun delete(path: String) {
        reclaimIfIdle()
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能删除根目录")
        writeLock.lock()
        try {
            val node = stat(norm) ?: throw FileNotFoundException("不存在：$norm")

            if (!node.isDirectory) {
                deleteFileWithRetry(norm, node)
                invalidate(parentOf(norm))
                invalidate(norm)
                return
            }
            deleteDirectoryRecursive(norm)
            invalidate(parentOf(norm))
            invalidate(norm)
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * 删除单个文件（带一次 sha 冲突重试）。
     *
     * GitHub Contents API 用 sha 做乐观锁：删除请求里的 sha 必须等同仓库
     * 当前 sha，否则返回 422（"is at X but expected Y"）。writeLock 已保证
     * 本服务内写操作互斥，但仓库仍可能被外部（网页端、其他客户端）改动，
     * 或存在未知时序，导致删除时 sha 已过期。这里捕获 422 后重新读取最新
     * sha 再删一次——一次重试足以覆盖"读取列表 → 删除"窗口期的时效竞争。
     *
     * 若目标是指针文件（大文件在 Releases），默认先删除对应资产再删指针文件：
     * 两者必须都成功才算删除完成，且资产删除先行（避免删了指针后资产泄漏）。
     * @param deleteLinkedAsset 是否删除指针关联的 Releases 资产。为 false 时
     *        只删指针文件（move 搬指针用：资产被目标指针继续引用，不能删）。
     */
    private fun deleteFileWithRetry(norm: String, node: VfsNode, deleteLinkedAsset: Boolean = true) {
        val gitPath = toGitPath(norm)
        // 先尝试解析指针（指针必然极小，读内容成本低；非指针返回 null 走普通删除）
        if (deleteLinkedAsset) {
            val pointer = tryReadPointer(norm)
            if (pointer != null) {
                try {
                    client.deleteLfsAsset(owner, repo, pointer.assetId)
                } catch (e: GitHubApiException) {
                    if (e.code != 404) throw e // 404 = 资产已被删，继续删指针即可
                }
            }
        }
        try {
            client.deleteFile(owner, repo, gitPath, node.sha, "删除 ${node.name}", branch)
        } catch (e: GitHubApiException) {
            if (e.code != 422) throw e
            LogBus.warn("删除 ${node.name} 遇 sha 冲突（422），重新读取最新 sha 重试…")
            val latest = client.getFileMeta(owner, repo, gitPath, branch)
                ?: return // 文件已不存在（可能已被并发删除），视为删除成功
            client.deleteFile(owner, repo, gitPath, latest.sha, "删除 ${node.name}", branch)
        }
    }

    private fun deleteDirectoryRecursive(normDir: String) {
        // list() 不含 .gitkeep 隐藏逻辑，占位文件也会被一并删掉，符合预期
        val children = list(normDir, useCache = false)
        for (child in children) {
            if (child.isDirectory) {
                deleteDirectoryRecursive(child.path)
            } else {
                deleteFileWithRetry(child.path, child)
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
            val pointer = tryReadPointer(from)
            if (pointer != null) {
                // LFS 大文件移动：资产在 Releases 不迁移，只需把指针文件复制到目标
                // （写新指针 → 删源指针【不删资产】）。避免整读大文件重传、也避免删资产。
                LogBus.info("移动 LFS 大文件（${pointer.size} 字节）→ $from -> $to")
                ensureParentDirs(to)
                val existing = client.getFileMeta(owner, repo, toGitPath(to), branch)
                if (existing != null) throw IOException("目标已存在：$to")
                writePointerFile(to, pointer, null, "移动 ${node.name}")
                deleteFileWithRetry(from, node, deleteLinkedAsset = false)
            } else {
                LogBus.info("移动文件（${node.size} 字节）→ 下载源...")
                val data = read(from)
                LogBus.info("源已下载（${data.size} 字节）→ 上传目标...")
                write(to, data, "移动 ${node.name}")
                LogBus.info("目标已上传 → 删除源...")
                delete(from)
            }
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
            val pointer = tryReadPointer(from)
            if (pointer != null) {
                // LFS 大文件复制：GitHub 不允许在 Releases 内复制资产，
                // 走"流式下载源资产 → 流式上传新资产"（不整读内存），再写新指针。
                LogBus.info("复制 LFS 大文件（${pointer.size} 字节）→ $from -> $to")
                writeLock.lock()
                try {
                    ensureParentDirs(to)
                    val existing = client.getFileMeta(owner, repo, toGitPath(to), branch)
                    if (existing != null) throw IOException("目标已存在：$to")
                    val releaseId = ensureLfsRelease()
                    val src = client.openLfsAssetStream(owner, repo, LFS_RELEASE_TAG, pointer)
                    val asset = try {
                        client.uploadLfsAsset(owner, repo, releaseId, newLfsAssetName(), src, MAX_LFS_SIZE, pointer.size)
                    } finally {
                        src.close()
                    }
                    val newPtr = LfsPointer(asset.assetId, asset.size, asset.sha256, System.currentTimeMillis(), asset.fileName)
                    writePointerFile(to, newPtr, null, "复制 ${node.name}")
                } finally {
                    writeLock.unlock()
                }
            } else {
                val data = read(from)
                write(to, data, "复制 ${node.name}")
            }
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