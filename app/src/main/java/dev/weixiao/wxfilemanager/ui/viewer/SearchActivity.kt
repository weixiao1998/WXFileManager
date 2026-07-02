package dev.weixiao.wxfilemanager.ui.viewer

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dev.weixiao.wxfilemanager.adapter.FileAdapter
import dev.weixiao.wxfilemanager.databinding.ActivitySearchBinding
import dev.weixiao.wxfilemanager.model.FileModel
import dev.weixiao.wxfilemanager.utils.SafManager
import dev.weixiao.wxfilemanager.utils.SmbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: FileAdapter

    /** 搜索关键字状态流，按键变更直接写入此流，由下游 debounce + flatMapLatest 处理 */
    private val queryFlow = MutableStateFlow("")

    private var rootPath: String = ""
    private var isSmb: Boolean = false

    /** 是否已在当前关键字下弹过"达到上限"提示，避免重复 Toast */
    private var limitToastShown: Boolean = false

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        rootPath = intent.getStringExtra("path") ?: ""
        isSmb = intent.getBooleanExtra("isSmb", false)

        adapter = FileAdapter { file ->
            handleFileClick(file)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                queryFlow.value = query.orEmpty()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                queryFlow.value = newText.orEmpty()
                return true
            }
        })

        binding.searchView.requestFocus()

        // 通过 collectLatest + flatMapLatest 保证：每次新关键字进来都会取消上一次未完成的搜索任务，
        // 不会出现并发多次 SMB 递归把连接打爆的情况。
        // 对增量搜索结果使用 sample(200ms) 节流，避免频繁 submitList 卡 UI。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                queryFlow
                    .debounce(300)
                    .distinctUntilChanged()
                    .filter { it.isNotEmpty() }
                    .flatMapLatest { query ->
                        // 每次关键字切换重置"已提示上限"标记
                        limitToastShown = false
                        searchAsFlow(rootPath, query, isSmb)
                            .flowOn(Dispatchers.IO)
                            .onStart { showLoading(true) }
                            .conflate()
                            .sample(200)
                    }
                    .collectLatest { results ->
                        adapter.submitList(results)
                        showLoading(false)
                        binding.emptyText.visibility =
                            if (results.isEmpty()) View.VISIBLE else View.GONE
                        // 命中数达到上限时提示一次（同一次搜索内只提示一次）
                        if (isSmb && !limitToastShown &&
                            results.size >= SmbManager.SEARCH_MAX_RESULTS) {
                            limitToastShown = true
                            android.widget.Toast.makeText(
                                this@SearchActivity,
                                "结果超过 ${SmbManager.SEARCH_MAX_RESULTS} 条，已停止搜索，请细化关键字",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
            }
        }
    }

    private fun showLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            binding.emptyText.visibility = View.GONE
        }
    }

    /**
     * 把搜索调用包装成 Flow，便于配合 flatMapLatest 实现自动取消。
     * SMB 分支使用 [SmbManager.searchRecursiveFlow] 的增量结果，边搜边刷新。
     * 本地/SAF 分支保持一次性 emit（本地遍历本身很快）。
     */
    private fun searchAsFlow(path: String, query: String, isSmb: Boolean) = flow {
        if (isSmb) {
            SmbManager.searchRecursiveFlow(path, query).collect { emit(it) }
        } else {
            val results = if (SafManager.isRestrictedPath(path)) {
                SafManager.searchRecursive(this@SearchActivity, path, query)
            } else {
                searchLocalRecursive(File(path), query)
            }
            emit(results)
        }
    }

    /**
     * 本地文件递归搜索。每访问一个文件前检查协程是否仍 active，
     * 若已被 flatMapLatest 取消则立即退出，避免无谓 IO。
     */
    private suspend fun searchLocalRecursive(dir: File, query: String): List<FileModel> = coroutineScope {
        val results = mutableListOf<FileModel>()
        for (file in dir.walkTopDown()) {
            ensureActive()
            if (file.name.contains(query, ignoreCase = true) && file.absolutePath != dir.absolutePath) {
                val isDirectory = file.isDirectory
                val extension = file.extension.lowercase()
                val mimeType = if (isDirectory) null else {
                    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                }
                results.add(
                    FileModel(
                        name = file.name,
                        path = file.absolutePath,
                        isDirectory = isDirectory,
                        size = if (isDirectory) 0 else file.length(),
                        lastModified = file.lastModified(),
                        mimeType = mimeType,
                        isSmb = false
                    )
                )
            }
        }
        results
    }

    private fun handleFileClick(file: FileModel) {
        if (file.isDirectory) {
            val intent = android.content.Intent(this, dev.weixiao.wxfilemanager.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("navigate_path", file.path)
                putExtra("is_smb", file.isSmb)
            }
            startActivity(intent)
            finish()
            return
        }

        if (file.isImage) {
            val imageList = adapter.currentList.filter { it.isImage }
            val position = imageList.indexOfFirst { it.path == file.path }.coerceAtLeast(0)

            val intent = android.content.Intent(this, ImageViewerActivity::class.java).apply {
                putParcelableArrayListExtra("imageList", ArrayList(imageList))
                putExtra("position", position)
            }
            startActivity(intent)
        } else if (file.isVideo) {
            val intent = android.content.Intent(this, VlcVideoPlayerActivity::class.java).apply {
                putExtra("name", file.name)
                putExtra("path", file.path)
                putExtra("isSmb", file.isSmb)
            }
            startActivity(intent)
        } else if (file.isText) {
            val intent = android.content.Intent(this, TextViewerActivity::class.java).apply {
                putExtra("name", file.name)
                putExtra("path", file.path)
                putExtra("isSmb", file.isSmb)
                putExtra("size", file.size)
            }
            startActivity(intent)
        } else if (file.isSmb) {
            showSmbFileOpenDialog(file)
        } else {
            dev.weixiao.wxfilemanager.utils.FileOpener.openWithExternalApp(this, file)
        }
    }

    private fun showSmbFileOpenDialog(file: FileModel) {
        val sizeStr = android.text.format.Formatter.formatFileSize(this, file.size)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage("此文件类型暂不支持内置打开（大小: $sizeStr）\n是否下载到本地后用其他应用打开？")
            .setPositiveButton("下载并打开") { _, _ ->
                downloadAndOpenSmbFile(file)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadAndOpenSmbFile(file: FileModel) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                dev.weixiao.wxfilemanager.utils.FileOpener.downloadSmbFile(this@SearchActivity, file)
            }
            binding.progressBar.visibility = View.GONE
            when (result) {
                is dev.weixiao.wxfilemanager.utils.FileOpener.DownloadResult.Success -> {
                    dev.weixiao.wxfilemanager.utils.FileOpener.openCachedFileWithExternalApp(
                        this@SearchActivity, result.file, file.mimeType
                    )
                }
                is dev.weixiao.wxfilemanager.utils.FileOpener.DownloadResult.InsufficientSpace -> {
                    val needStr = android.text.format.Formatter.formatFileSize(this@SearchActivity, result.required)
                    val haveStr = android.text.format.Formatter.formatFileSize(this@SearchActivity, result.available)
                    android.widget.Toast.makeText(
                        this@SearchActivity,
                        "缓存空间不足（需要 $needStr，剩余 $haveStr），请清理后重试",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                is dev.weixiao.wxfilemanager.utils.FileOpener.DownloadResult.Failure -> {
                    android.widget.Toast.makeText(
                        this@SearchActivity, "下载失败，请检查网络连接", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
