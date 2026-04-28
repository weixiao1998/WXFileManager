package top.weixiaoweb.wxfilemanager.adapter

import android.text.TextUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import top.weixiaoweb.wxfilemanager.R
import top.weixiaoweb.wxfilemanager.databinding.ItemFileBinding
import top.weixiaoweb.wxfilemanager.model.FileModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.recyclerview.widget.GridLayoutManager
import top.weixiaoweb.wxfilemanager.viewmodel.LocalViewModel

class FileAdapter(private val onItemClick: (FileModel) -> Unit) :
    ListAdapter<FileModel, FileAdapter.FileViewHolder>(FileDiffCallback()) {

    private var currentViewMode = FileModel.ViewMode.LIST_MEDIUM

    fun setViewMode(viewMode: FileModel.ViewMode) {
        this.currentViewMode = viewMode
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(private val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(file: FileModel) {
            binding.fileName.text = file.name

            // Cancel any pending Glide request to prevent stale thumbnails on recycled views
            Glide.with(binding.fileIcon).clear(binding.fileIcon)

            // Adjust sizes based on view mode
            val iconSize = when (currentViewMode) {
                FileModel.ViewMode.LIST_SMALL, FileModel.ViewMode.GRID_SMALL -> 32
                FileModel.ViewMode.LIST_MEDIUM, FileModel.ViewMode.GRID_MEDIUM -> 48
                FileModel.ViewMode.LIST_LARGE, FileModel.ViewMode.GRID_LARGE -> 72
            }
            
            val density = binding.root.context.resources.displayMetrics.density
            val pixelSize = (iconSize * density).toInt()
            
            binding.fileIcon.layoutParams.width = pixelSize
            binding.fileIcon.layoutParams.height = pixelSize

            val isGrid = currentViewMode.name.startsWith("GRID")
            val lp = binding.fileName.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val iconLp = binding.fileIcon.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val infoLp = binding.fileInfo.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams

            // Reset common constraints to avoid reuse issues
            lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.topToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.verticalChainStyle = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            lp.verticalBias = 0.5f
            
            iconLp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            iconLp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            iconLp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            iconLp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET

            if (isGrid) {
                binding.fileInfo.visibility = android.view.View.GONE
                binding.fileName.maxLines = 2
                binding.fileName.ellipsize = TextUtils.TruncateAt.END
                binding.fileName.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                
                // Use vertical constraints for grid
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.topToBottom = binding.fileIcon.id
                lp.marginStart = 0
                lp.topMargin = (4 * density).toInt()

                iconLp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.horizontalBias = 0.5f
            } else {
                binding.fileName.textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
                
                // For medium and large list views, allow 2 lines for filename if needed
                if (currentViewMode == FileModel.ViewMode.LIST_MEDIUM || currentViewMode == FileModel.ViewMode.LIST_LARGE) {
                    binding.fileName.maxLines = 2
                    binding.fileName.ellipsize = TextUtils.TruncateAt.END
                } else {
                    binding.fileName.maxLines = 1
                    binding.fileName.ellipsize = TextUtils.TruncateAt.MIDDLE
                }
                
                // Use horizontal constraints for list
                lp.startToEnd = binding.fileIcon.id
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.topToTop = binding.fileIcon.id
                lp.bottomToTop = binding.fileInfo.id
                lp.verticalChainStyle = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.CHAIN_PACKED
                lp.marginStart = (16 * density).toInt()
                lp.topMargin = 0
                lp.bottomMargin = 0

                infoLp.topToBottom = binding.fileName.id
                infoLp.bottomToBottom = binding.fileIcon.id
                infoLp.startToStart = binding.fileName.id
                infoLp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                infoLp.topMargin = (2 * density).toInt() // Small gap between name and info

                iconLp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                iconLp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                iconLp.horizontalBias = 0f

                if (file.itemType == FileModel.ItemType.FILE) {
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified))
                    val sizeStr = if (file.isDirectory) "文件夹" else Formatter.formatFileSize(binding.root.context, file.size)
                    binding.fileInfo.text = "$date | $sizeStr"
                    binding.fileInfo.visibility = android.view.View.VISIBLE
                } else if (file.itemType == FileModel.ItemType.SERVER) {
                    binding.fileInfo.text = "SMB 服务器 | ${file.path}"
                    binding.fileInfo.visibility = android.view.View.VISIBLE
                } else if (file.itemType == FileModel.ItemType.SHARE) {
                    binding.fileInfo.text = "共享目录"
                    binding.fileInfo.visibility = android.view.View.VISIBLE
                } else {
                    binding.fileInfo.visibility = android.view.View.GONE
                }
            }
            binding.fileName.layoutParams = lp
            binding.fileIcon.layoutParams = iconLp
            binding.fileInfo.layoutParams = infoLp

            val iconRes = when {
                file.itemType == FileModel.ItemType.SERVER -> R.drawable.ic_server
                file.itemType == FileModel.ItemType.SHARE -> R.drawable.ic_folder
                file.itemType == FileModel.ItemType.ADD_BUTTON -> R.drawable.ic_add
                file.isDirectory -> R.drawable.ic_folder
                file.isImage -> R.drawable.ic_image
                // Check extension for video even if isVideo is false (like AVI/WMV)
                file.isVideo || file.name.lowercase(Locale.getDefault()).endsWith(".avi") || file.name.lowercase(Locale.getDefault()).endsWith(".wmv") -> R.drawable.ic_video
                else -> R.drawable.ic_file
            }

            if (file.isImage || file.isVideo) {
                val loadModel: Any = if (file.isSmb) file else file.path
                Glide.with(binding.fileIcon)
                    .load(loadModel)
                    .placeholder(iconRes)
                    .error(iconRes)
                    .centerCrop()
                    .into(binding.fileIcon)
            } else {
                binding.fileIcon.setImageResource(iconRes)
            }

            binding.root.setOnClickListener { onItemClick(file) }
        }
    }

    class FileDiffCallback : DiffUtil.ItemCallback<FileModel>() {
        override fun areItemsTheSame(oldItem: FileModel, newItem: FileModel): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileModel, newItem: FileModel): Boolean {
            return oldItem == newItem
        }
    }
}
