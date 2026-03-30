package top.weixiaoweb.wxfilemanager.utils

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
import kotlinx.coroutines.runBlocking
import top.weixiaoweb.wxfilemanager.model.FileModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class SmbModelLoader : ModelLoader<FileModel, InputStream> {

    override fun buildLoadData(
        model: FileModel,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream>? {
        val nameLower = model.name.lowercase()
        if (nameLower.endsWith(".avi") || nameLower.endsWith(".wmv")) {
            return null
        }
        val cacheKey = "${model.path}_${model.size}"
        return ModelLoader.LoadData(ObjectKey(cacheKey), SmbDataFetcher(model, width, height))
    }

    override fun handles(model: FileModel): Boolean = model.isSmb

    class SmbDataFetcher(
        private val model: FileModel,
        private val width: Int,
        private val height: Int
    ) : DataFetcher<InputStream> {
        private var inputStream: InputStream? = null

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                // If size is 0, try to get real size for better thumbnail generation
                var currentModel = model
                if (currentModel.size <= 0 && currentModel.isSmb) {
                    runBlocking {
                        val share = SmbManager.getDiskShare()
                        if (share != null) {
                            try {
                                val info = share.getFileInformation(currentModel.path)
                                currentModel = currentModel.copy(
                                    size = info.standardInformation.endOfFile,
                                    lastModified = info.basicInformation.changeTime.toEpochMillis()
                                )
                                Log.d("SmbGlideLoader", "Refreshed file info for: ${currentModel.path}, size: ${currentModel.size}")
                            } catch (e: Exception) {
                                Log.w("SmbGlideLoader", "Failed to refresh file info for: ${currentModel.path}")
                            }
                        }
                    }
                }

                if (currentModel.isVideo) {
                    val bitmap = runBlocking {
                        getVideoThumbnail(currentModel, width, height)
                    }
                    if (bitmap != null) {
                        val bos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                        val data = bos.toByteArray()
                        bos.close()
                        val bis = ByteArrayInputStream(data)
                        inputStream = bis
                        callback.onDataReady(bis)
                    } else {
                        callback.onLoadFailed(Exception("Failed to get video thumbnail"))
                    }
                } else {
                    val stream = runBlocking {
                        SmbManager.getInputStream(currentModel.path)
                    }
                    if (stream != null) {
                        // Wrap in BufferedInputStream for better performance and reset support
                        val bufferedStream = java.io.BufferedInputStream(stream, 64 * 1024)
                        inputStream = bufferedStream
                        callback.onDataReady(bufferedStream)
                    } else {
                        callback.onLoadFailed(Exception("Failed to open SMB stream for: ${currentModel.path}"))
                    }
                }
            } catch (e: Exception) {
                Log.e("SmbGlideLoader", "Error loading data for: ${model.path}", e)
                callback.onLoadFailed(e)
            }
        }

        private suspend fun getVideoThumbnail(model: FileModel, requestedWidth: Int, requestedHeight: Int): Bitmap? {
            val path = model.path
            // Scale up the requested size to support high-density screens
            // Glide passes dimensions in pixels, but for better clarity, we want more source pixels
            val scaleFactor = 2 // 2x resolution for better clarity
            val width = (requestedWidth * scaleFactor).coerceAtLeast(300)
            val height = (requestedHeight * scaleFactor).coerceAtLeast(300)
            
            // Log.d("SmbGlideLoader", "Getting video thumbnail for: $path, target size: ${width}x${height}")
            val retriever = MediaMetadataRetriever()
            val smbFile = SmbManager.openFile(path) ?: run {
                Log.e("SmbGlideLoader", "Failed to open SMB file for: $path")
                return null
            }
            
            return try {
                retriever.setDataSource(object : android.media.MediaDataSource() {
                    private var readCount = 0
                    private val buffer = ByteArray(128 * 1024) // 128KB read-ahead buffer
                    private var bufferPos = -1L
                    private var bufferSize = 0

                    @Synchronized
                    override fun readAt(position: Long, dest: ByteArray, offset: Int, size: Int): Int {
                        if (position >= model.size) return -1
                        if (size == 0) return 0
                        
                        // Serve from buffer if possible
                        if (position >= bufferPos && position + size <= bufferPos + bufferSize && bufferPos != -1L) {
                            System.arraycopy(buffer, (position - bufferPos).toInt(), dest, offset, size)
                            return size
                        }

                        return try {
                            // If request is larger than our buffer, read directly to destination
                            if (size >= buffer.size) {
                                readFromSmb(position, dest, offset, size)
                            } else {
                                // Fill buffer starting at requested position
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
                
                // Get duration to decide sampling
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull() ?: 0L
                
                // Faster strategy: Only try main points initially with OPTION_CLOSEST_SYNC
                val isAvi = path.lowercase().endsWith(".avi")
                val timePoints = mutableListOf(1000000L, 5000000L, 0L)
                if (durationMs > 20000) {
                    timePoints.add(durationMs * 100L) // 10%
                }
                
                var bestBitmap: Bitmap? = null
                var bestScore = -1.0
                
                for (timeUs in timePoints) {
                    try {
                        Log.d("SmbGlideLoader", "Trying fast frame at $timeUs us (isAvi: $isAvi)")
                        // For AVI, OPTION_CLOSEST might be more reliable on some devices
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
                            // Log.d("SmbGlideLoader", "Got frame at $timeUs us, quality score: $score")
                            
                            if (score > 0.8) { 
                                bestBitmap = bitmap
                                break
                            } else if (score > bestScore) {
                                bestScore = score
                                bestBitmap = bitmap
                            }
                        }
                    } catch (e: Exception) {
                        // Log.e("SmbGlideLoader", "Failed fast frame at $timeUs us", e)
                    }
                }
                
                // Fallback: If no good frame found, try one last time with OPTION_CLOSEST (slower but more accurate)
                if (bestScore < 0.3 && bestBitmap == null) {
                    try {
                        // Log.d("SmbGlideLoader", "Falling back to slow OPTION_CLOSEST")
                        bestBitmap = retriever.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    } catch (e: Exception) {}
                }
                
                bestBitmap
            } catch (e: Exception) {
                Log.e("SmbGlideLoader", "Error setting data source or getting frame", e)
                null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {}
                try {
                    smbFile.close()
                } catch (e: Exception) {}
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
            
            // Calculate standard deviation of brightness to detect solid colors (even non-black/white)
            var variance = 0.0
            for (brightness in pixelBrightnessList) {
                variance += Math.pow(brightness - avgBrightness, 2.0)
            }
            val stdDev = Math.sqrt(variance / pixelBrightnessList.size)
            
            // Score based on brightness (bell curve)
            val brightnessScore = 1.0 - (Math.abs(avgBrightness - 128.0) / 128.0)
            
            // Score based on variance (stdDev): 
            // - Low stdDev (< 5.0) means it's a solid color (bad)
            // - High stdDev (> 15.0) means there's visual information (good)
            val varianceScore = (stdDev / 20.0).coerceAtMost(1.0)
            
            // Combined score
            val combinedScore = (brightnessScore * 0.4 + varianceScore * 0.6)
            
            Log.d("SmbGlideLoader", "Frame avgBrightness: $avgBrightness, stdDev: $stdDev, score: $combinedScore")
            
            return when {
                avgBrightness < 20.0 -> combinedScore * 0.1 // Too black
                avgBrightness > 235.0 -> combinedScore * 0.1 // Too white
                stdDev < 5.0 -> combinedScore * 0.05 // Almost certainly solid color
                else -> combinedScore
            }
        }

        override fun cleanup() {
            try {
                inputStream?.close()
            } catch (e: Exception) {}
        }

        override fun cancel() {
            // runBlocking cannot be easily cancelled from here, 
            // but cleanup will be called anyway.
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.REMOTE
    }

    class Factory : ModelLoaderFactory<FileModel, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<FileModel, InputStream> {
            return SmbModelLoader()
        }

        override fun teardown() {}
    }
}
