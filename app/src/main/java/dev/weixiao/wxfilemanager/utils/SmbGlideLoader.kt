package dev.weixiao.wxfilemanager.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.Log
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import dev.weixiao.wxfilemanager.model.FileModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class SmbModelLoader(private val context: Context) : ModelLoader<FileModel, InputStream> {

    override fun buildLoadData(
        model: FileModel,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream>? {
        val cacheKey = "${model.path}_${model.size}"
        return ModelLoader.LoadData(ObjectKey(cacheKey), SmbDataFetcher(context, model, width, height))
    }

    override fun handles(model: FileModel): Boolean = model.isSmb

    class SmbDataFetcher(
        private val context: Context,
        private val model: FileModel,
        private val width: Int,
        private val height: Int
    ) : DataFetcher<InputStream> {

        // 用 SupervisorJob 包装的独立 scope，便于在 cancel() 中取消任务
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var loadJob: Job? = null
        private var inputStream: InputStream? = null
        @Volatile
        private var smbFileRef: com.hierynomus.smbj.share.File? = null

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            loadJob = scope.launch {
                try {
                    // If size is 0, try to get real size for better thumbnail generation
                    var currentModel = model
                    if (currentModel.size <= 0 && currentModel.isSmb) {
                        val share = SmbManager.getDiskShare()
                        if (share != null) {
                            try {
                                val info = share.getFileInformation(currentModel.path)
                                currentModel = currentModel.copy(
                                    size = info.standardInformation.endOfFile,
                                    lastModified = info.basicInformation.changeTime.toEpochMillis()
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to refresh file info for: ${currentModel.path}")
                            }
                        }
                    }

                    if (currentModel.isVideo) {
                        // 先尝试 MMR（通过 MediaDataSource 随机访问完整文件，不受截断影响）
                        var bitmap: Bitmap? = getVideoThumbnail(currentModel, width, height)
                        if (bitmap == null) {
                            // FFmpeg 回退（支持 Hi10P / HEVC 10bit 等 MMR 不支持的格式）
                            bitmap = getVideoThumbnailByFfmpeg(currentModel, width, height)
                        }
                        if (bitmap != null) {
                            val data = ByteArrayOutputStream().use { bos ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                                bos.toByteArray()
                            }
                            val bis = ByteArrayInputStream(data)
                            inputStream = bis
                            callback.onDataReady(bis)
                        } else {
                            callback.onLoadFailed(Exception("Failed to get video thumbnail"))
                        }
                    } else {
                        val stream = SmbManager.getInputStream(currentModel.path)
                        if (stream != null) {
                            val bufferedStream = java.io.BufferedInputStream(stream, 64 * 1024)
                            inputStream = bufferedStream
                            callback.onDataReady(bufferedStream)
                        } else {
                            callback.onLoadFailed(Exception("Failed to open SMB stream for: ${currentModel.path}"))
                        }
                    }
                } catch (ce: CancellationException) {
                    // 任务被 Glide 取消，直接返回，cleanup() 会负责释放资源
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading data for: ${model.path}", e)
                    callback.onLoadFailed(e)
                }
            }
        }

        private fun getVideoThumbnailByFfmpeg(model: FileModel, requestedWidth: Int, requestedHeight: Int): Bitmap? {
            val uri = if (model.isSmb) {
                SmbManager.buildVlcSmbUri(model.path) ?: run {
                    Log.w(TAG, "Cannot build VLC SMB uri: ${model.path}")
                    return null
                }
            } else {
                android.net.Uri.parse("file://${model.path}")
            }
            val scaleFactor = 2
            val w = (requestedWidth * scaleFactor).coerceAtLeast(320)
            val h = (requestedHeight * scaleFactor).coerceAtLeast(320)
            return try {
                runBlocking {
                    extract(context, uri, w, h)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.w(TAG, "VLC thumbnail failed: ${model.path}", e)
                null
            }
        }

        private fun getVideoThumbnail(model: FileModel, requestedWidth: Int, requestedHeight: Int): Bitmap? {
            val path = model.path
            // Scale up the requested size to support high-density screens
            val scaleFactor = 2
            val width = (requestedWidth * scaleFactor).coerceAtLeast(300)
            val height = (requestedHeight * scaleFactor).coerceAtLeast(300)

            val retriever = MediaMetadataRetriever()
            val smbFile = SmbManager.openFile(path) ?: run {
                Log.e(TAG, "Failed to open SMB file for: $path")
                return null
            }
            smbFileRef = smbFile

            return try {
                retriever.setDataSource(object : android.media.MediaDataSource() {
                    private val buffer = ByteArray(128 * 1024)
                    private var bufferPos = -1L
                    private var bufferSize = 0

                    @Synchronized
                    override fun readAt(position: Long, dest: ByteArray, offset: Int, size: Int): Int {
                        if (position >= model.size) return -1
                        if (size == 0) return 0

                        if (position >= bufferPos && position + size <= bufferPos + bufferSize && bufferPos != -1L) {
                            System.arraycopy(buffer, (position - bufferPos).toInt(), dest, offset, size)
                            return size
                        }

                        return try {
                            if (size >= buffer.size) {
                                readFromSmb(position, dest, offset, size)
                            } else {
                                bufferPos = position
                                bufferSize = readFromSmb(position, buffer, 0, buffer.size)

                                if (bufferSize <= 0) {
                                    bufferPos = -1L
                                    -1
                                } else {
                                    val toCopy = Math.min(size, bufferSize)
                                    System.arraycopy(buffer, 0, dest, offset, toCopy)
                                    toCopy
                                }
                            }
                        } catch (e: Exception) {
                            -1
                        }
                    }

                    private fun readFromSmb(pos: Long, buf: ByteArray, off: Int, sz: Int): Int {
                        var totalRead = 0
                        var curPos = pos
                        var curOff = off
                        var remaining = sz

                        if (curPos + remaining > model.size) {
                            remaining = (model.size - curPos).toInt()
                        }
                        if (remaining <= 0) return -1

                        while (remaining > 0) {
                            val read = smbFile.read(buf, curPos, curOff, remaining)
                            if (read <= 0) break
                            totalRead += read
                            curPos += read
                            curOff += read
                            remaining -= read
                        }
                        return if (totalRead == 0) -1 else totalRead
                    }

                    @Synchronized
                    override fun getSize(): Long = model.size

                    override fun close() {}
                })

                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull() ?: 0L

                val isAvi = path.lowercase().endsWith(".avi")
                val timePoints = mutableListOf(1000000L, 5000000L, 0L)
                if (durationMs > 20000) {
                    timePoints.add(durationMs * 100L)
                }

                var bestBitmap: Bitmap? = null
                var bestScore = -1.0

                for (timeUs in timePoints) {
                    try {
                        val seekOption = if (isAvi && timeUs == 1000000L) {
                            MediaMetadataRetriever.OPTION_CLOSEST
                        } else {
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        }

                        val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(timeUs, seekOption, width, height)
                        } else {
                            retriever.getFrameAtTime(timeUs, seekOption)
                        }

                        if (bitmap != null) {
                            val score = calculateFrameQualityScore(bitmap)

                            if (score > 0.8) {
                                bestBitmap = bitmap
                                break
                            } else if (score > bestScore) {
                                bestScore = score
                                bestBitmap = bitmap
                            }
                        }
                    } catch (e: Exception) {
                        // ignore single frame failure
                    }
                }

                if (bestScore < 0.3 && bestBitmap == null) {
                    try {
                        bestBitmap = retriever.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    } catch (e: Exception) {}
                }

                bestBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Error setting data source or getting frame", e)
                null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {}
                try {
                    smbFile.close()
                } catch (e: Exception) {}
                smbFileRef = null
            }
        }

        /**
         * Calculates a quality score for a frame.
         * Returns 0.0 to 1.0, where 1.0 is best.
         * Penalizes all-black, all-white, and any solid-color frames.
         */
        private fun calculateFrameQualityScore(bitmap: Bitmap): Double {
            val width = bitmap.width
            val height = bitmap.height
            var totalBrightness = 0.0
            val sampleCount = 10
            val pixelBrightnessList = mutableListOf<Double>()

            for (i in 0 until sampleCount) {
                for (j in 0 until sampleCount) {
                    val x = i * (width / sampleCount)
                    val y = j * (height / sampleCount)
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val brightness = (0.299 * r + 0.587 * g + 0.114 * b)
                    totalBrightness += brightness
                    pixelBrightnessList.add(brightness)
                }
            }
            val avgBrightness = totalBrightness / (sampleCount * sampleCount)

            var variance = 0.0
            for (brightness in pixelBrightnessList) {
                variance += Math.pow(brightness - avgBrightness, 2.0)
            }
            val stdDev = Math.sqrt(variance / pixelBrightnessList.size)

            val brightnessScore = 1.0 - (Math.abs(avgBrightness - 128.0) / 128.0)
            val varianceScore = (stdDev / 20.0).coerceAtMost(1.0)
            val combinedScore = (brightnessScore * 0.4 + varianceScore * 0.6)

            return when {
                avgBrightness < 20.0 -> combinedScore * 0.1
                avgBrightness > 235.0 -> combinedScore * 0.1
                stdDev < 5.0 -> combinedScore * 0.05
                else -> combinedScore
            }
        }

        override fun cleanup() {
            try {
                inputStream?.close()
            } catch (e: Exception) {}
            inputStream = null
            try {
                smbFileRef?.close()
            } catch (e: Exception) {}
            smbFileRef = null
        }

        override fun cancel() {
            // 主动取消正在进行的加载任务并关闭底层资源，避免 Glide 滑动取消时仍持续拉流
            loadJob?.cancel()
            loadJob = null
            scope.cancel()
            try {
                smbFileRef?.close()
            } catch (e: Exception) {}
            smbFileRef = null
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.REMOTE

        companion object {
            private const val TAG = "SmbGlideLoader"
        }
    }

    class Factory(private val context: Context) : ModelLoaderFactory<FileModel, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<FileModel, InputStream> {
            return SmbModelLoader(context)
        }

        override fun teardown() {}
    }
}
