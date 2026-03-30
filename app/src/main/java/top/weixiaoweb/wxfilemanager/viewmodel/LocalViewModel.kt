package top.weixiaoweb.wxfilemanager.viewmodel

import android.app.Application
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.weixiaoweb.wxfilemanager.model.FileModel
import top.weixiaoweb.wxfilemanager.utils.SafManager
import java.io.File

class LocalViewModel(application: Application) : AndroidViewModel(application) {

    private val _files = MutableLiveData<List<FileModel>>()
    val files: LiveData<List<FileModel>> = _files

    private val _filteredFiles = MutableLiveData<List<FileModel>>()
    val filteredFiles: LiveData<List<FileModel>> = _filteredFiles

    private val _currentPath = MutableLiveData<String>()
    val currentPath: LiveData<String> = _currentPath

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _breadcrumbs = MutableLiveData<List<BreadcrumbItem>>()
    val breadcrumbs: LiveData<List<BreadcrumbItem>> = _breadcrumbs

    private val _requestPermissionPath = MutableLiveData<String?>()
    val requestPermissionPath: LiveData<String?> = _requestPermissionPath

    private var currentSearchQuery = ""
    private var currentSortMode = FileModel.SortMode.NAME_ASC
    private var currentViewMode = FileModel.ViewMode.LIST_MEDIUM

    private val _viewMode = MutableLiveData(FileModel.ViewMode.LIST_MEDIUM)
    val viewMode: LiveData<FileModel.ViewMode> = _viewMode

    data class BreadcrumbItem(val name: String, val path: String)

    init {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        _currentPath.value = rootPath
        loadFiles(rootPath)
    }

    fun loadFiles(path: String) {
        if (SafManager.needsPermission(getApplication(), path)) {
            _requestPermissionPath.value = path
            return
        }

        _loading.value = true
        _currentPath.value = path
        updateBreadcrumbs(path)
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                if (SafManager.isRestrictedPath(path)) {
                    SafManager.listFiles(getApplication(), path)
                } else {
                    val dir = File(path)
                    val files = dir.listFiles()?.toList() ?: emptyList()
                    files.map { file ->
                        val mimeType = if (file.isDirectory) null else {
                            val extension = file.extension.lowercase()
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                        }
                        FileModel(
                            name = file.name,
                            path = file.absolutePath,
                            isDirectory = file.isDirectory,
                            size = if (file.isDirectory) 0 else file.length(),
                            lastModified = file.lastModified(),
                            mimeType = mimeType
                        )
                    }
                }
            }
            _files.value = list
            applyFilterAndSort()
            _loading.value = false
        }
    }

    fun onPermissionGranted(path: String) {
        _requestPermissionPath.value = null
        loadFiles(path)
    }

    fun clearPermissionRequest() {
        _requestPermissionPath.value = null
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

    private fun updateBreadcrumbs(path: String) {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val crumbs = mutableListOf<BreadcrumbItem>()
        
        // Always start with "Home" (External Storage)
        crumbs.add(BreadcrumbItem("存储主目录", rootPath))
        
        if (path != rootPath && path.startsWith(rootPath)) {
            val subPath = path.substring(rootPath.length).trimStart('/')
            if (subPath.isNotEmpty()) {
                val segments = subPath.split('/')
                var currentAccumulatedPath = rootPath
                for (segment in segments) {
                    if (segment.isEmpty()) continue
                    currentAccumulatedPath += "/$segment"
                    crumbs.add(BreadcrumbItem(segment, currentAccumulatedPath))
                }
            }
        }
        _breadcrumbs.value = crumbs
    }

    fun navigateUp(): Boolean {
        val current = _currentPath.value ?: return false
        val parent = File(current).parentFile
        if (parent != null && parent.canRead()) {
            loadFiles(parent.absolutePath)
            return true
        }
        return false
    }
    
    fun getParentPath(): String? {
        val current = _currentPath.value ?: return null
        return File(current).parentFile?.absolutePath
    }
}
