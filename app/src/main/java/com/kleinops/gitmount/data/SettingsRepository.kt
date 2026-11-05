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
    val token: String = "",
    val owner: String = "",
    val repoName: String = "",
    val branch: String = "main",
    val mode: String = "WEBDAV",
    val ftpPort: Int = 2121,
    val webdavPort: Int = 8080,
    val username: String = "gitmount",
    val password: String = "",
    /** true = 服务只监听 127.0.0.1，仅本机可访问，不暴露局域网 */
    val loopbackOnly: Boolean = false,
    /** true = 隐藏前台服务常驻通知（改用静默渠道）。默认 true：常驻通知默认关闭 */
    val hideNotification: Boolean = true,
    /** true = 从最近任务列表隐藏本应用（后台运行时在任务切换界面不可见） */
    val hideFromRecents: Boolean = false,
    /** true = 打开 App 时自动启动服务（需已配置仓库） */
    val autoStart: Boolean = true,
    /**
     * 用户期望服务运行（持久化的运行意图）：
     * 手动点"开始服务"写 true，点"停止服务"（含通知栏停止按钮）写 false。
     * 服务进程被系统回收、按 START_STICKY 重建时，据此决定是否自动恢复——
     * true 则按最新设置拉起，false 则保持停止，避免"用户没开/已停却自己跑起来"。
     */
    val serviceEnabled: Boolean = false,
) {
    val isConfigured: Boolean get() = token.isNotBlank() && owner.isNotBlank() && repoName.isNotBlank()
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
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            token = TokenCrypto.decrypt(p[Keys.TOKEN] ?: ""),
            owner = p[Keys.OWNER] ?: "",
            repoName = p[Keys.REPO] ?: "",
            branch = p[Keys.BRANCH] ?: "main",
            mode = p[Keys.MODE] ?: "WEBDAV",
            ftpPort = p[Keys.FTP_PORT] ?: 2121,
            webdavPort = p[Keys.WEBDAV_PORT] ?: 8080,
            username = p[Keys.USERNAME] ?: "gitmount",
            password = p[Keys.PASSWORD] ?: "",
            loopbackOnly = p[Keys.LOOPBACK_ONLY] ?: false,
            hideNotification = p[Keys.HIDE_NOTIFICATION] ?: true,
            hideFromRecents = p[Keys.HIDE_FROM_RECENTS] ?: false,
            autoStart = p[Keys.AUTO_START] ?: true,
            serviceEnabled = p[Keys.SERVICE_ENABLED] ?: false,
        )
    }

    suspend fun saveRepo(token: String, owner: String, repo: String, branch: String) {
        context.dataStore.edit { p ->
            p[Keys.TOKEN] = TokenCrypto.encrypt(token)
            p[Keys.OWNER] = owner
            p[Keys.REPO] = repo
            p[Keys.BRANCH] = branch
        }
    }

    suspend fun saveServer(
        mode: String,
        ftpPort: Int,
        webdavPort: Int,
        username: String,
        password: String,
        loopbackOnly: Boolean,
        hideNotification: Boolean,
        autoStart: Boolean,
        hideFromRecents: Boolean = false,
    ) {
        context.dataStore.edit { p ->
            p[Keys.MODE] = mode
            p[Keys.FTP_PORT] = ftpPort
            p[Keys.WEBDAV_PORT] = webdavPort
            p[Keys.USERNAME] = username
            p[Keys.PASSWORD] = password
            p[Keys.LOOPBACK_ONLY] = loopbackOnly
            p[Keys.HIDE_NOTIFICATION] = hideNotification
            p[Keys.HIDE_FROM_RECENTS] = hideFromRecents
            p[Keys.AUTO_START] = autoStart
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