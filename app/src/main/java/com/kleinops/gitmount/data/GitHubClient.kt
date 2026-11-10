package com.kleinops.gitmount.data

import android.util.Base64
import com.kleinops.gitmount.service.LogBus
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
class GitHubClient(
    private val token: String,
    /**
     * 大文件分片下载临时落盘目录。
     * 由 MountService/MountPointManager 传入应用 cacheDir；为 null 时大文件走
     * 单连接流式下载（慢速 CDN 下可能触发规律性超时），有目录则启用 32 线程
     * Range 并发分片（多连接并行绕开单连接限速 + 分片独立重试）。
     */
    private val spoolDir: java.io.File? = null,
) {

    /** 超过该字节数的 LFS 资产启用分片并行下载（≤ 阈值走单流，避免小文件分片开销） */
    private val PARALLEL_MIN_BYTES = 8L * 1024 * 1024
    /** 分片并发上限（32 线程） */
    private val PARALLEL_MAX = 32
    /** 单个分片的理想大小（片数 = size / CHUNK，最多 32） */
    private val PARALLEL_CHUNK = 4L * 1024 * 1024
    /** 单分片失败重试次数（含首次） */
    private val PARALLEL_RETRY = 3

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
        // 关键：分片下载要真正并行，必须放宽每主机并发上限（OkHttp 默认 maxRequestsPerHost=5，
        // 32 线程分片若只有 5 个连接在跑会全部排队，提速归零）
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        })
        .build()

    /**
     * Range 探测专用 OkHttp：短超时（connect 8s / read 12s）。
     * 探测只是发个 GET + Range: bytes=0-0 验证认证与分片支持，理论上秒回；
     * 若某个认证模式/站点在弱网下卡住，用短超时快速失败换下一种认证/降级单流，
     * 绝不让"认证探测"拖成几十秒的阻塞（FTP 场景这会直接拖死整个 RETR）。
     */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
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
     * 获取 LFS 桶（release）的 numeric id；不存在则创建。
     * 每个仓库固定一个 tag 承载全部大文件资产，避免每文件一个 release 的碎片化。
     *
     * 关键演进（2026-11-09 二修）：桶必须为【已发布】release——下载已改走
     * GitHub Releases 浏览器直链（github.com/{owner}/{repo}/releases/download/{tag}/{file}），
     * draft release 无直链（恒 404）。因此创建时 draft=false（发布态）。
     * 旧版创建的 draft 桶由 publishLfsReleaseIfDraft() 在挂载启动时迁移发布。
     *
     * 历史经验备注：不能用 GET /releases/tags/{tag} 查桶——GitHub 对 draft release
     * 的该端点恒返回 404，导致"查不到桶→每次新建"的桶无限增殖；改为遍历 releases
     * 列表（含 publish 与 draft）按 tag_name 匹配。
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
            .put("draft", false)
            .put("make_latest", false)
        val body = execute(
            authRequest(url("/repos/$owner/$repo/releases"))
                .post(payload.toString().toRequestBody(jsonMedia))
        )
        return JSONObject(body).optLong("id", -1L).also {
            if (it <= 0) throw GitHubApiException(502, "创建 LFS 桶失败")
        }
    }

    /**
     * 迁移旧版创建的 draft LFS 桶为发布态（下载直链的前提）。
     * 幂等：桶不存在、已是发布态时静默跳过。
     * 迁移失败不阻断挂载——fallback 的 API 下载路径仍可用。
     */
    fun publishLfsReleaseIfDraft(owner: String, repo: String, tag: String) {
        try {
            val id = findReleaseIdByTag(owner, repo, tag)
            if (id <= 0) return
            val o = JSONObject(
                execute(authRequest(url("/repos/$owner/$repo/releases/$id")).get())
            )
            if (o.optBoolean("draft", false)) {
                LogBus.info("迁移 LFS 桶 $tag（id=$id）为发布态，启用下载直链")
                execute(
                    authRequest(url("/repos/$owner/$repo/releases/$id"))
                        .method("PATCH", JSONObject().put("draft", false).toString().toRequestBody(jsonMedia))
                )
            }
        } catch (e: Exception) {
            LogBus.warn("LFS 桶发布迁移失败（忽略，API 通道仍可用）：${e.message}")
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
     * 列出 LFS 桶内全部资产（name -> LfsAsset 映射）。
     * GitHub Releases 资产列表接口分页返回（per_page=100），逐个翻页直到取完。
     */
    fun listLfsAssets(owner: String, repo: String, releaseId: Long): Map<String, LfsAsset> {
        val result = LinkedHashMap<String, LfsAsset>()
        for (page in 1..10) {
            val body = execute(
                authRequest(url("/repos/$owner/$repo/releases/$releaseId/assets?per_page=100&page=$page")).get()
            )
            val arr = JSONArray(body)
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optLong("id", -1L)
                val name = o.optString("name", "")
                if (id > 0 && name.isNotEmpty()) {
                    result[name] = LfsAsset(id, o.optLong("size", 0L), name)
                }
            }
            if (arr.length() < 100) break
        }
        return result
    }

    /** 按资产名在桶内查找（上传幂等确认用）。找不到返回 null。 */
    fun findLfsAssetByName(
        owner: String,
        repo: String,
        releaseId: Long,
        fileName: String,
    ): LfsAsset? = listLfsAssets(owner, repo, releaseId)[fileName]

    /**
     * 上传资产到 LFS 桶（流式，已知总长度）。
     *
     * 关键约束（2026-11-09 实测）：与 Contents API 一样，uploads.github.com
     * 拒绝 Transfer-Encoding: chunked，强制要求请求带已知 Content-Length
     * （否则 400 "Bad Content-Length"）。因此调用方必须先确定总字节数：
     * WebDAV 用 HTTP Content-Length；FTP 数据连接无长度则先落盘临时文件统计；
     * 内存字节直接用 size。请求体仍边读边写（流式），只在头部声明固定长度。
     * 传输过程中边读边算 SHA-256，写入指针文件供下载后完整性比对。
     *
     * 幂等确认（2026-11-09 二修）：实测上传 body 已完整送达、资产已创建，但读
     * uploads.github.com 的响应体超时（SocketTimeoutException）——若直接判失败，
     * 指针不写、客户端看不到文件（表现为"上传成功但没成功标志，要再扫列表才出现"）。
     * 因此读响应超时/失败后，转"按资产名查桶"幂等确认：找到即视为成功。
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
            .build()
        val respBody = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = extractErrorMessage(resp.body?.string().orEmpty())
                    throw GitHubApiException(resp.code, msg)
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: java.net.SocketTimeoutException) {
            // 上传 body 可能已送达、响应被吞：先幂等确认再决定抛错与否。
            // 用 size 与 totalBytes 严格比对：写阶段超时（body 未传完）时资产 size 必然
            // 不匹配 → 仍报失败；读响应超时（body 已完整送达）时 size 匹配 → 视为成功。
            LogBus.warn("上传响应超时（${e.message}），按资产名幂等确认…")
            val found = findLfsAssetByName(owner, repo, releaseId, fileName)
            if (found != null && found.size == totalBytes) {
                val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                LogBus.info("幂等确认成功：资产 $fileName（${found.size} 字节）已完整创建，视为上传成功")
                return found.copy(sha256 = sha)
            }
            LogBus.warn("幂等确认未找到完整资产 $fileName（size=${found?.size}, 期望=$totalBytes），按上传失败处理")
            throw GitHubApiException(504, "上传超时且资产未确认，请重试（$fileName）")
        } catch (e: IOException) {
            // GitHubApiException（4xx/5xx 业务错误）是确定失败，不幂等确认；
            // 仅连接级 IO 错误（网断/重置）才可能"资产已创建但响应丢失"，需确认。
            if (e is GitHubApiException) throw e
            LogBus.warn("上传 IO 异常（${e.message}），按资产名幂等确认…")
            val found = findLfsAssetByName(owner, repo, releaseId, fileName)
            if (found != null && found.size == totalBytes) {
                val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                LogBus.info("幂等确认成功：资产 $fileName（${found.size} 字节）已完整创建，视为上传成功")
                return found.copy(sha256 = sha)
            }
            LogBus.warn("幂等确认未找到完整资产 $fileName（size=${found?.size}, 期望=$totalBytes），按上传失败处理")
            throw e
        }
        val o = JSONObject(respBody)
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
     * 长流下载的关键设计（v2 修正 + 二修直链）：
     * 1. 用 assetClient（读超时 90s = 两次读取间隔上限，不是总时长），
     *    CDN 慢速但持续有数据时绝不误杀，真正挂起时仍能主动失败；
     * 2. 优先走 GitHub Releases 浏览器直链
     *    github.com/{owner}/{repo}/releases/download/{tag}/{file}——
     *    绕开 api.github.com 网关（其 302 链路在国内网络下不稳定，实测下载中途
     *    SocketTimeoutException）。直链本身由 github.com 302 到带签名的 CDN，
     *    assetClient 自动跟随；携带 Authorization 兜底私有仓库。
     *    前提：桶必须是已发布 release（getOrCreateLfsRelease 已改为发布态 +
     *    publishLfsReleaseIfDraft 迁移旧桶）。draft 桶直链恒 404 → fallback。
     * 3. 直链失败（404/超时/旧指针无 fileName）时 fallback 到 API 资产端点
     *    /repos/{owner}/{repo}/releases/assets/{id}（原 v2 通道），保证旧数据可读。
     * 4. 返回的流由调用方负责关闭；close() 同时释放整个 Response，避免连接泄漏。
     */
    fun openLfsAssetStream(owner: String, repo: String, tag: String, pointer: GitRepository.LfsPointer?): java.io.InputStream {
        // 大文件 + 配置了临时目录 → 32 线程 Range 分片并行下载（绕开单连接限速规律性停滞 +
        // 分片独立重试）；否则走单连接流式（原直链三重认证 + API fallback 逻辑）。
        // 小文件分片收益为负（多连接开销 > 速度提升），保持单流。
        if (pointer != null && pointer.size >= PARALLEL_MIN_BYTES && spoolDir != null) {
            LogBus.info("LFS 大文件启用分片并行下载：${pointer.size} 字节（上限 ${PARALLEL_MAX} 线程）")
            return openLfsParallel(owner, repo, tag, pointer)
        }
        return openLfsSingle(owner, repo, tag, pointer)
    }

    /** 单连接流式下载（v2 直链三重认证 + API fallback，原逻辑） */
    private fun openLfsSingle(owner: String, repo: String, tag: String, pointer: GitRepository.LfsPointer?): java.io.InputStream {
        // 直链优先：指针携带 fileName 且桶已发布（tag 对应 release 存在）时走浏览器直链。
        // 私有仓库：直链第一跳必须带认证（github.com 认证通过才 302 到带签名的 CDN URL，
        // CDN 那跳靠 URL 签名、不需要再认证）。github.com 网页端点对 Bearer 兼容性弱于
        // API 网关，因此按 Bearer → token → Basic(x-access-token) 顺序尝试，401/403 换下一种，
        // 全部失败才 fallback 到 API 资产端点（保证私有仓库一定能下）。
        if (pointer != null && pointer.fileName.isNotBlank()) {
            val fileNameEnc = java.net.URLEncoder.encode(pointer.fileName, "UTF-8").replace("+", "%20")
            val directUrl = "https://github.com/$owner/$repo/releases/download/$tag/$fileNameEnc"
            var authTried = listOf<DirectAuthMode>()
            for (auth in listOf(DirectAuthMode.BEARER, DirectAuthMode.TOKEN, DirectAuthMode.BASIC)) {
                authTried = authTried + auth
                try {
                    LogBus.info("LFS 下载直链（$auth）：$directUrl")
                    return openStreamInternal(directUrl, authMode = auth, forceOctetStream = true)
                } catch (e: GitHubApiException) {
                    if (e.code == 401 || e.code == 403) {
                        LogBus.warn("直链 $auth 认证被拒（${e.code}），换下一种认证…")
                        continue
                    }
                    LogBus.warn("直链 $auth 下载失败（${e.message}），fallback 到 API 资产通道")
                    break
                } catch (e: Exception) {
                    LogBus.warn("直链 $auth 下载异常（${e.message}），fallback 到 API 资产通道")
                    break
                }
            }
            LogBus.info("直链认证均不可用（尝试 $authTried），fallback API 通道")
        }
        if (pointer == null || pointer.assetId <= 0) {
            throw GitHubApiException(502, "LFS 指针无效：无资产 ID")
        }
        LogBus.info("LFS 下载 API 通道：asset ${pointer.assetId}")
        val apiUrl = url("/repos/$owner/$repo/releases/assets/${pointer.assetId}")
        return openStreamInternal(apiUrl, authMode = DirectAuthMode.BEARER, forceOctetStream = true)
    }

    /**
     * 解析 LFS 资产的下载站点（URL + 认证模式 + 是否支持 Range），供分片并行下载复用。
     * 直链优先（fileName 非空）：按 Bearer → token → Basic 顺序探测，
     * 探测用 GET + Range: bytes=0-0——一次同时验证认证与 Range 支持
     * （206 = 支持分片；200 = 站点存在但不支持 Range；401/403 换下一种认证）。
     * 直链全部不可用或无 fileName → fallback API 资产端点（BEARER）。
     * @return null 表示指针无效（无资产 ID 且无 fileName）
     */
    private fun resolveLfsSite(
        owner: String,
        repo: String,
        tag: String,
        pointer: GitRepository.LfsPointer,
    ): Triple<String, DirectAuthMode, Boolean>? {
        if (pointer.fileName.isNotBlank()) {
            val fileNameEnc = java.net.URLEncoder.encode(pointer.fileName, "UTF-8").replace("+", "%20")
            val directUrl = "https://github.com/$owner/$repo/releases/download/$tag/$fileNameEnc"
            for (auth in listOf(DirectAuthMode.BEARER, DirectAuthMode.TOKEN, DirectAuthMode.BASIC)) {
                try {
                    when (probeRange(directUrl, auth)) {
                        RangeProbe.OK -> {
                            LogBus.info("LFS 下载直链（$auth）：$directUrl（Range 可用）")
                            return Triple(directUrl, auth, true)
                        }
                        RangeProbe.UNAUTHORIZED -> {
                            LogBus.warn("直链 $auth 认证被拒（401/403），换下一种认证…")
                            continue
                        }
                        RangeProbe.NO_RANGE -> {
                            // 站点存在但不支持 Range：退化为单片（仍比 API 网关快）
                            LogBus.warn("直链 $auth 不支持 Range，退化为单流下载")
                            return Triple(directUrl, auth, false)
                        }
                    }
                } catch (e: Exception) {
                    LogBus.warn("直链 $auth 探测异常（${e.message}），换下一种认证…")
                }
            }
            LogBus.info("直链认证均不可用，fallback API 通道")
        }
        if (pointer.assetId <= 0) return null
        val apiUrl = url("/repos/$owner/$repo/releases/assets/${pointer.assetId}")
        LogBus.info("LFS 下载 API 通道：asset ${pointer.assetId}")
        // API 资产端点同样探测 Range 支持：206 → 可分片；200/异常 → 降级单流
        // （GitHub API 对资产下载可能直接 200 全文，分片请求会拿不到 206 而误判失败）
        return try {
            when (probeRange(apiUrl, DirectAuthMode.BEARER)) {
                RangeProbe.OK -> Triple(apiUrl, DirectAuthMode.BEARER, true)
                RangeProbe.NO_RANGE -> {
                    LogBus.warn("API 通道不支持 Range，退化为单流下载")
                    Triple(apiUrl, DirectAuthMode.BEARER, false)
                }
                RangeProbe.UNAUTHORIZED -> {
                    LogBus.warn("API 通道认证被拒（401/403），退化为单流（由 openStreamInternal 报错误）")
                    Triple(apiUrl, DirectAuthMode.BEARER, false)
                }
            }
        } catch (e: Exception) {
            LogBus.warn("API 通道 Range 探测异常（${e.message}），退化为单流下载")
            Triple(apiUrl, DirectAuthMode.BEARER, false)
        }
    }

    /** Range 探测结果 */
    private enum class RangeProbe { OK, UNAUTHORIZED, NO_RANGE }

    /**
     * 探测站点可用性与 Range 支持：GET + Range: bytes=0-0。
     * 206 = 支持分片；401/403 = 认证失败；200 = 忽略 Range（不支持分片）；
     * 其他非 2xx = 站点不可用（404 等），抛异常由调用方决定换认证/fallback。
     */
    private fun probeRange(downloadUrl: String, authMode: DirectAuthMode): RangeProbe {
        val builder = Request.Builder()
            .url(downloadUrl)
            .addHeader("Authorization", authHeaderValue(authMode))
            .addHeader("Accept", "application/octet-stream")
            .addHeader("Range", "bytes=0-0")
        builder.header("X-GitHub-Api-Version", "2022-11-28")
        // 探测用短超时 client：认证探测失败应快速降级/换认证，不拖慢整体下载
        val resp = probeClient.newCall(builder.build()).execute()
        try {
            return when {
                resp.code == 206 -> RangeProbe.OK
                resp.code == 401 || resp.code == 403 -> RangeProbe.UNAUTHORIZED
                resp.code == 200 -> RangeProbe.NO_RANGE
                else -> throw GitHubApiException(resp.code, extractErrorMessage(resp.body?.string().orEmpty()))
            }
        } finally {
            resp.close()
        }
    }

    /**
     * 32 线程 Range 分片并行下载（边下边吐），返回按序读取的组合流。
     * 每个分片独立下载到临时文件、独立重试（PARALLEL_RETRY 次）、由 Future 管理；
     * 读流按序消费：切到第 i 片时先等该片下载完成，再顺序读出（不会读到半截数据）。
     * 首片刻意取小（256KB）保证客户端数据连接首字节延迟极小；
     * 多连接并行绕开单连接限速规律性停滞；close() 关闭流、删除全部临时文件并回收线程池。
     */
    private fun openLfsParallel(
        owner: String,
        repo: String,
        tag: String,
        pointer: GitRepository.LfsPointer,
    ): java.io.InputStream {
        val site = resolveLfsSite(owner, repo, tag, pointer)
            ?: throw GitHubApiException(502, "LFS 指针无效：无资产 ID")
        val (baseUrl, authMode, rangeOk) = site
        if (!rangeOk) {
            // 站点不支持 Range：分片无意义，退化为单流（直链认证已探测通过）
            LogBus.info("LFS 站点不支持 Range，退化为单流下载")
            return openStreamInternal(baseUrl, authMode = authMode, forceOctetStream = true)
        }
        val dir = spoolDir
            ?: throw IOException("分片下载需要临时目录")
        if (!dir.exists()) dir.mkdirs()
        val total = pointer.size
        // 首片 256KB（快速出首字节），其余按 PARALLEL_CHUNK 均分，总片数最多 PARALLEL_MAX
        val HEAD = 256 * 1024L
        val bodyStart = minOf(HEAD, total)
        val bodyLen = total - bodyStart
        val bodyParts = if (bodyLen > 0) {
            ((bodyLen + PARALLEL_CHUNK - 1) / PARALLEL_CHUNK).coerceIn(1, (PARALLEL_MAX - 1).toLong())
        } else 0L
        val parts = 1 + bodyParts.toInt()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(parts, PARALLEL_MAX))
        val fileByPart = (0 until parts).map { part ->
            java.io.File.createTempFile("gm-dl-$part-", ".part", dir)
        }
        val seg = if (bodyParts > 0) bodyLen / bodyParts else 0L
        // 每个分片的 [start, end] 闭区间
        fun rangeOf(part: Int): Pair<Long, Long> = when {
            part == 0 -> 0L to (bodyStart - 1).coerceAtLeast(0)
            part == parts - 1 -> (bodyStart + (part - 1) * seg) to (total - 1)
            else -> (bodyStart + (part - 1) * seg) to (bodyStart + part * seg - 1)
        }
        val futures = (0 until parts).map { part ->
            val (start, end) = rangeOf(part)
            val out = fileByPart[part]
            pool.submit(java.util.concurrent.Callable {
                var attempt = 0
                var lastErr: Exception? = null
                while (attempt < PARALLEL_RETRY) {
                    attempt++
                    try {
                        downloadRange(baseUrl, authMode, start, end, out)
                        lastErr = null
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        if (attempt < PARALLEL_RETRY) {
                            LogBus.warn("LFS 分片 $part（$start-$end）第 $attempt 次失败（${e.message}），重试…")
                            // 重试退避：第 1 次失败等 500ms，第 2 次等 2000ms。
                            // 32 片若同时失败同时重试，会瞬间再打爆 CDN 连接数造成集体再失败；
                            // 退避让重试错峰，显著提高重试成功率。
                            try {
                                Thread.sleep(if (attempt == 1) 500L else 2000L)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                        }
                    }
                }
                if (lastErr != null) {
                    throw IOException("LFS 分片 $part（$start-$end）下载失败：${lastErr.message}", lastErr)
                }
                Unit
            })
        }
        LogBus.info("LFS 分片下载启动：$total 字节 → $parts 片并发（首片 $HEAD 字节）")
        return SequentialPartsStream(fileByPart, futures, pool)
    }

    /** 下载 [start,end] 区间到分片文件（独立文件，从 offset 0 写入；带头认证 + Range，206 校验） */
    private fun downloadRange(
        baseUrl: String,
        authMode: DirectAuthMode,
        start: Long,
        end: Long,
        out: java.io.File,
    ) {
        val builder = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", authHeaderValue(authMode))
            .addHeader("Accept", "application/octet-stream")
            .addHeader("Range", "bytes=$start-$end")
        builder.header("X-GitHub-Api-Version", "2022-11-28")
        val resp = assetClient.newCall(builder.build()).execute()
        try {
            if (resp.code != 206) {
                throw GitHubApiException(resp.code, extractErrorMessage(resp.body?.string().orEmpty()))
            }
            val body = resp.body ?: throw GitHubApiException(502, "分片响应无内容")
            java.io.FileOutputStream(out).use { fos ->
                val src = body.byteStream()
                val buf = ByteArray(128 * 1024)
                var written = 0L
                val expected = end - start + 1
                while (written < expected) {
                    val n = src.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                    written += n
                }
                if (written != expected) {
                    throw IOException("LFS 分片 $start-$end 只收到 $written/$expected 字节")
                }
            }
        } finally {
            resp.close()
        }
    }

    /** 直链认证模式：github.com 网页端点对三种格式的兼容性依次递减，401/403 换下一种 */
    private enum class DirectAuthMode { BEARER, TOKEN, BASIC }

    /**
     * 生成对应认证模式的 Authorization header 值。
     * BASIC 用 x-access-token 作为用户名（GitHub git 操作同款机制），无需真实用户名。
     * 注意：token 是实例属性，必须在这里（实例方法内）访问，嵌套 enum 访问不到。
     */
    private fun authHeaderValue(mode: DirectAuthMode): String = when (mode) {
        DirectAuthMode.BEARER -> "Bearer $token"
        DirectAuthMode.TOKEN -> "token $token"
        DirectAuthMode.BASIC ->
            "Basic " + java.util.Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())
    }

    /**
     * 执行 GET 并返回响应流。
     * @param authMode 认证方式；API 通道用 BEARER，直链按序尝试三种。
     * @param forceOctetStream true 时声明 Accept: application/octet-stream（下载二进制必需）。
     */
    private fun openStreamInternal(
        downloadUrl: String,
        authMode: DirectAuthMode,
        forceOctetStream: Boolean = false,
    ): java.io.InputStream {
        val builder = Request.Builder()
            .url(downloadUrl)
            .addHeader("Authorization", authHeaderValue(authMode))
        if (forceOctetStream) {
            builder.header("Accept", "application/octet-stream")
        } else {
            builder.header("Accept", "application/vnd.github+json")
        }
        builder.header("X-GitHub-Api-Version", "2022-11-28")
        val resp = assetClient.newCall(builder.build()).execute()
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

/**
 * 组合分片流：按序读取多个分片文件。
 * 切到第 i 片时先等待该片下载 Future 完成（抛异常则传播为 IOException），
 * 再顺序读出该片全部字节，保证客户端永远读不到半截分片。
 * close() 关闭当前流、删除全部临时文件并中断线程池剩余任务。
 */
private class SequentialPartsStream(
    private val files: List<java.io.File>,
    private val futures: List<java.util.concurrent.Future<*>>,
    private val pool: java.util.concurrent.ExecutorService,
) : java.io.InputStream() {
    private var index = 0
    private var current: java.io.InputStream? = null
    private var closed = false

    private fun awaitPart(i: Int) {
        try {
            futures[i].get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException("LFS 分片 $i 下载失败：${e.cause?.message}", e.cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("LFS 分片 $i 下载被中断", e)
        } catch (e: java.util.concurrent.CancellationException) {
            // 流已关闭、任务被取消（close 的 pool.shutdownNow）时 get() 抛此异常
            throw IOException("LFS 分片 $i 下载已取消（流关闭）", e)
        }
    }

    override fun skip(n: Long): Long {
        if (closed) throw IOException("流已关闭")
        if (n <= 0) return 0
        var remaining = n
        // 逐片跳过：当前片剩余字节直接用 FileInputStream.skip（快），不够再切下一片。
        // 不重写的话 InputStream 默认 skip 会逐字节 read，156MB 断点续传会跳过极慢。
        while (remaining > 0) {
            if (index >= files.size) break
            val stream = current
            if (stream == null) {
                awaitPart(index)
                current = java.io.FileInputStream(files[index])
                continue
            }
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                // 当前片 skip 无法前进（接近片尾），切下一片
                stream.close()
                current = null
                index++
            }
        }
        return n - remaining
    }

    override fun read(): Int {
        val b = ByteArray(1)
        val n = read(b, 0, 1)
        return if (n < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("流已关闭")
        if (len == 0) return 0
        while (true) {
            if (index >= files.size) return -1
            val stream = current
            if (stream == null) {
                awaitPart(index)
                current = java.io.FileInputStream(files[index])
                continue
            }
            val n = stream.read(b, off, len)
            if (n >= 0) return n
            // 当前片读完：切下一片
            stream.close()
            current = null
            index++
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            current?.close()
        } catch (_: Exception) {
        } finally {
            current = null
        }
        pool.shutdownNow()
        files.forEach { file ->
            runCatching { file.delete() }
        }
    }
}