package dev.weixiao.wxfilemanager.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.nio.ByteBuffer

/**
 * 使用 libVLC 抓取一帧作为视频缩略图，用于 [MediaMetadataRetriever] 无法解码的格式
 * （如 AVI/DivX、Hi10P/HEVC 10bit、部分 WMV 等）。
 *
 * 实现思路：
 * - 创建 [ImageReader] 提供一个软件 Surface；
 * - 通过 [MediaPlayer.getVLCVout] 将该 Surface 交给 VLC 作为视频输出；
 * - 播放后 seek 到目标位置，在 [ImageReader.OnImageAvailableListener] 中读出一帧。
 *
 * 由于每次任务都要新建 [MediaPlayer]，成本较高，此处全局串行执行。
 */
object VlcThumbnailExtractor {

    private const val TAG = "VlcThumbnailExtractor"

    /** 全局互斥，避免并发起多个 VLC 实例导致资源紧张。 */
    private val mutex = Mutex()

    @Volatile
    private var sharedLibVLC: LibVLC? = null

    private fun getLibVLC(context: Context): LibVLC {
        val existing = sharedLibVLC
        if (existing != null && !existing.isReleased) return existing
        return synchronized(this) {
            val current = sharedLibVLC
            if (current != null && !current.isReleased) return@synchronized current
            val args = arrayListOf(
                "--no-audio",
                "--no-spu",
                "--no-osd",
                "--no-stats",
                "--no-snapshot-preview",
                "--avcodec-hw=none",
                "--input-fast-seek",
                "--network-caching=1500",
                "--verbose=0"
            )
            LibVLC(context.applicationContext, args).also { sharedLibVLC = it }
        }
    }

    /**
     * 提取缩略图。返回 null 表示失败或超时。
     * @param uri 视频 URI，支持 file:// 与 smb:// （VLC 自带 SMB 模块）。
     * @param seekMs 播放开始后跳转到的位置（毫秒），默认 3s。
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        timeoutMs: Long = 15_000L,
        seekMs: Long = 3_000L
    ): Bitmap? = mutex.withLock {
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                try {
                    doExtract(context, uri, width, height, seekMs)
                } catch (e: Exception) {
                    Log.w(TAG, "extract failed: $uri", e)
                    null
                }
            }
        }
    }

    private suspend fun doExtract(
        context: Context,
        uri: Uri,
        width: Int,
        height: Int,
        seekMs: Long
    ): Bitmap? {
        val libVLC = getLibVLC(context)
        val outW = width.coerceIn(160, 720)
        val outH = height.coerceIn(160, 720)

        val handlerThread = HandlerThread("VlcThumbReader").apply { start() }
        val handler = Handler(handlerThread.looper)
        val imageReader = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2)
        val frameSignal = CompletableDeferred<Bitmap?>()

        imageReader.setOnImageAvailableListener({ reader ->
            if (frameSignal.isCompleted) {
                // 已经拿到过一帧，后续帧丢弃即可。
                try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
                return@setOnImageAvailableListener
            }
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer: ByteBuffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val imgW = image.width
                val imgH = image.height
                val rowPadding = rowStride - pixelStride * imgW
                val bmpWidth = if (pixelStride > 0) imgW + rowPadding / pixelStride else imgW
                val bitmap = Bitmap.createBitmap(bmpWidth, imgH, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val result = if (bmpWidth == imgW) {
                    bitmap
                } else {
                    val trimmed = Bitmap.createBitmap(bitmap, 0, 0, imgW, imgH)
                    if (trimmed !== bitmap) bitmap.recycle()
                    trimmed
                }
                frameSignal.complete(result)
            } catch (e: Exception) {
                Log.w(TAG, "read image failed", e)
                frameSignal.complete(null)
            } finally {
                try { image.close() } catch (_: Exception) {}
            }
        }, handler)

        val mediaPlayer = MediaPlayer(libVLC)
        val media = Media(libVLC, uri).apply {
            addOption(":no-audio")
            addOption(":avcodec-hw=none")
            addOption(":input-fast-seek")
        }
        mediaPlayer.media = media
        media.release()

        val vout = mediaPlayer.vlcVout
        vout.setVideoSurface(imageReader.surface, null)
        vout.setWindowSize(outW, outH)
        vout.attachViews()

        var seeked = false
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    if (!seeked && seekMs > 0) {
                        seeked = true
                        try { mediaPlayer.setTime(seekMs) } catch (_: Exception) {}
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    if (!frameSignal.isCompleted) frameSignal.complete(null)
                }
                MediaPlayer.Event.EndReached -> {
                    if (!frameSignal.isCompleted) frameSignal.complete(null)
                }
                else -> {}
            }
        }

        return try {
            mediaPlayer.play()
            frameSignal.await()
        } finally {
            try { mediaPlayer.stop() } catch (_: Exception) {}
            try { vout.detachViews() } catch (_: Exception) {}
            try { mediaPlayer.release() } catch (_: Exception) {}
            try { imageReader.close() } catch (_: Exception) {}
            handlerThread.quitSafely()
        }
    }
}
