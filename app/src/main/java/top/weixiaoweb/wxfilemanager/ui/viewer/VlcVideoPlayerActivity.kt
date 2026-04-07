package top.weixiaoweb.wxfilemanager.ui.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import top.weixiaoweb.wxfilemanager.R
import top.weixiaoweb.wxfilemanager.databinding.ActivityVlcVideoPlayerBinding
import top.weixiaoweb.wxfilemanager.utils.SafManager
import top.weixiaoweb.wxfilemanager.utils.SmbManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VlcVideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVlcVideoPlayerBinding
    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    
    private var currentPath: String = ""
    private var currentName: String = ""
    private var isSmbFile = false
    private var isLandscape = false
    private var controlsVisible = false
    
    private val handler = Handler(Looper.getMainLooper())
    private var updateProgressRunnable: Runnable? = null
    
    // 手势支持相关
    private lateinit var gestureDetector: GestureDetector
    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var seekStartMs: Long = 0
    private var isSeeking: Boolean = false
    private var isFastForwarding: Boolean = false
    private var shouldIgnoreTap: Boolean = false  // 标记是否应该忽略点击事件
    private var isUpdatingSeekBar: Boolean = false  // 标记是否正在更新 SeekBar
    
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (mediaPlayer?.isPlaying == true) {
            // VLC 播放器设置 3 倍速
            mediaPlayer?.setRate(3.0f)
            isFastForwarding = true
            showSpeedHint(true)
            // 标记为应该忽略后续的点击事件
            shouldIgnoreTap = true
        }
    }

    companion object {
        private const val TAG = "VlcVideoPlayer"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        supportActionBar?.hide()
        
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        
        binding = ActivityVlcVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        currentName = intent.getStringExtra("name") ?: ""
        val path = intent.getStringExtra("path") ?: ""
        isSmbFile = intent.getBooleanExtra("isSmb", false)
        
        currentPath = path
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        setupUI()
        
        if (!setupVlcPlayer()) {
            showErrorAndFinish("VLC播放器初始化失败\n\n请检查：\n1. 设备架构是否支持\n2. 是否有足够的存储空间\n3. 应用是否有必要权限")
            return@onCreate
        }
        
        updateContainerVisibility()
        
        loadVideo(path)
    }
    
    private fun setupVlcPlayer(): Boolean {
        return try {
            val args = ArrayList<String>().apply {
                add("--avcodec-hw=any")
                add("--network-caching=1500")
                add("--aout=opensles")
                add("--no-video-title-show")
                add("--verbose=0")
            }
            
            libVLC = LibVLC(this.applicationContext, args)
            Log.d(TAG, "LibVLC初始化成功")
            
            mediaPlayer = MediaPlayer(libVLC).apply {
                setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Opening -> {
                            showProgressBar()
                        }
                        MediaPlayer.Event.Playing -> {
                            Log.d(TAG, "开始播放")
                            hideProgressBar()
                            startProgressUpdate()
                        }
                        MediaPlayer.Event.Paused -> {
                            stopProgressUpdate()
                        }
                        MediaPlayer.Event.Stopped -> {
                            hideProgressBar()
                            stopProgressUpdate()
                        }
                        MediaPlayer.Event.EndReached -> {
                            Log.d(TAG, "播放结束")
                            finish()
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            Log.e(TAG, "播放错误")
                            hideProgressBar()
                            Toast.makeText(this@VlcVideoPlayerActivity, "播放出错", Toast.LENGTH_SHORT).show()
                        }
                        MediaPlayer.Event.TimeChanged -> {
                            updateProgress()
                        }
                        MediaPlayer.Event.LengthChanged -> {
                            updateDuration()
                        }
                        else -> {}
                    }
                }
                
                attachViews(binding.vlcSurfaceView, null, false, false)
            }
            
            true
            
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "原生库加载失败", e)
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "状态异常", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败", e)
            false
        }
    }

    private fun showErrorAndFinish(message: String) {
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle("错误")
                .setMessage(message)
                .setPositiveButton("返回") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }
    
    private fun setupUI() {
        binding.tvTitlePortrait.text = currentName
        binding.tvTitleLandscape.text = currentName
        binding.tvVideoTitle.text = currentName
        
        binding.btnBackPortrait.setOnClickListener { 
            releaseAllResources()
            finish() 
        }
        binding.btnBackLandscape.setOnClickListener { 
            releaseAllResources()
            finish() 
        }
        
        binding.btnRotatePortrait.setOnClickListener { 
            toggleOrientation()
        }
        binding.btnRotateLandscape.setOnClickListener { 
            toggleOrientation()
        }
        
        binding.btnPlayPausePortrait.setOnClickListener { 
            togglePlayPause()
        }
        binding.btnPlayPauseLandscape.setOnClickListener { 
            togglePlayPause()
        }
        
        binding.btnRewind10Landscape.setOnClickListener { 
            seekRelative(-10000)
        }
        binding.btnForward10Landscape.setOnClickListener { 
            seekRelative(10000)
        }
        
        // 为所有控制层元素设置点击监听器，阻止事件冒泡到容器
        binding.topBarPortrait.setOnClickListener { /* 消费点击事件 */ }
        binding.bottomControlsPortrait.setOnClickListener { /* 消费点击事件 */ }
        binding.topBarLandscape.setOnClickListener { /* 消费点击事件 */ }
        binding.bottomControlsLandscape.setOnClickListener { /* 消费点击事件 */ }
        
        binding.seekbarPortrait.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                // 如果是手动拖动 SeekBar，才设置播放位置
                // 如果是代码更新（updateSeekHint），不设置播放位置，防止重复调用
                if (fromUser && mediaPlayer != null && !isUpdatingSeekBar) {
                    val duration = mediaPlayer?.length ?: 0L
                    if (duration > 0) {
                        val position = (progress * duration / 1000L).toLong()
                        mediaPlayer?.setTime(position)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                // 开始拖动时保持控制层显示
                showControlsForSeek()
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                // 停止拖动后恢复 5 秒倒计时
                scheduleHideControls()
            }
        })
        
        binding.seekbarLandscape.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null && !isUpdatingSeekBar) {
                    val duration = mediaPlayer?.length ?: 0L
                    if (duration > 0) {
                        val position = (progress * duration / 1000L).toLong()
                        mediaPlayer?.setTime(position)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                showControlsForSeek()
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                scheduleHideControls()
            }
        })
        
        binding.landscapeContainer.setOnClickListener {
            toggleControls()
        }
        
        // 竖屏时让 VLC SurfaceView 响应点击事件
        binding.vlcSurfaceView.setOnClickListener {
            toggleControls()
        }
        
        // 设置手势检测器
        setupGestureDetector()
    }
    
    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 如果发生了滑动或长按，忽略点击事件
                if (shouldIgnoreTap) {
                    shouldIgnoreTap = false
                    return true  // 消费事件，但不执行操作
                }
                toggleControls()
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                togglePlayPause()
                return true
            }
            
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })
        
        // 为竖屏和横屏设置触摸监听器
        val touchListener = View.OnTouchListener { _, event ->
            // 先让 GestureDetector 处理手势
            val gestureHandled = gestureDetector.onTouchEvent(event)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    seekStartMs = mediaPlayer?.time ?: 0
                    isSeeking = false
                    // 如果正在播放，设置长按检测（500ms 后触发快进）
                    if (mediaPlayer?.isPlaying == true) {
                        longPressHandler.postDelayed(longPressRunnable, 500)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - touchStartX
                    val deltaY = event.y - touchStartY
                    
                    // 检测水平滑动（滑动距离大于 50px 且水平距离大于垂直距离的 2 倍）
                    if (kotlin.math.abs(deltaX) > 50 && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 2) {
                        if (!isSeeking) {
                            isSeeking = true
                            // 检测到滑动，取消长按
                            longPressHandler.removeCallbacks(longPressRunnable)
                            // 标记为应该忽略后续的点击事件
                            shouldIgnoreTap = true
                            // 滑动时保持控制层显示
                            showControlsForSeek()
                        }
                        
                        val duration = mediaPlayer?.length ?: 0
                        if (duration > 0) {
                            val seekRange = 60_000L
                            val viewWidth = if (isLandscape) binding.landscapeContainer.width else binding.vlcSurfaceView.width
                            val seekDelta = (deltaX / viewWidth * seekRange).toLong()
                            val newPosition = (seekStartMs + seekDelta).coerceIn(0, duration)
                            
                            // 更新 SeekBar 和时间显示
                            updateSeekHint(newPosition, seekDelta)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 取消长按检测
                    longPressHandler.removeCallbacks(longPressRunnable)
                    
                    if (isSeeking) {
                        val deltaX = event.x - touchStartX
                        val duration = mediaPlayer?.length ?: 0
                        if (duration > 0) {
                            val seekRange = 60_000L
                            val viewWidth = if (isLandscape) binding.landscapeContainer.width else binding.vlcSurfaceView.width
                            val seekDelta = (deltaX / viewWidth * seekRange).toLong()
                            val newPosition = (seekStartMs + seekDelta).coerceIn(0, duration)
                            mediaPlayer?.setTime(newPosition)
                        }
                        isSeeking = false
                        // 滑动结束后恢复 5 秒倒计时（只有控制层显示时才设置）
                        if (controlsVisible) {
                            scheduleHideControls()
                        }
                    }
                    
                    // 如果正在快进，恢复正常速度
                    if (isFastForwarding) {
                        mediaPlayer?.setRate(1.0f)
                        isFastForwarding = false
                        showSpeedHint(false)
                    }
                }
            }
            
            // 返回 true 表示消费了触摸事件
            true
        }
        
        binding.vlcSurfaceView.setOnTouchListener(touchListener)
        binding.landscapeContainer.setOnTouchListener(touchListener)
    }
    
    private fun updateContainerVisibility() {
        if (isLandscape) {
            binding.videoContainerPortrait.visibility = View.GONE
            binding.portraitInfoContainer.visibility = View.GONE
            binding.landscapeContainer.visibility = View.VISIBLE
            
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            
            showControls()
        } else {
            binding.videoContainerPortrait.visibility = View.VISIBLE
            binding.portraitInfoContainer.visibility = View.GONE
            binding.landscapeContainer.visibility = View.GONE
            
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            
            showControls()
        }
    }
    
    private fun loadVideo(path: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val uri = if (isSmbFile) {
                    val connInfo = SmbManager.getConnectionInfo()
                    if (connInfo != null) {
                        var relativePath = path.replace("\\", "/")

                        while (relativePath.contains("//")) {
                            relativePath = relativePath.replace("//", "/")
                        }

                        val encodedPath = Uri.encode(relativePath, "/")
                        val encodedUser = Uri.encode(connInfo.user, "@:/")
                        val encodedPass = Uri.encode(connInfo.pass, "@:/")

                        val finalUriString = "smb://$encodedUser:$encodedPass@${connInfo.host}/${connInfo.share}/$encodedPath"
                        Uri.parse(finalUriString)
                    } else {
                        Log.e(TAG, "无法获取SMB连接信息")
                        var normalizedPath = path.replace("\\", "/")
                        while (normalizedPath.contains("//")) {
                            normalizedPath = normalizedPath.replace("//", "/")
                        }
                        val encodedPath = Uri.encode(normalizedPath, "/")
                        Uri.parse("smb://$encodedPath")
                    }
                } else {
                    if (SafManager.isRestrictedPath(path)) {
                        SafManager.getFileUri(this@VlcVideoPlayerActivity, path) ?: Uri.parse("file://$path")
                    } else {
                        Uri.parse("file://$path")
                    }
                }
                
                withContext(Dispatchers.Main) {
                    try {
                        val vlcInstance = libVLC ?: return@withContext
                        val player = mediaPlayer ?: return@withContext
                        
                        val media = Media(vlcInstance, uri)
                        
                        player.media = media
                        media.release()
                        
                        player.play()
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "加载视频失败", e)
                        Toast.makeText(this@VlcVideoPlayerActivity, "加载视频失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载视频失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VlcVideoPlayerActivity, "加载视频失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun togglePlayPause() {
        val player = mediaPlayer ?: return
        
        if (player.isPlaying) {
            player.pause()
            updatePlayPauseButton(false)
        } else {
            player.play()
            updatePlayPauseButton(true)
            startProgressUpdate()
        }
    }
    
    private fun updatePlayPauseButton(isPlaying: Boolean) {
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        binding.btnPlayPausePortrait.setImageResource(iconRes)
        binding.btnPlayPauseLandscape.setImageResource(iconRes)
    }
    
    private fun seekRelative(offsetMs: Long) {
        val player = mediaPlayer ?: return
        val newPosition = (player.time + offsetMs).coerceIn(0, player.length.toLong())
        player.setTime(newPosition)
    }
    
    private fun startProgressUpdate() {
        stopProgressUpdate()
        updateProgressRunnable = object : Runnable {
            override fun run() {
                updateProgress()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateProgressRunnable!!)
    }
    
    private fun stopProgressUpdate() {
        updateProgressRunnable?.let {
            handler.removeCallbacks(it)
            updateProgressRunnable = null
        }
    }
    
    private fun updateProgress() {
        val player = mediaPlayer ?: return
        val position = player.time
        val duration = player.length
        
        val timeText = formatTime(position)
        binding.tvCurrentTimePortrait.text = timeText
        binding.tvCurrentTimeLandscape.text = timeText
        
        if (duration > 0) {
            val progress = ((position * 1000) / duration).toInt()
            binding.seekbarPortrait.progress = progress
            binding.seekbarLandscape.progress = progress
        }
    }
    
    private fun updateDuration() {
        val player = mediaPlayer ?: return
        val duration = player.length
        val durationText = formatTime(duration)
        binding.tvDurationPortrait.text = durationText
        binding.tvDurationLandscape.text = durationText
        binding.tvVideoInfo.text = "时长: $durationText"
    }
    
    private fun formatTime(ms: Long): String {
        val sdf = SimpleDateFormat("mm:ss", Locale.getDefault())
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }
    
    private fun toggleControls() {
        if (controlsVisible) {
            // 如果控制层已经显示，先移除所有回调，再隐藏
            handler.removeCallbacks { hideControls() }
            hideControls()
        } else {
            showControls()
        }
    }
    
    private fun showControls() {
        controlsVisible = true
        
        // 先移除所有回调，防止提前隐藏
        handler.removeCallbacks { hideControls() }
        
        // 先隐藏所有控制层，再显示当前方向的控制层
        if (isLandscape) {
            // 横屏：隐藏竖屏控制层，显示横屏控制层
            binding.topBarPortrait.visibility = View.GONE
            binding.topMaskPortrait.visibility = View.GONE
            binding.bottomControlsPortrait.visibility = View.GONE
            
            binding.topBarLandscape.visibility = View.VISIBLE
            binding.topMaskLandscape.visibility = View.VISIBLE
            binding.bottomControlsLandscape.visibility = View.VISIBLE
            
            // 横屏时设置控制层可点击，阻止事件穿透到 landscapeContainer
            binding.topBarLandscape.isClickable = true
            binding.bottomControlsLandscape.isClickable = true
        } else {
            // 竖屏：隐藏横屏控制层，显示竖屏控制层
            binding.topBarLandscape.visibility = View.GONE
            binding.topMaskLandscape.visibility = View.GONE
            binding.bottomControlsLandscape.visibility = View.GONE
            
            binding.topBarPortrait.visibility = View.VISIBLE
            binding.topMaskPortrait.visibility = View.VISIBLE
            binding.bottomControlsPortrait.visibility = View.VISIBLE
            
            // 竖屏时设置控制层可点击，阻止事件穿透到 vlcSurfaceView
            binding.topBarPortrait.isClickable = true
            binding.bottomControlsPortrait.isClickable = true
        }
        
        // 5 秒后自动隐藏控制层（与 Media3 播放器保持一致）
        handler.postDelayed({ hideControls() }, 5000)
    }
    
    private fun showControlsForSeek() {
        controlsVisible = true
        // 只移除回调，不设置新的倒计时
        // 倒计时会在滑动结束后的 scheduleHideControls() 中设置
        handler.removeCallbacks { hideControls() }
        
        if (isLandscape) {
            binding.topBarLandscape.visibility = View.VISIBLE
            binding.topMaskLandscape.visibility = View.VISIBLE
            binding.bottomControlsLandscape.visibility = View.VISIBLE
        } else {
            binding.topBarPortrait.visibility = View.VISIBLE
            binding.topMaskPortrait.visibility = View.VISIBLE
            binding.bottomControlsPortrait.visibility = View.VISIBLE
        }
    }
    
    private fun updateSeekHint(positionMs: Long, deltaMs: Long) {
        val player = mediaPlayer ?: return
        val duration = player.length
        
        // 更新 SeekBar（标记为代码更新，不触发 setTime）
        if (duration > 0) {
            isUpdatingSeekBar = true
            val progress = ((positionMs * 1000) / duration).toInt()
            binding.seekbarPortrait.progress = progress
            binding.seekbarLandscape.progress = progress
            isUpdatingSeekBar = false
        }
        
        // 更新时间显示
        val timeText = formatTime(positionMs)
        binding.tvCurrentTimePortrait.text = timeText
        binding.tvCurrentTimeLandscape.text = timeText
        
        // 显示拖动提示（可选）
        val sign = if (deltaMs >= 0) "+" else ""
        val timeStr = formatTime(kotlin.math.abs(deltaMs))
        // 可以在这里添加拖动提示的 UI
    }
    
    private fun showSpeedHint(show: Boolean) {
        // VLC 播放器可以添加速度提示 UI，暂时简化处理
        if (show) {
            Toast.makeText(this, "3x 倍速播放中", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun scheduleHideControls() {
        // 只有控制层显示时才设置倒计时
        if (!controlsVisible) {
            return
        }
        handler.removeCallbacks { hideControls() }
        handler.postDelayed({ hideControls() }, 5000)
    }
    
    private fun hideControls() {
        // 如果控制层已经隐藏，直接返回
        if (!controlsVisible) {
            return
        }
        
        controlsVisible = false
        
        // 先移除所有回调，防止重复调用
        handler.removeCallbacks { hideControls() }
        
        // 隐藏所有控制层
        binding.topBarPortrait.visibility = View.GONE
        binding.topMaskPortrait.visibility = View.GONE
        binding.bottomControlsPortrait.visibility = View.GONE
        binding.topBarLandscape.visibility = View.GONE
        binding.topMaskLandscape.visibility = View.GONE
        binding.bottomControlsLandscape.visibility = View.GONE
        
        // 设置所有控制层不可点击
        binding.topBarPortrait.isClickable = false
        binding.bottomControlsPortrait.isClickable = false
        binding.topBarLandscape.isClickable = false
        binding.bottomControlsLandscape.isClickable = false
    }
    
    private fun toggleOrientation() {
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateContainerVisibility()
    }
    
    private fun showProgressBar() {
        binding.progressBar.visibility = View.VISIBLE
    }
    
    private fun hideProgressBar() {
        binding.progressBar.visibility = View.GONE
    }
    
    override fun onResume() {
        super.onResume()
        if (isSmbFile) {
            CoroutineScope(Dispatchers.IO).launch {
                SmbManager.checkAndReconnect()
            }
        }
    }
    
    private fun releaseAllResources() {
        stopProgressUpdate()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            libVLC?.release()
        } catch (e: Exception) {
            Log.e(TAG, "清理资源时出错", e)
        } finally {
            mediaPlayer = null
            libVLC = null
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        releaseAllResources()
    }
    
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        releaseAllResources()
        super.onBackPressed()
    }
}
