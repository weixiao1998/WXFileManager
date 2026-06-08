package dev.weixiao.wxfilemanager.adapter

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import dev.weixiao.wxfilemanager.R
import dev.weixiao.wxfilemanager.databinding.ItemFileGridBinding
import dev.weixiao.wxfilemanager.databinding.ItemFileListBinding
import dev.weixiao.wxfilemanager.model.FileModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileAdapter(private val onItemClick: (FileModel) -> Unit) :
    ListAdapter<FileModel, FileAdapter.FileViewHolder>(FileDiffCallback()) {

    private var currentViewMode: FileModel.ViewMode = FileModel.ViewMode.LIST_MEDIUM

    fun setViewMode(viewMode: FileModel.ViewMode) {
        if (this.currentViewMode == viewMode) return
        val previousIsGrid = this.currentViewMode.isGrid
        this.currentViewMode = viewMode

        // 仅当 list/grid 类别切换时，需要重新创建 ViewHolder（因为 itemViewType 变了）；
        // 否则只需通过 payload 调整图标尺寸，避免重新加载缩略图。
        if (previousIsGrid != viewMode.isGrid) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_VIEW_MODE)
        } else {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_ICON_SIZE)
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (currentViewMode.isGrid) VIEW_TYPE_GRID else VIEW_TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            GridViewHolder(ItemFileGridBinding.inflate(inflater, parent, false))
        } else {
            ListViewHolder(ItemFileListBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position), currentViewMode)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        // 仅调整图标尺寸，不重新加载缩略图，保证切换视图模式时不闪烁
        if (payloads.contains(PAYLOAD_ICON_SIZE) || payloads.contains(PAYLOAD_VIEW_MODE)) {
            holder.updateIconSize(currentViewMode)
        }
    }

    abstract class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract val iconView: ImageView
        abstract val nameView: TextView
        abstract val infoView: TextView

        abstract fun bind(file: FileModel, viewMode: FileModel.ViewMode)

        fun updateIconSize(viewMode: FileModel.ViewMode) {
            val density = itemView.context.resources.displayMetrics.density
            val pixelSize = (viewMode.iconSizeDp * density).toInt()
            val lp = iconView.layoutParams
            if (lp.width != pixelSize || lp.height != pixelSize) {
                lp.width = pixelSize
                lp.height = pixelSize
                iconView.layoutParams = lp
            }
        }
    }

    inner class ListViewHolder(private val binding: ItemFileListBinding) :
        FileViewHolder(binding.root) {
        override val iconView: ImageView get() = binding.fileIcon
        override val nameView: TextView get() = binding.fileName
        override val infoView: TextView get() = binding.fileInfo

        override fun bind(file: FileModel, viewMode: FileModel.ViewMode) {
            bindCommon(this, file, viewMode, onItemClick)
            bindListInfo(binding, file)
        }
    }

    inner class GridViewHolder(private val binding: ItemFileGridBinding) :
        FileViewHolder(binding.root) {
        override val iconView: ImageView get() = binding.fileIcon
        override val nameView: TextView get() = binding.fileName
        override val infoView: TextView get() = binding.fileInfo

        override fun bind(file: FileModel, viewMode: FileModel.ViewMode) {
            bindCommon(this, file, viewMode, onItemClick)
            // Grid 模式始终不显示 info 行
            binding.fileInfo.visibility = View.GONE
        }
    }

    class FileDiffCallback : DiffUtil.ItemCallback<FileModel>() {
        override fun areItemsTheSame(oldItem: FileModel, newItem: FileModel): Boolean =
            oldItem.path == newItem.path

        override fun areContentsTheSame(oldItem: FileModel, newItem: FileModel): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_LIST = 0
        private const val VIEW_TYPE_GRID = 1

        private val PAYLOAD_VIEW_MODE = Any()
        private val PAYLOAD_ICON_SIZE = Any()

        private val FileModel.ViewMode.isGrid: Boolean
            get() = this == FileModel.ViewMode.GRID_SMALL ||
                this == FileModel.ViewMode.GRID_MEDIUM ||
                this == FileModel.ViewMode.GRID_LARGE

        private val FileModel.ViewMode.iconSizeDp: Int
            get() = when (this) {
                FileModel.ViewMode.LIST_SMALL, FileModel.ViewMode.GRID_SMALL -> 32
                FileModel.ViewMode.LIST_MEDIUM, FileModel.ViewMode.GRID_MEDIUM -> 48
                FileModel.ViewMode.LIST_LARGE, FileModel.ViewMode.GRID_LARGE -> 72
            }

        /** 公共绑定逻辑：图标、缩略图、点击回调 */
        private fun bindCommon(
            holder: FileViewHolder,
            file: FileModel,
            viewMode: FileModel.ViewMode,
            onItemClick: (FileModel) -> Unit
        ) {
            holder.nameView.text = file.name
            holder.updateIconSize(viewMode)

            // 先取消上一次的 Glide 请求，避免回收的 ImageView 显示旧缩略图
            Glide.with(holder.iconView).clear(holder.iconView)

            val iconRes = resolveIconRes(file)
            if (file.isImage || file.isVideo) {
                val loadModel: Any = if (file.isSmb) file else file.path
                Glide.with(holder.iconView)
                    .load(loadModel)
                    .placeholder(iconRes)
                    .error(iconRes)
                    .centerCrop()
                    .into(holder.iconView)
            } else {
                holder.iconView.setImageResource(iconRes)
            }

            holder.itemView.setOnClickListener { onItemClick(file) }
        }

        private fun bindListInfo(binding: ItemFileListBinding, file: FileModel) {
            when (file.itemType) {
                FileModel.ItemType.FILE -> {
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(file.lastModified))
                    val sizeStr = if (file.isDirectory) "文件夹"
                    else Formatter.formatFileSize(binding.root.context, file.size)
                    binding.fileInfo.text = "$date | $sizeStr"
                    binding.fileInfo.visibility = View.VISIBLE
                }
                FileModel.ItemType.SERVER -> {
                    binding.fileInfo.text = "SMB 服务器 | ${file.path}"
                    binding.fileInfo.visibility = View.VISIBLE
                }
                FileModel.ItemType.SHARE -> {
                    binding.fileInfo.text = "共享目录"
                    binding.fileInfo.visibility = View.VISIBLE
                }
                FileModel.ItemType.ADD_BUTTON -> {
                    binding.fileInfo.visibility = View.GONE
                }
            }
        }

        private fun resolveIconRes(file: FileModel): Int = when {
            file.itemType == FileModel.ItemType.SERVER -> R.drawable.ic_server
            file.itemType == FileModel.ItemType.SHARE -> R.drawable.ic_folder
            file.itemType == FileModel.ItemType.ADD_BUTTON -> R.drawable.ic_add
            file.isDirectory -> R.drawable.ic_folder
            file.isImage -> R.drawable.ic_image
            file.isVideo ||
                file.name.lowercase(Locale.getDefault()).endsWith(".avi") ||
                file.name.lowercase(Locale.getDefault()).endsWith(".wmv") -> R.drawable.ic_video
            else -> R.drawable.ic_file
        }
    }
}
