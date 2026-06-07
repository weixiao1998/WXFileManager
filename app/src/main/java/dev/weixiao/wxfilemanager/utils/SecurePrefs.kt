package dev.weixiao.wxfilemanager.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 加密的 SharedPreferences 封装。
 *
 * 主要用于保存 SMB 密码等敏感信息。当 EncryptedSharedPreferences 初始化失败
 * （例如 Keystore 异常），会降级为普通 SharedPreferences 以保证可用性。
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val FILE_NAME = "smb_servers_secure"

    @Volatile
    private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    private fun create(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences 初始化失败，降级为普通 SharedPreferences", e)
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        }
    }
}
