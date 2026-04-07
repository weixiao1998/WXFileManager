package top.weixiaoweb.wxfilemanager.utils

import android.webkit.MimeTypeMap
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.PipeShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo0
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.weixiaoweb.wxfilemanager.model.FileModel
import java.io.InputStream
import java.util.EnumSet

object SmbManager {
    private val client = SMBClient()
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null
    
    private var lastHost: String? = null
    private var lastUser: String? = null
    private var lastPass: String? = null
    private var lastShare: String? = null
    
    private var lastConnectionTime: Long = 0
    private val connectionTimeout: Long = 60_000L
    private val reconnectLock = Any()
    
    private val directoryCache = mutableMapOf<String, CacheEntry>()
    private val cacheLock = Any()
    private val cacheExpiryTime: Long = 30_000L
    
    data class CacheEntry(
        val files: List<FileModel>,
        val timestamp: Long
    )
    
    fun clearCache() {
        synchronized(cacheLock) {
            directoryCache.clear()
        }
    }
    
    private fun getCacheKey(path: String): String {
        val share = lastShare ?: ""
        return "$share:$path"
    }

    fun clearCacheForPath(path: String) {
        synchronized(cacheLock) {
            directoryCache.remove(getCacheKey(path))
        }
    }

    fun isConnected(): Boolean {
        synchronized(reconnectLock) {
            if (connection?.isConnected != true || session == null) {
                return false
            }
            
            if (System.currentTimeMillis() - lastConnectionTime > connectionTimeout) {
                return try {
                    diskShare?.list("") != null
                } catch (e: Exception) {
                    false
                }
            }
            return true
        }
    }
    
    fun checkAndReconnect(): Boolean {
        if (isConnected()) return true
        return tryReconnectSync()
    }

    fun getConnectionInfo(): ConnectionInfo? {
        synchronized(reconnectLock) {
            val host = lastHost ?: return null
            val user = lastUser ?: return null
            val pass = lastPass ?: return null
            val share = lastShare ?: return null
            return ConnectionInfo(host, user, pass, share)
        }
    }

    data class ConnectionInfo(
        val host: String,
        val user: String,
        val pass: String,
        val share: String
    )
    
    private fun tryReconnectSync(): Boolean {
        synchronized(reconnectLock) {
            val host = lastHost ?: return false
            val user = lastUser ?: return false
            val pass = lastPass ?: return false
            val share = lastShare
            
            repeat(3) { attempt ->
                try {
                    disconnectInternal()
                    
                    connection = client.connect(host)
                    val auth = AuthenticationContext(user, pass.toCharArray(), "")
                    session = connection?.authenticate(auth)
                    
                    if (share != null) {
                        diskShare = session?.connectShare(share) as? DiskShare
                    }
                    
                    if (diskShare != null || share == null) {
                        lastConnectionTime = System.currentTimeMillis()
                        return true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                try {
                    Thread.sleep(500L * (attempt + 1))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return false
        }
    }

    suspend fun connect(host: String, user: String, pass: String, share: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isConnected() && lastHost == host && lastUser == user && lastPass == pass) {
                if (share != null && lastShare != share) {
                    return@withContext connectShare(share)
                }
                lastConnectionTime = System.currentTimeMillis()
                return@withContext true
            }

            disconnect()
            lastHost = host
            lastUser = user
            lastPass = pass
            lastShare = share

            connection = client.connect(host)
            val auth = AuthenticationContext(user, pass.toCharArray(), "")
            session = connection?.authenticate(auth)
            if (share != null) {
                diskShare = session?.connectShare(share) as? DiskShare
                if (diskShare != null) {
                    lastConnectionTime = System.currentTimeMillis()
                    true
                } else {
                    false
                }
            } else {
                lastConnectionTime = System.currentTimeMillis()
                true 
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (isConnected()) return true
        
        val host = lastHost ?: return false
        val user = lastUser ?: return false
        val pass = lastPass ?: return false
        val share = lastShare
        
        repeat(3) { attempt ->
            try {
                disconnect()
                val result = connect(host, user, pass, share)
                if (result) return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
            Thread.sleep(500L * (attempt + 1))
        }
        return false
    }

    suspend fun listShares(): List<String> = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext emptyList()
        val currentSession = session ?: return@withContext emptyList()
        try {
            val transport = SMBTransportFactories.SRVSVC.getTransport(currentSession)
            val serverService = ServerService(transport)
            val shares: List<NetShareInfo0> = serverService.shares0
            
            // Filter out administrative shares and test access
            shares.map { it.netName }
                .filter { !it.endsWith("$") }
                .filter { shareName ->
                    // Proactively check if the share is accessible by trying to list its root
                    try {
                        val testShare = currentSession.connectShare(shareName) as? DiskShare
                        if (testShare == null) return@filter false
                        
                        // Try to list the root directory to verify read access
                        try {
                            testShare.list("")
                            testShare.close()
                            true
                        } catch (e: Exception) {
                            testShare.close()
                            false
                        }
                    } catch (e: Exception) {
                        false
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun connectShare(share: String): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        val currentSession = session ?: return@withContext false
        try {
            diskShare?.close()
            diskShare = currentSession.connectShare(share) as? DiskShare
            lastShare = share
            
            clearCache()
            
            if (diskShare != null) {
                lastConnectionTime = System.currentTimeMillis()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun listFiles(path: String, useCache: Boolean = true): List<FileModel> = withContext(Dispatchers.IO) {
        val cacheKey = getCacheKey(path)
        
        if (useCache) {
            synchronized(cacheLock) {
                val cached = directoryCache[cacheKey]
                if (cached != null && System.currentTimeMillis() - cached.timestamp < cacheExpiryTime) {
                    return@withContext cached.files
                }
            }
        }
        
        if (!ensureConnected()) return@withContext emptyList()
        
        var share = diskShare ?: return@withContext emptyList()
        
        try {
            val fileList = try {
                share.list(path)
            } catch (e: Exception) {
                if (ensureConnected()) {
                    share = diskShare ?: return@withContext emptyList()
                    share.list(path)
                } else {
                    throw e
                }
            }

            val result = fileList.filter { it.fileName != "." && it.fileName != ".." }
                .map { fileInfo ->
                    val isDirectory = fileInfo.fileAttributes.and(0x10L) != 0L
                    val fullPath = if (path.isEmpty()) fileInfo.fileName else "$path\\${fileInfo.fileName}"
                    val extension = fileInfo.fileName.substringAfterLast('.', "").lowercase()
                    val mimeType = if (isDirectory) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    
                    FileModel(
                        name = fileInfo.fileName,
                        path = fullPath,
                        isDirectory = isDirectory,
                        size = fileInfo.endOfFile,
                        lastModified = fileInfo.changeTime.toEpochMillis(),
                        mimeType = mimeType,
                        isSmb = true,
                        smbUrl = "smb://$fullPath"
                    )
                }
                .sortedWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })
            
            synchronized(cacheLock) {
                directoryCache[cacheKey] = CacheEntry(result, System.currentTimeMillis())
            }
            
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun searchRecursive(path: String, query: String): List<FileModel> = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext emptyList()
        val results = mutableListOf<FileModel>()
        val share = diskShare ?: return@withContext emptyList()
        
        fun doSearch(currentPath: String) {
            try {
                val list = share.list(currentPath).filter { it.fileName != "." && it.fileName != ".." }
                for (fileInfo in list) {
                    val isDirectory = fileInfo.fileAttributes.and(0x10L) != 0L
                    val fileName = fileInfo.fileName
                    val fullPath = if (currentPath.isEmpty()) fileName else "$currentPath\\$fileName"
                    
                    if (fileName.contains(query, ignoreCase = true)) {
                        val extension = fileName.substringAfterLast('.', "").lowercase()
                        val mimeType = if (isDirectory) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                        results.add(FileModel(
                            name = fileName,
                            path = fullPath,
                            isDirectory = isDirectory,
                            size = fileInfo.endOfFile,
                            lastModified = fileInfo.changeTime.toEpochMillis(),
                            mimeType = mimeType,
                            isSmb = true,
                            smbUrl = "smb://$fullPath"
                        ))
                    }
                    
                    if (isDirectory) {
                        doSearch(fullPath)
                    }
                }
            } catch (e: Exception) {
                // Skip restricted directories
            }
        }
        
        doSearch(path)
        results
    }

    suspend fun getInputStream(path: String): InputStream? = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext null
        try {
            val file = openFile(path) ?: return@withContext null
            file.inputStream
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun findSubtitles(videoPath: String): List<FileModel> = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext emptyList()
        val share = diskShare ?: return@withContext emptyList()
        try {
            val parentPath = videoPath.substringBeforeLast('\\', "")
            val videoBaseName = videoPath.substringAfterLast('\\').substringBeforeLast('.')
            val subtitleExtensions = listOf("srt", "vtt", "ass", "ssa")
            
            share.list(parentPath).filter { fileInfo ->
                val fileName = fileInfo.fileName
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val baseName = fileName.substringBeforeLast('.')
                subtitleExtensions.contains(ext) && baseName.startsWith(videoBaseName)
            }.map { fileInfo ->
                val fullPath = if (parentPath.isEmpty()) fileInfo.fileName else "$parentPath\\${fileInfo.fileName}"
                FileModel(
                    name = fileInfo.fileName,
                    path = fullPath,
                    isDirectory = false,
                    size = fileInfo.endOfFile,
                    lastModified = fileInfo.changeTime.toEpochMillis(),
                    mimeType = "application/x-subrip", // Default, will be handled by Media3
                    isSmb = true,
                    smbUrl = "smb://$fullPath"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun openFile(path: String): File? {
        synchronized(reconnectLock) {
            val share = diskShare ?: return null
            
            return try {
                share.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_RANDOM_ACCESS)
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun getDiskShare(): DiskShare? {
        synchronized(reconnectLock) {
            return diskShare
        }
    }

    fun disconnect() {
        synchronized(reconnectLock) {
            disconnectInternal()
        }
    }
    
    private fun disconnectInternal() {
        try {
            diskShare?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            session?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            connection?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        diskShare = null
        session = null
        connection = null
    }
}
