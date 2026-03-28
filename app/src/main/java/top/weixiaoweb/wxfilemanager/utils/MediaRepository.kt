package top.weixiaoweb.wxfilemanager.utils

import top.weixiaoweb.wxfilemanager.model.FileModel

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