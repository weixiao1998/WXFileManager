package dev.weixiao.wxfilemanager

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import wseemann.media.FFmpegMediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream

/**
 * 在独立进程 (:ffmpeg_thumb) 中运行 FFmpeg 缩略图提取。
 * 当 FFmpeg 原生层发生 SIGABRT 时，仅本进程崩溃，主进程不受影响。
 */
class FfmpegThumbProvider : ContentProvider() {

    companion object {
        private const val TAG = "FfmpegThumbProvider"
        const val AUTHORITY = "dev.weixiao.wxfilemanager.ffmpeg_thumb"
        const val METHOD_EXTRACT = "extract"
        private val ffmpegLock = Any()
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_EXTRACT || arg == null) return null
        val inputPath = arg
        val width = extras?.getInt("width", 320) ?: 320
        val height = extras?.getInt("height", 320) ?: 320
        val ctx = context ?: return null

        // 串行化 FFmpeg 调用，避免原生库并发问题
        synchronized(ffmpegLock) {
            return extractThumbnail(ctx, inputPath, width, height)
        }
    }

    private fun extractThumbnail(ctx: android.content.Context, inputPath: String, width: Int, height: Int): Bundle {
        val result = Bundle()
        val retriever = FFmpegMediaMetadataRetriever()
        try {
            retriever.setDataSource(inputPath)
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

            if (bmp != null) {
                val outputFile = File(ctx.cacheDir, "ffmpeg_thumb_out_${System.nanoTime()}.jpg")
                FileOutputStream(outputFile).use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                }
                result.putString("output", outputFile.absolutePath)
                result.putBoolean("success", true)
            } else {
                result.putBoolean("success", false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "extract failed", e)
            result.putBoolean("success", false)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return result
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
