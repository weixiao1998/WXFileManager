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
        
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val imageList = dev.weixiao.wxfilemanager.utils.MediaRepository.getImageList()
        val initialPosition = intent.getIntExtra("position", 0)

        updateTitle(imageList.getOrNull(initialPosition)?.name ?: "")

        adapter = ImagePagerAdapter {
            toggleFullscreen()
        }
        binding.viewPager.adapter = adapter
        adapter.submitList(imageList)
        binding.viewPager.setCurrentItem(initialPosition, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateTitle(imageList.getOrNull(position)?.name ?: "")
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

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        setImmersiveMode(isFullscreen)
    }

    private fun setImmersiveMode(enable: Boolean) {
        isFullscreen = enable
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (enable) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            binding.topBar.visibility = View.GONE
            binding.topMask.visibility = View.GONE
            binding.bottomMask.visibility = View.GONE
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
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
