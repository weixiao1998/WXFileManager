package dev.weixiao.wxfilemanager.ui.viewer

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeAfterAudioFocusGain = false
    private var wasPlayingBeforeNoisy = false
    private var isNoisyReceiverRegistered = false
    private var isDuckingVolume = false
    private var volumeBeforeDuck = 100

    private val hideGestureHintRunnable = Runnable { hideGestureHint() }
    private val hideSeekHintRunnable = Runnable { hideSeekHint() }

    private val hideControlsRunnable = Runnable { hideControls() }

    private var videoList: List<FileModel> = emptyList()
    private var currentEpisodePosition = 0
    private lateinit var episodeAdapter: VideoEpisodeAdapter
    private lateinit var landscapeEpisodeAdapter: VideoEpisodeAdapter
    private var isPlaylistPanelVisible = false
    
    // 标记当前是否为 .ts 文件（若 duration 为 0 则使用 position 进度）
    private var isTsFile = false
    
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (isControlsLocked) return@Runnable
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.setRate(3.0f)
            isFastForwarding = true
            showSpeedHint(true)
            shouldIgnoreTap = true
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterAudioFocusGain = false
                pauseForAudioInterrupt()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeAfterAudioFocusGain = mediaPlayer?.isPlaying == true
                pauseForAudioInterrupt()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckPlayerVolume()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                restorePlayerVolumeAfterDuck()
                hasAudioFocus = true
                if (resumeAfterAudioFocusGain) {
                    resumeAfterAudioFocusGain = false
                    mediaPlayer?.play()
                    updatePlayPauseButton(true)
                    startProgressUpdate()
                }
            }
        }
    }

    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                wasPlayingBeforeNoisy = mediaPlayer?.isPlaying == true
                if (wasPlayingBeforeNoisy) {
                    restorePlayerVolumeAfterDuck()
                    mediaPlayer?.pause()
                    updatePlayPauseButton(false)
                    stopProgressUpdate()
                    unregisterNoisyAudioReceiver()
                    abandonAudioFocus()
                    Toast.makeText(this@VlcVideoPlayerActivity, "音频设备断开，已暂停播放", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var currentSpuDelay: Long = 0L
    private var currentAudioDelay: Long = 0L
    private var skipNextResumeReload = false

    private enum class RepeatMode { LIST, ONE, RANDOM }
    private enum class VideoScaleMode(val label: String) {
        BEST_FIT("适应屏幕"),
        FILL("填充屏幕"),
        SIXTEEN_NINE("16:9"),
        FOUR_THREE("4:3"),
        ORIGINAL("原始比例")
    }

    private var repeatMode: RepeatMode = RepeatMode.LIST
    private var videoScaleMode: VideoScaleMode = VideoScaleMode.BEST_FIT

    private val subtitlePickerLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                loadExternalSubtitle(uri.toString())
            }
        }
    }

    private val audioTrackPickerLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                loadExternalAudioTrack(uri.toString())
            }
        }
    }

    companion object {
        private const val TAG = "VlcVideoPlayer"
        private val SUBTITLE_EXTENSIONS = listOf("srt", "vtt", "ass", "ssa")
        private val AUDIO_EXTENSIONS = listOf("mp3", "aac", "ac3", "dts", "flac", "m4a", "wav", "ogg", "opus", "mka")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        supportActionBar?.hide()
        
        binding = ActivityVlcVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBackPressedHandler()

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
    
    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isPlaylistPanelVisible) {
                    hidePlaylistPanel()
                    return
                }
                releaseAllResources()
                finish()
            }
        })
    }

    private fun setupVlcPlayer(): Boolean {
        return try {
            val args = ArrayList<String>().apply {
                add("--avcodec-hw=any")
                add("--network-caching=1500")
                add("--aout=opensles")
                add("--no-video-title-show")
                add("--verbose=0")
                // 启用 timeshift 以支持 .ts 等流式格式的 seek 和时长获取
                add("--input-timeshift-granularity=0")
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
                            applyVideoScaleMode()
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
                            handler.post { handleEndReached() }
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
        episodeAdapter = VideoEpisodeAdapter { _, position ->
            playVideoAt(position)
        }
        landscapeEpisodeAdapter = VideoEpisodeAdapter { _, position ->
            playVideoAt(position)
            hidePlaylistPanel()
        }
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = episodeAdapter
        binding.rvEpisodesLandscape.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodesLandscape.adapter = landscapeEpisodeAdapter
    }

    private fun loadVideoList(currentPath: String, isSmb: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
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
                        landscapeEpisodeAdapter.setCurrentPlaying(currentEpisodePosition)
                    }
                    episodeAdapter.submitList(videoList) {
                        if (currentEpisodePosition >= 0) {
                            binding.rvEpisodes.scrollToPosition(currentEpisodePosition)
                        }
                    }
                    landscapeEpisodeAdapter.submitList(videoList) {
                        if (currentEpisodePosition >= 0) {
                            binding.rvEpisodesLandscape.scrollToPosition(currentEpisodePosition)
                        }
                    }
                    val title = "选集 (共${videoList.size}个)"
                    binding.tvEpisodeTitle.text = title
                    binding.tvPlaylistTitleLandscape.text = title
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
        currentSpuDelay = 0L

        episodeAdapter.setCurrentPlaying(position)
        landscapeEpisodeAdapter.setCurrentPlaying(position)

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
            togglePlaylistPanel()
        }

        binding.btnClosePlaylistLandscape.setOnClickListener {
            hidePlaylistPanel()
        }

        binding.playlistPanelLandscape.setOnClickListener { }

        binding.btnRepeatLandscape.setOnClickListener {
            cycleRepeatMode()
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
                binding.btnLockLandscape.setImageResource(R.drawable.ic_lock_on)
                Toast.makeText(this, "已锁定", Toast.LENGTH_SHORT).show()
            } else {
                showControls()
                binding.btnLockLandscape.setImageResource(R.drawable.ic_lock_off)
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
                    if (isTsFile) {
                        val newPos = progress / 1000f
                        mediaPlayer?.position = newPos
                        showTsSeekHint(newPos)
                    } else {
                        val duration = mediaPlayer?.length ?: 0L
                        if (duration > 0) {
                            val position = (progress * duration / 1000L).toLong()
                            mediaPlayer?.setTime(position)
                            showSeekHint(position, duration)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = true
                showControlsForSeek()
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = false
                handler.removeCallbacks(hideSeekHintRunnable)
                handler.postDelayed(hideSeekHintRunnable, 800)
                scheduleHideControls()
            }
        })
        
        binding.seekbarLandscape.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null && !isUpdatingSeekBar) {
                    if (isTsFile) {
                        val newPos = progress / 1000f
                        mediaPlayer?.position = newPos
                        showTsSeekHint(newPos)
                    } else {
                        val duration = mediaPlayer?.length ?: 0L
                        if (duration > 0) {
                            val position = (progress * duration / 1000L).toLong()
                            mediaPlayer?.setTime(position)
                            showSeekHint(position, duration)
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = true
                showControlsForSeek()
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = false
                handler.removeCallbacks(hideSeekHintRunnable)
                handler.postDelayed(hideSeekHintRunnable, 800)
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
                if (isControlsLocked) return true
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
                    if (mediaPlayer?.isPlaying == true && !isControlsLocked) {
                        longPressHandler.postDelayed(longPressRunnable, 500)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchStartX
                    val deltaY = event.rawY - touchStartY
                    
                    if (kotlin.math.abs(deltaX) > 50 && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 2 && !isAdjustingVolume && !isAdjustingBrightness) {
                        if (isControlsLocked) return@OnTouchListener true
                        if (!isSeeking) {
                            isSeeking = true
                            longPressHandler.removeCallbacks(longPressRunnable)
                            shouldIgnoreTap = true
                            showControlsForSeek()
                        }
                        
                        if (isTsFile) {
                            val viewWidth = if (isLandscape) binding.landscapeContainer.width else binding.vlcSurfaceView.width
                            val seekPercent = (deltaX / viewWidth * 0.1f)
                            val currentPos = mediaPlayer?.position ?: 0f
                            val newPos = (currentPos + seekPercent).coerceIn(0f, 1f)
                            isUpdatingSeekBar = true
                            val progress = (newPos * 1000).toInt().coerceIn(0, 1000)
                            binding.seekbarPortrait.progress = progress
                            binding.seekbarLandscape.progress = progress
                            isUpdatingSeekBar = false
                            showTsSeekHint(newPos, seekPercent)
                        } else {
                            val duration = mediaPlayer?.length ?: 0
                            if (duration > 0) {
                                val seekRange = 60_000L
                                val viewWidth = if (isLandscape) binding.landscapeContainer.width else binding.vlcSurfaceView.width
                                val seekDelta = (deltaX / viewWidth * seekRange).toLong()
                                val newPosition = (seekStartMs + seekDelta).coerceIn(0, duration)
                                updateSeekHint(newPosition, seekDelta)
                            }
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
                        if (isTsFile) {
                            // .ts 文件使用 position 百分比跳转
                            val viewWidth = if (isLandscape) binding.landscapeContainer.width else binding.vlcSurfaceView.width
                            val seekPercent = (deltaX / viewWidth * 0.1f)
                            val currentPos = mediaPlayer?.position ?: 0f
                            val newPos = (currentPos + seekPercent).coerceIn(0f, 1f)
                            mediaPlayer?.position = newPos
                        } else {
                            val duration = mediaPlayer?.length ?: 0
                            if (duration > 0) {
                                val seekRange = 60_000L
                                val viewWidth = if (isLandscape) binding.landscapeContainer.width else binding.vlcSurfaceView.width
                                val seekDelta = (deltaX / viewWidth * seekRange).toLong()
                                val newPosition = (seekStartMs + seekDelta).coerceIn(0, duration)
                                mediaPlayer?.setTime(newPosition)
                            }
                        }
                        isSeeking = false
                        handler.removeCallbacks(hideSeekHintRunnable)
                        handler.postDelayed(hideSeekHintRunnable, 800)
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
            isPlaylistPanelVisible = false
            binding.playlistPanelLandscape.visibility = View.GONE
            binding.landscapeContainer.visibility = View.GONE

            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())

            showControls()
        }
    }
    
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun pauseForAudioInterrupt() {
        restorePlayerVolumeAfterDuck()
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            updatePlayPauseButton(false)
            stopProgressUpdate()
        }
    }

    private fun duckPlayerVolume() {
        val player = mediaPlayer ?: return
        if (!isDuckingVolume) {
            volumeBeforeDuck = player.volume.coerceAtLeast(0)
            isDuckingVolume = true
        }
        val duckVolume = if (volumeBeforeDuck <= 0) {
            0
        } else {
            (volumeBeforeDuck * 0.3f).toInt().coerceIn(1, volumeBeforeDuck)
        }
        player.volume = duckVolume
    }

    private fun restorePlayerVolumeAfterDuck() {
        if (!isDuckingVolume) return
        mediaPlayer?.volume = volumeBeforeDuck.coerceAtLeast(0)
        isDuckingVolume = false
    }

    private fun registerNoisyAudioReceiver() {
        if (isNoisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyAudioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyAudioReceiver, filter)
        }
        isNoisyReceiverRegistered = true
    }

    private fun unregisterNoisyAudioReceiver() {
        if (!isNoisyReceiverRegistered) return
        try {
            unregisterReceiver(noisyAudioReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "音频断开监听未注册或已注销", e)
        }
        isNoisyReceiverRegistered = false
    }

    private fun loadVideo(path: String) {
        isTsFile = path.endsWith(".ts", ignoreCase = true)
        
        lifecycleScope.launch(Dispatchers.IO) {
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
                        
                        val media = Media(vlcInstance, uri).apply {
                            if (isTsFile) {
                                addOption(":input-timeshift=1")
                                addOption(":file-caching=5000")
                                addOption(":network-caching=5000")
                                addOption(":smb-seekable=1")
                                addOption(":ts-seek-percent=1")
                            }
                        }
                        
                        player.media = media
                        media.release()
                        if (requestAudioFocus()) {
                            registerNoisyAudioReceiver()
                            player.play()
                        } else {
                            Toast.makeText(this@VlcVideoPlayerActivity, "无法获取音频焦点", Toast.LENGTH_SHORT).show()
                        }
                        
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
            stopProgressUpdate()
            unregisterNoisyAudioReceiver()
            restorePlayerVolumeAfterDuck()
            abandonAudioFocus()
        } else if (requestAudioFocus()) {
            registerNoisyAudioReceiver()
            player.play()
            updatePlayPauseButton(true)
            startProgressUpdate()
        } else {
            Toast.makeText(this, "无法获取音频焦点", Toast.LENGTH_SHORT).show()
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
        if (isTsFile) {
            // .ts 文件使用 position 百分比跳转（约 10 秒 ≈ 假设总时长 1 小时的 0.28%）
            // 简化处理：每次快进/快退 2% 的位置
            val offsetPercent = if (offsetMs > 0) 0.02f else -0.02f
            val newPos = (player.position + offsetPercent).coerceIn(0f, 1f)
            player.position = newPos
        } else {
            val newPosition = (player.time + offsetMs).coerceIn(0, player.length.toLong())
            player.setTime(newPosition)
        }
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
        val duration = player.length
        
        val timeText = formatTime(player.time)
        binding.tvCurrentTimePortrait.text = timeText
        binding.tvCurrentTimeLandscape.text = timeText
        
        if (duration > 0) {
            val progress = ((player.time * 1000) / duration).toInt()
            binding.seekbarPortrait.progress = progress.coerceIn(0, 1000)
            binding.seekbarLandscape.progress = progress.coerceIn(0, 1000)
        } else if (isTsFile) {
            val pos = player.position
            val progress = (pos * 1000).toInt()
            binding.seekbarPortrait.progress = progress.coerceIn(0, 1000)
            binding.seekbarLandscape.progress = progress.coerceIn(0, 1000)
        }
    }
    
    private fun updateDuration() {
        val player = mediaPlayer ?: return
        val duration = player.length
        if (duration > 0) {
            val durationText = formatTime(duration)
            binding.tvDurationPortrait.text = durationText
            binding.tvDurationLandscape.text = durationText
            binding.tvVideoInfo.text = "时长: $durationText"
        } else if (isTsFile) {
            binding.tvDurationPortrait.text = "--:--"
            binding.tvDurationLandscape.text = "--:--"
            binding.tvVideoInfo.text = "时长: --:--"
        }
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

    private fun togglePlaylistPanel() {
        if (isPlaylistPanelVisible) {
            hidePlaylistPanel()
        } else {
            showPlaylistPanel()
        }
    }

    private fun showPlaylistPanel() {
        if (!isLandscape || isControlsLocked) return
        isPlaylistPanelVisible = true
        handler.removeCallbacks(hideControlsRunnable)
        binding.playlistPanelLandscape.visibility = View.VISIBLE
        if (currentEpisodePosition >= 0 && videoList.isNotEmpty()) {
            binding.rvEpisodesLandscape.scrollToPosition(currentEpisodePosition)
        }
    }

    private fun hidePlaylistPanel() {
        isPlaylistPanelVisible = false
        binding.playlistPanelLandscape.visibility = View.GONE
        if (controlsVisible) {
            scheduleHideControls()
        }
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
        
        if (duration > 0) {
            isUpdatingSeekBar = true
            val progress = ((positionMs * 1000) / duration).toInt()
            binding.seekbarPortrait.progress = progress.coerceIn(0, 1000)
            binding.seekbarLandscape.progress = progress.coerceIn(0, 1000)
            isUpdatingSeekBar = false
        }
        
        val timeText = formatTime(positionMs)
        binding.tvCurrentTimePortrait.text = timeText
        binding.tvCurrentTimeLandscape.text = timeText
        showSeekHint(positionMs, duration, deltaMs)
    }

    private fun showSeekHint(positionMs: Long, durationMs: Long, deltaMs: Long? = null) {
        if (!isLandscape) return
        handler.removeCallbacks(hideSeekHintRunnable)
        val deltaText = deltaMs?.let {
            val sign = if (it >= 0) "+" else "-"
            "$sign${formatTime(kotlin.math.abs(it))}\n"
        } ?: ""
        val durationText = if (durationMs > 0) formatTime(durationMs) else "--:--"
        binding.tvSeekHintLandscape.text = "$deltaText${formatTime(positionMs)} / $durationText"
        binding.tvSeekHintLandscape.visibility = View.VISIBLE
    }

    private fun showTsSeekHint(position: Float, delta: Float? = null) {
        if (!isLandscape) return
        handler.removeCallbacks(hideSeekHintRunnable)
        val percent = (position * 100).toInt().coerceIn(0, 100)
        val deltaText = delta?.let {
            val sign = if (it >= 0f) "+" else "-"
            val deltaPercent = (kotlin.math.abs(it) * 100).toInt()
            "$sign$deltaPercent%\n"
        } ?: ""
        binding.tvSeekHintLandscape.text = "$deltaText$percent%"
        binding.tvSeekHintLandscape.visibility = View.VISIBLE
    }

    private fun hideSeekHint() {
        binding.tvSeekHintLandscape.visibility = View.GONE
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
        handler.removeCallbacks(hideSeekHintRunnable)
        hideSeekHint()
        isPlaylistPanelVisible = false
        binding.playlistPanelLandscape.visibility = View.GONE

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
        lifecycleScope.launch(Dispatchers.IO) {
            val externalSubtitles = if (isSmbFile) {
                SmbManager.findSubtitles(currentPath).mapNotNull { f ->
                    f.smbUrl?.let { f.name to it }
                }
            } else {
                findLocalSubtitles(currentPath).map { it.name to "file://${it.absolutePath}" }
            }

            val embeddedTracks = withContext(Dispatchers.Main) {
                mediaPlayer?.spuTracks?.toList() ?: emptyList()
            }

            withContext(Dispatchers.Main) {
                showSubtitleDialog(embeddedTracks, externalSubtitles)
            }
        }
    }

    private fun showSubtitleDialog(
        embeddedTracks: List<MediaPlayer.TrackDescription>,
        externalSubtitles: List<Pair<String, String>>
    ) {
        val currentSpuTrack = mediaPlayer?.spuTrack ?: -1

        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val closePrefix = if (currentSpuTrack <= 0) "● " else "  "
        items.add("${closePrefix}关闭")
        actions.add {
            mediaPlayer?.setSpuTrack(-1)
            Toast.makeText(this, "字幕已关闭", Toast.LENGTH_SHORT).show()
        }

        for (track in embeddedTracks) {
            if (track.id <= 0) continue
            val prefix = if (track.id == currentSpuTrack) "● " else "  "
            items.add("${prefix}${track.name}")
            actions.add {
                mediaPlayer?.setSpuTrack(track.id)
                Toast.makeText(this, track.name, Toast.LENGTH_SHORT).show()
            }
        }

        for ((name, uri) in externalSubtitles) {
            items.add("  $name")
            actions.add {
                loadExternalSubtitle(uri)
            }
        }

        items.add("手动选择字幕文件")
        actions.add {
            openSubtitleFilePicker()
        }

        items.add("字幕延迟调整")
        actions.add {
            showSubtitleDelayDialog()
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("字幕选择")
            .setItems(items.toTypedArray()) { _, which ->
                actions[which]()
            }
            .show()
    }

    private fun findLocalSubtitles(videoPath: String): List<JFile> {
        val videoFile = JFile(videoPath)
        val parentDir = videoFile.parentFile ?: return emptyList()
        val videoBaseName = videoFile.nameWithoutExtension

        return parentDir.listFiles()?.filter { file ->
            val ext = file.extension.lowercase()
            val baseName = file.nameWithoutExtension
            SUBTITLE_EXTENSIONS.contains(ext) && baseName.startsWith(videoBaseName)
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun loadExternalSubtitle(uriString: String) {
        val player = mediaPlayer ?: return
        val success = player.addSlave(1, uriString, true)
        if (success) {
            Toast.makeText(this, "字幕已加载", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "字幕加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSubtitleFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/x-subrip",
                "text/vtt",
                "text/plain"
            ))
        }
        try {
            skipNextResumeReload = true
            subtitlePickerLauncher.launch(intent)
        } catch (e: Exception) {
            skipNextResumeReload = false
            Log.e(TAG, "无法打开文件选择器", e)
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSubtitleDelayDialog() {
        val delayMs = mediaPlayer?.spuDelay ?: currentSpuDelay

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val delayText = android.widget.TextView(this).apply {
            text = formatSubtitleDelay(delayMs)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMinus = android.widget.Button(this).apply {
            text = "-0.5s"
            setOnClickListener {
                val newDelay = (mediaPlayer?.spuDelay ?: currentSpuDelay) - 500
                currentSpuDelay = newDelay
                mediaPlayer?.setSpuDelay(newDelay)
                delayText.text = formatSubtitleDelay(newDelay)
            }
        }

        val btnPlus = android.widget.Button(this).apply {
            text = "+0.5s"
            setOnClickListener {
                val newDelay = (mediaPlayer?.spuDelay ?: currentSpuDelay) + 500
                currentSpuDelay = newDelay
                mediaPlayer?.setSpuDelay(newDelay)
                delayText.text = formatSubtitleDelay(newDelay)
            }
        }

        container.addView(btnMinus)
        container.addView(delayText)
        container.addView(btnPlus)

        android.app.AlertDialog.Builder(this)
            .setTitle("字幕延迟调整")
            .setView(container)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun formatSubtitleDelay(delayMs: Long): String {
        val sec = delayMs / 1000.0
        return if (sec >= 0) "+${String.format("%.1f", sec)}s" else "${String.format("%.1f", sec)}s"
    }

    private fun showAudioTrackSelectionDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val externalAudios = if (isSmbFile) {
                SmbManager.findAudioTracks(currentPath).mapNotNull { f ->
                    f.smbUrl?.let { f.name to it }
                }
            } else {
                findLocalAudioTracks(currentPath).map { it.name to "file://${it.absolutePath}" }
            }

            val embeddedTracks = withContext(Dispatchers.Main) {
                mediaPlayer?.audioTracks?.toList() ?: emptyList()
            }

            withContext(Dispatchers.Main) {
                showAudioTrackDialog(embeddedTracks, externalAudios)
            }
        }
    }

    private fun showAudioTrackDialog(
        embeddedTracks: List<MediaPlayer.TrackDescription>,
        externalAudios: List<Pair<String, String>>
    ) {
        val currentAudioTrack = mediaPlayer?.audioTrack ?: -1

        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val closePrefix = if (currentAudioTrack <= 0) "● " else "  "
        items.add("${closePrefix}关闭")
        actions.add {
            mediaPlayer?.setAudioTrack(-1)
            Toast.makeText(this, "音轨已关闭", Toast.LENGTH_SHORT).show()
        }

        for (track in embeddedTracks) {
            if (track.id <= 0) continue
            val prefix = if (track.id == currentAudioTrack) "● " else "  "
            items.add("${prefix}${track.name}")
            actions.add {
                mediaPlayer?.setAudioTrack(track.id)
                Toast.makeText(this, track.name, Toast.LENGTH_SHORT).show()
            }
        }

        for ((name, uri) in externalAudios) {
            items.add("  $name")
            actions.add {
                loadExternalAudioTrack(uri)
            }
        }

        items.add("手动选择音轨文件")
        actions.add {
            openAudioTrackFilePicker()
        }

        items.add("音频延迟调整")
        actions.add {
            showAudioDelayDialog()
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("音轨选择")
            .setItems(items.toTypedArray()) { _, which ->
                actions[which]()
            }
            .show()
    }

    private fun findLocalAudioTracks(videoPath: String): List<JFile> {
        val videoFile = JFile(videoPath)
        val parentDir = videoFile.parentFile ?: return emptyList()
        val videoBaseName = videoFile.nameWithoutExtension

        return parentDir.listFiles()?.filter { file ->
            val ext = file.extension.lowercase()
            val baseName = file.nameWithoutExtension
            AUDIO_EXTENSIONS.contains(ext) && baseName.startsWith(videoBaseName)
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun loadExternalAudioTrack(uriString: String) {
        val player = mediaPlayer ?: return
        val success = player.addSlave(0, uriString, true)
        if (success) {
            Toast.makeText(this, "音轨已加载", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "音轨加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAudioTrackFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        try {
            skipNextResumeReload = true
            audioTrackPickerLauncher.launch(intent)
        } catch (e: Exception) {
            skipNextResumeReload = false
            Log.e(TAG, "无法打开文件选择器", e)
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAudioDelayDialog() {
        val delayUs = mediaPlayer?.audioDelay ?: currentAudioDelay

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val delayText = android.widget.TextView(this).apply {
            text = formatAudioDelay(delayUs)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMinus = android.widget.Button(this).apply {
            text = "-0.5s"
            setOnClickListener {
                val newDelay = (mediaPlayer?.audioDelay ?: currentAudioDelay) - 500_000L
                currentAudioDelay = newDelay
                mediaPlayer?.setAudioDelay(newDelay)
                delayText.text = formatAudioDelay(newDelay)
            }
        }

        val btnPlus = android.widget.Button(this).apply {
            text = "+0.5s"
            setOnClickListener {
                val newDelay = (mediaPlayer?.audioDelay ?: currentAudioDelay) + 500_000L
                currentAudioDelay = newDelay
                mediaPlayer?.setAudioDelay(newDelay)
                delayText.text = formatAudioDelay(newDelay)
            }
        }

        container.addView(btnMinus)
        container.addView(delayText)
        container.addView(btnPlus)

        android.app.AlertDialog.Builder(this)
            .setTitle("音频延迟调整")
            .setView(container)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun formatAudioDelay(delayUs: Long): String {
        val sec = delayUs / 1_000_000.0
        return if (sec >= 0) "+${String.format("%.1f", sec)}s" else String.format("%.1f", sec) + "s"
    }

    private fun takeScreenshot() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "播放器未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        val surfaceView = findVideoSurfaceView(binding.vlcSurfaceView)
        if (surfaceView == null || surfaceView.width <= 0 || surfaceView.height <= 0) {
            Toast.makeText(this, "视频尚未就绪，无法截图", Toast.LENGTH_SHORT).show()
            return
        }

        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        val copyThread = HandlerThread("ScreenshotPixelCopy").apply { start() }
        val copyHandler = Handler(copyThread.looper)

        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                copyThread.quitSafely()
                if (result != PixelCopy.SUCCESS) {
                    runOnUiThread {
                        Toast.makeText(this, "截图失败 (code=$result)", Toast.LENGTH_SHORT).show()
                    }
                    bitmap.recycle()
                    return@request
                }
                persistScreenshot(bitmap)
            }, copyHandler)
        } catch (e: Throwable) {
            Log.e(TAG, "PixelCopy 调用失败", e)
            copyThread.quitSafely()
            bitmap.recycle()
            Toast.makeText(this, "截图失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findVideoSurfaceView(root: View): SurfaceView? {
        if (root is SurfaceView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val found = findVideoSurfaceView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun persistScreenshot(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val baseName = currentName.substringBeforeLast('.', currentName).ifBlank { "video" }
        val safeName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val fileName = "${safeName}_$timestamp.png"

        lifecycleScope.launch(Dispatchers.IO) {
            val savedLocation = saveBitmapToGallery(bitmap, fileName)
            bitmap.recycle()
            withContext(Dispatchers.Main) {
                if (savedLocation != null) {
                    Toast.makeText(
                        this@VlcVideoPlayerActivity,
                        "截图已保存：$savedLocation",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this@VlcVideoPlayerActivity, "保存截图失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, displayName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = Environment.DIRECTORY_PICTURES + "/WXFileManager/Screenshots"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        return null
                    }
                } ?: return null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "$relativePath/$displayName"
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val targetDir = JFile(picturesDir, "WXFileManager/Screenshots")
                if (!targetDir.exists() && !targetDir.mkdirs()) return null
                val destFile = JFile(targetDir, displayName)
                destFile.outputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        return null
                    }
                }
                MediaScannerConnection.scanFile(
                    this,
                    arrayOf(destFile.absolutePath),
                    arrayOf("image/png"),
                    null
                )
                destFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存截图到相册失败", e)
            null
        }
    }

    private fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.LIST -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.RANDOM
            RepeatMode.RANDOM -> RepeatMode.LIST
        }
        updateRepeatModeUi()
        val tip = when (repeatMode) {
            RepeatMode.LIST -> "列表循环"
            RepeatMode.ONE -> "单集循环"
            RepeatMode.RANDOM -> "随机播放"
        }
        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
    }

    private fun updateRepeatModeUi() {
        val iconRes = when (repeatMode) {
            RepeatMode.LIST -> R.drawable.ic_repeat_list
            RepeatMode.ONE -> R.drawable.ic_repeat_one
            RepeatMode.RANDOM -> R.drawable.ic_repeat_random
        }
        binding.btnRepeatLandscape.setImageResource(iconRes)
    }

    private fun handleEndReached() {
        when (repeatMode) {
            RepeatMode.ONE -> {
                if (currentPath.isNotEmpty()) {
                    loadVideo(currentPath)
                } else {
                    finish()
                }
            }
            RepeatMode.LIST -> {
                if (videoList.isEmpty()) {
                    finish()
                } else {
                    val next = if (currentEpisodePosition < videoList.size - 1) {
                        currentEpisodePosition + 1
                    } else {
                        0
                    }
                    playVideoAt(next)
                }
            }
            RepeatMode.RANDOM -> {
                if (videoList.isEmpty()) {
                    finish()
                } else if (videoList.size == 1) {
                    loadVideo(currentPath)
                } else {
                    var next: Int
                    do {
                        next = (0 until videoList.size).random()
                    } while (next == currentEpisodePosition)
                    playVideoAt(next)
                }
            }
        }
    }

    private fun showMenuDialog() {
        val items = arrayOf(
            "画面比例：${videoScaleMode.label}",
            "视频信息"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("播放菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showVideoScaleDialog()
                    1 -> showVideoInfoDialog()
                }
            }
            .show()
    }

    private fun showVideoScaleDialog() {
        val modes = VideoScaleMode.entries.toTypedArray()
        val labels = modes.map { it.label }.toTypedArray()
        val checked = modes.indexOf(videoScaleMode)
        android.app.AlertDialog.Builder(this)
            .setTitle("画面比例")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                videoScaleMode = modes[which]
                applyVideoScaleMode()
                Toast.makeText(this, "已切换为：${videoScaleMode.label}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    private fun applyVideoScaleMode() {
        val player = mediaPlayer ?: return
        val surfaceWidth = binding.vlcSurfaceView.width
        val surfaceHeight = binding.vlcSurfaceView.height
        when (videoScaleMode) {
            VideoScaleMode.BEST_FIT -> {
                player.aspectRatio = null
                player.scale = 0f
            }
            VideoScaleMode.FILL -> {
                player.aspectRatio = if (surfaceWidth > 0 && surfaceHeight > 0) {
                    "$surfaceWidth:$surfaceHeight"
                } else {
                    null
                }
                player.scale = 0f
            }
            VideoScaleMode.SIXTEEN_NINE -> {
                player.aspectRatio = "16:9"
                player.scale = 0f
            }
            VideoScaleMode.FOUR_THREE -> {
                player.aspectRatio = "4:3"
                player.scale = 0f
            }
            VideoScaleMode.ORIGINAL -> {
                player.aspectRatio = null
                player.scale = 1f
            }
        }
    }

    private fun showVideoInfoDialog() {
        val player = mediaPlayer
        val duration = player?.length ?: 0L
        val currentTime = player?.time ?: 0L
        val videoTrack = try {
            player?.currentVideoTrack
        } catch (e: Exception) {
            null
        }
        val resolution = if (videoTrack != null && videoTrack.width > 0 && videoTrack.height > 0) {
            "${videoTrack.width} x ${videoTrack.height}"
        } else {
            "未知"
        }
        val progress = if (duration > 0) {
            "${formatTime(currentTime)} / ${formatTime(duration)}"
        } else {
            "${formatTime(currentTime)} / --:--"
        }
        val sourceType = if (isSmbFile) "SMB 网络文件" else "本地文件"
        val repeatText = when (repeatMode) {
            RepeatMode.LIST -> "列表循环"
            RepeatMode.ONE -> "单集循环"
            RepeatMode.RANDOM -> "随机播放"
        }
        val info = buildString {
            appendLine("文件名：$currentName")
            appendLine("来源：$sourceType")
            appendLine("分辨率：$resolution")
            appendLine("播放进度：$progress")
            appendLine("播放速度：${String.format(Locale.getDefault(), "%.2fx", player?.rate ?: 1.0f)}")
            appendLine("画面比例：${videoScaleMode.label}")
            appendLine("循环模式：$repeatText")
            appendLine("字幕延迟：${formatSubtitleDelay(currentSpuDelay)}")
            appendLine("音频延迟：${formatAudioDelay(currentAudioDelay)}")
            appendLine("路径：$currentPath")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("视频信息")
            .setMessage(info)
            .setPositiveButton("确定", null)
            .show()
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
        if (skipNextResumeReload) {
            skipNextResumeReload = false
        } else if (!isFirstResume) {
            restorePosition = mediaPlayer?.time ?: 0L
            restorePlaying = mediaPlayer?.isPlaying ?: false
            mediaPlayer?.stop()
            mediaPlayer?.attachViews(binding.vlcSurfaceView, null, false, false)
            if (currentPath.isNotEmpty()) {
                loadVideo(currentPath)
            }
        }
        if (isFirstResume) {
            isFirstResume = false
        }
        if (isSmbFile) {
            lifecycleScope.launch(Dispatchers.IO) {
                SmbManager.checkAndReconnect()
            }
        }
    }
    
    private fun releaseAllResources() {
        stopProgressUpdate()
        handler.removeCallbacks(hideGestureHintRunnable)
        handler.removeCallbacks(hideSeekHintRunnable)
        handler.removeCallbacks(hideControlsRunnable)
        isPlaylistPanelVisible = false
        binding.playlistPanelLandscape.visibility = View.GONE
        unregisterNoisyAudioReceiver()
        restorePlayerVolumeAfterDuck()
        abandonAudioFocus()
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
    
}
