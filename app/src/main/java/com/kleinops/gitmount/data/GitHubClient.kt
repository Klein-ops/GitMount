package com.kleinops.gitmount.data

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** GitHub API 异常（携带 HTTP 状态码，便于上层给出可读提示） */
class GitHubApiException(val code: Int, detail: String) : IOException(detail)

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

    /** 创建私有仓库（auto_init 生成默认分支，便于 Contents API 直接写入） */
    fun createPrivateRepo(name: String, description: String = "GitMount 托管的私有数据仓库") {
        val payload = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("private", true)
            .put("auto_init", true)
        execute(
            authRequest(url("/user/repos"))
                .post(payload.toString().toRequestBody(jsonMedia))
        )
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
            .put("content", Base64.encodeToString(content, Base64.NO_WRAP))
            .put("branch", branch)
        sha?.let { payload.put("sha", it) }
        val url = url("/repos/$owner/$repo/contents/${encodePath(path)}")
        val body = execute(
            authRequest(url)
                .put(payload.toString().toRequestBody(jsonMedia))
        )
        val o = JSONObject(body)
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