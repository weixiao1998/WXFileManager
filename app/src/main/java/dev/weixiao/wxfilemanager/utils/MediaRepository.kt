package dev.weixiao.wxfilemanager.utils

import dev.weixiao.wxfilemanager.model.FileModel

object MediaRepository {
    private var currentImageList: List<FileModel> = emptyList()

    fun setImageList(list: List<FileModel>) {
        currentImageList = list
    }

    fun getImageList(): List<FileModel> {
        return currentImageList
    }

    fun clear() {
        currentImageList = emptyList()
    }
}