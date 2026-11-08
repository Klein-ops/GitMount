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
    /** true = 强制所有挂载点只监听 127.0.0.1（全局覆盖开关）。
     *  开启时无视各挂载点的单独开关、一律仅本机可访问；
     *  关闭时按各挂载点的单独开关（MountPointConfig.loopbackOnly）生效。 */
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
    /** 全部挂载点（动态激活架构：服务启动时为每个挂载点起空壳监听，连接认证后才激活） */
    val mountPoints: List<MountPointConfig> = emptyList(),
    /** 当前激活挂载点 id（UI 以"当前挂载点"视角编辑配置；服务端同时监听全部挂载点端口） */
    val activeMountPointId: String = "",
    /**
     * 全局共享 GitHub 令牌（Keystore 加密后落盘）：所有挂载点默认共用同一个 GitHub API 令牌。
     * 挂载点的独立令牌为空时自动继承共享令牌；需要独立的挂载点可在挂载点编辑里单独填写。
     * 绑定/新建仓库时填的令牌会自动成为共享令牌（同时写进当前挂载点）。
     */
    val sharedToken: String = "",
) {
    /** 当前激活挂载点；无挂载点或 id 失配时回退到第一个挂载点 */
    val activeMountPoint: MountPointConfig?
        get() = mountPoints.find { it.id == activeMountPointId } ?: mountPoints.firstOrNull()

    /** 当前是否有至少一个「启用且可用」的挂载点（服务启动的全局判定）。
     *  服务启停是全局操作：只要存在任意一个启用且可用的挂载点即可启动，
     *  其余未配置/被停用的挂载点由服务启动时自动跳过。 */
    val isConfigured: Boolean
        get() = mountPoints.any { it.isUsable(sharedToken) }

    // ---------- 以下为"当前挂载点"的代理 getter（保持旧代码的 settings.token/owner/... 调用不变） ----------

    /** 当前挂载点的生效 GitHub 令牌：挂载点独立令牌为空时回退到全局共享令牌 */
    val token: String get() = activeMountPoint?.effectiveToken(sharedToken) ?: ""
    val owner: String get() = activeMountPoint?.owner ?: ""
    val repoName: String get() = activeMountPoint?.repoName ?: ""
    val branch: String get() = activeMountPoint?.branch ?: "main"
    val mode: String get() = activeMountPoint?.mode ?: "WEBDAV"
    val ftpPort: Int get() = activeMountPoint?.ftpPort ?: 2121
    val webdavPort: Int get() = activeMountPoint?.webdavPort ?: 8080
    val username: String get() = activeMountPoint?.username ?: ""
    val password: String get() = activeMountPoint?.password ?: ""
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

        // 多挂载点存储：JSON 数组（挂载点字段水平冗余在每条记录里，token 逐挂载点加密）
        val MOUNT_POINTS_JSON = stringPreferencesKey("accounts_json")
        val ACTIVE_MOUNT_POINT_ID = stringPreferencesKey("active_account_id")
        // 全局共享 GitHub 令牌（所有挂载点默认共用；挂载点 token 为空即继承）
        val SHARED_TOKEN = stringPreferencesKey("shared_token_enc")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        // 多挂载点：优先读 accounts_json；为空则把旧的单挂载点配置迁移成第一个挂载点
        val mountPoints = parseMountPointsJson(p[Keys.MOUNT_POINTS_JSON])
            .takeIf { it.isNotEmpty() }
            ?: legacySingleMountPoint(p)?.takeIf { it.isConfigured }?.let { listOf(it) }
            ?: emptyList()
        val activeId = p[Keys.ACTIVE_MOUNT_POINT_ID]
            ?.takeIf { id -> mountPoints.any { it.id == id } }
            ?: mountPoints.firstOrNull()?.id
            ?: ""
        // 共享令牌：优先读已存的共享令牌；老版本迁移场景（有挂载点但无共享令牌）时，
        // 用第一个已配置挂载点的令牌作为共享令牌，保证"所有挂载点默认共享"开箱即用。
        val sharedToken = TokenCrypto.decrypt(p[Keys.SHARED_TOKEN] ?: "")
            .ifBlank { mountPoints.firstOrNull { it.token.isNotBlank() }?.token ?: "" }
        AppSettings(
            loopbackOnly = p[Keys.LOOPBACK_ONLY] ?: false,
            hideNotification = p[Keys.HIDE_NOTIFICATION] ?: true,
            hideFromRecents = p[Keys.HIDE_FROM_RECENTS] ?: false,
            autoStart = p[Keys.AUTO_START] ?: true,
            serviceEnabled = p[Keys.SERVICE_ENABLED] ?: false,
            mountPoints = mountPoints,
            activeMountPointId = activeId,
            sharedToken = sharedToken,
        )
    }

    /** 解析挂载点 JSON（[{id,name,token,owner,repoName,branch,mode,ftpPort,webdavPort,username,password}]） */
    private fun parseMountPointsJson(json: String?): List<MountPointConfig> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                MountPointConfig(
                    id = o.optString("id", "").ifBlank { return@mapNotNull null },
                    name = o.optString("name", "挂载点"),
                    token = TokenCrypto.decrypt(o.optString("token", "")),
                    owner = o.optString("owner", ""),
                    repoName = o.optString("repoName", ""),
                    branch = o.optString("branch", "main"),
                    mode = o.optString("mode", "WEBDAV"),
                    ftpPort = o.optInt("ftpPort", 2121),
                    webdavPort = o.optInt("webdavPort", 8080),
                    username = o.optString("username", ""),
                    password = o.optString("password", ""),
                    enabled = !o.has("enabled") || o.optBoolean("enabled", true),
                    loopbackOnly = o.optBoolean("loopbackOnly", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 旧版单挂载点配置 → MountPointConfig（token 解密，字段来源与旧 AppSettings 读取一致） */
    private fun legacySingleMountPoint(p: Preferences): MountPointConfig? {
        val tk = TokenCrypto.decrypt(p[Keys.TOKEN] ?: "")
        if (tk.isBlank()) return null
        val owner = p[Keys.OWNER] ?: return null
        val repo = p[Keys.REPO] ?: return null
        return MountPointConfig(
            id = "default",
            name = "默认挂载点",
            token = tk,
            owner = owner,
            repoName = repo,
            branch = p[Keys.BRANCH] ?: "main",
            mode = p[Keys.MODE] ?: "WEBDAV",
            ftpPort = p[Keys.FTP_PORT] ?: 2121,
            webdavPort = p[Keys.WEBDAV_PORT] ?: 8080,
            username = p[Keys.USERNAME] ?: "",
            password = p[Keys.PASSWORD] ?: "",
        )
    }

    /**
     * 把仓库绑定到指定挂载点（挂载点页新建/编辑时调用；不再依赖"激活挂载点"）。
     * 绑定仓库时填的令牌自动成为全局共享令牌；挂载点独立令牌置空（继承共享）——
     * 这样所有挂载点默认共用同一个 GitHub API，需要独立的挂载点再在挂载点编辑里单独填。
     * @param targetMountPointId 目标挂载点 id；为 null 时回退旧行为（绑定到激活挂载点或创建默认挂载点）
     */
    suspend fun saveRepo(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        targetMountPointId: String? = null,
    ) {
        context.dataStore.edit { p ->
            // 多挂载点：更新目标挂载点的仓库绑定（保留该挂载点其余字段）。
            // 尚无挂载点或目标挂载点不存在时（首次绑定），自动创建默认挂载点，避免绑定丢失。
            val current = readMountPointsOrLegacy(p)
            val targetId = targetMountPointId
                ?.takeIf { id -> current.any { it.id == id } }
                ?: p[Keys.ACTIVE_MOUNT_POINT_ID]
                    ?.takeIf { id -> current.any { it.id == id } }
                ?: current.firstOrNull()?.id
            val mountPoints = if (targetId != null) {
                current.map { mp ->
                    if (mp.id == targetId) {
                        mp.copy(token = "", owner = owner, repoName = repo, branch = branch)
                    } else mp
                }
            } else {
                listOf(
                    MountPointConfig(
                        id = "default",
                        name = "默认挂载点",
                        token = "",
                        owner = owner,
                        repoName = repo,
                        branch = branch,
                    )
                )
            }
            p[Keys.MOUNT_POINTS_JSON] = writeMountPointsJson(mountPoints)
            if (p[Keys.ACTIVE_MOUNT_POINT_ID].isNullOrBlank() || mountPoints.none { it.id == p[Keys.ACTIVE_MOUNT_POINT_ID] }) {
                p[Keys.ACTIVE_MOUNT_POINT_ID] = mountPoints.first().id
            }
            if (token.isNotBlank()) {
                p[Keys.SHARED_TOKEN] = TokenCrypto.encrypt(token)
            }
        }
    }

    /** 保存全局共享 GitHub 令牌（所有挂载点默认共用；挂载点 token 为空即继承） */
    suspend fun saveSharedToken(token: String) {
        context.dataStore.edit { p ->
            if (token.isBlank()) {
                p.remove(Keys.SHARED_TOKEN)
            } else {
                p[Keys.SHARED_TOKEN] = TokenCrypto.encrypt(token)
            }
        }
    }

    /** 序列化挂载点列表为 JSON（token 逐挂载点加密落盘） */
    private fun writeMountPointsJson(mountPoints: List<MountPointConfig>): String {
        val arr = org.json.JSONArray()
        mountPoints.forEach { mp ->
            arr.put(
                org.json.JSONObject()
                    .put("id", mp.id)
                    .put("name", mp.name)
                    .put("token", TokenCrypto.encrypt(mp.token))
                    .put("owner", mp.owner)
                    .put("repoName", mp.repoName)
                    .put("branch", mp.branch)
                    .put("mode", mp.mode)
                    .put("ftpPort", mp.ftpPort)
                    .put("webdavPort", mp.webdavPort)
                    .put("username", mp.username)
                    .put("password", mp.password)
                    .put("enabled", mp.enabled)
                    .put("loopbackOnly", mp.loopbackOnly)
            )
        }
        return arr.toString()
    }

    /** 读取挂载点列表；无 accounts_json 时尝试旧单挂载点迁移（返回挂载点列表，不落盘） */
    private fun readMountPointsOrLegacy(p: Preferences): List<MountPointConfig> =
        parseMountPointsJson(p[Keys.MOUNT_POINTS_JSON])
            .takeIf { it.isNotEmpty() }
            ?: legacySingleMountPoint(p)?.let { listOf(it) }
            ?: emptyList()

    /** 保存单个挂载点（新增或整体替换；同时维护激活挂载点 id） */
    suspend fun saveMountPoint(mountPoint: MountPointConfig, activate: Boolean = true) {
        context.dataStore.edit { p ->
            val mountPoints = readMountPointsOrLegacy(p)
            val idx = mountPoints.indexOfFirst { it.id == mountPoint.id }
            val updated = if (idx >= 0) mountPoints.toMutableList().apply { set(idx, mountPoint) } else mountPoints + mountPoint
            p[Keys.MOUNT_POINTS_JSON] = writeMountPointsJson(updated)
            if (activate || p[Keys.ACTIVE_MOUNT_POINT_ID].isNullOrBlank()) {
                p[Keys.ACTIVE_MOUNT_POINT_ID] = mountPoint.id
            }
        }
    }

    /** 删除挂载点；若删的是激活挂载点，激活 id 回退到剩余第一个 */
    suspend fun deleteMountPoint(id: String) {
        context.dataStore.edit { p ->
            val mountPoints = readMountPointsOrLegacy(p).filterNot { it.id == id }
            p[Keys.MOUNT_POINTS_JSON] = writeMountPointsJson(mountPoints)
            if (p[Keys.ACTIVE_MOUNT_POINT_ID] == id) {
                p[Keys.ACTIVE_MOUNT_POINT_ID] = mountPoints.firstOrNull()?.id ?: ""
            }
        }
    }

    /**
     * 保存服务配置（服务协议 / 端口 / 访问账号）。
     * 与网络后台类设置（仅本机访问、通知、自启动等）分离，见 [saveBackgroundSettings]。
     * 多挂载点下：更新当前激活挂载点的协议/端口/账号字段（保留仓库绑定）。
     */
    suspend fun saveServiceConfig(
        mode: String,
        ftpPort: Int,
        webdavPort: Int,
        username: String,
        password: String,
    ) {
        context.dataStore.edit { p ->
            val mountPoints = readMountPointsOrLegacy(p)
            val activeId = p[Keys.ACTIVE_MOUNT_POINT_ID]
                ?.takeIf { id -> mountPoints.any { it.id == id } }
            if (activeId != null) {
                // 只更新激活挂载点的协议/端口/账号（保留其仓库绑定与其余字段）
                val updated = mountPoints.map { mp ->
                    if (mp.id == activeId) {
                        mp.copy(mode = mode, ftpPort = ftpPort, webdavPort = webdavPort, username = username, password = password)
                    } else mp
                }
                p[Keys.MOUNT_POINTS_JSON] = writeMountPointsJson(updated)
            }
            // 无激活挂载点时（理论不出现：先绑定仓库才有激活挂载点）不写，避免把挂载点列表误清空
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