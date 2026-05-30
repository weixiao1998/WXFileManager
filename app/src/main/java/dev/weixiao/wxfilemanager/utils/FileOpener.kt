package dev.weixiao.wxfilemanager.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.weixiao.wxfilemanager.model.FileModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileOpener {

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

    suspend fun downloadSmbFileToCache(context: Context, file: FileModel): File? =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = SmbManager.getInputStream(file.path) ?: return@withContext null
                val cacheDir = File(context.cacheDir, "smb_open_cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val cachedFile = File(cacheDir, file.name)
                cachedFile.outputStream().use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                cachedFile
            } catch (e: Exception) {
                e.printStackTrace()
                null
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
