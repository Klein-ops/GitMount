package com.kleinops.gitmount.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 用户配置（token 以 Keystore AES-GCM 加密后落盘） */
data class AppSettings(
    /** true = 服务只监听 127.0.0.1，仅本机可访问，不暴露局域网（全局设置） */
    val loopbackOnly: Boolean = false,
    /** true = 隐藏前台服务常驻通知（改用静默渠道）。默认 true：常驻通知默认关闭（全局设置） */
    val hideNotification: Boolean = true,
    /** true = 从最近任务列表隐藏本应用（后台运行时在任务切换界面不可见）（全局设置） */
    val hideFromRecents: Boolean = false,
    /** true = 打开 App 时自动启动服务（全局设置） */
    val autoStart: Boolean = true,
    /**
     * 用户期望服务运行（持久化的运行意图，全局）：
     * 手动点"开始服务"写 true，点"停止服务"（含通知栏停止按钮）写 false。
     * 服务进程被系统回收、按 START_STICKY 重建时，据此决定是否自动恢复——
     * true 则按最新设置拉起，false 则保持停止，避免"用户没开/已停却自己跑起来"。
     */
    val serviceEnabled: Boolean = false,
    /** 全部账户（动态激活架构：服务启动时为每个账户起空壳监听，连接认证后才激活） */
    val accounts: List<AccountConfig> = emptyList(),
    /** 当前激活账户 id（UI 以"当前账户"视角编辑配置；服务端同时监听全部账户端口） */
    val activeAccountId: String = "",
) {
    /** 当前激活账户；无账户或 id 失配时回退到第一个账户 */
    val activeAccount: AccountConfig?
        get() = accounts.find { it.id == activeAccountId } ?: accounts.firstOrNull()

    val isConfigured: Boolean
        get() = activeAccount?.isConfigured == true

    // ---------- 以下为"当前账户"的代理 getter（保持旧代码的 settings.token/owner/... 调用不变） ----------

    val token: String get() = activeAccount?.token ?: ""
    val owner: String get() = activeAccount?.owner ?: ""
    val repoName: String get() = activeAccount?.repoName ?: ""
    val branch: String get() = activeAccount?.branch ?: "main"
    val mode: String get() = activeAccount?.mode ?: "WEBDAV"
    val ftpPort: Int get() = activeAccount?.ftpPort ?: 2121
    val webdavPort: Int get() = activeAccount?.webdavPort ?: 8080
    val username: String get() = activeAccount?.username ?: "gitmount"
    val password: String get() = activeAccount?.password ?: ""
}

/** GitHub token 加密：Android Keystore 生成 AES-256-GCM 密钥，密文+IV 存 DataStore */
object TokenCrypto {
    private const val KEY_ALIAS = "gitmount_token_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        if (blob.isEmpty()) return ""
        return try {
            val parts = blob.split(":", limit = 2)
            if (parts.size != 2) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("token_enc")
        val OWNER = stringPreferencesKey("owner")
        val REPO = stringPreferencesKey("repo")
        val BRANCH = stringPreferencesKey("branch")
        val MODE = stringPreferencesKey("mode")
        val FTP_PORT = intPreferencesKey("ftp_port")
        val WEBDAV_PORT = intPreferencesKey("webdav_port")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
        val LOOPBACK_ONLY = booleanPreferencesKey("loopback_only")
        val HIDE_NOTIFICATION = booleanPreferencesKey("hide_notification")
        val HIDE_FROM_RECENTS = booleanPreferencesKey("hide_from_recents")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val SERVICE_ENABLED = booleanPreferencesKey("service_enabled")

        // 多账户存储：JSON 数组（账户字段水平冗余在每条记录里，token 逐账户加密）
        val ACCOUNTS_JSON = stringPreferencesKey("accounts_json")
        val ACTIVE_ACCOUNT_ID = stringPreferencesKey("active_account_id")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        // 多账户：优先读 accounts_json；为空则把旧的单账户配置迁移成第一个账户
        val accounts = parseAccountsJson(p[Keys.ACCOUNTS_JSON])
            .takeIf { it.isNotEmpty() }
            ?: legacySingleAccount(p).takeIf { it.isConfigured }?.let { listOf(it) }
            ?: emptyList()
        val activeId = p[Keys.ACTIVE_ACCOUNT_ID]
            ?.takeIf { id -> accounts.any { it.id == id } }
            ?: accounts.firstOrNull()?.id
            ?: ""
        AppSettings(
            loopbackOnly = p[Keys.LOOPBACK_ONLY] ?: false,
            hideNotification = p[Keys.HIDE_NOTIFICATION] ?: true,
            hideFromRecents = p[Keys.HIDE_FROM_RECENTS] ?: false,
            autoStart = p[Keys.AUTO_START] ?: true,
            serviceEnabled = p[Keys.SERVICE_ENABLED] ?: false,
            accounts = accounts,
            activeAccountId = activeId,
        )
    }

    /** 解析账户 JSON（[{id,name,token,owner,repoName,branch,mode,ftpPort,webdavPort,username,password}]） */
    private fun parseAccountsJson(json: String?): List<AccountConfig> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                AccountConfig(
                    id = o.optString("id", "").ifBlank { return@mapNotNull null },
                    name = o.optString("name", "账户"),
                    token = TokenCrypto.decrypt(o.optString("token", "")),
                    owner = o.optString("owner", ""),
                    repoName = o.optString("repoName", ""),
                    branch = o.optString("branch", "main"),
                    mode = o.optString("mode", "WEBDAV"),
                    ftpPort = o.optInt("ftpPort", 2121),
                    webdavPort = o.optInt("webdavPort", 8080),
                    username = o.optString("username", "gitmount"),
                    password = o.optString("password", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 旧版单账户配置 → AccountConfig（token 解密，字段来源与旧 AppSettings 读取一致） */
    private fun legacySingleAccount(p: Preferences): AccountConfig? {
        val tk = TokenCrypto.decrypt(p[Keys.TOKEN] ?: "")
        if (tk.isBlank()) return null
        val owner = p[Keys.OWNER] ?: return null
        val repo = p[Keys.REPO] ?: return null
        return AccountConfig(
            id = "default",
            name = "默认账户",
            token = tk,
            owner = owner,
            repoName = repo,
            branch = p[Keys.BRANCH] ?: "main",
            mode = p[Keys.MODE] ?: "WEBDAV",
            ftpPort = p[Keys.FTP_PORT] ?: 2121,
            webdavPort = p[Keys.WEBDAV_PORT] ?: 8080,
            username = p[Keys.USERNAME] ?: "gitmount",
            password = p[Keys.PASSWORD] ?: "",
        )
    }

    suspend fun saveRepo(token: String, owner: String, repo: String, branch: String) {
        context.dataStore.edit { p ->
            // 多账户：更新当前激活账户的仓库绑定（保留该账户其余字段）。
            // 尚无账户或激活账户不存在时（首次绑定），自动创建默认账户，避免绑定丢失。
            val current = readAccountsOrLegacy(p)
            val activeId = p[Keys.ACTIVE_ACCOUNT_ID]
                ?.takeIf { id -> current.any { it.id == id } }
                ?: current.firstOrNull()?.id
            val accounts = if (activeId != null) {
                current.map { a ->
                    if (a.id == activeId) {
                        a.copy(token = token, owner = owner, repoName = repo, branch = branch)
                    } else a
                }
            } else {
                listOf(
                    AccountConfig(
                        id = "default",
                        name = "默认账户",
                        token = token,
                        owner = owner,
                        repoName = repo,
                        branch = branch,
                    )
                )
            }
            p[Keys.ACCOUNTS_JSON] = writeAccountsJson(accounts)
            if (p[Keys.ACTIVE_ACCOUNT_ID].isNullOrBlank() || accounts.none { it.id == p[Keys.ACTIVE_ACCOUNT_ID] }) {
                p[Keys.ACTIVE_ACCOUNT_ID] = accounts.first().id
            }
        }
    }

    /** 序列化账户列表为 JSON（token 逐账户加密落盘） */
    private fun writeAccountsJson(accounts: List<AccountConfig>): String {
        val arr = org.json.JSONArray()
        accounts.forEach { a ->
            arr.put(
                org.json.JSONObject()
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("token", TokenCrypto.encrypt(a.token))
                    .put("owner", a.owner)
                    .put("repoName", a.repoName)
                    .put("branch", a.branch)
                    .put("mode", a.mode)
                    .put("ftpPort", a.ftpPort)
                    .put("webdavPort", a.webdavPort)
                    .put("username", a.username)
                    .put("password", a.password)
            )
        }
        return arr.toString()
    }

    /** 读取账户列表；无 accounts_json 时尝试旧单账户迁移（返回账户列表，不落盘） */
    private fun readAccountsOrLegacy(p: Preferences): List<AccountConfig> =
        parseAccountsJson(p[Keys.ACCOUNTS_JSON])
            .takeIf { it.isNotEmpty() }
            ?: legacySingleAccount(p)?.let { listOf(it) }
            ?: emptyList()

    /** 保存单个账户（新增或整体替换；同时维护激活账户 id） */
    suspend fun saveAccount(account: AccountConfig, activate: Boolean = true) {
        context.dataStore.edit { p ->
            val accounts = readAccountsOrLegacy(p)
            val idx = accounts.indexOfFirst { it.id == account.id }
            val updated = if (idx >= 0) accounts.toMutableList().apply { set(idx, account) } else accounts + account
            p[Keys.ACCOUNTS_JSON] = writeAccountsJson(updated)
            if (activate || p[Keys.ACTIVE_ACCOUNT_ID].isNullOrBlank()) {
                p[Keys.ACTIVE_ACCOUNT_ID] = account.id
            }
        }
    }

    /** 删除账户；若删的是激活账户，激活 id 回退到剩余第一个 */
    suspend fun deleteAccount(id: String) {
        context.dataStore.edit { p ->
            val accounts = readAccountsOrLegacy(p).filterNot { it.id == id }
            p[Keys.ACCOUNTS_JSON] = writeAccountsJson(accounts)
            if (p[Keys.ACTIVE_ACCOUNT_ID] == id) {
                p[Keys.ACTIVE_ACCOUNT_ID] = accounts.firstOrNull()?.id ?: ""
            }
        }
    }

    /** 切换激活账户 */
    suspend fun setActiveAccount(id: String) {
        context.dataStore.edit { p -> p[Keys.ACTIVE_ACCOUNT_ID] = id }
    }

    /**
     * 保存服务配置（服务协议 / 端口 / 访问账号）。
     * 与网络后台类设置（仅本机访问、通知、自启动等）分离，见 [saveBackgroundSettings]。
     * 多账户下：更新当前激活账户的协议/端口/账号字段（保留仓库绑定）。
     */
    suspend fun saveServiceConfig(
        mode: String,
        ftpPort: Int,
        webdavPort: Int,
        username: String,
        password: String,
    ) {
        context.dataStore.edit { p ->
            val accounts = readAccountsOrLegacy(p)
            val activeId = p[Keys.ACTIVE_ACCOUNT_ID]
                ?.takeIf { id -> accounts.any { it.id == id } }
            if (activeId != null) {
                // 只更新激活账户的协议/端口/账号（保留其仓库绑定与其余字段）
                val updated = accounts.map { a ->
                    if (a.id == activeId) {
                        a.copy(mode = mode, ftpPort = ftpPort, webdavPort = webdavPort, username = username, password = password)
                    } else a
                }
                p[Keys.ACCOUNTS_JSON] = writeAccountsJson(updated)
            }
            // 无激活账户时（理论不出现：先绑定仓库才有激活账户）不写，避免把账户列表误清空
        }
    }

    /**
     * 保存网络与后台类设置（仅本机访问、常驻通知、后台隐藏、自启动）。
     * 由右上角「设置」页维护；服务本身运行参数（协议/端口/账号）在 [saveServiceConfig]。
     */
    suspend fun saveBackgroundSettings(
        loopbackOnly: Boolean,
        hideNotification: Boolean,
        autoStart: Boolean,
        hideFromRecents: Boolean = false,
    ) {
        context.dataStore.edit { p ->
            p[Keys.LOOPBACK_ONLY] = loopbackOnly
            p[Keys.HIDE_NOTIFICATION] = hideNotification
            p[Keys.AUTO_START] = autoStart
            p[Keys.HIDE_FROM_RECENTS] = hideFromRecents
        }
    }

    /**
     * 记录/清除"用户期望服务运行"的持久化意图。
     * 手动点"开始服务"传 true，点"停止服务"（含通知栏停止按钮）传 false。
     * 服务被系统回收、按 START_STICKY 重建时据此决定是否自动恢复。
     */
    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.SERVICE_ENABLED] = enabled }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}