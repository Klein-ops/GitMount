package com.kleinops.gitmount.data

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
        private const val CACHE_TTL_MS = 4_000L
        /** Contents API 单文件上限；超过则明确拒绝，避免请求被 GitHub 打断 */
        const val MAX_FILE_SIZE = 95L * 1024 * 1024
    }

    private class CacheEntry(val nodes: List<VfsNode>, val at: Long)

    private val dirCache = ConcurrentHashMap<String, CacheEntry>()

    /** 规范化路径：/a/b/ -> /a/b；"" -> "/" */
    fun normalize(path: String): String {
        val raw = path.replace('\\', '/')
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("非法路径：$path")
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
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
        if (path == null) dirCache.clear() else dirCache.remove(normalize(path))
    }

    /** 列目录（带 TTL 缓存） */
    fun list(path: String, useCache: Boolean = true): List<VfsNode> {
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
        // 先查父目录缓存/列表，命中即可判定类型，避免多余请求
        val siblings = list(parent, useCache)
        siblings.firstOrNull { it.name == name }?.let { return it }
        // 列表里没有，可能是文件夹为空（GitHub 无空目录，通常不存在）；再尝试直接取元信息
        return try {
            val meta = client.getFileMeta(owner, repo, toGitPath(norm), branch) ?: return null
            VfsNode(name, norm, false, meta.size, meta.sha)
        } catch (e: IOException) {
            null
        }
    }

    fun exists(path: String): Boolean = stat(path) != null

    fun isDirectory(path: String): Boolean = stat(path)?.isDirectory == true

    /** 读取文件字节 */
    fun read(path: String): ByteArray {
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能读取目录")
        return try {
            client.getFile(owner, repo, toGitPath(norm), branch).first
        } catch (e: GitHubApiException) {
            if (e.code == 404) throw FileNotFoundException("文件不存在：$norm") else throw e
        }
    }

    /** 读取文件并同时拿到 sha（上传前做乐观锁用） */
    fun readWithMeta(path: String): Pair<ByteArray, FileMeta> {
        val norm = normalize(path)
        return client.getFile(owner, repo, toGitPath(norm), branch)
    }

    /**
     * 写入文件（自动判断新建还是覆盖）。
     * 覆盖时总是先取最新 sha，避免客户端缓存过期导致 409 冲突。
     */
    fun write(path: String, data: ByteArray, commitMessage: String? = null): VfsNode {
        val norm = normalize(path)
        if (norm == "/") throw IOException("不能写入根目录")
        if (data.size > MAX_FILE_SIZE) {
            throw IOException("文件超过 ${MAX_FILE_SIZE / 1024 / 1024}MB 上限，GitHub Contents API 不支持")
        }
        ensureParentDirs(norm)

        val gitPath = toGitPath(norm)
        val existing = client.getFileMeta(owner, repo, gitPath, branch)
        val msg = commitMessage ?: if (existing == null) "上传 ${nameOf(norm)}" else "更新 ${nameOf(norm)}"
        val meta = client.putFile(owner, repo, gitPath, data, existing?.sha, msg, branch)

        invalidate(parentOf(norm))
        invalidate(norm)
        return VfsNode(nameOf(norm), norm, false, data.size.toLong(), meta.sha)
    }

    /** 创建目录：GitHub 无空目录，用 .gitkeep 占位 */
    fun createDirectory(path: String) {
        val norm = normalize(path)
        if (norm == "/") return
        if (isDirectory(norm)) return
        if (exists(norm)) throw IOException("已存在同名文件：$norm")
        ensureParentDirs("$norm/placeholder")
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

    /** 递归创建父目录链 */
    private fun ensureParentDirs(filePath: String) {
        val parent = parentOf(normalize(filePath))
        if (parent == "/") return
        if (isDirectory(parent)) return
        ensureParentDirs("$parent/x")
        val keepPath = toGitPath(parent) + "/" + KEEP_FILE
        if (client.getFileMeta(owner, repo, keepPath, branch) == null) {
            client.putFile(owner, repo, keepPath, ByteArray(0), null, "创建目录 ${nameOf(parent)}", branch)
        }
        invalidate(parentOf(parent))
        invalidate(parent)
    }

    /** 删除文件或目录（目录递归删除） */
    fun delete(path: String) {
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
        val children = list(normDir, useCache = false)
        for (child in children) {
            if (child.isDirectory) {
                deleteDirectoryRecursive(child.path)
            } else {
                client.deleteFile(owner, repo, toGitPath(child.path), child.sha, "删除 ${child.name}", branch)
            }
        }
        // 目录里可能有 .gitkeep，需要一并清理，否则删完目录后占位文件残留
        invalidate(normDir)
        val remain = list(normDir, useCache = false)
        for (r in remain) {
            client.deleteFile(owner, repo, toGitPath(r.path), r.sha, "删除 ${r.name}", branch)
        }
    }

    /** 移动/重命名（GitHub 无原生 move，走读-写-删） */
    fun move(fromPath: String, toPath: String) {
        val from = normalize(fromPath)
        val to = normalize(toPath)
        if (from == "/" || to == "/") throw IOException("不能移动根目录")
        if (from == to) return
        if (exists(to)) throw IOException("目标已存在：$to")
        val node = stat(from) ?: throw FileNotFoundException("源不存在：$from")

        if (node.isDirectory) {
            createDirectory(to)
            for (child in list(from, useCache = false)) {
                if (child.name == KEEP_FILE) continue
                move(child.path, "$to/${child.name}")
            }
            delete(from)
        } else {
            val data = read(from)
            write(to, data, "移动 ${node.name}")
            delete(from)
        }
    }

    /** 供 UI 展示的修改时间（GitHub Contents API 不提供文件 mtime，统一用当前时间占位） */
    fun displayModified(): Date = Date()

    fun formatTimestamp(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)

    /** 仓库总配额参考（GitHub 建议单仓库 < 1GB） */
    fun usageHint(): String = "GitHub 建议私有仓库保持在 1GB 以内"
}