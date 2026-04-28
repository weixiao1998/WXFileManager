package top.weixiaoweb.wxfilemanager.ui.viewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
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
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.weixiaoweb.wxfilemanager.R
import top.weixiaoweb.wxfilemanager.adapter.VideoEpisodeAdapter
import top.weixiaoweb.wxfilemanager.databinding.ActivityVideoPlayerBinding
import top.weixiaoweb.wxfilemanager.model.FileModel
import top.weixiaoweb.wxfilemanager.utils.SafManager
import top.weixiaoweb.wxfilemanager.utils.SmbDataSource
import top.weixiaoweb.wxfilemanager.utils.SmbManager
import java.io.File as JFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoPlayer"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var isSmbFile = false
    private var currentPath: String = ""
    private var currentName: String = ""
    private var wasPlaying = false
    private var isLandscape = false
    private var controlsVisible = false
    private var isFastForwarding = false
    private var isControlsLocked = false
    private var repeatMode = Player.REPEAT_MODE_OFF
    
    private lateinit var gestureDetector: GestureDetector
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (player?.isPlaying == true) {
            player?.playbackParameters = PlaybackParameters(3.0f)
            isFastForwarding = true
            showSpeedHint(true)
        }
    }
    
    private var videoList: List<FileModel> = emptyList()
    private var currentPosition = 0
    private lateinit var episodeAdapter: VideoEpisodeAdapter
    
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isSeeking = false
    private var seekStartMs = 0L
    
    private val seekHintHandler = Handler(Looper.getMainLooper())
    private val hideSeekHintRunnable = Runnable {
        if (isLandscape) {
            binding.tvSeekHintLandscape.visibility = View.GONE
        } else {
            binding.tvSeekHintPortrait.visibility = View.GONE
        }
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isSeeking) {
                player?.let { exoPlayer ->
                    if (exoPlayer.duration > 0) {
                        val progress = ((exoPlayer.currentPosition * 1000) / exoPlayer.duration).toInt()
                        if (isLandscape) {
                            binding.seekbarLandscape.progress = progress
                            binding.tvCurrentTimeLandscape.text = formatTime(exoPlayer.currentPosition)
                        } else {
                            binding.seekbarPortrait.progress = progress
                            binding.tvCurrentTimePortrait.text = formatTime(exoPlayer.currentPosition)
                        }
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentName = intent.getStringExtra("name") ?: ""
        val path = intent.getStringExtra("path") ?: ""
        val isSmb = intent.getBooleanExtra("isSmb", false)
        
        isSmbFile = isSmb
        currentPath = path
        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isHi10PVideo(currentName) || isVlcPreferredVideo(currentName)) {
            Log.d(TAG, "检测到Hi10P/VLC优先视频，直接跳转VLC播放器: $currentName")
            switchToAlternativePlayer()
            return
        }

        setupPlayer()
        setupUI()
        setupTouchListener()
        setupEpisodeList()
        
        loadVideo(path, currentName, isSmb)
        loadVideoList(path, isSmb)
        
        updateLayoutForOrientation()
        
        if (isLandscape) {
            hideControls()
        }
    }
    
    @OptIn(UnstableApi::class)
    private fun setupPlayer() {
        val dataSourceFactory: DataSource.Factory = if (isSmbFile) {
            DataSource.Factory { SmbDataSource() }
        } else {
            androidx.media3.datasource.DefaultDataSource.Factory(this)
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        
        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val duration = player?.duration ?: 0
                    if (duration > 0) {
                        if (isLandscape) {
                            binding.tvDurationLandscape.text = formatTime(duration)
                        } else {
                            binding.tvDurationPortrait.text = formatTime(duration)
                        }
                    }
                    updateVideoInfo()
                } else if (playbackState == Player.STATE_IDLE) {
                }
            }
            
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                handlePlaybackError(error)
            }
        })
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        binding.btnRepeatLandscape.setImageResource(R.drawable.ic_repeat_list)
        
        binding.btnBackPortrait.setOnClickListener { finish() }
        binding.btnBackLandscape.setOnClickListener { finish() }

        binding.btnRotatePortrait.setOnClickListener { toggleOrientation() }
        binding.btnRotateLandscape.setOnClickListener { toggleOrientation() }

        val rewind10Listener = View.OnClickListener {
            player?.let { exoPlayer ->
                val newPosition = (exoPlayer.currentPosition - 10_000).coerceAtLeast(0)
                exoPlayer.seekTo(newPosition)
            }
        }
        val forward10Listener = View.OnClickListener {
            player?.let { exoPlayer ->
                val duration = exoPlayer.duration
                if (duration > 0) {
                    val newPosition = (exoPlayer.currentPosition + 10_000).coerceAtMost(duration)
                    exoPlayer.seekTo(newPosition)
                }
            }
        }
        val prevListener = View.OnClickListener {
            if (videoList.isNotEmpty()) {
                val newPosition = if (currentPosition > 0) currentPosition - 1 else videoList.size - 1
                playVideoAt(newPosition)
            }
        }
        val nextListener = View.OnClickListener {
            if (videoList.isNotEmpty()) {
                val newPosition = if (currentPosition < videoList.size - 1) currentPosition + 1 else 0
                playVideoAt(newPosition)
            }
        }
        val playPauseListener = View.OnClickListener { togglePlayPause() }

        binding.btnRewind10Landscape.setOnClickListener(rewind10Listener)
        binding.btnPrevLandscape.setOnClickListener(prevListener)
        binding.btnPlayPausePortrait.setOnClickListener(playPauseListener)
        binding.btnPlayPauseLandscape.setOnClickListener(playPauseListener)
        binding.btnNextLandscape.setOnClickListener(nextListener)
        binding.btnForward10Landscape.setOnClickListener(forward10Listener)

        binding.btnRepeatLandscape.setOnClickListener {
            repeatMode = (repeatMode + 1) % 3
            player?.repeatMode = repeatMode
            when (repeatMode) {
                Player.REPEAT_MODE_OFF -> {
                    binding.btnRepeatLandscape.setImageResource(R.drawable.ic_repeat_list)
                    Toast.makeText(this, "列表播放", Toast.LENGTH_SHORT).show()
                }
                Player.REPEAT_MODE_ONE -> {
                    binding.btnRepeatLandscape.setImageResource(R.drawable.ic_repeat_one)
                    Toast.makeText(this, "单曲循环", Toast.LENGTH_SHORT).show()
                }
                Player.REPEAT_MODE_ALL -> {
                    binding.btnRepeatLandscape.setImageResource(R.drawable.ic_repeat_random)
                    Toast.makeText(this, "随机播放", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSpeedLandscape.setOnClickListener {
            showSpeedSelectionDialog()
        }

        binding.btnPlaylistLandscape.setOnClickListener {
            togglePortraitContainer()
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

        binding.btnSubtitleLandscape.setOnClickListener {
            showSubtitleSelectionDialog()
        }

        binding.btnAudioTrackLandscape.setOnClickListener {
            showAudioTrackSelectionDialog()
        }

        binding.btnMenuLandscape.setOnClickListener {
            showMenuDialog()
        }

        binding.seekbarPortrait.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0
                    val position = (progress * duration) / 1000
                    player?.seekTo(position)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.seekbarLandscape.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0
                    val position = (progress * duration) / 1000
                    player?.seekTo(position)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseButton(isPlaying)
            }
        })
    }

    private fun showSpeedSelectionDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        android.app.AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setItems(speeds) { _, which ->
                val speed = speedValues[which]
                player?.playbackParameters = PlaybackParameters(speed)
                Toast.makeText(this, "${speeds[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun togglePortraitContainer() {
        if (binding.portraitContainer.visibility == View.VISIBLE) {
            binding.portraitContainer.visibility = View.GONE
            binding.landscapeContainer.visibility = View.VISIBLE
        } else {
            binding.portraitContainer.visibility = View.VISIBLE
            binding.landscapeContainer.visibility = View.GONE
            binding.playerViewLandscape.player = null
            binding.playerViewPortrait.player = player
        }
    }

    private fun takeScreenshot() {
        Toast.makeText(this, "截图功能开发中", Toast.LENGTH_SHORT).show()
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
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
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
        
        val touchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    seekStartMs = player?.currentPosition ?: 0
                    isSeeking = false
                    if (player?.isPlaying == true) {
                        longPressHandler.postDelayed(longPressRunnable, 500)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isFastForwarding) {
                        val deltaX = event.x - touchStartX
                        val deltaY = event.y - touchStartY
                        
                        if (kotlin.math.abs(deltaX) > 50 && kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 2) {
                            if (!isSeeking) {
                                isSeeking = true
                                longPressHandler.removeCallbacks(longPressRunnable)
                                showControlsForSeek()
                            }
                            
                            val duration = player?.duration ?: 0
                            if (duration > 0) {
                                val seekRange = 60_000L
                                val viewWidth = if (isLandscape) binding.playerViewLandscape.width else binding.playerViewPortrait.width
                                val seekDelta = (deltaX / viewWidth * seekRange).toLong()
                                val newPosition = (seekStartMs + seekDelta).coerceIn(0, duration)
                                
                                showSeekHint(newPosition, seekDelta)
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    
                    if (isSeeking) {
                        val deltaX = event.x - touchStartX
                        val duration = player?.duration ?: 0
                        if (duration > 0) {
                            val seekRange = 60_000L
                            val viewWidth = if (isLandscape) binding.playerViewLandscape.width else binding.playerViewPortrait.width
                            val seekDelta = (deltaX / viewWidth * seekRange).toLong()
                            val newPosition = (seekStartMs + seekDelta).coerceIn(0, duration)
                            player?.seekTo(newPosition)
                        }
                        isSeeking = false
                        seekHintHandler.removeCallbacks(hideSeekHintRunnable)
                        seekHintHandler.postDelayed(hideSeekHintRunnable, 500)
                        scheduleHideControls()
                    }
                    
                    if (isFastForwarding) {
                        player?.playbackParameters = PlaybackParameters(1.0f)
                        isFastForwarding = false
                        showSpeedHint(false)
                    }
                }
            }
            gestureDetector.onTouchEvent(event)
        }
        
        binding.playerViewPortrait.setOnTouchListener(touchListener)
        binding.playerViewLandscape.setOnTouchListener(touchListener)
    }
    
    private fun showSeekHint(positionMs: Long, deltaMs: Long) {
        val hintView = if (isLandscape) binding.tvSeekHintLandscape else binding.tvSeekHintPortrait
        val sign = if (deltaMs >= 0) "+" else ""
        val timeStr = formatTimeFull(kotlin.math.abs(deltaMs))
        val positionStr = formatTimeFull(positionMs)
        hintView.text = "$sign$timeStr\n$positionStr"
        hintView.visibility = View.VISIBLE
        
        val duration = player?.duration ?: 0
        if (duration > 0) {
            val progress = ((positionMs * 1000) / duration).toInt()
            if (isLandscape) {
                binding.seekbarLandscape.progress = progress
                binding.tvCurrentTimeLandscape.text = formatTime(positionMs)
            } else {
                binding.seekbarPortrait.progress = progress
                binding.tvCurrentTimePortrait.text = formatTime(positionMs)
            }
        }
    }
    
    private fun showSpeedHint(show: Boolean) {
        val hintView = if (isLandscape) binding.tvSpeedHintLandscape else binding.tvSpeedHintPortrait
        if (show) {
            hintView.text = "3x 倍速播放中"
            hintView.visibility = View.VISIBLE
        } else {
            hintView.visibility = View.GONE
        }
    }
    
    private fun formatTimeFull(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
    
    private fun setupEpisodeList() {
        episodeAdapter = VideoEpisodeAdapter { file, position ->
            playVideoAt(position)
        }
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = episodeAdapter
    }
    
    private fun loadVideo(path: String, name: String, isSmb: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            binding.tvTitlePortrait.text = name
            binding.tvTitleLandscape.text = name
            binding.tvVideoTitle.text = name
            
            val uri = if (isSmb) {
                Uri.parse("smb://$path")
            } else {
                if (SafManager.isRestrictedPath(path)) {
                    SafManager.getFileUri(this@VideoPlayerActivity, path) ?: Uri.parse("file://$path")
                } else {
                    Uri.parse("file://$path")
                }
            }
            val mediaItemBuilder = MediaItem.Builder().setUri(uri)
            
            val subtitles = if (isSmb) {
                SmbManager.findSubtitles(path)
            } else {
                if (SafManager.isRestrictedPath(path)) {
                    findSafSubtitles(path)
                } else {
                    findLocalSubtitles(path)
                }
            }
            
            if (subtitles.isNotEmpty()) {
                val subtitleConfigs = subtitles.map { sub ->
                    val subUri = if (isSmb) {
                        Uri.parse("smb://${sub.path}")
                    } else {
                        if (SafManager.isRestrictedPath(sub.path)) {
                            SafManager.getFileUri(this@VideoPlayerActivity, sub.path) ?: Uri.parse("file://${sub.path}")
                        } else {
                            Uri.parse("file://${sub.path}")
                        }
                    }
                    val mimeType = when (sub.name.substringAfterLast('.', "").lowercase()) {
                        "srt" -> MimeTypes.APPLICATION_SUBRIP
                        "vtt" -> MimeTypes.TEXT_VTT
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        else -> MimeTypes.APPLICATION_SUBRIP
                    }
                    MediaItem.SubtitleConfiguration.Builder(subUri)
                        .setMimeType(mimeType)
                        .setLanguage("zh")
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .build()
                }
                mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs)
                Toast.makeText(this@VideoPlayerActivity, "已发现 ${subtitles.size} 个字幕文件", Toast.LENGTH_SHORT).show()
            }

            player?.setMediaItem(mediaItemBuilder.build())
            player?.prepare()
            player?.play()
            
            if (isLandscape) {
                binding.playerViewPortrait.player = null
                binding.playerViewLandscape.player = player
            } else {
                binding.playerViewLandscape.player = null
                binding.playerViewPortrait.player = player
            }
            
            handler.post(updateProgressRunnable)
        }
    }
    
    private fun loadVideoList(currentPath: String, isSmb: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val parentPath = if (isSmb) {
                    currentPath.substringBeforeLast('\\', "")
                } else {
                    JFile(currentPath).parent ?: ""
                }
                
                val files = if (isSmb) {
                    SmbManager.listFiles(parentPath)
                } else {
                    if (SafManager.isRestrictedPath(parentPath)) {
                        SafManager.listFiles(this@VideoPlayerActivity, parentPath)
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
                currentPosition = videoList.indexOfFirst { it.path == currentPath }
                
                withContext(Dispatchers.Main) {
                    if (currentPosition >= 0) {
                        episodeAdapter.setCurrentPlaying(currentPosition)
                    }
                    episodeAdapter.submitList(videoList) {
                        if (currentPosition >= 0) {
                            binding.rvEpisodes.scrollToPosition(currentPosition)
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
        currentPosition = position
        currentPath = file.path
        currentName = file.name
        
        episodeAdapter.setCurrentPlaying(position)
        
        binding.tvTitlePortrait.text = file.name
        binding.tvTitleLandscape.text = file.name
        binding.tvVideoTitle.text = file.name
        
        loadVideo(file.path, file.name, file.isSmb)
    }
    
    private fun handlePlaybackError(error: androidx.media3.common.PlaybackException) {
        val errorMessage = error.message ?: "未知错误"
        android.util.Log.e(TAG, "播放错误: $errorMessage (code: ${error.errorCode})")
        
        showVlcPlayerOption("视频播放失败: $errorMessage")
    }
    
    private fun showVlcPlayerOption(reason: String) {
        val message = "⚠️ $reason\n\n" +
            "可能是不支持的格式（如Hi10P 10-bit视频）。\n\n" +
            "本应用已内置VLC播放器，可以支持此格式！"
        
        android.app.AlertDialog.Builder(this)
            .setTitle("🎬 视频播放失败")
            .setMessage(message)
            .setPositiveButton("🎯 使用内置VLC播放") { _, _ ->
                switchToAlternativePlayer()
            }
            .setNegativeButton("返回") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun isHi10PVideo(fileName: String): Boolean {
        val hi10pPatterns = listOf(
            "hi10p",
            "Hi10P",
            "HI10P",
            "10bit",
            "10Bit",
            "10BIT"
        )

        return hi10pPatterns.any { pattern ->
            fileName.contains(pattern, ignoreCase = true)
        }
    }

    private fun isVlcPreferredVideo(fileName: String): Boolean {
        val vlcPreferredExtensions = listOf("rmvb", "rm")
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return vlcPreferredExtensions.contains(extension)
    }
    
    private fun switchToAlternativePlayer() {
        val intent = android.content.Intent(this, VlcVideoPlayerActivity::class.java).apply {
            putExtra("name", currentName)
            putExtra("path", currentPath)
            putExtra("isSmb", isSmbFile)
        }
        startActivity(intent)
        finish()
    }
    
    private fun updateVideoInfo() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val duration = player?.duration ?: 0
                val videoSize = player?.videoSize
                val width = videoSize?.width ?: 0
                val height = videoSize?.height ?: 0
                
                var fileSize = 0L
                withContext(Dispatchers.IO) {
                    if (isSmbFile) {
                        val smbFile = SmbManager.openFile(currentPath)
                        try {
                            fileSize = smbFile?.fileInformation?.standardInformation?.endOfFile ?: 0L
                        } finally {
                            smbFile?.close()
                        }
                    } else {
                        val file = JFile(currentPath)
                        fileSize = if (file.exists()) file.length() else 0L
                    }
                }
                
                val durationStr = if (duration > 0) {
                    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    sdf.format(Date(duration))
                } else {
                    "未知"
                }
                
                val sizeStr = formatFileSize(fileSize)
                
                val infoStr = if (width > 0 && height > 0) {
                    "${width}x${height} · $durationStr · $sizeStr"
                } else {
                    "$durationStr · $sizeStr"
                }
                
                binding.tvVideoInfo.text = infoStr
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }
    
    private fun updatePlayPauseButton(isPlaying: Boolean) {
        val portraitIcon = if (isPlaying) R.drawable.ic_pause_portrait else R.drawable.ic_play_portrait
        val landscapeIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        binding.btnPlayPausePortrait.setImageResource(portraitIcon)
        binding.btnPlayPauseLandscape.setImageResource(landscapeIcon)
    }
    
    private fun toggleControls() {
        if (isControlsLocked) {
            binding.btnLockLandscape.visibility = View.VISIBLE
            return
        }
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        if (isControlsLocked) return
        controlsVisible = true
        handler.removeCallbacks(hideControlsRunnable)

        if (isLandscape) {
            binding.topBarLandscape.animate().cancel()
            binding.topMaskLandscape.animate().cancel()
            binding.bottomControlsLandscape.animate().cancel()
            binding.btnScreenshotLandscape.animate().cancel()
            binding.btnLockLandscape.animate().cancel()

            binding.topBarLandscape.visibility = View.VISIBLE
            binding.topMaskLandscape.visibility = View.VISIBLE
            binding.bottomControlsLandscape.visibility = View.VISIBLE
            binding.btnScreenshotLandscape.visibility = View.VISIBLE
            binding.btnLockLandscape.visibility = View.VISIBLE

            binding.topBarLandscape.alpha = 1f
            binding.topMaskLandscape.alpha = 1f
            binding.bottomControlsLandscape.alpha = 1f
            binding.btnScreenshotLandscape.alpha = 1f
            binding.btnLockLandscape.alpha = 1f
        } else {
            binding.topBarPortrait.animate().cancel()
            binding.topMaskPortrait.animate().cancel()
            binding.bottomControlsPortrait.animate().cancel()

            binding.topBarPortrait.visibility = View.VISIBLE
            binding.topMaskPortrait.visibility = View.VISIBLE
            binding.bottomControlsPortrait.visibility = View.VISIBLE

            binding.topBarPortrait.alpha = 1f
            binding.topMaskPortrait.alpha = 1f
            binding.bottomControlsPortrait.alpha = 1f
        }

        handler.postDelayed(hideControlsRunnable, 5000)
    }

    private fun showControlsForSeek() {
        if (isControlsLocked) return
        controlsVisible = true
        handler.removeCallbacks(hideControlsRunnable)

        if (isLandscape) {
            binding.topBarLandscape.animate().cancel()
            binding.topMaskLandscape.animate().cancel()
            binding.bottomControlsLandscape.animate().cancel()
            binding.btnScreenshotLandscape.animate().cancel()
            binding.btnLockLandscape.animate().cancel()

            binding.topBarLandscape.visibility = View.VISIBLE
            binding.topMaskLandscape.visibility = View.VISIBLE
            binding.bottomControlsLandscape.visibility = View.VISIBLE
            binding.btnScreenshotLandscape.visibility = View.VISIBLE
            binding.btnLockLandscape.visibility = View.VISIBLE

            binding.topBarLandscape.alpha = 1f
            binding.topMaskLandscape.alpha = 1f
            binding.bottomControlsLandscape.alpha = 1f
            binding.btnScreenshotLandscape.alpha = 1f
            binding.btnLockLandscape.alpha = 1f
        } else {
            binding.topBarPortrait.animate().cancel()
            binding.topMaskPortrait.animate().cancel()
            binding.bottomControlsPortrait.animate().cancel()

            binding.topBarPortrait.visibility = View.VISIBLE
            binding.topMaskPortrait.visibility = View.VISIBLE
            binding.bottomControlsPortrait.visibility = View.VISIBLE

            binding.topBarPortrait.alpha = 1f
            binding.topMaskPortrait.alpha = 1f
            binding.bottomControlsPortrait.alpha = 1f
        }
    }

    private fun scheduleHideControls() {
        if (isControlsLocked) return
        handler.postDelayed(hideControlsRunnable, 3000)
    }

    private val hideControlsRunnable = Runnable { hideControls() }

    private fun hideControls() {
        controlsVisible = false

        if (isLandscape) {
            binding.topBarLandscape.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!controlsVisible) {
                            binding.topBarLandscape.visibility = View.GONE
                        }
                    }
                })
                .start()
            binding.topMaskLandscape.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!controlsVisible) {
                            binding.topMaskLandscape.visibility = View.GONE
                        }
                    }
                })
                .start()
            binding.bottomControlsLandscape.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!controlsVisible) {
                            binding.bottomControlsLandscape.visibility = View.GONE
                        }
                    }
                })
                .start()
            binding.btnScreenshotLandscape.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!controlsVisible) {
                            binding.btnScreenshotLandscape.visibility = View.GONE
                        }
                    }
                })
                .start()
            if (!isControlsLocked) {
                binding.btnLockLandscape.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (!controlsVisible) {
                                binding.btnLockLandscape.visibility = View.GONE
                            }
                        }
                    })
                    .start()
            }
        } else {
            binding.topBarPortrait.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!controlsVisible) {
                            binding.topBarPortrait.visibility = View.GONE
                        }
                    }
                })
                .start()
            binding.topMaskPortrait.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!controlsVisible) {
                            binding.topMaskPortrait.visibility = View.GONE
                        }
                    }
                })
                .start()
            binding.bottomControlsPortrait.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (!controlsVisible) {
                            binding.bottomControlsPortrait.visibility = View.GONE
                        }
                    }
                })
                .start()
        }
    }
    
    private fun toggleOrientation() {
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
    
    private fun updateLayoutForOrientation() {
        if (isLandscape) {
            binding.portraitContainer.visibility = View.GONE
            binding.landscapeContainer.visibility = View.VISIBLE
            
            binding.playerViewPortrait.player = null
            binding.playerViewLandscape.player = player
            
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            binding.portraitContainer.visibility = View.VISIBLE
            binding.landscapeContainer.visibility = View.GONE
            
            binding.playerViewLandscape.player = null
            binding.playerViewPortrait.player = player
            
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    
    private fun formatTime(ms: Long): String {
        val sdf = SimpleDateFormat("mm:ss", Locale.getDefault())
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }
    
    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.1f GB", gb)
    }

    private fun findLocalSubtitles(videoPath: String): List<FileModel> {
        val videoFile = JFile(videoPath)
        val parentDir = videoFile.parentFile ?: return emptyList()
        val videoBaseName = videoFile.nameWithoutExtension
        val subtitleExtensions = listOf("srt", "vtt", "ass", "ssa")
        
        return parentDir.listFiles()?.filter { file ->
            val ext = file.extension.lowercase()
            val baseName = file.nameWithoutExtension
            subtitleExtensions.contains(ext) && baseName.startsWith(videoBaseName)
        }?.map { file ->
            FileModel(
                name = file.name,
                path = file.absolutePath,
                isDirectory = false,
                size = file.length(),
                lastModified = file.lastModified(),
                mimeType = "application/x-subrip",
                isSmb = false,
                smbUrl = ""
            )
        } ?: emptyList()
    }

    private fun findSafSubtitles(videoPath: String): List<FileModel> {
        val videoFile = JFile(videoPath)
        val parentPath = videoFile.parent ?: return emptyList()
        val videoBaseName = videoFile.nameWithoutExtension
        val subtitleExtensions = listOf("srt", "vtt", "ass", "ssa")
        
        return SafManager.listFiles(this, parentPath).filter { file ->
            val ext = file.name.substringAfterLast('.', "").lowercase()
            val baseName = file.name.substringBeforeLast('.', "")
            subtitleExtensions.contains(ext) && baseName.startsWith(videoBaseName)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        
        isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateLayoutForOrientation()
        
        if (isLandscape) {
            hideControls()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateProgressRunnable)
        player?.release()
        player = null
    }
    
    override fun onPause() {
        super.onPause()
        wasPlaying = player?.isPlaying == true
        player?.pause()
    }
    
    override fun onResume() {
        super.onResume()
        if (isSmbFile) {
            CoroutineScope(Dispatchers.IO).launch {
                if (!SmbManager.checkAndReconnect()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@VideoPlayerActivity, "SMB连接已断开，请返回重试", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        if (wasPlaying) {
            player?.play()
        }
    }
}
