package top.weixiaoweb.wxfilemanager

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory
import com.bumptech.glide.module.AppGlideModule

@GlideModule
class WXGlideModule : AppGlideModule() {
    
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val diskCacheSizeBytes: Long = 100 * 1024 * 1024
        
        builder.setDiskCache(
            DiskLruCacheFactory(
                context.cacheDir.absolutePath,
                "image_manager_disk_cache",
                diskCacheSizeBytes
            )
        )
    }
    
    override fun isManifestParsingEnabled(): Boolean {
        return false
    }
}
