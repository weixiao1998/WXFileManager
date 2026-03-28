package top.weixiaoweb.wxfilemanager.ui.smb

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.weixiaoweb.wxfilemanager.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import top.weixiaoweb.wxfilemanager.adapter.FileAdapter
import top.weixiaoweb.wxfilemanager.databinding.DialogSmbConnectBinding
import top.weixiaoweb.wxfilemanager.databinding.DialogViewSettingsBinding
import top.weixiaoweb.wxfilemanager.databinding.FragmentFileListBinding
import top.weixiaoweb.wxfilemanager.model.FileModel
import top.weixiaoweb.wxfilemanager.utils.SmbManager
import top.weixiaoweb.wxfilemanager.viewmodel.SmbViewModel

class SmbFragment : Fragment() {

    private var _binding: FragmentFileListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SmbViewModel by viewModels()
    private lateinit var adapter: FileAdapter
    private val loadingRunnable = Runnable { binding.loadingIndicator.visibility = View.VISIBLE }

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
            handleItemClick(file)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        viewModel.filteredFiles.observe(viewLifecycleOwner) { files ->
            if (viewModel.viewState.value != SmbViewModel.ViewState.SERVERS) {
                adapter.submitList(files)
                binding.emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewModel.viewMode.observe(viewLifecycleOwner) { mode ->
            adapter.setViewMode(mode)
            updateRecyclerViewLayout(mode)
        }

        viewModel.servers.observe(viewLifecycleOwner) { servers ->
            if (viewModel.viewState.value == SmbViewModel.ViewState.SERVERS) {
                showServers(servers)
            }
        }

        viewModel.viewState.observe(viewLifecycleOwner) { state ->
            when (state) {
                SmbViewModel.ViewState.SERVERS -> {
                    showServers(viewModel.servers.value ?: emptyList())
                }
                SmbViewModel.ViewState.SHARES, SmbViewModel.ViewState.FILES -> {
                    adapter.submitList(viewModel.filteredFiles.value)
                    binding.emptyText.visibility = if (viewModel.filteredFiles.value?.isEmpty() == true) View.VISIBLE else View.GONE
                }
                else -> {}
            }
        }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            if (!loading) {
                binding.swipeRefresh.isRefreshing = false
                // If loading finishes, remove any pending show-indicator callbacks
                binding.loadingIndicator.removeCallbacks(loadingRunnable)
                binding.loadingIndicator.visibility = View.GONE
            } else {
                // Delay showing the indicator to avoid flickering for fast loads
                binding.loadingIndicator.removeCallbacks(loadingRunnable)
                binding.loadingIndicator.postDelayed(loadingRunnable, 300)
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        }

        viewModel.breadcrumbs.observe(viewLifecycleOwner) { crumbs ->
            updateBreadcrumbUI(crumbs)
        }

        arguments?.getString("start_path")?.let {
            // Check if it's a share or a deeper path
            // This is a bit complex for SMB, but let's assume it's a file path within a share for now
            // Or if it's just a share name
            viewModel.loadFiles(it)
            arguments?.remove("start_path")
        }

        binding.swipeRefresh.setOnRefreshListener {
            when (viewModel.viewState.value) {
                SmbViewModel.ViewState.SERVERS -> {
                    binding.swipeRefresh.isRefreshing = false
                }
                SmbViewModel.ViewState.SHARES -> {
                    binding.swipeRefresh.isRefreshing = false
                }
                SmbViewModel.ViewState.FILES -> {
                    viewModel.loadFiles(viewModel.currentPath.value ?: "")
                }
                else -> binding.swipeRefresh.isRefreshing = false
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.navigateUp()) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    fun showSearchDialog() {
        val intent = android.content.Intent(requireContext(), top.weixiaoweb.wxfilemanager.ui.viewer.SearchActivity::class.java).apply {
            putExtra("path", viewModel.currentPath.value ?: "")
            putExtra("isSmb", true)
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
        dialogView.btnClearCache.setOnClickListener {
            com.bumptech.glide.Glide.get(requireContext()).clearMemory()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.bumptech.glide.Glide.get(requireContext()).clearDiskCache()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(context, "缓存已清理", Toast.LENGTH_SHORT).show()
                    updateCacheSize(dialogView)
                }
            }
        }

        dialog.show()
    }

    private fun updateCacheSize(binding: top.weixiaoweb.wxfilemanager.databinding.DialogViewSettingsBinding) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val size = getDirSize(requireContext().cacheDir) + (requireContext().externalCacheDir?.let { getDirSize(it) } ?: 0L)
            val sizeStr = android.text.format.Formatter.formatFileSize(requireContext(), size)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                binding.tvCacheSize.text = "缩略图缓存: $sizeStr"
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

    private fun showSortAndViewModeMenu(view: View) {
        // Redundant
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

    private fun handleItemClick(file: FileModel) {
        when (viewModel.viewState.value) {
            SmbViewModel.ViewState.SERVERS -> {
                if (file.itemType == FileModel.ItemType.ADD_BUTTON) {
                    showConnectDialog()
                } else {
                    val server = viewModel.servers.value?.find { "${it.user}@${it.host}" == file.name }
                    if (server != null) {
                        viewModel.connect(server)
                    }
                }
            }
            SmbViewModel.ViewState.SHARES -> {
                viewModel.selectShare(file.name)
            }
            SmbViewModel.ViewState.FILES -> {
                if (file.isDirectory) {
                    viewModel.loadFiles(file.path)
                } else {
                    handleFileClick(file)
                }
            }
            else -> {}
        }
    }

    private fun showServers(servers: List<top.weixiaoweb.wxfilemanager.model.SmbServer>) {
        val serverFiles = servers.map { server ->
            FileModel(
                name = "${server.user}@${server.host}",
                path = server.host,
                isDirectory = true,
                size = 0,
                lastModified = 0,
                isSmb = true,
                smbUrl = "smb://${server.host}",
                itemType = FileModel.ItemType.SERVER
            )
        }.toMutableList()
        
        serverFiles.add(0, FileModel(
            name = "+ 添加新服务器",
            path = "ADD_SERVER",
            isDirectory = false,
            size = 0,
            lastModified = 0,
            isSmb = true,
            itemType = FileModel.ItemType.ADD_BUTTON
        ))

        adapter.submitList(serverFiles)
        binding.emptyText.visibility = View.GONE
    }

    private fun updateBreadcrumbUI(crumbs: List<SmbViewModel.BreadcrumbItem>) {
        binding.breadcrumbContainer.removeAllViews()
        for (i in crumbs.indices) {
            val crumb = crumbs[i]
            val crumbBinding = top.weixiaoweb.wxfilemanager.databinding.ItemBreadcrumbBinding.inflate(layoutInflater, binding.breadcrumbContainer, false)
            crumbBinding.breadcrumbName.text = crumb.name
            crumbBinding.breadcrumbDivider.visibility = if (i == crumbs.size - 1) View.GONE else View.VISIBLE
            
            crumbBinding.root.setOnClickListener {
                if (crumb.share == null) {
                    // Back to server list
                    viewModel.navigateUp() // This is a bit simplified
                } else if (crumb.path.isEmpty()) {
                    viewModel.selectShare(crumb.share)
                } else {
                    viewModel.loadFiles(crumb.path)
                }
            }
            binding.breadcrumbContainer.addView(crumbBinding.root)
        }
        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun showConnectDialog() {
        val dialogBinding = DialogSmbConnectBinding.inflate(layoutInflater)
        viewModel.resetConnectionEvent() // Reset previous success state
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("连接 SMB 服务器")
            .setView(dialogBinding.root)
            .setPositiveButton("连接", null) // Set to null to prevent auto-dismiss
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val host = dialogBinding.etHost.text.toString()
                val user = dialogBinding.etUser.text.toString()
                val pass = dialogBinding.etPass.text.toString()
                
                if (host.isBlank()) {
                    Toast.makeText(context, "请输入主机地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                viewModel.connect(host, user, pass, null)
            }
        }

        // Observe connection success to dismiss dialog
        viewModel.connectSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                dialog.dismiss()
                // Reset success state to prevent dismissing next time dialog shows
                // Actually we should probably use a single-event LiveData pattern, 
                // but for now we can just observe and check the value.
            }
        }

        dialog.show()
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
            val intent = android.content.Intent(requireContext(), top.weixiaoweb.wxfilemanager.ui.viewer.VideoPlayerActivity::class.java).apply {
                putExtra("name", file.name)
                putExtra("path", file.path)
                putExtra("isSmb", true)
            }
            startActivity(intent)
        } else {
            Toast.makeText(context, "Opening SMB file: ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.viewState.value != SmbViewModel.ViewState.SERVERS) {
            CoroutineScope(Dispatchers.IO).launch {
                if (!SmbManager.checkAndReconnect()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "SMB连接已断开，请刷新", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
