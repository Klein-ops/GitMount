package com.kleinops.gitmount.data

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

/** GitHub API 异常（携带 HTTP 状态码，便于上层给出可读提示） */
class GitHubApiException(val code: Int, detail: String) : IOException(detail)

/** 本地拦截：上传超过单文件上限（避免把必然被 GitHub 拒绝的大文件发给它，省 API 调用） */
class FileTooLargeException(val limit: Long) :
    IOException("文件超过 ${limit / 1000 / 1000}MB 上限，GitHub Contents API 不支持（本地拦截）")

/** 文件元信息（sha 用于更新/删除时的并发控制） */
data class FileMeta(
    val sha: String,
    val size: Long,
)

/** 仓库概要（用于“选择已有仓库”界面） */
data class RepoBrief(
    val name: String,
    val fullName: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
)

/**
 * 仓库容量信息。
 * GitHub 的 size 单位为 KB，且**包含全部提交历史**占用的空间；
 * 私有仓库免费额度为 500MB；公开仓库没有存储配额上限。
 */
data class RepoInfo(
    val owner: String,
    val repo: String,
    val sizeKb: Long,
    /** 是否私有仓库。公开仓库无配额，UI 只展示已用容量，不展示剩余/占比/超限。 */
    val isPrivate: Boolean,
    val defaultBranch: String,
) {
    /** 私有仓库容量上限：500MB = 512000 KB（仅对私有仓库有意义） */
    val quotaKb: Long get() = 500L * 1024

    val usedKb: Long get() = sizeKb

    /** 剩余容量：仅私有仓库适用（公开仓库无配额，调用方应据 isPrivate 判断） */
    val remainingKb: Long get() = (quotaKb - sizeKb).coerceAtLeast(0)

    /** 已用占比：仅私有仓库适用（公开仓库无配额，调用方应据 isPrivate 判断） */
    val percentUsed: Int
        get() = ((sizeKb.toDouble() / quotaKb) * 100).toInt().coerceIn(0, 100)

    /** 是否超出配额：仅私有仓库适用（公开仓库无配额，调用方应据 isPrivate 判断） */
    val exceeded: Boolean get() = sizeKb > quotaKb

    /** 人类可读的已用容量（保留 1 位小数） */
    val usedHuman: String get() = formatKb(sizeKb)

    /** 人类可读的剩余容量 */
    val remainingHuman: String get() = formatKb(remainingKb)

    private fun formatKb(kb: Long): String = when {
        kb >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", kb / 1024.0 / 1024.0)
        kb >= 1024 -> String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
        else -> "$kb KB"
    }
}

/**
 * GitHub REST API 客户端。
 * 覆盖本应用需要的全部操作：校验 token、创建私有仓库、列目录、读写删文件、目录建占位文件。
 * 全部使用同步调用，由调用方调度到 IO 线程。
 */
class GitHubClient(private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 大文件资产下载专用 OkHttp：连接 15s / 读 30s，比全局 client 短得多。
     * 原因：asset 下载会 302 到 release-assets CDN，若 CDN 慢/挂起而全局
     * readTimeout(120s)，FTP/WebDAV 转发线程会傻等 120s 才失败，而客户端
     * （控制连接 readLine / HTTP 读响应）超时通常只有 30~60s，必然客户端先
     * 超时（SocketTimeoutException）。用短超时让服务器在客户端超时之前主动
     * 失败并回明确错误，客户端能收到失败而不是干等。
     */
    private val assetClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String): String = "https://api.github.com$path"

    private fun authRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/vnd.github+json")
        .addHeader("X-GitHub-Api-Version", "2022-11-28")

    private fun execute(builder: Request.Builder): String {
        // OkHttp Request.Builder 的 get/post/put/delete 均返回 Builder 本身，
        // 需要显式 build() 得到 Request 才能交给 Call。
        client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = extractErrorMessage(body)
                throw GitHubApiException(resp.code, msg)
            }
            return body
        }
    }

    // ---------- GitHub Releases 通道（大文件 >40MB 走这里，仓库里只留指针） ----------

    /** 大文件资产元信息（上传成功后返回） */
    data class LfsAsset(
        val assetId: Long,
        val size: Long,
        val fileName: String,
        /** 上传内容的 SHA-256（流式边传边算，用于下载后完整性比对） */
        val sha256: String = "",
    )

    /**
     * 获取 LFS 桶（release）的 numeric id；不存在则创建（draft）。
     * 每个仓库固定一个 tag 承载全部大文件资产，避免每文件一个 release 的碎片化。
     * draft release 对外不可见，正好满足"仅本机/局域网访问"的语义。
     *
     * 2026-11-09 实测修正：**不能用 GET /releases/tags/{tag} 查桶**——GitHub 对
     * draft release 的该端点【恒返回 404】（draft 未发布，tag ref 不存在），
     * 导致每次上传大文件都"查不到桶→新建桶"，桶无限增殖，且 hasLfsBucket 恒 false
     * 造成列表显示指针小文件、下载拿到索引而非资产。改为遍历 releases 列表
     * （draft 也包含在列表里）按 tag_name 匹配，一次修复桶增殖 + 大小显示 + 下载链路。
     */
    fun getOrCreateLfsRelease(
        owner: String,
        repo: String,
        tag: String,
        title: String = "GitMount LFS 存储桶",
    ): Long {
        val existing = findReleaseIdByTag(owner, repo, tag)
        if (existing > 0) return existing
        // 创建：POST /repos/{owner}/{repo}/releases，draft=true（不对外暴露）
        val payload = JSONObject()
            .put("tag_name", tag)
            .put("name", title)
            .put("body", "GitMount 大文件存储桶（GitHub Contents API 单文件上限之外的二进制资产）。请勿手动删除。")
            .put("draft", true)
        val body = execute(
            authRequest(url("/repos/$owner/$repo/releases"))
                .post(payload.toString().toRequestBody(jsonMedia))
        )
        return JSONObject(body).optLong("id", -1L).also {
            if (it <= 0) throw GitHubApiException(502, "创建 LFS 桶失败")
        }
    }

    /**
     * 只读查询 LFS 桶是否已存在（不创建）。不存在返回 null。
     * 用于目录列表时判断"是否值得解析小文件是否为指针"——没有大文件的仓库
     * 跳过解析，避免对每个小文件都发一次 GET 打爆配额。
     */
    fun getLfsReleaseIfExists(owner: String, repo: String, tag: String): Long? =
        findReleaseIdByTag(owner, repo, tag).takeIf { it > 0 }

    /**
     * 遍历 releases 列表（含 draft）按 tag_name 匹配，返回 release id；找不到返回 -1。
     * 分页遍历最多 3 页（300 个 release），正常仓库远达不到；每页不足 100 个即停。
     */
    private fun findReleaseIdByTag(owner: String, repo: String, tag: String): Long {
        for (page in 1..3) {
            val arr = try {
                val body = execute(
                    authRequest(url("/repos/$owner/$repo/releases?per_page=100&page=$page")).get()
                )
                JSONArray(body)
            } catch (e: GitHubApiException) {
                if (e.code == 404) return -1L else throw e
            }
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("tag_name") == tag) {
                    val id = o.optLong("id", -1L)
                    if (id > 0) return id
                }
            }
            if (arr.length() < 100) break
        }
        return -1L
    }

    /**
     * 上传资产到 LFS 桶（流式，已知总长度）。
     *
     * 上传接口与普通 REST 不同：域名是 uploads.github.com，请求体是【原始二进制】
     * （不是 base64 JSON）。实测（2026-11-09）：该接口【拒绝 Transfer-Encoding: chunked】，
     * 强制要求请求带已知 Content-Length（否则返回 400 "Bad Content-Length"）。
     * 因此调用方必须先确定总字节数（WebDAV 用 HTTP Content-Length；
     * FTP 数据连接无长度则先落盘临时文件统计；内存字节直接用 size）。
     * 请求体仍边读边写（流式），只是长度声明为已知固定值。
     *
     * @param fileName 资产名（同一 release 内必须唯一，重复上传会 422）
     * @param input 数据流（边读边写，不整体驻留内存）
     * @param maxBytes 本地拦截上限（超过立即抛 FileTooLargeException 掐断）
     * @param totalBytes 数据流总长度（必须与 input 实际字节数一致，GitHub 按此校验）
     */
    fun uploadLfsAsset(
        owner: String,
        repo: String,
        releaseId: Long,
        fileName: String,
        input: java.io.InputStream,
        maxBytes: Long,
        totalBytes: Long,
    ): LfsAsset {
        val name = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val uploadUrl = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=$name"
        // 流式边传边算 SHA-256：用 DigestInputStream 包一层，写请求体时自动累计摘要，
        // 上传成功后 digest.digest() 即完整文件哈希（写进 LFS 指针，下载后可比对完整性）。
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val digestInput = java.security.DigestInputStream(input, digest)
        val body = StreamingRawRequestBody(digestInput, maxBytes, totalBytes)
        val req = authRequest(uploadUrl)
            .header("Content-Type", "application/octet-stream")
            .post(body)
        val resp = execute(req)
        val o = JSONObject(resp)
        val id = o.optLong("id", -1L)
        if (id <= 0) throw GitHubApiException(502, "上传资产失败：无法解析响应")
        val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return LfsAsset(id, o.optLong("size", 0L), o.optString("name", fileName), sha256)
    }

    /** 删除 LFS 桶内指定资产（与"删指针文件"配合，先删资产避免泄漏） */
    fun deleteLfsAsset(owner: String, repo: String, assetId: Long) {
        execute(authRequest(url("/repos/$owner/$repo/releases/assets/$assetId")).delete())
    }

    /**
     * 打开 LFS 资产下载流（私有仓库必须带 token，browserUrl 为 null）。
     * 返回的流由调用方负责关闭；关闭时同时释放 OkHttp Response。
     */
    fun openLfsAssetStream(owner: String, repo: String, assetId: Long): java.io.InputStream {
        val req = authRequest(url("/repos/$owner/$repo/releases/assets/$assetId"))
            .header("Accept", "application/octet-stream")
            .get()
            .build()
        // 用 assetClient（短超时）：CDN 挂起时在 30s 内主动失败，而不是傻等 120s
        // 拖垮转发线程，让 FTP/WebDAV 客户端在自身超时前收到明确失败而非 readLine 卡死
        val resp = assetClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            val msg = extractErrorMessage(resp.body?.string().orEmpty())
            resp.close()
            throw GitHubApiException(resp.code, msg)
        }
        val body = resp.body ?: run {
            resp.close()
            throw GitHubApiException(502, "资产下载响应无内容")
        }
        // 包装：关闭流时同时释放整个 Response，避免连接泄漏
        return object : java.io.FilterInputStream(body.byteStream()) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    body.close()
                }
            }
        }
    }

    /**
     * 单文件接口的响应必须是 JSON 对象。
     * 若路径实际是目录，GitHub 返回 JSON 数组；旧实现直接 JSONObject(body) 会抛
     * JSONException（"Value [...] cannot be converted to JSONObject"），
     * 这种底层异常冒泡到协议层会变成难以理解的报错。这里统一转换成语义明确的异常。
     */
    private fun requireObject(body: String, path: String): JSONObject {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) {
            throw GitHubApiException(409, "目标是一个目录而非文件：$path")
        }
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw GitHubApiException(502, "GitHub 返回了无法解析的响应")
        }
    }

    private fun extractErrorMessage(body: String): String {
        return try {
            val obj = JSONObject(body)
            obj.optString("message").ifBlank { "HTTP ${obj.optString("status")}" }
        } catch (e: Exception) {
            if (body.length > 200) body.take(200) else body
        }
    }

    /** 校验 token 并返回用户名（失败抛异常） */
    fun getUser(): String {
        val body = execute(authRequest(url("/user")).get())
        return JSONObject(body).optString("login")
    }

    /**
     * 查询 GitHub API 本小时速率限制（GET /rate_limit 不计入配额）。
     * 返回 core 主限流（除 search 外全部 REST 调用）的 limit/remaining/reset。
     * 便于用户了解"这个小时还能调用多少次 GitHub API"，避免快到上限时
     * 上传/下载突然 403 才反应过来。
     */
    fun rateLimit(): Pair<Int, Int> {
        val body = execute(authRequest(url("/rate_limit")).get())
        val o = JSONObject(body)
        val core = o.optJSONObject("resources")?.optJSONObject("core") ?: JSONObject()
        // remaining 可能为负（超出后 GitHub 返回 0？实测为 0 封顶），coerceAtLeast 兜底
        val remaining = core.optInt("remaining", -1).coerceAtLeast(0)
        val limit = core.optInt("limit", -1)
        if (remaining < 0 || limit < 0) throw GitHubApiException(502, "无法解析速率限制响应")
        return limit to remaining
    }

    /** 查询仓库默认分支（用于判断仓库已存在） */
    fun getDefaultBranch(owner: String, repo: String): String? {
        return try {
            val body = execute(authRequest(url("/repos/$owner/$repo")).get())
            JSONObject(body).optString("default_branch").ifBlank { "main" }
        } catch (e: GitHubApiException) {
            if (e.code == 404) null else throw e
        }
    }

    /**
     * 查询仓库容量信息（size 单位为 KB，包含提交历史）。
     * 私有仓库免费额度 500MB，超限后写入会失败，需要清理历史或扩容；
     * 公开仓库无配额上限。
     */
    fun getRepoInfo(owner: String, repo: String): RepoInfo? {
        return try {
            val body = execute(authRequest(url("/repos/$owner/$repo")).get())
            val o = JSONObject(body)
            RepoInfo(
                owner = owner,
                repo = repo,
                sizeKb = o.optLong("size"),
                isPrivate = o.optBoolean("private", false),
                defaultBranch = o.optString("default_branch").ifBlank { "main" },
            )
        } catch (e: GitHubApiException) {
            if (e.code == 404) null else throw e
        }
    }

    /**
     * 获取分支最新 commit 的 sha 及其根 tree 的 sha（一次调用）。
     * tree 是当前仓库全部文件的目录结构索引，blob 对象仍被它引用，
     * 因此用它重建孤儿提交可以做到“全部文件保留、历史清空”。
     */
    fun getHeadTree(owner: String, repo: String, branch: String): Pair<String, String> {
        // 注意：不能用 /git/commits/{branch} —— Git Data API 只接受 commit SHA，传分支名必 404。
        // 用 Commits API（/commits/{ref}）接受 ref/分支名，顶层 sha 即最新提交，commit.tree.sha 即根 tree。
        val body = execute(authRequest(url("/repos/$owner/$repo/commits/$branch")).get())
        val o = JSONObject(body)
        val commitSha = o.optString("sha")
        val treeSha = o.optJSONObject("commit")?.optJSONObject("tree")?.optString("sha").orEmpty()
        if (commitSha.isBlank() || treeSha.isBlank()) {
            throw GitHubApiException(502, "无法解析仓库提交信息")
        }
        return commitSha to treeSha
    }

    /**
     * 创建孤儿提交（无父提交，parents 为空数组），复用传入的 tree sha。
     * @return 新 commit 的 sha
     */
    fun createOrphanCommit(owner: String, repo: String, treeSha: String, message: String): String {
        val payload = JSONObject()
            .put("message", message)
            .put("tree", treeSha)
            .put("parents", JSONArray()) // 关键：空 parents = 孤儿提交，与历史彻底断开
        val body = execute(
            authRequest(url("/repos/$owner/$repo/git/commits"))
                .post(payload.toString().toRequestBody(jsonMedia))
        )
        val sha = JSONObject(body).optString("sha")
        if (sha.isBlank()) throw GitHubApiException(502, "创建提交失败")
        return sha
    }

    /** 强制移动分支引用到指定 commit（把默认分支指向孤儿提交，旧历史随即不可达） */
    fun updateBranchRef(owner: String, repo: String, branch: String, commitSha: String) {
        val payload = JSONObject()
            .put("sha", commitSha)
            .put("force", true)
        execute(
            authRequest(url("/repos/$owner/$repo/git/refs/heads/$branch"))
                .patch(payload.toString().toRequestBody(jsonMedia))
        )
    }

    /**
     * 列出当前令牌可访问的仓库（本人拥有的，含私有）。
     * 一次最多返回 100 个（按最近更新排序）；超出时可分页，这里保持简单。
     */
    fun listUserRepos(): List<RepoBrief> {
        val body = execute(
            authRequest(url("/user/repos?affiliation=owner&per_page=100&sort=updated")).get()
        )
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    RepoBrief(
                        name = o.optString("name"),
                        fullName = o.optString("full_name"),
                        isPrivate = o.optBoolean("private"),
                        defaultBranch = o.optString("default_branch").ifBlank { "main" },
                    )
                )
            }
        }
    }

    /** 创建仓库（auto_init 生成默认分支，便于 Contents API 直接写入） */
    fun createRepo(name: String, isPrivate: Boolean, description: String? = null) {
        val payload = JSONObject()
            .put("name", name)
            .put("description", description ?: if (isPrivate) "GitMount 托管的私有数据仓库" else "GitMount 托管的数据仓库")
            .put("private", isPrivate)
            .put("auto_init", true)
        execute(
            authRequest(url("/user/repos"))
                .post(payload.toString().toRequestBody(jsonMedia))
        )
    }

    /**
     * 删除仓库（不可逆，永久删除仓库及其全部文件与提交历史）。
     * 危险操作：仅允许在用户明确输入完整仓库名确认后调用。
     * DELETE /repos/{owner}/{repo} 成功时返回 204 无响应体。
     */
    fun deleteRepo(owner: String, repo: String) {
        execute(authRequest(url("/repos/$owner/$repo")).delete())
    }

    /** 列出目录内容 */
    fun listDir(owner: String, repo: String, path: String, branch: String): List<GitEntry> {
        val encodedPath = if (path.isEmpty()) "" else "/${encodePath(path)}"
        val url = url("/repos/$owner/$repo/contents$encodedPath") + "?ref=$branch"
        val body = execute(authRequest(url).get())
        // 若该路径其实是文件，GitHub 返回单个对象；调用方传错时按“非目录”处理
        if (!body.trimStart().startsWith("[")) {
            throw GitHubApiException(409, "目标不是目录：$path")
        }
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    GitEntry(
                        name = o.optString("name"),
                        path = o.optString("path"),
                        type = o.optString("type"),
                        size = o.optLong("size"),
                        sha = o.optString("sha"),
                    )
                )
            }
        }
    }

    /**
     * 读取文件内容（返回字节与 sha）。
     *
     * 注意：Contents API 对 >1MB 的文件不返回内容（encoding == "none"），
     * 此时必须回退到 Git Blob API（上限 100MB），否则会读到空内容。
     */
    fun getFile(owner: String, repo: String, path: String, branch: String): Pair<ByteArray, FileMeta> {
        val url = url("/repos/$owner/$repo/contents/${encodePath(path)}") + "?ref=$branch"
        val body = execute(authRequest(url).get())
        val o = requireObject(body, path)
        val sha = o.optString("sha")
        val size = o.optLong("size")
        val encoding = o.optString("encoding", "base64")
        val bytes = if (encoding == "base64") {
            val b64 = o.optString("content").replace("\n", "")
            Base64.decode(b64, Base64.DEFAULT)
        } else {
            getBlobBytes(owner, repo, sha)
        }
        return bytes to FileMeta(sha, size)
    }

    /** 通过 Git Blob API 读取内容（base64，支持到 100MB） */
    fun getBlobBytes(owner: String, repo: String, sha: String): ByteArray {
        val body = execute(authRequest(url("/repos/$owner/$repo/git/blobs/$sha")).get())
        val o = JSONObject(body)
        val encoding = o.optString("encoding", "base64")
        val b64 = o.optString("content").replace("\n", "")
        return if (encoding == "base64") Base64.decode(b64, Base64.DEFAULT) else b64.toByteArray()
    }

    /** 查询单文件元信息（不存在或目标是目录时返回 null） */
    fun getFileMeta(owner: String, repo: String, path: String, branch: String): FileMeta? {
        return try {
            val url = url("/repos/$owner/$repo/contents/${encodePath(path)}") + "?ref=$branch"
            val body = execute(authRequest(url).get())
            // 目录会返回数组，对文件元信息查询而言视为“不是文件”
            if (body.trimStart().startsWith("[")) return null
            val o = requireObject(body, path)
            FileMeta(o.optString("sha"), o.optLong("size"))
        } catch (e: GitHubApiException) {
            if (e.code == 404) null else throw e
        }
    }

    /**
     * 写入文件（创建或覆盖）。
     * @param sha 覆盖时必传（用于乐观锁）；创建时为 null
     */
    fun putFile(
        owner: String,
        repo: String,
        path: String,
        content: ByteArray,
        sha: String?,
        message: String,
        branch: String,
    ): FileMeta {
        val payload = JSONObject()
            .put("message", message)
            .put("branch", branch)
            .put("content", content) // 占位，实际值由流式 RequestBody 覆盖
        sha?.let { payload.put("sha", it) }
        val url = url("/repos/$owner/$repo/contents/${encodePath(path)}")

        // 关键内存优化：78MB 文件的 base64 文本约 104MB，若用 encodeToString 生成
        // 大 String（UTF-16 下 208MB），再拼进 JSON.toString() 又复制一份，直接 OOM。
        // 这里改为流式 base64 RequestBody：content 只保留一份原始字节，
        // 编码产物边写边丢，峰值内存 = content 大小 + 一小块缓冲。
        val body = Base64RequestBody(payload, "content", content)

        val resp = execute(
            authRequest(url).put(body)
        )
        val o = JSONObject(resp)
        return FileMeta(o.optString("sha"), content.size.toLong())
    }

    /** 删除文件 */
    fun deleteFile(owner: String, repo: String, path: String, sha: String, message: String, branch: String) {
        val payload = JSONObject()
            .put("message", message)
            .put("sha", sha)
            .put("branch", branch)
        val url = url("/repos/$owner/$repo/contents/${encodePath(path)}")
        execute(
            authRequest(url)
                .delete(payload.toString().toRequestBody(jsonMedia))
        )
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}

/**
 * 流式 base64 JSON RequestBody。
 *
 * 目的：避免大文件上传时把 base64 文本拼成超大 String。
 * 例如 78MB 文件 → base64 约 104MB → Java String(UTF-16) 就是 208MB，
 * 再并入 JSON.toString() 又复制一份，默认堆（256MB）直接 OOM。
 *
 * 实现：请求体形如 {"message":"...","branch":"...","content":"<base64>","sha":"..."}，
 * content 字段的 base64 按小块边编码边写入 sink，不整体驻留内存。
 */
private class Base64RequestBody(
    private val payload: JSONObject,
    private val contentKey: String,
    private val content: ByteArray,
) : RequestBody() {

    private val b64Len: Long = ((content.size + 2L) / 3L) * 4L

    override fun contentType(): okhttp3.MediaType? =
        "application/json; charset=utf-8".toMediaType()

    override fun contentLength(): Long = headBytes().size + b64Len + tailBytes().size

    override fun writeTo(sink: BufferedSink) {
        sink.write(headBytes())
        // 按 3 字节一组（base64 严格 3→4），每次 3KB 源 → 4KB 文本，内存占用恒定
        val chunk = 3072
        var offset = 0
        while (offset < content.size) {
            val end = minOf(offset + chunk, content.size)
            val piece = content.copyOfRange(offset, end)
            sink.writeUtf8(Base64.encodeToString(piece, Base64.NO_WRAP))
            offset = end
        }
        sink.write(tailBytes())
    }

    /** 请求体 head：非 content 字段 + content 键的开头引号 */
    private fun headBytes(): ByteArray {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        fun sep() {
            if (!first) sb.append(',') else first = false
        }
        payload.keys().forEach { key ->
            if (key != contentKey) {
                sep()
                sb.append(JSONObject.quote(key)).append(':')
                when (val v = payload.get(key)) {
                    is String -> sb.append(JSONObject.quote(v))
                    is Number, is Boolean -> sb.append(v)
                    else -> sb.append(JSONObject.quote(v.toString()))
                }
            }
        }
        sep()
        sb.append(JSONObject.quote(contentKey)).append(":\"")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** 请求体 tail：content 值收尾引号 + 闭合大括号 */
    private fun tailBytes(): ByteArray =
        "\"}".toByteArray(Charsets.UTF_8)
}

/**
 * 流式【原始二进制】RequestBody（给 GitHub Releases 上传资产用）。
 *
 * release 资产上传接口（uploads.github.com）接受的是裸二进制，不需要 base64。
 *
 * 长度语义（2026-11-09 实测修正）：uploads.github.com **拒绝 chunked**，
 * 强制要求已知 Content-Length（否则 400 "Bad Content-Length"）。
 * 因此本类不再返回 -1，而是返回调用方传入的 totalBytes（已知固定长度），
 * OkHttp 会带 Content-Length 发送；数据仍边读边写，不整体驻留内存。
 * 达到 maxBytes 上限立即抛 FileTooLargeException 掐断请求，
 * 避免把必然被 GitHub 拒绝的超大文件白白推给它（省流量、免白等）。
 */
private class StreamingRawRequestBody(
    private val input: java.io.InputStream,
    private val maxBytes: Long,
    private val totalBytes: Long,
) : RequestBody() {

    override fun contentType(): okhttp3.MediaType? =
        "application/octet-stream".toMediaType()

    /** 返回已知总长度：GitHub 要求固定 Content-Length（拒绝 chunked） */
    override fun contentLength(): Long = totalBytes

    override fun writeTo(sink: BufferedSink) {
        val chunk = 64 * 1024
        val buf = ByteArray(chunk)
        var total = 0L
        while (total < totalBytes) {
            val want = minOf(chunk.toLong(), totalBytes - total).toInt()
            val n = try {
                input.read(buf, 0, want)
            } catch (e: java.net.SocketTimeoutException) {
                // 客户端发完数据但未关闭数据连接（在等 226），视为传输结束
                break
            }
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                // 已超限：立即抛异常让 OkHttp 中止请求，不把数据推给 GitHub
                throw FileTooLargeException(maxBytes)
            }
            sink.write(buf, 0, n)
        }
        // 实际读取不足声明长度：宁可失败，也不上传尾部损坏的文件
        // （正常路径下 input 读出字节数应恰好 = totalBytes；少说明客户端中断/长度声明有误）
        if (total < totalBytes) {
            throw IOException("上传数据不完整：预期 $totalBytes 字节，实际收到 $total 字节")
        }
    }
}