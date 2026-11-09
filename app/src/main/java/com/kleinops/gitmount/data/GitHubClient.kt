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

/** Releases 大文件资产元信息（上传成功后返回） */
data class LfsAsset(
    val assetId: Long,
    val size: Long,
    val fileName: String,
    /** 上传内容的 SHA-256（流式边传边算，写入指针供下载后完整性比对） */
    val sha256: String = "",
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
     * 大文件资产下载专用 OkHttp。
     *
     * 超时选型的关键认知（v2 修正）：OkHttp 的 readTimeout 语义是【两次读取之间的
     * 最大间隔】，不是"整个传输的总时长"。旧版设 30s，导致 156MB 大文件在 CDN 慢速
     * 但持续传输时，任意一次 TCP 读到数据的间隔超过 30s 就被判超时——这正是"下载
     * 只走了几 KB 就 SocketTimeoutException、且每次几乎同一时间失败"的根因之一。
     *
     * 这里放宽到 90s：网络慢但持续有数据时绝不误杀；CDN 真正挂起（超过 90s 无任何
     * 字节）时仍然主动失败，且远小于全局 client 的 120s，转发线程不会被拖死。
     * 连接超时保持 15s（建立连接要快，失败早报）。
     */
    private val assetClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
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

    // ---------- GitHub Releases 通道（大文件 >40MB 走这里，仓库里只留指针） ----------

    /**
     * 获取 LFS 桶（release）的 numeric id；不存在则创建（draft）。
     * 每个仓库固定一个 tag 承载全部大文件资产，避免每文件一个 release 的碎片化。
     * draft release 对外不可见，正好满足"仅本机/局域网访问"的语义。
     *
     * 关键经验（2026-11-09 实测）：不能用 GET /releases/tags/{tag} 查桶——GitHub 对
     * draft release 的该端点恒返回 404（draft 未发布，tag ref 不存在），导致"查不到桶
     * →每次新建"的桶无限增殖。改为遍历 releases 列表（draft 也包含）按 tag_name 匹配。
     */
    fun getOrCreateLfsRelease(
        owner: String,
        repo: String,
        tag: String,
        title: String = "GitMount LFS 存储桶",
    ): Long {
        val existing = findReleaseIdByTag(owner, repo, tag)
        if (existing > 0) return existing
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

    /** 只读查询 LFS 桶是否已存在（不创建）。作为惰性检查：无大文件的仓库跳过指针解析。 */
    fun getLfsReleaseIfExists(owner: String, repo: String, tag: String): Long? =
        findReleaseIdByTag(owner, repo, tag).takeIf { it > 0 }

    /** 遍历 releases 列表（含 draft）按 tag_name 匹配，返回 release id；找不到返回 -1。 */
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
     * 关键约束（2026-11-09 实测）：与 Contents API 一样，uploads.github.com
     * 拒绝 Transfer-Encoding: chunked，强制要求请求带已知 Content-Length
     * （否则 400 "Bad Content-Length"）。因此调用方必须先确定总字节数：
     * WebDAV 用 HTTP Content-Length；FTP 数据连接无长度则先落盘临时文件统计；
     * 内存字节直接用 size。请求体仍边读边写（流式），只在头部声明固定长度。
     * 传输过程中边读边算 SHA-256，写入指针文件供下载后完整性比对。
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
        // OkHttp DELETE 默认会携带 Content-Length: 0，uploads 无 body，成功返回 204
        execute(authRequest(url("/repos/$owner/$repo/releases/assets/$assetId")).delete())
    }

    /**
     * 打开 LFS 资产下载流。
     *
     * 长流下载的关键设计（v2 修正）：
     * 1. 用 assetClient（读超时 90s = 两次读取间隔上限，不是总时长），
     *    CDN 慢速但持续有数据时绝不误杀，真正挂起时仍能主动失败；
     * 2. Accept: application/octet-stream 让 GitHub 直接返回二进制体，不走 browser_download_url
     *    （那会在 302 后多一跳，且私库需要浏览器会话 cookie，API 路径 + token 更可靠）；
     * 3. 返回的流由调用方负责关闭；close() 同时释放整个 Response，避免连接泄漏。
     */
    fun openLfsAssetStream(owner: String, repo: String, assetId: Long): java.io.InputStream {
        val req = authRequest(url("/repos/$owner/$repo/releases/assets/$assetId"))
            .header("Accept", "application/octet-stream")
            .get()
            .build()
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

    /**
     * 流式写入文件（创建或覆盖）：边读输入流边 base64 转发给 GitHub（chunked）。
     *
     * 与 putFile 的区别：putFile 需要完整 ByteArray（App 必须先把整个文件收进内存，
     * 客户端进度会先飙到 100% 再开始真正上传，造成"假 100% / 卡顿"）。
     * 本方法接受 InputStream，请求体长度未知 → OkHttp 走 Transfer-Encoding: chunked，
     * 数据一边从上游读取一边写入 GitHub，客户端进度被 GitHub 真实速度拖住
     * （chunked PUT 已在终端实测：GitHub 返回 HTTP 201）。
     */
    fun putFileStream(
        owner: String,
        repo: String,
        path: String,
        input: java.io.InputStream,
        sha: String?,
        message: String,
        branch: String,
    ): FileMeta {
        val payload = JSONObject()
            .put("message", message)
            .put("branch", branch)
        sha?.let { payload.put("sha", it) }
        val url = url("/repos/$owner/$repo/contents/${encodePath(path)}")

        val body = StreamingBase64RequestBody(payload, "content", input, GitRepository.MAX_FILE_SIZE)
        val resp = execute(
            authRequest(url).put(body)
        )
        val o = JSONObject(resp)
        // PUT 响应结构为 {content:{...,sha,size}, commit:{...}}，sha/size 在 content 内
        val c = o.optJSONObject("content")
        return FileMeta(
            c?.optString("sha") ?: "",
            c?.optLong("size") ?: 0L,
        )
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
 * 流式 base64 JSON RequestBody（边读边传）。
 *
 * 与 Base64RequestBody 的区别：数据源是 InputStream 而非 ByteArray，
 * 且故意让 contentLength() 返回 -1（未知长度）→ OkHttp 走
 * Transfer-Encoding: chunked，GitHub 按流式接收（已在终端实测
 * chunked PUT 成功：HTTP 201）。
 *
 * 这样 FTP 数据连接收到的字节【边收边发】给 GitHub，客户端进度
 * 会被 GitHub 公网真实速度拖住，不再出现"客户端 100% 但 GitHub
 * 刚开始传"的假完成。
 *
 * base64 块边界：每 3072 字节源（3 的倍数）编码为 4096 字节文本，
 * 块与块拼接后整体解码正确；最后不足一块的残余字节单独编码补 padding。
 * 注意：InputStream.read() 不保证一次填满，必须用 fill 循环凑满一整块
 * 再编码，否则块边界被打碎会导致解码错误（文件损坏）。
 *
 * 结束判定：input.read() 返回 -1（客户端关闭数据连接）或抛
 * SocketTimeoutException（客户端发完但未关闭，在等 226，
 * 对应 FtpServer 的 DATA_IDLE_TIMEOUT_MS 语义）均视为数据已收完，
 * 把残余字节编码后写 tail 收尾请求。
 */
private class StreamingBase64RequestBody(
    private val payload: JSONObject,
    private val contentKey: String,
    private val input: java.io.InputStream,
    /** 本地拦截阈值：流式上传无法预知总长度，转发时累计计数，超过立即抛异常掐断 */
    private val maxBytes: Long,
) : RequestBody() {

    override fun contentType(): okhttp3.MediaType? =
        "application/json; charset=utf-8".toMediaType()

    /** 返回 -1 = 长度未知 → OkHttp 对该请求体使用 chunked 传输 */
    override fun contentLength(): Long = -1L

    override fun writeTo(sink: BufferedSink) {
        sink.write(headBytes())
        val chunk = 3072
        val buf = ByteArray(chunk)
        var filled = 0
        var total = 0L
        while (true) {
            val n = try {
                // 循环读直到填满 chunk 或 EOF：read() 不保证一次返回 len 字节
                input.read(buf, filled, chunk - filled)
            } catch (e: java.net.SocketTimeoutException) {
                // 客户端发完数据但未关闭数据连接（在等 226），视为传输结束
                break
            }
            if (n < 0) break
            filled += n
            total += n
            if (total > maxBytes) {
                // 已经超限：不再写 tail（请求体不成 JSON 也无所谓，会直接失败），
                // 立即抛异常让 OkHttp 中止请求，避免继续把数据推给 GitHub
                throw FileTooLargeException(maxBytes)
            }
            if (filled == chunk) {
                // 满一块：整块编码（无 padding），块边界严格 3072→4096
                sink.writeUtf8(Base64.encodeToString(buf, Base64.NO_WRAP))
                filled = 0
            }
        }
        if (filled > 0) {
            // 残余不足一块：单独编码（编码器自动补 padding）
            sink.writeUtf8(Base64.encodeToString(java.util.Arrays.copyOf(buf, filled), Base64.NO_WRAP))
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
 * 原始二进制流式 RequestBody（固定 Content-Length）。
 *
 * 用于上传 Releases 大文件资产：请求体是原始字节（不是 base64 JSON）。
 * 关键约束（2026-11-09 实测）：uploads.github.com 拒绝 Transfer-Encoding: chunked，
 * 必须带已知 Content-Length，因此 contentLength() 返回调用方传入的 totalBytes。
 *
 * writeTo 边读 input 边写 sink，不整体驻留内存；input 通常包了 DigestInputStream
 * 边传边算 SHA-256。读超时（客户端发完数据但未关闭数据连接）按流结束处理。
 * 超过 maxBytes 立即抛 FileTooLargeException 掐断，避免超大文件白传。
 */
private class StreamingRawRequestBody(
    private val input: java.io.InputStream,
    /** 本地拦截上限：累计写入超过立即掐断（2GB） */
    private val maxBytes: Long,
    /** 数据流总长度：必须与 input 实际字节数一致（GitHub 按此校验，不一致会 400） */
    private val totalBytes: Long,
) : RequestBody() {

    override fun contentType(): okhttp3.MediaType? =
        "application/octet-stream".toMediaType()

    /** 固定长度（GitHub 拒绝 chunked，必须声明已知 Content-Length） */
    override fun contentLength(): Long = totalBytes

    override fun writeTo(sink: BufferedSink) {
        val buf = ByteArray(128 * 1024)
        var written = 0L
        // 关键：按 totalBytes 精确读满即停，绝不等待 EOF。
        // HTTP keep-alive 客户端 PUT 发完 body 后保持连接等响应、不会关闭数据流，
        // 若这里读到 EOF 才停会永久卡死（正是旧版"读超时/卡死"的同类问题）。
        // FTP spool 场景上游是 FileInputStream，读到 EOF 但 written < totalBytes 表示
        // 长度不一致，让 OkHttp 写出少于 Content-Length 的字节，GitHub 会以 400 拒绝。
        while (written < totalBytes) {
            val want = minOf(buf.size.toLong(), totalBytes - written).toInt()
            val n = try {
                input.read(buf, 0, want)
            } catch (e: java.net.SocketTimeoutException) {
                // 上游超时（通常只出现在 FTP 场景）：按流结束处理，交给 OkHttp 长度校验
                break
            }
            if (n < 0) break
            written += n
            if (written > maxBytes) {
                // 超出本地拦截线：抛异常让 OkHttp 中止请求，避免把超限数据推给 GitHub
                throw FileTooLargeException(maxBytes)
            }
            sink.write(buf, 0, n)
        }
    }
}