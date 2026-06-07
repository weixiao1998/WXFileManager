package dev.weixiao.wxfilemanager.model

import android.os.Parcel
import android.os.Parcelable

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
    constructor(parcel: Parcel) : this(
        name = parcel.readString().orEmpty(),
        path = parcel.readString().orEmpty(),
        isDirectory = parcel.readByte() != 0.toByte(),
        size = parcel.readLong(),
        lastModified = parcel.readLong(),
        mimeType = parcel.readString(),
        isSmb = parcel.readByte() != 0.toByte(),
        smbUrl = parcel.readString(),
        itemType = ItemType.valueOf(parcel.readString() ?: ItemType.FILE.name)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(name)
        parcel.writeString(path)
        parcel.writeByte(if (isDirectory) 1.toByte() else 0.toByte())
        parcel.writeLong(size)
        parcel.writeLong(lastModified)
        parcel.writeString(mimeType)
        parcel.writeByte(if (isSmb) 1.toByte() else 0.toByte())
        parcel.writeString(smbUrl)
        parcel.writeString(itemType.name)
    }

    override fun describeContents(): Int = 0
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
            if (mimeType?.startsWith("video/") == true) return true
            val videoExtensions = listOf("mp4", "mkv", "mov", "wmv", "flv", "3gp", "ts", "webm", "m4v", "rmvb", "rm", "avi")
            val extension = name.substringAfterLast('.', "").lowercase()
            return videoExtensions.contains(extension)
        }

    val isText: Boolean
        get() {
            if (isDirectory) return false
            if (mimeType?.startsWith("text/") == true) return true
            if (mimeType != null && mimeType in TEXT_MIME_WHITELIST) return true
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) {
                val lowerName = name.lowercase()
                return lowerName in NO_EXT_TEXT_FILES
            }
            return extension in TEXT_EXTENSIONS
        }

    companion object CREATOR : Parcelable.Creator<FileModel> {
        override fun createFromParcel(parcel: Parcel): FileModel = FileModel(parcel)

        override fun newArray(size: Int): Array<FileModel?> = arrayOfNulls(size)

        private val TEXT_EXTENSIONS = setOf(
            "txt", "log", "md", "markdown", "rst",
            "json", "xml", "yml", "yaml", "toml", "ini", "conf", "properties", "csv", "tsv",
            "html", "htm", "css", "scss", "less", "js", "jsx", "ts", "tsx", "vue",
            "kt", "kts", "java", "py", "rb", "go", "rs", "swift", "dart", "groovy",
            "c", "cpp", "cc", "cxx", "h", "hpp", "m", "mm",
            "sh", "bash", "zsh", "bat", "cmd", "ps1",
            "sql", "gradle", "pro", "gitignore", "gitattributes", "env",
            "srt", "vtt", "ass", "lrc"
        )
        private val TEXT_MIME_WHITELIST = setOf(
            "application/json", "application/xml", "application/javascript",
            "application/x-sh", "application/x-yaml", "application/x-properties",
            "application/x-shellscript", "application/sql"
        )
        private val NO_EXT_TEXT_FILES = setOf(
            "readme", "license", "makefile", "dockerfile", "changelog", "authors", "notice"
        )
    }
}
