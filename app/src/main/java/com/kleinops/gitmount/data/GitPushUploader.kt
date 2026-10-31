package com.kleinops.gitmount.data

import android.util.Base64
import com.kleinops.gitmount.service.LogBus
import java.io.File
import java.io.IOException

/**
 * 基于内嵌 git 本体的上传器：本地无完整副本，只推增量对象。
 *
 * 原理（沙箱已对真实 GitHub 私有仓库验证）：
 * 1. bare promisor 仓库 + `fetch --filter=blob:none` 只拉 commit/tree 元数据，不拉文件内容；
 * 2. 新文件内容用 `hash-object -w --stdin` 写成 blob（流式，无 base64 膨胀）；
 * 3. 读出旧根 tree，递归更新目标路径的每一层 tree，
 *    用 `hash-object -t tree -w --stdin` 手工构造（绕过 mktree 对缺失 blob 的校验）；
 * 4. 构造 commit（tree + parent = 旧 HEAD）；
 * 5. `update-ref refs/heads/<branch> <newcommit>` 后 `push`（thin pack 协商，只发增量）。
 *
 * 效果：不受 GitHub Contents API 100MB（base64 后）限制，78MB 等大文件直接放行；
 * 本地仓库只膨胀元数据（每次上传新增 1 blob + 若干 tree + 1 commit，通常 <10KB）。
 */
class GitPushUploader(
    private val git: GitNative,
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val branch: String,
) {
    private val remoteUrl: String = "https://github.com/$owner/$repo.git"

    private val authHeader: String
        get() = "Basic " + Base64.encodeToString(
            "x-access-token:$token".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )

    /** tree 条目（mode/type/sha/name 与 ls-tree 输出对齐） */
    private data class TreeEntry(val mode: String, val sha: String, val name: String)

    /** 确保 bare promisor 仓库存在 */
    fun ensureRepo() {
        git.ensureBinary()
        if (!File(git.repoDir, "HEAD").exists()) {
            val r = git.runGit(listOf("init", "--bare"), timeoutSeconds = 60)
            if (!r.ok) throw IOException("git init 失败：${r.output.take(300)}")
            LogBus.info("git push 仓库初始化完成：${git.repoDir.absolutePath}")
        }
        // origin 与 promisor 标记：声明本仓库缺失对象由远端按需提供，
        // 这是 thin pack 协商（本地缺 blob 仍能 push）的前提，缺了会 push 失败。
        val hasOrigin = git.runGit(listOf("remote")).stdout.lineSequence().any { it == "origin" }
        if (!hasOrigin) {
            git.runGit(
                listOf("remote", "add", "origin", remoteUrl),
                timeoutSeconds = 30,
            )
        }
        git.runGit(listOf("config", "remote.origin.promisor", "true"))
        git.runGit(listOf("config", "remote.origin.partialclonefilter", "blob:none"))
        git.runGit(listOf("config", "http.extraheader", "Authorization: $authHeader"))
        git.runGit(listOf("config", "http.version", "HTTP/1.1"))
    }

    /**
     * 拉取最新元数据，返回 (HEAD commit, root tree)。仓库无提交时返回 ("", "")。
     */
    private fun fetchMeta(): Pair<String, String> {
        val f = git.runGit(
            listOf(
                "-c", "http.extraheader=Authorization: $authHeader",
                "fetch", "--filter=blob:none", "origin", branch,
            ),
            timeoutSeconds = 600,
        )
        if (!f.ok) {
            // 远端可能为空仓库（无该分支），此时当作无提交处理
            if (f.stderr.contains("couldn't find remote ref") || f.stderr.contains("not found")) {
                return "" to ""
            }
            throw IOException("git fetch 失败：${f.output.take(400)}")
        }
        // 关键：把 FETCH_HEAD 写成本地分支引用，push 时才有 src refspec；
        // 否则本地 refs/heads/<branch> 不存在，push 报 "src refspec ... does not match any"。
        git.runGit(listOf("update-ref", "refs/heads/$branch", "FETCH_HEAD"))
        val head = git.runGit(listOf("rev-parse", "refs/heads/$branch")).stdout.trim()
        if (head.isEmpty()) return "" to ""
        val tree = git.runGit(listOf("rev-parse", "$head^{tree}")).stdout.trim()
        return head to tree
    }

    /**
     * 上传文件：返回新 blob 的 sha（与 Contents API 返回的 sha 语义一致）。
     * @param path 仓库内相对路径，如 "a/b/c.txt"
     */
    fun upload(path: String, data: ByteArray, message: String): String {
        ensureRepo()
        val (head, rootTree) = fetchMeta()

        // 1) 新文件内容 -> blob（流式写 stdin，无 base64 膨胀）
        val blob = git.runGit(listOf("hash-object", "-w", "--stdin"), input = data, timeoutSeconds = 600)
        if (!blob.ok) throw IOException("写入文件对象失败：${blob.output.take(300)}")
        val blobSha = blob.stdout.trim()
        LogBus.info("git blob 写入成功：${blobSha.take(7)}（${data.size} 字节）")

        // 2) 递归更新 tree：目标路径每一层都构造新 tree
        val cleanPath = path.removePrefix("/")
        val parts = cleanPath.split("/").filter { it.isNotBlank() }
        if (parts.isEmpty()) throw IOException("无效路径：$path")
        val newRootTree = updateTree(rootTree, parts, blobSha)

        // 3) 构造 commit
        val ts = System.currentTimeMillis() / 1000
        val author = "gitmount <gitmount@local>"
        val sb = StringBuilder()
        sb.append("tree $newRootTree\n")
        if (head.isNotBlank()) sb.append("parent $head\n")
        sb.append("author $author $ts +0000\n")
        sb.append("committer $author $ts +0000\n\n")
        sb.append(message).append('\n')
        val commit = git.runGit(
            listOf("hash-object", "-t", "commit", "-w", "--stdin"),
            input = sb.toString().toByteArray(Charsets.UTF_8),
        )
        if (!commit.ok) throw IOException("构造 commit 失败：${commit.output.take(300)}")
        val commitSha = commit.stdout.trim()
        LogBus.info("git commit 构造成功：${commitSha.take(7)}（tree $newRootTree，parent ${head.take(7).ifBlank { "无" }}）")

        // 4) 更新本地分支引用
        val ref = git.runGit(listOf("update-ref", "refs/heads/$branch", commitSha))
        if (!ref.ok) throw IOException("update-ref 失败：${ref.output.take(300)}")

        // 5) push（thin pack：只发新增对象）
        val push = git.runGit(
            listOf(
                "-c", "http.extraheader=Authorization: $authHeader",
                "push", "origin", branch,
            ),
            timeoutSeconds = 900,
        )
        if (!push.ok) {
            throw IOException("git push 失败：${push.output.take(500)}")
        }
        LogBus.info("git push 成功：$path → $owner/$repo ($branch)")
        return blobSha
    }

    /**
     * 递归更新 tree：把 parts[0] 指向的条目替换为（最终指向 blob 的）新子树。
     * @param treeSha 当前层 tree sha；"" 表示空 tree（新目录链）
     */
    private fun updateTree(treeSha: String, parts: List<String>, blobSha: String): String {
        val headName = parts[0]
        val rest = parts.drop(1)

        // 读当前层条目
        val entries = if (treeSha.isBlank()) {
            mutableListOf()
        } else {
            val ls = git.runGit(listOf("ls-tree", treeSha))
            if (!ls.ok) throw IOException("ls-tree 失败：${ls.output.take(300)}")
            ls.stdout.lineSequence().filter { it.isNotBlank() }.map { line ->
                val meta = line.substringBefore('\t').split(' ')
                // <mode> <type> <sha>\t<name>
                // ls-tree 目录 mode 是 040000（6 位），tree 对象内必须是 40000（4 位）：
                // 统一转成 int 再 %o 归一（"040000"→"40000"，"100644"→"100644"）
                val mode = meta[0].toInt(8).toString(8)
                TreeEntry(mode, meta[2], line.substringAfter('\t', ""))
            }.toMutableList()
        }

        var replaced = false
        for (i in entries.indices) {
            if (entries[i].name == headName) {
                if (rest.isEmpty()) {
                    entries[i] = TreeEntry("100644", blobSha, headName)
                } else {
                    entries[i] = TreeEntry("40000", updateTree(entries[i].sha, rest, blobSha), headName)
                }
                replaced = true
                break
            }
        }
        if (!replaced) {
            if (rest.isEmpty()) {
                entries.add(TreeEntry("100644", blobSha, headName))
            } else {
                entries.add(TreeEntry("40000", updateTree("", rest, blobSha), headName))
            }
        }

        // 按 git tree 规则排序：条目比较用 name 的原始字节，且目录（40000）比较时
        // 按 "name/" 参与排序（等价于目录名 + '/'），与 git 内部 tree-walk 完全一致。
        // 排序键：mode 为 40000（目录）时附加 '/'，再按 name 的 UTF-8 字节序比较。
        entries.sortWith { a, b ->
            val aKey = if (a.mode == "40000") "${a.name}/" else a.name
            val bKey = if (b.mode == "40000") "${b.name}/" else b.name
            compareBytes(aKey, bKey)
        }

        // 构造 tree 二进制：<mode> <name>\0<20-byte sha>
        val raw = java.io.ByteArrayOutputStream()
        for (e in entries) {
            raw.write(e.mode.toByteArray(Charsets.US_ASCII))
            raw.write(' '.code)
            raw.write(e.name.toByteArray(Charsets.UTF_8))
            raw.write(0)
            raw.write(hexToBytes(e.sha))
        }
        val r = git.runGit(
            listOf("hash-object", "-t", "tree", "-w", "--stdin"),
            input = raw.toByteArray(),
        )
        if (!r.ok) throw IOException("构造 tree 失败：${r.output.take(300)}")
        return r.stdout.trim()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    /** 无符号字节序比较（git tree 条目排序规则） */
    private fun compareBytes(a: String, b: String): Int {
        val ba = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        val n = minOf(ba.size, bb.size)
        for (i in 0 until n) {
            val x = ba[i].toInt() and 0xFF
            val y = bb[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return ba.size - bb.size
    }
}