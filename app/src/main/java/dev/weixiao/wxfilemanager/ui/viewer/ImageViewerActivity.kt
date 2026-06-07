package dev.weixiao.wxfilemanager.ui.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import dev.weixiao.wxfilemanager.adapter.ImagePagerAdapter
import dev.weixiao.wxfilemanager.databinding.ActivityImageViewerBinding
import dev.weixiao.wxfilemanager.model.FileModel

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private var isFullscreen = true
    private lateinit var adapter: ImagePagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // 必须在 setContentView 之前调用，让窗口内容延伸到系统栏背后，
        // 避免系统栏显示/隐藏时根视图被重新布局导致图片位置抖动
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 在根容器上消费 insets，阻止系统栏 insets 影响任何子 View 的布局。
        // 由于系统栏始终隐藏，无需为 top_bar 预留状态栏 padding。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, _ ->
            WindowInsetsCompat.CONSUMED
        }

        val imageList = dev.weixiao.wxfilemanager.utils.MediaRepository.getImageList()
        val initialPosition = intent.getIntExtra("position", 0)

        updateTitle(imageList.getOrNull(initialPosition)?.name ?: "")
        updateProgress(initialPosition, imageList.size)

        adapter = ImagePagerAdapter {
            toggleFullscreen()
        }
        binding.viewPager.adapter = adapter
        adapter.submitList(imageList)
        binding.viewPager.setCurrentItem(initialPosition, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTitle(imageList.getOrNull(position)?.name ?: "")
                updateProgress(position, imageList.size)
            }
        })

        binding.btnRotate.setOnClickListener {
            requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        // Initial fullscreen state - start in immersive mode
        setImmersiveMode(true)
    }

    private fun updateTitle(name: String) {
        binding.tvTitle.text = name
    }

    private fun updateProgress(position: Int, total: Int) {
        binding.tvProgress.text = if (total > 0) "${position + 1}/$total" else ""
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        setImmersiveMode(isFullscreen)
    }

    private fun setImmersiveMode(enable: Boolean) {
        // 始终保持系统栏隐藏，仅切换自定义信息层的可见性，
        // 避免系统栏显隐导致图片位置变动
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        isFullscreen = enable
        if (enable) {
            binding.topBar.visibility = View.GONE
            binding.topMask.visibility = View.GONE
            binding.bottomMask.visibility = View.GONE
        } else {
            binding.topBar.visibility = View.VISIBLE
            binding.topMask.visibility = View.VISIBLE
            binding.bottomMask.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dev.weixiao.wxfilemanager.utils.MediaRepository.clear()
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
