package dev.weixiao.wxfilemanager

import android.app.Application
import android.util.Log
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dev.weixiao.wxfilemanager.model.FileModel
import dev.weixiao.wxfilemanager.utils.SmbModelLoader
import java.io.File
import java.io.InputStream

class WXApplication : Application() {

    /**
     * 进程级协程作用域，仅用于无法绑定 UI 生命周期的后台任务（如启动期缓存清理）。
     * 使用 SupervisorJob 防止单个任务失败影响其它任务。
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        ensureCachePrivacy()
        autoClearCache()

        // Manually register SMB loader for Glide to bypass annotation processor issues in AGP 9.1
        Glide.get(this).registry.append(
            FileModel::class.java,
            InputStream::class.java,
            SmbModelLoader.Factory()
        )
    }

    /**
     * Periodically clear cache if it exceeds a threshold (e.g., 500MB)
     */
    private fun autoClearCache() {
        val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        val lastClearTime = prefs.getLong("last_cache_clear", 0L)
        val currentTime = System.currentTimeMillis()

        // Clear every 7 days or if manually requested (not implemented yet)
        if (currentTime - lastClearTime > 7 * 24 * 60 * 60 * 1000L) {
            applicationScope.launch {
                try {
                    val cacheDir = cacheDir
                    val cacheSize = getDirSize(cacheDir)
                    if (cacheSize > 500 * 1024 * 1024) { // 500MB
                        Glide.get(this@WXApplication).clearDiskCache()
                        prefs.edit().putLong("last_cache_clear", currentTime).apply()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "auto clear cache failed", e)
                }
            }
        }
    }

    private fun getDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getDirSize(file) else file.length()
        }
        return size
    }

    /**
     * Ensures that cached thumbnails are not scanned by system gallery.
     */
    private fun ensureCachePrivacy() {
        try {
            // Glide default disk cache is in internal cacheDir, which is private.
            // But we add .nomedia to the entire cache directory as a double safety measure.
            val nomedia = File(cacheDir, ".nomedia")
            if (!nomedia.exists()) {
                nomedia.createNewFile()
            }

            // Also check external cache dir if it exists
            externalCacheDir?.let {
                val extNomedia = File(it, ".nomedia")
                if (!extNomedia.exists()) {
                    extNomedia.createNewFile()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensure cache privacy failed", e)
        }
    }

    companion object {
        private const val TAG = "WXApplication"
    }
}
