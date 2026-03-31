package top.weixiaoweb.wxfilemanager.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import top.weixiaoweb.wxfilemanager.model.FileModel
import top.weixiaoweb.wxfilemanager.model.SmbServer
import top.weixiaoweb.wxfilemanager.utils.SmbManager

class SmbViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("smb_servers", Context.MODE_PRIVATE)
    private val gson = Gson()

    enum class ViewState {
        SERVERS, SHARES, FILES
    }

    private val _viewState = MutableLiveData(ViewState.SERVERS)
    val viewState: LiveData<ViewState> = _viewState

    private val _servers = MutableLiveData<List<SmbServer>>()
    val servers: LiveData<List<SmbServer>> = _servers

    private val _files = MutableLiveData<List<FileModel>>()
    val files: LiveData<List<FileModel>> = _files

    private val _filteredFiles = MutableLiveData<List<FileModel>>()
    val filteredFiles: LiveData<List<FileModel>> = _filteredFiles

    private val _currentPath = MutableLiveData<String>("")
    val currentPath: LiveData<String> = _currentPath

    private val _currentShare = MutableLiveData<String?>(null)
    val currentShare: LiveData<String?> = _currentShare

    private val _currentServer = MutableLiveData<SmbServer?>(null)
    val currentServer: LiveData<SmbServer?> = _currentServer

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _connected = MutableLiveData<Boolean>(false)
    val connected: LiveData<Boolean> = _connected

    private val _breadcrumbs = MutableLiveData<List<BreadcrumbItem>>()
    val breadcrumbs: LiveData<List<BreadcrumbItem>> = _breadcrumbs

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _connectSuccess = MutableLiveData<Boolean>()
    val connectSuccess: LiveData<Boolean> = _connectSuccess

    private var currentSearchQuery = ""
    private var currentSortMode = FileModel.SortMode.NAME_ASC
    private var currentViewMode = FileModel.ViewMode.LIST_MEDIUM

    private val _viewMode = MutableLiveData(FileModel.ViewMode.LIST_MEDIUM)
    val viewMode: LiveData<FileModel.ViewMode> = _viewMode

    fun resetConnectionEvent() {
        _connectSuccess.value = false
    }

    data class BreadcrumbItem(val name: String, val path: String, val share: String? = null)

    init {
        loadSavedServers()
        updateBreadcrumbs("", null)
        
        // Try to restore last connection if it was active
        val lastHost = prefs.getString("last_host", null)
        val lastUser = prefs.getString("last_user", null)
        val lastPass = prefs.getString("last_pass", null)
        val lastShare = prefs.getString("last_share", null)
        
        if (lastHost != null && lastUser != null && lastPass != null) {
            connect(lastHost, lastUser, lastPass, lastShare)
        }
    }

    private fun loadSavedServers() {
        val json = prefs.getString("server_list", null)
        val serverList: List<SmbServer> = if (json != null) {
            val type = object : TypeToken<List<SmbServer>>() {}.type
            gson.fromJson(json, type)
        } else {
            emptyList()
        }
        _servers.value = serverList
    }

    fun saveServer(server: SmbServer) {
        val current = _servers.value?.toMutableList() ?: mutableListOf()
        if (current.none { it.host == server.host && it.user == server.user }) {
            current.add(server)
            _servers.value = current
            prefs.edit().putString("server_list", gson.toJson(current)).apply()
        }
    }

    fun connect(server: SmbServer) {
        connect(server.host, server.user, server.pass, null)
    }

    fun connect(host: String, user: String, pass: String, share: String? = null) {
        _loading.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                val result = SmbManager.connect(host, user, pass, share)
                _connected.value = result
                if (result) {
                    val server = SmbServer(host, user, pass)
                    saveServer(server)
                    _currentServer.value = server
                    
                    // Save last connection for recovery
                    prefs.edit().apply {
                        putString("last_host", host)
                        putString("last_user", user)
                        putString("last_pass", pass)
                        putString("last_share", share)
                    }.apply()

                    if (share != null) {
                        _currentShare.value = share
                        _viewState.value = ViewState.FILES
                        val lastPath = prefs.getString("last_path", "")
                        loadFiles(lastPath ?: "")
                    } else {
                        _currentShare.value = null
                        _viewState.value = ViewState.SHARES
                        loadShares()
                    }
                    _connectSuccess.value = true
                } else {
                    _errorMessage.value = "无法连接到服务器或共享目录"
                    _loading.value = false
                    _connectSuccess.value = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "连接失败: ${e.localizedMessage ?: e.message}"
                _loading.value = false
                _connected.value = false
                _connectSuccess.value = false
            }
        }
    }

    private fun loadShares() {
        _loading.value = true
        updateBreadcrumbs("", null)
        viewModelScope.launch {
            val shares = SmbManager.listShares()
            _files.value = shares.map { shareName ->
                FileModel(
                    name = shareName,
                    path = shareName,
                    isDirectory = true,
                    size = 0,
                    lastModified = 0,
                    isSmb = true,
                    smbUrl = "smb://$shareName",
                    itemType = FileModel.ItemType.SHARE
                )
            }
            applyFilterAndSort()
            _loading.value = false
        }
    }

    fun selectShare(shareName: String) {
        _loading.value = true
        viewModelScope.launch {
            if (SmbManager.connectShare(shareName)) {
                _currentShare.value = shareName
                _viewState.value = ViewState.FILES
                
                // Update last share
                prefs.edit().putString("last_share", shareName).apply()
                
                loadFiles("")
            } else {
                _loading.value = false
            }
        }
    }

    fun loadFiles(path: String, useCache: Boolean = true) {
        _loading.value = true
        _currentPath.value = path
        updateBreadcrumbs(path, _currentShare.value)
        
        prefs.edit().putString("last_path", path).apply()
        
        viewModelScope.launch {
            try {
                val list = SmbManager.listFiles(path, useCache)
                _files.value = list
                applyFilterAndSort()
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "连接已断开，请尝试刷新"
                _files.value = emptyList()
            } finally {
                _loading.value = false
            }
        }
    }

    private fun clearLastConnection() {
        prefs.edit().apply {
            remove("last_host")
            remove("last_user")
            remove("last_pass")
            remove("last_share")
            remove("last_path")
        }.apply()
    }

    fun setSearchQuery(query: String) {
        currentSearchQuery = query
        applyFilterAndSort()
    }

    fun setSortMode(mode: FileModel.SortMode) {
        currentSortMode = mode
        applyFilterAndSort()
    }

    fun setViewMode(mode: FileModel.ViewMode) {
        currentViewMode = mode
        _viewMode.value = mode
    }

    private fun applyFilterAndSort() {
        val allFiles = _files.value ?: return
        
        val filtered = if (currentSearchQuery.isEmpty()) {
            allFiles
        } else {
            allFiles.filter { it.name.contains(currentSearchQuery, ignoreCase = true) }
        }

        val sorted = when (currentSortMode) {
            FileModel.SortMode.NAME_ASC -> filtered.sortedWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.name.lowercase() })
            FileModel.SortMode.NAME_DESC -> filtered.sortedWith(compareByDescending<FileModel> { it.isDirectory }.thenByDescending { it.name.lowercase() })
            FileModel.SortMode.DATE_ASC -> filtered.sortedWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.lastModified })
            FileModel.SortMode.DATE_DESC -> filtered.sortedWith(compareByDescending<FileModel> { it.isDirectory }.thenByDescending { it.lastModified })
            FileModel.SortMode.SIZE_ASC -> filtered.sortedWith(compareByDescending<FileModel> { it.isDirectory }.thenBy { it.size })
            FileModel.SortMode.SIZE_DESC -> filtered.sortedWith(compareByDescending<FileModel> { it.isDirectory }.thenByDescending { it.size })
        }
        
        _filteredFiles.value = sorted
    }

    private fun updateBreadcrumbs(path: String, share: String?) {
        val crumbs = mutableListOf<BreadcrumbItem>()
        crumbs.add(BreadcrumbItem("局域网 SMB", "", null))
        
        if (share != null) {
            crumbs.add(BreadcrumbItem(share, "", share))
            if (path.isNotEmpty()) {
                val segments = path.split('\\')
                var currentAccumulatedPath = ""
                for (segment in segments) {
                    if (segment.isEmpty()) continue
                    currentAccumulatedPath = if (currentAccumulatedPath.isEmpty()) segment else "$currentAccumulatedPath\\$segment"
                    crumbs.add(BreadcrumbItem(segment, currentAccumulatedPath, share))
                }
            }
        }
        _breadcrumbs.value = crumbs
    }

    fun navigateUp(): Boolean {
        when (_viewState.value) {
            ViewState.SERVERS -> return false
            ViewState.SHARES -> {
                _viewState.value = ViewState.SERVERS
                _connected.value = false
                _files.value = emptyList()
                updateBreadcrumbs("", null)
                clearLastConnection()
                return true
            }
            ViewState.FILES -> {
                val current = _currentPath.value ?: ""
                if (current.isEmpty()) {
                    _viewState.value = ViewState.SHARES
                    _currentShare.value = null
                    loadShares()
                    return true
                }
                val lastBackslash = current.lastIndexOf('\\')
                val parent = if (lastBackslash == -1) "" else current.substring(0, lastBackslash)
                loadFiles(parent)
                return true
            }
            else -> return false
        }
    }
    
    fun getParentPath(): String? {
        val current = _currentPath.value ?: return null
        if (current.isEmpty()) return null
        val lastBackslash = current.lastIndexOf('\\')
        return if (lastBackslash == -1) "" else current.substring(0, lastBackslash)
    }

    override fun onCleared() {
        super.onCleared()
        SmbManager.disconnect()
    }
}
