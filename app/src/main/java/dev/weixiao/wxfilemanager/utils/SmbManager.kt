package dev.weixiao.wxfilemanager.utils

import android.util.Log
import android.util.LruCache
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
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.mssrvs.dto.NetShareInfo0
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import dev.weixiao.wxfilemanager.model.FileModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

object SmbManager {
    private const val TAG = "SmbManager"

    /** 目录缓存最大条目数，防止长时间浏览造成内存膨胀 */
    private const val DIRECTORY_CACHE_MAX_ENTRIES = 64

    private var client: SMBClient = SMBClient()
    private var connection: Connection? = null
    private var session: Session? = null
    private var diskShare: DiskShare? = null

    private var lastHost: String? = null
    private var lastUser: String? = null
    private var lastPass: String? = null
    private var lastShare: String? = null

    private var lastConnectionTime: Long = 0
    private val connectionTimeout: Long = 60_000L

    /** 协程协作锁，替代原先的 synchronized 监视器，便于在加锁区内 delay。*/
    private val reconnectMutex = Mutex()

    /** LRU 目录缓存：超过 [DIRECTORY_CACHE_MAX_ENTRIES] 条会自动淘汰最久未访问的条目 */
    private val directoryCache = LruCache<String, CacheEntry>(DIRECTORY_CACHE_MAX_ENTRIES)
    private val cacheLock = Any()
    private val cacheExpiryTime: Long = 30_000L

    data class CacheEntry(
        val files: List<FileModel>,
        val timestamp: Long
    )

    fun clearCache() {
        synchronized(cacheLock) {
            directoryCache.evictAll()
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

    /**
     * 轻量状态检查（无 IO 探测）。原先会在超时后用 list("") 主动探测，
     * 但该调用阻塞且可能挂起，已移至挂起函数 [probeConnection] 中处理。
     */
    fun isConnected(): Boolean {
        if (connection?.isConnected != true || session == null) return false
        return true
    }

    /**
     * 在 IO 线程上主动探测一次共享是否可读，用于判断连接是否仍有效。
     */
    private suspend fun probeConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            diskShare?.list("") != null
        } catch (e: Exception) {
            false
        }
    }

    suspend fun checkAndReconnect(): Boolean {
        if (isConnected()) {
            if (System.currentTimeMillis() - lastConnectionTime <= connectionTimeout) return true
            if (probeConnection()) {
                lastConnectionTime = System.currentTimeMillis()
                return true
            }
        }
        return tryReconnect()
    }

    fun getConnectionInfo(): ConnectionInfo? {
        val host = lastHost ?: return null
        val user = lastUser ?: return null
        val pass = lastPass ?: return null
        val share = lastShare ?: return null
        return ConnectionInfo(host, user, pass, share)
    }

    /**
     * 构造 VLC 使用的 smb:// URI。VLC 自带 SMB 模块，与 smbj 无关。
     * 传入的 [path] 应为共享内相对路径（可能使用反斜杠），会自动规范并 URL 编码。
     */
    fun buildVlcSmbUri(path: String): android.net.Uri? {
        val info = getConnectionInfo() ?: return null
        var relative = path.replace("\\", "/")
        while (relative.contains("//")) {
            relative = relative.replace("//", "/")
        }
        val encodedPath = android.net.Uri.encode(relative, "/")
        val encodedUser = android.net.Uri.encode(info.user, "@:/")
        val encodedPass = android.net.Uri.encode(info.pass, "@:/")
        return android.net.Uri.parse("smb://$encodedUser:$encodedPass@${info.host}/${info.share}/$encodedPath")
    }

    data class ConnectionInfo(
        val host: String,
        val user: String,
        val pass: String,
        val share: String
    )

    private suspend fun tryReconnect(): Boolean = reconnectMutex.withLock {
        val host = lastHost ?: return@withLock false
        val user = lastUser ?: return@withLock false
        val pass = lastPass ?: return@withLock false
        val share = lastShare

        repeat(3) { attempt ->
            try {
                disconnectInternal()

                withContext(Dispatchers.IO) {
                    connection = client.connect(host)
                    val auth = AuthenticationContext(user, pass.toCharArray(), "")
                    session = connection?.authenticate(auth)

                    if (share != null) {
                        diskShare = session?.connectShare(share) as? DiskShare
                    }
                }

                if (diskShare != null || share == null) {
                    lastConnectionTime = System.currentTimeMillis()
                    return@withLock true
                }
            } catch (e: Exception) {
                Log.w(TAG, "reconnect attempt #${attempt + 1} failed", e)
            }

            // 使用协作式 delay，避免阻塞 Dispatcher 线程
            delay(500L * (attempt + 1))
        }
        false
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
            Log.w(TAG, "connect failed", e)
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
                Log.w(TAG, "ensureConnected attempt #${attempt + 1} failed", e)
            }
            delay(500L * (attempt + 1))
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
            Log.w(TAG, "listShares failed", e)
            emptyList()
        }
    }

    suspend fun connectShare(share: String): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        // 已连接到同一共享时直接返回，避免 close 重建导致并发操作失败
        if (diskShare != null && lastShare == share) return@withContext true
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
            Log.w(TAG, "connectShare failed", e)
            false
        }
    }

    suspend fun listFiles(path: String, useCache: Boolean = true): List<FileModel> = withContext(Dispatchers.IO) {
        val cacheKey = getCacheKey(path)

        if (useCache) {
            synchronized(cacheLock) {
                val cached = directoryCache.get(cacheKey)
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
                directoryCache.put(cacheKey, CacheEntry(result, System.currentTimeMillis()))
            }

            result
        } catch (e: Exception) {
            Log.w(TAG, "listFiles failed for $path", e)
            emptyList()
        }
    }

    /** 搜索时跳过的系统噪声目录（大小写不敏感匹配） */
    private val SEARCH_SKIP_DIR_NAMES = setOf(
        "\$recycle.bin",
        "\$recycle-bin",
        "recycle.bin",
        "recycler",
        "system volume information",
        "\$winreagent",
        "\$sysreset",
        "config.msi",
        "found.000",
        ".ds_store",
        ".thumbnails",
        ".trash",
        ".trash-1000"
    )

    private fun shouldSkipDir(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in SEARCH_SKIP_DIR_NAMES) return true
        // 以 .Trash- 开头的 Linux 回收站目录
        if (lower.startsWith(".trash-")) return true
        return false
    }

    /** 搜索结果上限，达到后停止遍历 */
    const val SEARCH_MAX_RESULTS = 500

    /** 目录并发遍历度 */
    private const val SEARCH_CONCURRENCY = 6

    /**
     * 流式增量搜索：每积累一定数量或每完成一个目录就发射一次已命中的结果。
     * 收集端可用 conflate/sample 节流刷新，避免频繁 submitList。
     *
     * 特性：
     *  - 增量 emit：首批结果 ~百毫秒级可见，不再"整棵树扫完才出"
     *  - 跳过系统噪声目录（$RECYCLE.BIN 等）
     *  - 有界并发（Semaphore）：多目录并行 list，深目录显著加速
     *  - 结果上限：命中 [SEARCH_MAX_RESULTS] 后停止，避免 UI 被压垮
     *  - 优先读取 [directoryCache] 中已缓存的目录，无需再走网络
     */
    fun searchRecursiveFlow(path: String, query: String): Flow<List<FileModel>> = channelFlow {
        if (!ensureConnected()) {
            send(emptyList())
            return@channelFlow
        }
        val share = diskShare ?: run {
            send(emptyList())
            return@channelFlow
        }

        val results = mutableListOf<FileModel>()
        val resultsLock = Mutex()
        val reachedLimit = AtomicBoolean(false)
        val semaphore = Semaphore(SEARCH_CONCURRENCY)

        suspend fun tryEmitSnapshot() {
            val snapshot = resultsLock.withLock { results.toList() }
            send(snapshot)
        }

        // 使用 coroutineScope 让并发子任务共享结构化并发；任一失败/取消都会向上传播
        coroutineScope {
            suspend fun doSearch(currentPath: String) {
                if (reachedLimit.get()) return
                coroutineContext.ensureActive()

                val list = try {
                    semaphore.withPermit {
                        // 优先复用已缓存的目录（若在缓存有效期内）
                        val cacheKey = getCacheKey(currentPath)
                        val cached = synchronized(cacheLock) {
                            directoryCache.get(cacheKey)?.takeIf {
                                System.currentTimeMillis() - it.timestamp < cacheExpiryTime
                            }
                        }
                        if (cached != null) {
                            // 缓存里的 FileModel 已经解析好，直接过滤匹配即可
                            null to cached.files
                        } else {
                            val raw = share.list(currentPath)
                                .filter { it.fileName != "." && it.fileName != ".." }
                            raw to null
                        }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // 权限受限等，跳过
                    return
                }

                val (rawList, cachedFiles) = list
                val childDirs = mutableListOf<String>()
                var hitInThisDir = false

                if (cachedFiles != null) {
                    // 走缓存分支
                    for (f in cachedFiles) {
                        if (reachedLimit.get()) return
                        coroutineContext.ensureActive()
                        if (f.isDirectory && shouldSkipDir(f.name)) continue

                        if (f.name.contains(query, ignoreCase = true)) {
                            val added = resultsLock.withLock {
                                if (results.size >= SEARCH_MAX_RESULTS) {
                                    reachedLimit.set(true)
                                    false
                                } else {
                                    results.add(f)
                                    true
                                }
                            }
                            if (added) hitInThisDir = true else return
                        }
                        if (f.isDirectory) childDirs.add(f.path)
                    }
                } else {
                    // 走原始 FileIdBothDirectoryInformation 分支
                    for (fileInfo in rawList!!) {
                        if (reachedLimit.get()) return
                        coroutineContext.ensureActive()
                        val isDirectory = fileInfo.fileAttributes.and(0x10L) != 0L
                        val fileName = fileInfo.fileName

                        if (isDirectory && shouldSkipDir(fileName)) continue

                        val fullPath = if (currentPath.isEmpty()) fileName else "$currentPath\\$fileName"

                        if (fileName.contains(query, ignoreCase = true)) {
                            val extension = fileName.substringAfterLast('.', "").lowercase()
                            val mimeType = if (isDirectory) null
                                else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                            val model = FileModel(
                                name = fileName,
                                path = fullPath,
                                isDirectory = isDirectory,
                                size = fileInfo.endOfFile,
                                lastModified = fileInfo.changeTime.toEpochMillis(),
                                mimeType = mimeType,
                                isSmb = true,
                                smbUrl = "smb://$fullPath"
                            )
                            val added = resultsLock.withLock {
                                if (results.size >= SEARCH_MAX_RESULTS) {
                                    reachedLimit.set(true)
                                    false
                                } else {
                                    results.add(model)
                                    true
                                }
                            }
                            if (added) hitInThisDir = true else return
                        }

                        if (isDirectory) childDirs.add(fullPath)
                    }
                }

                if (hitInThisDir) tryEmitSnapshot()

                // 并发下探子目录
                val jobs = childDirs.map { child ->
                    launch { doSearch(child) }
                }
                jobs.forEach { it.join() }
            }

            doSearch(path)
        }

        // 最终快照，保证收集端至少能拿到完整结果一次
        tryEmitSnapshot()
    }

    /**
     * 兼容旧调用方的一次性搜索。内部收集流式版本并返回最终列表。
     */
    suspend fun searchRecursive(path: String, query: String): List<FileModel> {
        var last: List<FileModel> = emptyList()
        searchRecursiveFlow(path, query).collect { last = it }
        return last
    }

    suspend fun getInputStream(path: String): InputStream? = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext null
        try {
            val file = openFile(path) ?: return@withContext null
            file.inputStream
        } catch (e: Exception) {
            Log.w(TAG, "getInputStream failed for $path", e)
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
                    mimeType = "application/x-subrip",
                    isSmb = true,
                    smbUrl = "smb://$fullPath"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "findSubtitles failed", e)
            emptyList()
        }
    }

    suspend fun findAudioTracks(videoPath: String): List<FileModel> = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext emptyList()
        val share = diskShare ?: return@withContext emptyList()
        try {
            val parentPath = videoPath.substringBeforeLast('\\', "")
            val videoBaseName = videoPath.substringAfterLast('\\').substringBeforeLast('.')
            val audioExtensions = listOf("mp3", "aac", "ac3", "dts", "flac", "m4a", "wav", "ogg", "opus", "mka")

            share.list(parentPath).filter { fileInfo ->
                val fileName = fileInfo.fileName
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val baseName = fileName.substringBeforeLast('.')
                audioExtensions.contains(ext) && baseName.startsWith(videoBaseName)
            }.map { fileInfo ->
                val fullPath = if (parentPath.isEmpty()) fileInfo.fileName else "$parentPath\\${fileInfo.fileName}"
                FileModel(
                    name = fileInfo.fileName,
                    path = fullPath,
                    isDirectory = false,
                    size = fileInfo.endOfFile,
                    lastModified = fileInfo.changeTime.toEpochMillis(),
                    mimeType = "audio/*",
                    isSmb = true,
                    smbUrl = "smb://$fullPath"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "findAudioTracks failed", e)
            emptyList()
        }
    }

    fun openFile(path: String): File? {
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
            Log.w(TAG, "openFile failed for $path", e)
            null
        }
    }

    fun getDiskShare(): DiskShare? = diskShare

    fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        try {
            diskShare?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close diskShare failed", e)
        }
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close session failed", e)
        }
        try {
            connection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close connection failed", e)
        }
        diskShare = null
        session = null
        connection = null

        // 关闭并重建 SMBClient，释放其内部线程池/连接池等资源；
        // 重建是为了避免上次握手残留状态影响下一次连接。
        try {
            client.close()
        } catch (e: Exception) {
            Log.w(TAG, "close client failed", e)
        }
        client = SMBClient()
    }
}
