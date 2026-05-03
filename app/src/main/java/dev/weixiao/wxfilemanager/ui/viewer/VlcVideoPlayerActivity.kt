package dev.weixiao.wxfilemanager.ui.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import dev.weixiao.wxfilemanager.R
import dev.weixiao.wxfilemanager.adapter.VideoEpisodeAdapter
import dev.weixiao.wxfilemanager.databinding.ActivityVlcVideoPlayerBinding
import dev.weixiao.wxfilemanager.model.FileModel
import dev.weixiao.wxfilemanager.utils.SafManager
import dev.weixiao.wxfilemanager.utils.SmbManager
import java.io.File as JFile
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
    private var isControlsLocked = false

    private val handler = Handler(Looper.getMainLooper())
    private var updateProgressRunnable: Runnable? = null

    // 手势支持相关
    private lateinit var gestureDetector: GestureDetector
    private var touchStartX: Float = 0f
    private var touchStartY: Float = 0f
    private var seekStartMs: Long = 0
    private var isSeeking: Boolean = false
    private var isFastForwarding: Boolean = false
    private var shouldIgnoreTap: Boolean = false
    private var isUpdatingSeekBar: Boolean = false
    private var isFirstResume = true
    private var restorePosition: Long = -1L
    private var restorePlaying: Boolean = false

    private var isAdjustingVolume = false
    private var isAdjustingBrightness = false
    private var initialVolume: Int = 0
    private var maxVolume: Int = 0
    private var initialBrightness: Float = 0f
    private lateinit var audioManager: AudioManager

    private val hideGestureHintRunnable = Runnable { hideGestureHint() }

    private val hideControlsRunnable = Runnable { hideControls() }

    private var videoList: List<FileModel> = emptyList()
    private var currentEpisodePosition = 0
    private lateinit var episodeAdapter: VideoEpisodeAdapter
    
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

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        initialBrightness = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }

        setupUI()

        if (!setupVlcPlayer()) {
            showErrorAndFinish("VLC播放器初始化失败\n\n请检查：\n1. 设备架构是否支持\n2. 是否有足够的存储空间\n3. 应用是否有必要权限")
            return@onCreate
        }

        setupEpisodeList()
        loadVideoList(path, isSmbFile)

        if (!isLandscape) {
            applyPortraitLayout()
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
                            updatePlayPauseButton(true)
                            if (restorePosition >= 0) {
                                val pos = restorePosition
                                val doPause = !restorePlaying
                                restorePosition = -1L
                                mediaPlayer?.setTime(pos)
                                if (doPause) {
                                    handler.postDelayed({
                                        mediaPlayer?.pause()
                                    }, 150)
                                }
                            }
                            startProgressUpdate()
                        }
                        MediaPlayer.Event.Paused -> {
                            updatePlayPauseButton(false)
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

    private fun setupEpisodeList() {
        episodeAdapter = VideoEpisodeAdapter { file, position ->
            playVideoAt(position)
        }
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = episodeAdapter
    }

    private fun loadVideoList(currentPath: String, isSmb: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val parentPath = if (isSmb) {
                    currentPath.replace("/", "\\").substringBeforeLast("\\", "")
                } else {
                    JFile(currentPath).parent ?: ""
                }

                val files = if (isSmb) {
                    SmbManager.listFiles(parentPath)
                } else {
                    if (SafManager.isRestrictedPath(parentPath)) {
                        SafManager.listFiles(this@VlcVideoPlayerActivity, parentPath)
                    } else {
                        JFile(parentPath).listFiles()?.map { file ->
                            FileModel(
                                name = file.name,
                                path = file.absolutePath,
                                isDirectory = file.isDirectory,
                                size = file.length(),
                                lastModified = file.lastModified(),
                                mimeType = null,
                                isSmb = false,
                                smbUrl = ""
                            )
                        } ?: emptyList()
                    }
                }

                videoList = files.filter { it.isVideo }.sortedBy { it.name.lowercase() }
                currentEpisodePosition = videoList.indexOfFirst { it.path == this@VlcVideoPlayerActivity.currentPath }

                withContext(Dispatchers.Main) {
                    if (currentEpisodePosition >= 0) {
                        episodeAdapter.setCurrentPlaying(currentEpisodePosition)
                    }
                    episodeAdapter.submitList(videoList) {
                        if (currentEpisodePosition >= 0) {
                            binding.rvEpisodes.scrollToPosition(currentEpisodePosition)
                        }
                    }
                    binding.tvEpisodeTitle.text = "选集 (共${videoList.size}个)"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun playVideoAt(position: Int) {
        if (position < 0 || position >= videoList.size) return

        val file = videoList[position]
        currentEpisodePosition = position
        currentPath = file.path
        currentName = file.name

        episodeAdapter.setCurrentPlaying(position)

        binding.tvTitlePortrait.text = file.name
        binding.tvTitleLandscape.text = file.name
        binding.tvVideoTitle.text = file.name

        loadVideo(file.path)
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

        binding.btnPrevLandscape.setOnClickListener {
            if (videoList.isNotEmpty()) {
                val newPosition = if (currentEpisodePosition > 0) currentEpisodePosition - 1 else videoList.size - 1
                playVideoAt(newPosition)
            }
        }

        binding.btnNextLandscape.setOnClickListener {
            if (videoList.isNotEmpty()) {
                val newPosition = if (currentEpisodePosition < videoList.size - 1) currentEpisodePosition + 1 else 0
                playVideoAt(newPosition)
            }
        }

        binding.btnSpeedLandscape.setOnClickListener {
            showSpeedSelectionDialog()
        }

        binding.btnPlaylistLandscape.setOnClickListener {
            toggleOrientation()
        }

        binding.btnRepeatLandscape.setOnClickListener {
            // VLC 不支持标准 repeat mode，简单提示即可
            Toast.makeText(this, "循环播放功能开发中", Toast.LENGTH_SHORT).show()
        }

        binding.btnSubtitleLandscape.setOnClickListener {
            showSubtitleSelectionDialog()
        }

        binding.btnAudioTrackLandscape.setOnClickListener {
            showAudioTrackSelectionDialog()
        }

        binding.btnMenuLandscape.setOnClickListener {
            showMenuDialog()
        }

        binding.btnScreenshotLandscape.setOnClickListener {
            takeScreenshot()
        }

        binding.btnLockLandscape.setOnClickListener {
            isControlsLocked = !isControlsLocked
            if (isControlsLocked) {
                hideControls()
                binding.btnLockLandscape.visibility = View.VISIBLE
                Toast.makeText(this, "已锁定", Toast.LENGTH_SHORT).show()
            } else {
                showControls()
                Toast.makeText(this, "已解锁", Toast.LENGTH_SHORT).show()
            }
        }

        // 为所有控制层元素设置点击监听器，阻止事件冒泡到容器
        binding.topBarPortrait.setOnClickListener { /* 消费点击事件 */ }
        binding.bottomControlsPortrait.setOnClickListener { /* 消费点击事件 */ }
        binding.topBarLandscape.setOnClickListener { /* 消费点击事件 */ }
        binding.bottomControlsLandscape.setOnClickListener { /* 消费点击事件 */ }
        
        binding.seekbarPortrait.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
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
                isSeeking = true
                showControlsForSeek()
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = false
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
                isSeeking = true
                showControlsForSeek()
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = false
                scheduleHideControls()
            }
        })
        
        binding.landscapeContainer.setOnClickListener {
            toggleControls()
        }
        
        // 竖屏时让 VLC SurfaceView 响应点击事件（仅在竖屏且 portrait_container 不可见时生效）
        binding.vlcSurfaceView.setOnClickListener {
            toggleControls()
        }

        // video_overlay_portrait 可见时会拦截触摸事件，需要注册手势
        binding.videoOverlayPortrait.setOnClickListener {
            toggleControls()
        }
        
        // 设置手势检测器
        setupGestureDetector()
    }
    
    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (shouldIgnoreTap) {
                    shouldIgnoreTap = false
                    return true
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
        
        val touchListener = View.OnTouchListener { view, event ->
            val gestureHandled = gestureDetector.onTouchEvent(event)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    seekStartMs = mediaPlayer?.time ?: 0
                    isSeeking = false
                    isAdjustingVolume = false
                    isAdjustingBrightness = false
                    if (mediaPlayer?.isPlaying == true) {
                        longPressHandler.postDelayed(longPressRunnable, 500)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchStartX
                    val deltaY = event.rawY - touchStartY
                    
                    if (kotlin.math.abs(deltaX) > 50 && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 2 && !isAdjustingVolume && !isAdjustingBrightness) {
                        if (!isSeeking) {
                            isSeeking = true
                            longPressHandler.removeCallbacks(longPressRunnable)
                            shouldIgnoreTap = true
                            showControlsForSeek()
                        }
                        
                        val duration = mediaPlayer?.length ?: 0
                        if (duration > 0) {
                            val seekRange = 60_000L
                            val viewWidth = if (isLandscape) binding.landscapeContainer.width else binding.vlcSurfaceView.width
                            val seekDelta = (deltaX / viewWidth * seekRange).toLong()
                            val newPosition = (seekStartMs + seekDelta).coerceIn(0, duration)
                            updateSeekHint(newPosition, seekDelta)
                        }
                    } else if (kotlin.math.abs(deltaY) > 50 && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX) * 2 && !isSeeking) {
                        if (isControlsLocked) return@OnTouchListener true
                        
                        val viewWidth = view.width
                        val isRightSide = touchStartX >= (view.rootView.width / 2f)
                        
                        if (isRightSide && !isAdjustingBrightness) {
                            if (!isAdjustingVolume) {
                                isAdjustingVolume = true
                                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                longPressHandler.removeCallbacks(longPressRunnable)
                                shouldIgnoreTap = true
                            }
                            adjustVolume(deltaY, view.rootView.height)
                        } else if (!isRightSide && !isAdjustingVolume) {
                            if (!isAdjustingBrightness) {
                                isAdjustingBrightness = true
                                initialBrightness = window.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                                longPressHandler.removeCallbacks(longPressRunnable)
                                shouldIgnoreTap = true
                            }
                            adjustBrightness(deltaY, view.rootView.height)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)

                    if (isSeeking) {
                        val deltaX = event.rawX - touchStartX
                        val duration = mediaPlayer?.length ?: 0
                        if (duration > 0) {
                            val seekRange = 60_000L
                            val viewWidth = if (isLandscape) binding.landscapeContainer.width else binding.vlcSurfaceView.width
                            val seekDelta = (deltaX / viewWidth * seekRange).toLong()
                            val newPosition = (seekStartMs + seekDelta).coerceIn(0, duration)
                            mediaPlayer?.setTime(newPosition)
                        }
                        isSeeking = false
                        if (controlsVisible) {
                            scheduleHideControls()
                        }
                        shouldIgnoreTap = false
                    }

                    if (isAdjustingVolume || isAdjustingBrightness) {
                        isAdjustingVolume = false
                        isAdjustingBrightness = false
                        handler.removeCallbacks(hideGestureHintRunnable)
                        handler.postDelayed(hideGestureHintRunnable, 1000)
                        shouldIgnoreTap = false
                    }

                    if (isFastForwarding) {
                        mediaPlayer?.setRate(1.0f)
                        isFastForwarding = false
                        showSpeedHint(false)
                        shouldIgnoreTap = false
                    }
                }
            }
            
            true
        }
        
        binding.vlcSurfaceView.setOnTouchListener(touchListener)
        binding.videoOverlayPortrait.setOnTouchListener(touchListener)
        binding.landscapeContainer.setOnTouchListener(touchListener)
    }
    
    private fun applyPortraitLayout() {
        binding.rootContainer.post {
            val rootHeight = binding.rootContainer.height
            if (rootHeight <= 0) return@post

            val videoHeightPx = (200 * resources.displayMetrics.density).toInt()

            val vlcParams = binding.vlcSurfaceView.layoutParams
            vlcParams.height = videoHeightPx
            binding.vlcSurfaceView.layoutParams = vlcParams

            val infoParams = binding.portraitInfoContainer.layoutParams as FrameLayout.LayoutParams
            infoParams.height = rootHeight - videoHeightPx
            infoParams.topMargin = videoHeightPx
            binding.portraitInfoContainer.layoutParams = infoParams
        }
    }

    private fun updateContainerVisibility() {
        if (isLandscape) {
            val vlcParams = binding.vlcSurfaceView.layoutParams
            vlcParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            binding.vlcSurfaceView.layoutParams = vlcParams

            binding.videoOverlayPortrait.visibility = View.GONE
            binding.portraitInfoContainer.visibility = View.GONE
            binding.landscapeContainer.visibility = View.VISIBLE

            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

            showControls()
        } else {
            val videoHeightPx = (200 * resources.displayMetrics.density).toInt()

            val vlcParams = binding.vlcSurfaceView.layoutParams
            vlcParams.height = videoHeightPx
            binding.vlcSurfaceView.layoutParams = vlcParams

            val infoParams = binding.portraitInfoContainer.layoutParams as FrameLayout.LayoutParams
            infoParams.height = binding.rootContainer.height - videoHeightPx
            infoParams.topMargin = videoHeightPx
            binding.portraitInfoContainer.layoutParams = infoParams

            binding.videoOverlayPortrait.visibility = View.VISIBLE
            binding.portraitInfoContainer.visibility = View.VISIBLE
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
        val portraitIconRes = if (isPlaying) R.drawable.ic_pause_portrait else R.drawable.ic_play_portrait
        val landscapeIconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPausePortrait.setImageResource(portraitIconRes)
        binding.btnPlayPauseLandscape.setImageResource(landscapeIconRes)
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
        if (isSeeking) return
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
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
    
    private fun toggleControls() {
        if (isControlsLocked) {
            binding.btnLockLandscape.visibility = View.VISIBLE
            return
        }
        if (controlsVisible) {
            // 如果控制层已经显示，先移除所有回调，再隐藏
            handler.removeCallbacks(hideControlsRunnable)
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        if (isControlsLocked) return
        controlsVisible = true

        // 先移除所有回调，防止提前隐藏
        handler.removeCallbacks(hideControlsRunnable)

        // 先隐藏所有控制层，再显示当前方向的控制层
        if (isLandscape) {
            // 横屏：隐藏竖屏控制层，显示横屏控制层
            binding.topBarPortrait.visibility = View.GONE
            binding.topMaskPortrait.visibility = View.GONE
            binding.bottomControlsPortrait.visibility = View.GONE

            binding.topBarLandscape.visibility = View.VISIBLE
            binding.topMaskLandscape.visibility = View.VISIBLE
            binding.bottomControlsLandscape.visibility = View.VISIBLE
            binding.btnScreenshotLandscape.visibility = View.VISIBLE
            binding.btnLockLandscape.visibility = View.VISIBLE

            // 横屏时设置控制层可点击，阻止事件穿透到 landscapeContainer
            binding.topBarLandscape.isClickable = true
            binding.bottomControlsLandscape.isClickable = true
        } else {
            // 竖屏：隐藏横屏控制层，显示竖屏控制层
            binding.topBarLandscape.visibility = View.GONE
            binding.topMaskLandscape.visibility = View.GONE
            binding.bottomControlsLandscape.visibility = View.GONE
            binding.btnScreenshotLandscape.visibility = View.GONE
            binding.btnLockLandscape.visibility = View.GONE

            binding.topBarPortrait.visibility = View.VISIBLE
            binding.topMaskPortrait.visibility = View.VISIBLE
            binding.bottomControlsPortrait.visibility = View.VISIBLE

            // 竖屏时设置控制层可点击，阻止事件穿透到 vlcSurfaceView
            binding.topBarPortrait.isClickable = true
            binding.bottomControlsPortrait.isClickable = true
        }

        // 5 秒后自动隐藏控制层（与 Media3 播放器保持一致）
        handler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun showControlsForSeek() {
        if (isControlsLocked) return
        controlsVisible = true
        // 只移除回调，不设置新的倒计时
        // 倒计时会在滑动结束后的 scheduleHideControls() 中设置
        handler.removeCallbacks(hideControlsRunnable)

        if (isLandscape) {
            binding.topBarLandscape.visibility = View.VISIBLE
            binding.topMaskLandscape.visibility = View.VISIBLE
            binding.bottomControlsLandscape.visibility = View.VISIBLE
            binding.btnScreenshotLandscape.visibility = View.VISIBLE
            binding.btnLockLandscape.visibility = View.VISIBLE
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
        if (show) {
            binding.tvSpeedHintLandscape.text = "3x 倍速播放中"
            binding.tvSpeedHintLandscape.visibility = View.VISIBLE
        } else {
            binding.tvSpeedHintLandscape.visibility = View.GONE
        }
    }

    private fun adjustVolume(deltaY: Float, viewHeight: Int) {
        val deltaVolume = -(deltaY / viewHeight * maxVolume).toInt()
        val newVolume = (initialVolume + deltaVolume).coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
        val percent = (newVolume.toFloat() / maxVolume * 100).toInt()
        showGestureHint(isVolume = true, percent = percent)
    }

    private fun adjustBrightness(deltaY: Float, viewHeight: Int) {
        val deltaBrightness = -(deltaY / viewHeight)
        val newBrightness = (initialBrightness + deltaBrightness).coerceIn(0f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = newBrightness }
        val percent = (newBrightness * 100).toInt()
        showGestureHint(isVolume = false, percent = percent)
    }

    private fun showGestureHint(isVolume: Boolean, percent: Int) {
        handler.removeCallbacks(hideGestureHintRunnable)
        binding.ivGestureIcon.setImageResource(
            if (isVolume) R.drawable.ic_volume_indicator else R.drawable.ic_brightness
        )
        binding.pbGesture.progress = percent
        binding.tvGesturePercent.text = "$percent%"
        if (!isLandscape) {
            val videoHeightPx = (200 * resources.displayMetrics.density).toInt()
            val rootViewHeight = binding.rootContainer.height
            val offsetY = (videoHeightPx / 2f) - (rootViewHeight / 2f)
            binding.gestureHintContainer.translationY = offsetY
        } else {
            binding.gestureHintContainer.translationY = 0f
        }
        binding.gestureHintContainer.visibility = View.VISIBLE
    }

    private fun hideGestureHint() {
        binding.gestureHintContainer.visibility = View.GONE
    }
    
    private fun scheduleHideControls() {
        // 只有控制层显示时才设置倒计时
        if (!controlsVisible) {
            return
        }
        if (isControlsLocked) return
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun hideControls() {
        // 如果控制层已经隐藏，直接返回
        if (!controlsVisible) {
            return
        }

        controlsVisible = false

        // 先移除所有回调，防止重复调用
        handler.removeCallbacks(hideControlsRunnable)

        // 隐藏所有控制层
        binding.topBarPortrait.visibility = View.GONE
        binding.topMaskPortrait.visibility = View.GONE
        binding.bottomControlsPortrait.visibility = View.GONE
        binding.topBarLandscape.visibility = View.GONE
        binding.topMaskLandscape.visibility = View.GONE
        binding.bottomControlsLandscape.visibility = View.GONE
        binding.btnScreenshotLandscape.visibility = View.GONE
        if (!isControlsLocked) {
            binding.btnLockLandscape.visibility = View.GONE
        }

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

    private fun showSpeedSelectionDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        android.app.AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setItems(speeds) { _, which ->
                val speed = speedValues[which]
                mediaPlayer?.setRate(speed)
                Toast.makeText(this, "${speeds[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showSubtitleSelectionDialog() {
        Toast.makeText(this, "字幕选择功能开发中", Toast.LENGTH_SHORT).show()
    }

    private fun showAudioTrackSelectionDialog() {
        Toast.makeText(this, "音轨选择功能开发中", Toast.LENGTH_SHORT).show()
    }

    private fun showMenuDialog() {
        Toast.makeText(this, "菜单功能开发中", Toast.LENGTH_SHORT).show()
    }

    private fun takeScreenshot() {
        Toast.makeText(this, "截图功能开发中", Toast.LENGTH_SHORT).show()
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateContainerVisibility()
        if (!isLandscape) {
            applyPortraitLayout()
        }
    }
    
    private fun showProgressBar() {
        binding.progressBar.visibility = View.VISIBLE
    }
    
    private fun hideProgressBar() {
        binding.progressBar.visibility = View.GONE
    }
    
    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
        } else {
            restorePosition = mediaPlayer?.time ?: 0L
            restorePlaying = mediaPlayer?.isPlaying ?: false
            mediaPlayer?.stop()
            mediaPlayer?.attachViews(binding.vlcSurfaceView, null, false, false)
            if (currentPath.isNotEmpty()) {
                loadVideo(currentPath)
            }
        }
        if (isSmbFile) {
            CoroutineScope(Dispatchers.IO).launch {
                SmbManager.checkAndReconnect()
            }
        }
    }
    
    private fun releaseAllResources() {
        stopProgressUpdate()
        handler.removeCallbacks(hideGestureHintRunnable)
        handler.removeCallbacks(hideControlsRunnable)
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
