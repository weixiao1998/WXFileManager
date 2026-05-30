package dev.weixiao.wxfilemanager.utils

import android.content.Context
import dev.weixiao.wxfilemanager.model.FileModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.universalchardet.UniversalDetector
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

object TextFileLoader {

    const val MEMORY_LIMIT_BYTES: Long = 10L * 1024 * 1024
    const val CACHE_LIMIT_BYTES: Long = 100L * 1024 * 1024

    sealed class LoadResult {
        data class Success(
            val content: String,
            val charset: Charset,
            val autoDetected: Boolean
        ) : LoadResult()

        data class TooLarge(val size: Long) : LoadResult()
        data class Error(val throwable: Throwable) : LoadResult()
    }

    suspend fun load(
        context: Context,
        file: FileModel,
        charsetOverride: Charset? = null
    ): LoadResult = withContext(Dispatchers.IO) {
        try {
            if (file.size > CACHE_LIMIT_BYTES) {
                return@withContext LoadResult.TooLarge(file.size)
            }

            val bytes: ByteArray = if (file.isSmb) {
                loadFromSmb(context, file)
            } else {
                java.io.File(file.path).readBytes()
            }

            val charset = charsetOverride ?: detectCharset(bytes)
            val content = String(bytes, charset)
            LoadResult.Success(content, charset, autoDetected = charsetOverride == null)
        } catch (t: Throwable) {
            LoadResult.Error(t)
        }
    }

    /**
     * 清理本工具产生的所有临时缓存文件。
     */
    fun cleanupCache(context: Context) {
        try {
            context.cacheDir.listFiles { f ->
                f.name.startsWith(CACHE_PREFIX) && f.name.endsWith(CACHE_SUFFIX)
            }?.forEach { it.delete() }
        } catch (_: Throwable) {
            // 忽略清理失败
        }
    }

    private fun detectCharset(bytes: ByteArray): Charset {
        if (bytes.isEmpty()) return Charsets.UTF_8
        val detector = UniversalDetector(null)
        val sampleLen = minOf(bytes.size, 8192)
        detector.handleData(bytes, 0, sampleLen)
        detector.dataEnd()
        val detected = detector.detectedCharset
        detector.reset()
        return if (detected.isNullOrEmpty()) {
            Charsets.UTF_8
        } else {
            runCatching { Charset.forName(detected) }.getOrDefault(Charsets.UTF_8)
        }
    }

    private suspend fun loadFromSmb(context: Context, file: FileModel): ByteArray {
        val input: InputStream = SmbManager.getInputStream(file.path)
            ?: throw IOException("Failed to open SMB stream: ${file.path}")
        return if (file.size <= MEMORY_LIMIT_BYTES) {
            input.use { it.readBytes() }
        } else {
            val cacheFile = java.io.File(
                context.cacheDir,
                "$CACHE_PREFIX${file.name.hashCode()}_${System.currentTimeMillis()}$CACHE_SUFFIX"
            )
            try {
                input.use { ins ->
                    cacheFile.outputStream().use { out -> ins.copyTo(out) }
                }
                cacheFile.readBytes()
            } finally {
                cacheFile.delete()
            }
        }
    }

    private const val CACHE_PREFIX = "text_viewer_"
    private const val CACHE_SUFFIX = ".tmp"
}
