package dev.weixiao.wxfilemanager.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.weixiao.wxfilemanager.model.FileModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileOpener {

    private const val TAG = "FileOpener"

    /** 下载到缓存时希望保留的最小可用空间，避免把应用 cacheDir 挤爆 */
    private const val MIN_FREE_SPACE_BYTES = 50L * 1024 * 1024

    /**
     * SMB 文件下载结果。区分"成功 / 空间不足 / 其它失败"，便于上层给出针对性提示。
     */
    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class InsufficientSpace(val required: Long, val available: Long) : DownloadResult()
        data class Failure(val cause: Throwable?) : DownloadResult()
    }

    fun openWithExternalApp(context: Context, file: FileModel) {
        val uri = getLocalFileUri(context, file) ?: run {
            return
        }
        val mimeType = getFileMimeType(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "选择打开方式").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                context,
                "没有找到能打开此文件的应用",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun openCachedFileWithExternalApp(context: Context, cachedFile: File, mimeType: String?) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, cachedFile)
        val resolvedType = mimeType ?: getMimeTypeFromExtension(cachedFile.name)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "选择打开方式").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(
                context,
                "没有找到能打开此文件的应用",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 兼容旧调用方：仍返回 [File]?；空间不足或其它失败统一返回 null。
     * 推荐新代码使用 [downloadSmbFile] 以拿到详细原因。
     */
    suspend fun downloadSmbFileToCache(context: Context, file: FileModel): File? {
        return when (val result = downloadSmbFile(context, file)) {
            is DownloadResult.Success -> result.file
            else -> null
        }
    }

    suspend fun downloadSmbFile(context: Context, file: FileModel): DownloadResult =
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "smb_open_cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                // 文件大小已知时（>0），先做空间预检；为 0 时无法预判，跳过。
                if (file.size > 0) {
                    val available = cacheDir.usableSpace
                    val required = file.size + MIN_FREE_SPACE_BYTES
                    if (available < required) {
                        Log.w(TAG, "Insufficient cache space: need $required, have $available")
                        return@withContext DownloadResult.InsufficientSpace(file.size, available)
                    }
                }

                val inputStream = SmbManager.getInputStream(file.path)
                    ?: return@withContext DownloadResult.Failure(null)

                val cachedFile = File(cacheDir, file.name)
                cachedFile.outputStream().use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                DownloadResult.Success(cachedFile)
            } catch (e: Exception) {
                Log.w(TAG, "downloadSmbFile failed for ${file.path}", e)
                DownloadResult.Failure(e)
            }
        }

    private fun getLocalFileUri(context: Context, file: FileModel): Uri? {
        return if (SafManager.isRestrictedPath(file.path)) {
            SafManager.getFileUri(context, file.path)
        } else {
            val javaFile = File(file.path)
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, javaFile)
        }
    }

    private fun getFileMimeType(file: FileModel): String {
        file.mimeType?.let { return it }
        return getMimeTypeFromExtension(file.name)
    }

    private fun getMimeTypeFromExtension(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}
