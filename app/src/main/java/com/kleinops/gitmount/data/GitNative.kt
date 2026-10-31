package com.kleinops.gitmount.data

import android.content.Context
import android.os.Build
import com.kleinops.gitmount.service.LogBus
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 内嵌 git 可执行文件（git-standalone 2.43.4，arm64 全静态单文件）的管理与进程执行。
 *
 * 要点：
 * 1. assets 里的 ELF 不能直接执行，首次使用解压到本应用私有目录 filesDir/git-bin/git；
 * 2. 执行时固定 HOME=filesDir + GIT_CONFIG_NOSYSTEM=1，否则宿主全局 gitconfig 里
 *    `url.git@github.com:.insteadof=https://github.com/` 会把 HTTPS 悄悄改写为 SSH，
 *    导致 token 认证失效（沙箱验证时已踩过这个坑）；
 * 3. GIT_TERMINAL_PROMPT=0：认证失败直接报错，绝不挂起等用户输入。
 */
class GitNative(private val context: Context) {

    private val binDir: File get() = File(context.filesDir, "git-bin")
    val gitBinary: File get() = File(binDir, "git")

    /** bare promisor 仓库位置（全部元数据，不含文件内容） */
    val repoDir: File get() = File(context.filesDir, "gitmount-repo")

    /** 当前设备是否带内嵌 git 二进制（目前仅 arm64-v8a） */
    fun supported(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    /** 确保 git 可执行文件就位（assets 解压一次，之后复用） */
    fun ensureBinary(): File {
        if (gitBinary.exists() && gitBinary.length() > 10_000_000) return gitBinary
        binDir.mkdirs()
        val assetName = "git/arm64-v8a/git"
        val stream = try {
            context.assets.open(assetName)
        } catch (e: IOException) {
            throw IOException("assets 缺少 $assetName（当前设备不支持 git push 方案）", e)
        }
        val tmp = File(binDir, "git.tmp")
        stream.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.setExecutable(true, true)) {
            throw IOException("无法设置 git 可执行权限")
        }
        if (!tmp.renameTo(gitBinary)) {
            gitBinary.delete()
            if (!tmp.renameTo(gitBinary)) throw IOException("git 二进制就位失败")
        }
        LogBus.info("git 二进制就绪：${gitBinary.absolutePath}（${gitBinary.length() / 1024 / 1024}MB）")
        return gitBinary
    }

    /** git 命令执行结果 */
    data class GitResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
        val output: String get() = if (stdout.isNotBlank()) stdout else stderr
    }

    /**
     * 执行 git 命令（工作目录默认 bare 仓库目录）。
     * @param input 需要写入 stdin 的字节（如 hash-object --stdin），null 则立即关闭 stdin
     */
    fun runGit(
        args: List<String>,
        cwd: File = repoDir,
        input: ByteArray? = null,
        timeoutSeconds: Long = 300,
    ): GitResult {
        if (!gitBinary.exists()) ensureBinary()
        val cmd = ArrayList<String>(args.size + 1)
        cmd.add(gitBinary.absolutePath)
        cmd.addAll(args)
        val pb = ProcessBuilder(cmd)
        pb.directory(cwd)
        val env = pb.environment()
        env["HOME"] = context.filesDir.absolutePath
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["GIT_TERMINAL_PROMPT"] = "0"
        val proc = pb.start()
        // 写 stdin：有内容则写完关闭；无内容立即关闭（否则 git 会一直等 EOF）
        proc.outputStream.use { out ->
            if (input != null && input.isNotEmpty()) out.write(input)
        }
        // 并发读 stdout/stderr：顺序读在输出量大时会因管道缓冲写满而死锁
        var stdoutBytes = ByteArray(0)
        var stderrBytes = ByteArray(0)
        val tOut = Thread { stdoutBytes = proc.inputStream.readBytes() }.apply {
            isDaemon = true; start()
        }
        val tErr = Thread { stderrBytes = proc.errorStream.readBytes() }.apply {
            isDaemon = true; start()
        }
        val done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        tOut.join(3000)
        tErr.join(3000)
        if (!done) {
            proc.destroyForcibly()
            throw IOException("git 命令超时（${timeoutSeconds}s）：${args.take(4).joinToString(" ")}")
        }
        return GitResult(proc.exitValue(), stdoutBytes.toString(Charsets.UTF_8), stderrBytes.toString(Charsets.UTF_8))
    }
}