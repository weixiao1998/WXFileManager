@file:JvmName("FfmpegThumbnailExtractor")
package dev.weixiao.wxfilemanager.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import wseemann.media.FFmpegMediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

/**
 * 使用 FFmpegMediaMetadataRetriever (内置 FFmpeg) 提取视频缩略图。
 * 支持 Hi10P / 10-bit 等 Android 原生解码器不支持的格式。
 *
 * 本地文件：直接传入路径
 * SMB 文件：复制前 5MB 到临时文件后传入
 */

private const val TAG = "FfmpegThumbExtractor"
private const val SMB_HEADER_SIZE = 5 * 1024 * 1024L
private val mutex = Mutex()

suspend fun extract(
    context: Context, uri: Uri, width: Int, height: Int,
    timeoutMs: Long = 15_000L, @Suppress("UNUSED_PARAMETER") seekMs: Long = 0L
): Bitmap? = mutex.withLock {
    withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) {
            try { extractImpl(context, uri, width, height) }
            catch (e: Exception) { Log.w(TAG, "ext fail", e); null }
        }
    }
}

private fun extractImpl(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
    val localPath: String?
    var tmpFile: File? = null

    try {
        localPath = when (uri.scheme) {
            "file" -> uri.path
            "smb" -> {
                tmpFile = copySmbHeader(context, uri) ?: return null
                tmpFile!!.absolutePath
            }
            else -> return null
        }

        val retriever = FFmpegMediaMetadataRetriever()
        try {
            retriever.setDataSource(localPath)
            val outW = width.coerceIn(160, 720)
            val outH = height.coerceIn(160, 720)

            var bmp = retriever.getScaledFrameAtTime(
                1_000_000,
                FFmpegMediaMetadataRetriever.OPTION_CLOSEST,
                outW, outH
            )
            if (bmp == null) {
                bmp = retriever.getFrameAtTime(
                    1_000_000,
                    FFmpegMediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bmp != null) {
                    bmp = Bitmap.createScaledBitmap(bmp, outW, outH, true)
                }
            }
            return bmp
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    } finally {
        tmpFile?.delete()
    }
}

private fun copySmbHeader(context: Context, uri: Uri): File? {
    val tmp = File(context.cacheDir, "ffmpeg_thumb_${System.nanoTime()}.tmp")
    return try {
        val uriObj = Uri.parse(uri.toString())
        val shareName = uriObj.pathSegments.getOrNull(0) ?: return null
        val filePath = uriObj.pathSegments.drop(1).joinToString("/").let { Uri.decode(it) }

        val smb = SmbManager
        if (!smb.isConnected()) { Log.w(TAG, "SMB not connected"); return null }
        runBlocking { smb.connectShare(shareName) }

        val f = smb.openFile(filePath) ?: return null
        return try {
            val fileSize = f.getFileInformation().standardInformation.endOfFile
            val copySize = minOf(SMB_HEADER_SIZE, fileSize)
            val buf = ByteArray(copySize.toInt())
            var totalRead = 0
            while (totalRead < copySize) {
                val n = f.read(buf, totalRead.toLong(), totalRead, (copySize - totalRead).toInt())
                if (n <= 0) break
                totalRead += n
            }
            if (totalRead > 0) {
                FileOutputStream(tmp).use { it.write(buf, 0, totalRead) }
                Log.d(TAG, "SMB header copied: $totalRead bytes")
                tmp
            } else {
                tmp.delete()
                null
            }
        } finally {
            f.close()
        }
    } catch (e: Exception) {
        Log.w(TAG, "SMB header copy fail", e)
        tmp.delete()
        null
    }
}
