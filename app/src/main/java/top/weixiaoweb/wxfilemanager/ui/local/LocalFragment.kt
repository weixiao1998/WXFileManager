package top.weixiaoweb.wxfilemanager.ui.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.weixiaoweb.wxfilemanager.databinding.DialogViewSettingsBinding
import com.google.android.material.tabs.TabLayout
import top.weixiaoweb.wxfilemanager.R
import top.weixiaoweb.wxfilemanager.adapter.FileAdapter
import top.weixiaoweb.wxfilemanager.databinding.FragmentFileListBinding
import android.util.Log
import top.weixiaoweb.wxfilemanager.model.FileModel
import top.weixiaoweb.wxfilemanager.utils.SafManager
import top.weixiaoweb.wxfilemanager.viewmodel.LocalViewModel

class LocalFragment : Fragment() {

    private var _binding: FragmentFileListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LocalViewModel by viewModels()
    private lateinit var adapter: FileAdapter
    private val loadingRunnable = Runnable { binding.loadingIndicator.visibility = View.VISIBLE }
    
    private val scrollPositions = mutableMapOf<String, Int>()

    private val safLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            val path = viewModel.requestPermissionPath.value
            if (uri != null && path != null) {
                SafManager.saveUriPermission(requireContext(), path, uri)
                viewModel.onPermissionGranted(path)
            }
        } else {
            viewModel.clearPermissionRequest()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFileListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FileAdapter { file ->
            if (file.isDirectory) {
                saveScrollPosition()
                viewModel.loadFiles(file.path)
            } else {
                handleFileClick(file)
            }
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        viewModel.filteredFiles.observe(viewLifecycleOwner) { files ->
            adapter.submitList(files) {
                binding.emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
                if (!viewModel.loading.value!!) {
                    restoreScrollPosition()
                }
            }
        }

        viewModel.viewMode.observe(viewLifecycleOwner) { mode ->
            adapter.setViewMode(mode)
            updateRecyclerViewLayout(mode)
        }
        
        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            if (!loading) {
                binding.swipeRefresh.isRefreshing = false
                binding.loadingIndicator.removeCallbacks(loadingRunnable)
                binding.loadingIndicator.visibility = View.GONE
            } else {
                binding.loadingIndicator.removeCallbacks(loadingRunnable)
                binding.loadingIndicator.postDelayed(loadingRunnable, 300)
            }
        }

        viewModel.breadcrumbs.observe(viewLifecycleOwner) { crumbs ->
            updateBreadcrumbUI(crumbs)
        }

        viewModel.requestPermissionPath.observe(viewLifecycleOwner) { path ->
            if (path != null) {
                showSafPermissionDialog(path)
            }
        }

        arguments?.getString("start_path")?.let {
            viewModel.loadFiles(it)
            arguments?.remove("start_path")
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadFiles(viewModel.currentPath.value ?: "")
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveScrollPosition()
                if (!viewModel.navigateUp()) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showSafPermissionDialog(path: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("需要权限")
            .setMessage("Android 系统限制访问 $path 目录。请在接下来的界面中点击“使用此文件夹”并授予权限。")
            .setPositiveButton("去授权") { _, _ ->
                val intent = SafManager.getActionOpenDocumentTreeIntent(path)
                if (intent != null) {
                    safLauncher.launch(intent)
                } else {
                    Toast.makeText(context, "无法发起授权请求", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                viewModel.clearPermissionRequest()
            }
            .setCancelable(false)
            .show()
    }

    fun showSearchDialog() {
        val intent = android.content.Intent(requireContext(), top.weixiaoweb.wxfilemanager.ui.viewer.SearchActivity::class.java).apply {
            putExtra("path", viewModel.currentPath.value ?: "")
            putExtra("isSmb", false)
        }
        startActivity(intent)
    }

    fun showViewSettingsDialog() {
        val dialogView = DialogViewSettingsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView.root)
            .create()

        // Set current selections
        when (viewModel.viewMode.value) {
            FileModel.ViewMode.LIST_SMALL -> dialogView.chipListSmall.isChecked = true
            FileModel.ViewMode.LIST_MEDIUM -> dialogView.chipListMedium.isChecked = true
            FileModel.ViewMode.LIST_LARGE -> dialogView.chipListLarge.isChecked = true
            FileModel.ViewMode.GRID_SMALL -> dialogView.chipGridSmall.isChecked = true
            FileModel.ViewMode.GRID_MEDIUM -> dialogView.chipGridMedium.isChecked = true
            FileModel.ViewMode.GRID_LARGE -> dialogView.chipGridLarge.isChecked = true
            else -> {}
        }

        val viewModeListener = com.google.android.material.chip.ChipGroup.OnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@OnCheckedStateChangeListener
            
            // Uncheck other group to simulate single selection across both groups
            if (group == dialogView.chipGroupViewList) {
                dialogView.chipGroupViewGrid.clearCheck()
            } else {
                dialogView.chipGroupViewList.clearCheck()
            }

            val mode = when (checkedId) {
                R.id.chip_list_small -> FileModel.ViewMode.LIST_SMALL
                R.id.chip_list_medium -> FileModel.ViewMode.LIST_MEDIUM
                R.id.chip_list_large -> FileModel.ViewMode.LIST_LARGE
                R.id.chip_grid_small -> FileModel.ViewMode.GRID_SMALL
                R.id.chip_grid_medium -> FileModel.ViewMode.GRID_MEDIUM
                R.id.chip_grid_large -> FileModel.ViewMode.GRID_LARGE
                else -> null
            }
            mode?.let { viewModel.setViewMode(it) }
        }

        dialogView.chipGroupViewList.setOnCheckedStateChangeListener(viewModeListener)
        dialogView.chipGroupViewGrid.setOnCheckedStateChangeListener(viewModeListener)

        dialogView.chipGroupSort.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val sort = when (checkedId) {
                R.id.chip_sort_name_asc -> FileModel.SortMode.NAME_ASC
                R.id.chip_sort_name_desc -> FileModel.SortMode.NAME_DESC
                R.id.chip_sort_date_desc -> FileModel.SortMode.DATE_DESC
                R.id.chip_sort_date_asc -> FileModel.SortMode.DATE_ASC
                R.id.chip_sort_size_desc -> FileModel.SortMode.SIZE_DESC
                R.id.chip_sort_size_asc -> FileModel.SortMode.SIZE_ASC
                else -> null
            }
            sort?.let { viewModel.setSortMode(it) }
        }

        // Cache Management
        updateCacheSize(dialogView)
        
        dialogView.btnClearThumbnailCache.setOnClickListener {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.bumptech.glide.Glide.get(requireContext()).clearDiskCache()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.bumptech.glide.Glide.get(requireContext()).clearMemory()
                    Toast.makeText(context, "缩略图缓存已清理", Toast.LENGTH_SHORT).show()
                    updateCacheSize(dialogView)
                }
            }
        }
        
        dialogView.btnClearTempCache.setOnClickListener {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val cacheDir = requireContext().cacheDir
                val glideCacheDir = java.io.File(cacheDir, "image_manager_disk_cache")
                
                cacheDir.listFiles()?.forEach { file ->
                    if (file.name != ".nomedia" && file.name != "image_manager_disk_cache") {
                        deleteRecursively(file)
                    }
                }
                
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(context, "临时文件已清理", Toast.LENGTH_SHORT).show()
                    updateCacheSize(dialogView)
                }
            }
        }

        dialog.show()
    }

    private fun updateCacheSize(binding: top.weixiaoweb.wxfilemanager.databinding.DialogViewSettingsBinding) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val cacheDir = requireContext().cacheDir
            val glideCacheDir = java.io.File(cacheDir, "image_manager_disk_cache")
            
            val thumbnailSize = if (glideCacheDir.exists()) getDirSize(glideCacheDir) else 0L
            
            var tempSize = 0L
            cacheDir.listFiles()?.forEach { file ->
                if (file.name != ".nomedia" && file.name != "image_manager_disk_cache") {
                    tempSize += if (file.isDirectory) getDirSize(file) else file.length()
                }
            }
            
            val thumbnailSizeStr = android.text.format.Formatter.formatFileSize(requireContext(), thumbnailSize)
            val tempSizeStr = android.text.format.Formatter.formatFileSize(requireContext(), tempSize)
            
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                binding.tvThumbnailCacheSize.text = "缩略图缓存: $thumbnailSizeStr"
                binding.tvTempCacheSize.text = "临时文件: $tempSizeStr"
            }
        }
    }

    private fun getDirSize(dir: java.io.File): Long {
        var size = 0L
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getDirSize(file) else file.length()
        }
        return size
    }

    private fun deleteRecursively(file: java.io.File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private fun showSortAndViewModeMenu(view: View) {
        // Redundant now, can be removed later
    }

    private fun updateRecyclerViewLayout(mode: FileModel.ViewMode) {
        val isGrid = mode.name.startsWith("GRID")
        if (isGrid) {
            val spanCount = when (mode) {
                FileModel.ViewMode.GRID_SMALL -> 5
                FileModel.ViewMode.GRID_MEDIUM -> 4
                FileModel.ViewMode.GRID_LARGE -> 3
                else -> 4
            }
            binding.recyclerView.layoutManager = GridLayoutManager(context, spanCount)
        } else {
            binding.recyclerView.layoutManager = LinearLayoutManager(context)
        }
    }

    private fun updateBreadcrumbUI(crumbs: List<LocalViewModel.BreadcrumbItem>) {
        binding.breadcrumbContainer.removeAllViews()
        for (i in crumbs.indices) {
            val crumb = crumbs[i]
            val crumbBinding = top.weixiaoweb.wxfilemanager.databinding.ItemBreadcrumbBinding.inflate(layoutInflater, binding.breadcrumbContainer, false)
            crumbBinding.breadcrumbName.text = crumb.name
            crumbBinding.breadcrumbDivider.visibility = if (i == crumbs.size - 1) View.GONE else View.VISIBLE
            
            crumbBinding.root.setOnClickListener {
                saveScrollPosition()
                viewModel.loadFiles(crumb.path)
            }
            binding.breadcrumbContainer.addView(crumbBinding.root)
        }
        
        // Auto scroll to the end
        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun handleFileClick(file: FileModel) {
        if (file.isImage) {
            val imageList = viewModel.filteredFiles.value?.filter { it.isImage } ?: emptyList()
            val position = imageList.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
            
            top.weixiaoweb.wxfilemanager.utils.MediaRepository.setImageList(imageList)
            
            val intent = android.content.Intent(requireContext(), top.weixiaoweb.wxfilemanager.ui.viewer.ImageViewerActivity::class.java).apply {
                putExtra("position", position)
            }
            startActivity(intent)
        } else if (file.isVideo) {
            val intent = android.content.Intent(requireContext(), top.weixiaoweb.wxfilemanager.ui.viewer.VlcVideoPlayerActivity::class.java).apply {
                putExtra("name", file.name)
                putExtra("path", file.path)
                putExtra("isSmb", false)
                putExtra("size", file.size)
                putExtra("lastModified", file.lastModified)
            }
            startActivity(intent)
        } else {
            Toast.makeText(context, "Opening file: ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun saveScrollPosition() {
        val currentPath = viewModel.currentPath.value ?: return
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        if (position != RecyclerView.NO_POSITION) {
            scrollPositions[currentPath] = position
            Log.d("LocalFragment", "saveScrollPosition: path=$currentPath, position=$position")
        }
    }
    
    private fun restoreScrollPosition() {
        val currentPath = viewModel.currentPath.value ?: return
        val position = scrollPositions[currentPath]
        Log.d("LocalFragment", "restoreScrollPosition: path=$currentPath, savedPosition=$position, allPositions=$scrollPositions")
        if (position != null) {
            val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager ?: return
            layoutManager.scrollToPositionWithOffset(position, 0)
            Log.d("LocalFragment", "restoreScrollPosition done: position=$position")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
