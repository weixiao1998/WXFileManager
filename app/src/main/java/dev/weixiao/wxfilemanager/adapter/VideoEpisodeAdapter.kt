package dev.weixiao.wxfilemanager.adapter

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.weixiao.wxfilemanager.R
import dev.weixiao.wxfilemanager.model.FileModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoEpisodeAdapter(
    private val onItemClick: (FileModel, Int) -> Unit
) : ListAdapter<FileModel, VideoEpisodeAdapter.ViewHolder>(DiffCallback()) {

    private var currentPlayingPosition: Int = -1
    private val dateFormat = SimpleDateFormat("mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.iv_thumbnail)
        val tvDuration: TextView = view.findViewById(R.id.tv_duration)
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val tvSize: TextView = view.findViewById(R.id.tv_size)
        val ivPlaying: ImageView = view.findViewById(R.id.iv_playing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        // Cancel any pending Glide request to prevent stale thumbnails on recycled views
        Glide.with(holder.ivThumbnail).clear(holder.ivThumbnail)

        holder.tvName.text = item.name
        holder.tvSize.text = formatFileSize(item.size)

        val isPlaying = position == currentPlayingPosition
        holder.ivPlaying.visibility = if (isPlaying) View.VISIBLE else View.GONE

        if (isPlaying) {
            holder.itemView.setBackgroundColor(0x33FFFFFF)
        } else {
            holder.itemView.setBackgroundColor(0x00000000)
        }

        holder.itemView.setOnClickListener {
            onItemClick(item, position)
        }

        loadThumbnailWithGlide(holder, item)
        loadDuration(holder, item)
    }
    
    private fun loadThumbnailWithGlide(holder: ViewHolder, item: FileModel) {
        holder.ivThumbnail.setImageResource(R.drawable.ic_video)
        
        Glide.with(holder.itemView.context)
            .`as`(Bitmap::class.java)
            .load(item)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .override(holder.ivThumbnail.width, holder.ivThumbnail.height)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        holder.ivThumbnail.setImageBitmap(resource)
                    }
                }
                
                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                    holder.ivThumbnail.setImageResource(R.drawable.ic_video)
                }
                
                override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                    holder.ivThumbnail.setImageResource(R.drawable.ic_video)
                }
            })
    }
    
    private fun loadDuration(holder: ViewHolder, item: FileModel) {
        holder.tvDuration.visibility = View.GONE
        
        if (item.isSmb) {
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val duration = withContext(Dispatchers.IO) {
                    getVideoDuration(item)
                }
                
                if (duration != null && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    holder.tvDuration.text = dateFormat.format(Date(duration))
                    holder.tvDuration.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun getVideoDuration(item: FileModel): Long? {
        val retriever = MediaMetadataRetriever()
        
        try {
            retriever.setDataSource(item.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            return duration?.toLongOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.1f GB", gb)
    }

    fun setCurrentPlaying(position: Int) {
        val oldPosition = currentPlayingPosition
        currentPlayingPosition = position
        notifyItemChanged(oldPosition)
        notifyItemChanged(position)
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
