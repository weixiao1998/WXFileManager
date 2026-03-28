package top.weixiaoweb.wxfilemanager.utils

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File
import java.io.InputStream
import java.util.EnumSet

class SmbDataSource : BaseDataSource(false) {
    private var file: File? = null
    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false
    private var currentPath: String = ""
    private var currentPosition: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        currentPath = uri?.toString()?.substringAfter("smb://") ?: throw Exception("Invalid path")
        currentPosition = dataSpec.position
        
        file = openFileWithRetry(currentPath) ?: throw Exception("Failed to open SMB file: $currentPath")

        val length = file?.fileInformation?.standardInformation?.endOfFile ?: C.LENGTH_UNSET.toLong()
        
        inputStream = file?.inputStream
        if (dataSpec.position > 0) {
            var skipped = 0L
            while (skipped < dataSpec.position) {
                val skip = inputStream?.skip(dataSpec.position - skipped) ?: 0
                if (skip == 0L) {
                    if (inputStream?.read() == -1) break
                    skipped++
                } else {
                    skipped += skip
                }
            }
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            length - dataSpec.position
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
        
        var bytesRead = inputStream?.read(buffer, offset, bytesToRead) ?: -1
        
        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        currentPath = ""
        currentPosition = 0
        try {
            inputStream?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inputStream = null
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
    }
}
