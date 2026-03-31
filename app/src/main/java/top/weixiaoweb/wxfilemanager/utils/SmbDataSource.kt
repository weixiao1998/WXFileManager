package top.weixiaoweb.wxfilemanager.utils

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.smbj.share.File

class SmbDataSource : BaseDataSource(false) {
    private var file: File? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false
    private var currentPath: String = ""
    private var currentPosition: Long = 0
    private var fileLength: Long = 0
    
    private var buffer: ByteArray = ByteArray(BUFFER_SIZE)
    private var bufferStartPos: Long = -1
    private var bufferValidBytes: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        currentPath = uri?.toString()?.substringAfter("smb://") ?: throw Exception("Invalid path")
        currentPosition = dataSpec.position
        
        file = openFileWithRetry(currentPath) ?: throw Exception("Failed to open SMB file: $currentPath")

        fileLength = file?.fileInformation?.standardInformation?.endOfFile ?: C.LENGTH_UNSET.toLong()
        
        currentPosition = dataSpec.position
        bufferStartPos = -1
        bufferValidBytes = 0

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            fileLength - dataSpec.position
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }
    
    private fun openFileWithRetry(path: String, maxRetries: Int = 3): File? {
        for (attempt in 0 until maxRetries) {
            if (!SmbManager.checkAndReconnect()) {
                Thread.sleep(300L * (attempt + 1))
                continue
            }
            
            val result = SmbManager.openFile(path)
            if (result != null) return result
            
            Thread.sleep(300L * (attempt + 1))
        }
        return null
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(bytesRemaining, length.toLong()).toInt()
        
        var totalBytesRead = 0
        var remainingToRead = bytesToRead
        var destOffset = offset
        
        while (remainingToRead > 0) {
            val bytesReadFromBuffer = readFromBuffer(buffer, destOffset, remainingToRead)
            if (bytesReadFromBuffer > 0) {
                totalBytesRead += bytesReadFromBuffer
                destOffset += bytesReadFromBuffer
                remainingToRead -= bytesReadFromBuffer
                continue
            }
            
            if (!fillBuffer()) {
                break
            }
        }
        
        if (totalBytesRead == 0) {
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= totalBytesRead
        }
        bytesTransferred(totalBytesRead)
        return totalBytesRead
    }
    
    private fun readFromBuffer(dest: ByteArray, offset: Int, length: Int): Int {
        if (bufferValidBytes == 0 || bufferStartPos < 0) return 0
        
        val bufferOffset = (currentPosition - bufferStartPos).toInt()
        if (bufferOffset < 0 || bufferOffset >= bufferValidBytes) return 0
        
        val availableInBuffer = bufferValidBytes - bufferOffset
        val toCopy = minOf(length, availableInBuffer)
        
        System.arraycopy(buffer, bufferOffset, dest, offset, toCopy)
        currentPosition += toCopy
        
        return toCopy
    }
    
    private fun fillBuffer(): Boolean {
        val readStart = currentPosition
        val bytesToRead = minOf(BUFFER_SIZE.toLong(), fileLength - currentPosition).toInt()
        
        if (bytesToRead <= 0) return false
        
        val bytesRead = file?.read(buffer, readStart, 0, bytesToRead) ?: -1
        
        if (bytesRead <= 0) return false
        
        bufferStartPos = readStart
        bufferValidBytes = bytesRead
        return true
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        currentPath = ""
        currentPosition = 0
        fileLength = 0
        bufferStartPos = -1
        bufferValidBytes = 0
        try {
            file?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
    
    companion object {
        private const val BUFFER_SIZE = 128 * 1024
    }
}
