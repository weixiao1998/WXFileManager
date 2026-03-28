package top.weixiaoweb.wxfilemanager.utils

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.module.AppGlideModule

@GlideModule
class WxGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // Limit disk cache to 250MB (default is 250MB, but let's be explicit and maybe reduce if needed)
        val diskCacheSizeBytes = 250L * 1024 * 1024 // 250MB
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, diskCacheSizeBytes))
    }

    // Disable manifest parsing to speed up startup
    override fun isManifestParsingEnabled(): Boolean = false
}
