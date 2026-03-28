package top.weixiaoweb.wxfilemanager.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import top.weixiaoweb.wxfilemanager.databinding.ItemImageViewerBinding
import top.weixiaoweb.wxfilemanager.model.FileModel
import top.weixiaoweb.wxfilemanager.utils.SafManager

class ImagePagerAdapter(private val onItemClick: () -> Unit) : ListAdapter<FileModel, ImagePagerAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImageViewerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemImageViewerBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener { onItemClick() }
        }

        fun bind(file: FileModel) {
            val loadModel: Any = if (file.isSmb) {
                file
            } else {
                if (SafManager.isRestrictedPath(file.path)) {
                    SafManager.getFileUri(binding.root.context, file.path) ?: file.path
                } else {
                    file.path
                }
            }

            Glide.with(binding.imageView)
                .load(loadModel)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_close_clear_cancel)
                .into(binding.imageView)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<FileModel>() {
        override fun areItemsTheSame(oldItem: FileModel, newItem: FileModel): Boolean {
            return oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: FileModel, newItem: FileModel): Boolean {
            return oldItem == newItem
        }
    }
}
