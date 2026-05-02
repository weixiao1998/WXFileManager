package dev.weixiao.wxfilemanager.ui.viewer

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.weixiao.wxfilemanager.adapter.FileAdapter
import dev.weixiao.wxfilemanager.databinding.ActivitySearchBinding
import dev.weixiao.wxfilemanager.model.FileModel
import dev.weixiao.wxfilemanager.utils.SafManager
import dev.weixiao.wxfilemanager.utils.SmbManager
import java.io.File

import android.os.Handler
import android.os.Looper
import androidx.appcompat.widget.SearchView

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: FileAdapter
    private var searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val path = intent.getStringExtra("path") ?: ""
        val isSmb = intent.getBooleanExtra("isSmb", false)

        adapter = FileAdapter { file ->
            handleFileClick(file)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { performSearch(path, it, isSmb) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                searchRunnable = Runnable {
                    newText?.let { if (it.length >= 1) performSearch(path, it, isSmb) }
                }
                searchHandler.postDelayed(searchRunnable!!, 500)
                return true
            }
        })
        
        // Auto focus search view
        binding.searchView.requestFocus()
    }

    private fun performSearch(path: String, query: String, isSmb: Boolean) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                if (isSmb) {
                    SmbManager.searchRecursive(path, query)
                } else {
                    if (SafManager.isRestrictedPath(path)) {
                        SafManager.searchRecursive(this@SearchActivity, path, query)
                    } else {
                        searchLocalRecursive(File(path), query)
                    }
                }
            }
            adapter.submitList(results)
            binding.progressBar.visibility = View.GONE
            binding.emptyText.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun searchLocalRecursive(dir: File, query: String): List<FileModel> {
        val results = mutableListOf<FileModel>()
        dir.walkTopDown().forEach { file ->
            if (file.name.contains(query, ignoreCase = true) && file.absolutePath != dir.absolutePath) {
                val isDirectory = file.isDirectory
                val extension = file.extension.lowercase()
                val mimeType = if (isDirectory) null else {
                    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                }
                results.add(FileModel(
                    name = file.name,
                    path = file.absolutePath,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0 else file.length(),
                    lastModified = file.lastModified(),
                    mimeType = mimeType,
                    isSmb = false
                ))
            }
        }
        return results
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