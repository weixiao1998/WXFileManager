package dev.weixiao.wxfilemanager.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FileModel(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String? = null,
    val isSmb: Boolean = false,
    val smbUrl: String? = null,
    val itemType: ItemType = ItemType.FILE
) : Parcelable {
    enum class ItemType {
        FILE, SERVER, SHARE, ADD_BUTTON
    }

    enum class SortMode {
        NAME_ASC, NAME_DESC, DATE_ASC, DATE_DESC, SIZE_ASC, SIZE_DESC
    }

    enum class ViewMode {
        LIST_SMALL, LIST_MEDIUM, LIST_LARGE, 
        GRID_SMALL, GRID_MEDIUM, GRID_LARGE
    }

    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true
    
    val isVideo: Boolean
        get() {
            if (mimeType?.startsWith("video/") == true && mimeType != "video/avi" && mimeType != "video/x-msvideo") return true
            val videoExtensions = listOf("mp4", "mkv", "mov", "wmv", "flv", "3gp", "ts", "webm", "m4v", "rmvb", "rm")
            val extension = name.substringAfterLast('.', "").lowercase()
            return videoExtensions.contains(extension)
        }
}
