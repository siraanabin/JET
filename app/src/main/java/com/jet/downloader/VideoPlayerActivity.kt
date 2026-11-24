package com.jet.downloader

import android.Manifest
import android.app.PictureInPictureParams
import android.content.*
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.*
import android.util.Size
import android.provider.MediaStore
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.ScaleGestureDetector
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.jet.downloader.player.RoundedTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.jet.downloader.player.PlayerPrefsManager
import com.jet.downloader.player.PlaybackStateTracker
import com.jet.downloader.player.applyGranularVolumePercent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MX-style playback UI with asynchronous playlist thumbnail decoding.
class VideoPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_FILE_PATH = "video_file_path"
    }
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var seek: RoundedTimeBar
    private lateinit var currentTime: TextView
    private lateinit var durationTime: TextView
    private lateinit var controls: View
    private lateinit var topBar: View
    private lateinit var bottomPanel: View
    private lateinit var centerControls: View
    private lateinit var playlist: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var playlistDrawer: LinearLayout
    private lateinit var playlistScrim: View
    private lateinit var playlistToggle: ImageButton
    private lateinit var volumeHud: View
    private lateinit var brightnessHud: View
    private lateinit var seekHud: View
    private lateinit var gestureOverlay: View
    private lateinit var seekPill: TextView
    private lateinit var leftTapHud: View
    private lateinit var rightTapHud: View
    private lateinit var zoomHud: TextView
    private lateinit var hudPercent: TextView
    private var pendingSeekPosition = 0L
    private var player: ExoPlayer? = null
    private var bgAudioEnabled = false
    private var isStartingBackgroundService = false
    private val backgroundNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startBackgroundAudioNow()
        }
    private val thumbDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val thumbCache = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val thumbLoading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var currentPath = ""
    private var locked = false
    private var muted = false
    private var volumePercent = 50
    private lateinit var playerPrefs: PlayerPrefsManager
    private var aspect = 0
    private var controlsVisible = true
    private var isTouchDown = false
    private var isScaling = false
    private var isSwipeSeeking = false
    private var isAdjustingVolume = false
    private var isAdjustingBrightness = false
    private var isUserScrubbing = false
    private var seekCommitPending = false
    private val hideLockIndicator = Runnable { findViewById<View>(R.id.player_unlock)?.visibility = View.GONE }
    private lateinit var scaleDetector: ScaleGestureDetector
    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private lateinit var bottomIcons: View
    private var gestureMode = 0 // 1 brightness, 2 volume, 3 seek
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureStartValue = 0f
    private var gestureStartPosition = 0L
    private var lastSeekDelta = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val clock = object : Runnable {
        override fun run() {
            findViewById<TextView>(R.id.player_clock).text =
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 30_000L)
        }
    }
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0) {
                val percent = (level * 100 / scale).coerceIn(0, 100)
                findViewById<TextView>(R.id.player_battery).text = "$percent%"
                val fill = findViewById<View>(R.id.player_battery_fill)
                fill.layoutParams = fill.layoutParams.apply {
                    height = (14f * percent / 100f).roundToInt().coerceAtLeast(1)
                }
            }
        }
    }
    private val progress = object : Runnable {
        override fun run() {
            updateProgress()
            recordPlaybackPosition()
            handler.postDelayed(this, 300L)
        }
    }
    private val hideHud = Runnable {
        if (isTouchDown || isSwipeSeeking || isAdjustingVolume || isAdjustingBrightness || isUserScrubbing) return@Runnable
        fadeHud(volumeHud); fadeHud(brightnessHud); fadeHud(seekHud); fadeHud(seekPill)
        fadeHud(leftTapHud); fadeHud(rightTapHud); fadeHud(zoomHud)
    }
    private val hideControls = Runnable {
        if (isTouchDown || isSwipeSeeking || isAdjustingVolume || isAdjustingBrightness || isUserScrubbing) return@Runnable
        if (!locked && player?.isPlaying == true) showControls(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video_player)
        applyImmersivePlayerMode()
        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.player_title)
        seek = findViewById(R.id.player_seek)
        currentTime = findViewById(R.id.player_current_time)
        durationTime = findViewById(R.id.player_duration)
        controls = findViewById(R.id.player_controls)
        topBar = findViewById(R.id.player_top_bar)
        bottomPanel = findViewById(R.id.player_bottom_panel)
        centerControls = findViewById(R.id.player_center_controls)
        playlist = findViewById(R.id.player_playlist_list)
        playlist.layoutManager = LinearLayoutManager(this)
        playlistAdapter = PlaylistAdapter()
        playlist.adapter = playlistAdapter
        playlistDrawer = findViewById(R.id.player_playlist_drawer)
        playlistScrim = findViewById(R.id.player_playlist_scrim)
        playlistToggle = findViewById(R.id.player_playlist_toggle)
        volumeHud = findViewById(R.id.player_volume_hud)
        brightnessHud = findViewById(R.id.player_brightness_hud)
        seekHud = findViewById(R.id.player_seek_hud)
        leftTapHud = findViewById(R.id.player_left_tap_hud)
        rightTapHud = findViewById(R.id.player_right_tap_hud)
        zoomHud = findViewById(R.id.player_zoom_hud)
        hudPercent = findViewById(R.id.player_hud_percent)
        gestureOverlay = findViewById(R.id.player_gesture_overlay)
        seekPill = findViewById(R.id.player_seek_pill)
        bottomIcons = findViewById(R.id.player_bottom_icons)
        titleView.isSelected = true

        val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        if (path.isBlank() || !File(path).isFile) {
            Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        currentPath = path
        val backgroundHandoff = PlayerPlaybackService.handoffToVideo(this, path)
        sendBroadcast(Intent("com.jet.downloader.action.VIDEO_PLAYBACK_STARTED").setPackage(packageName))
        val savedPlayback = PlaybackStateTracker.restore(this, path)
        val resumePositionMs = backgroundHandoff?.positionMs ?: savedPlayback?.positionMs ?: 0L
        val resumeWasPlaying = backgroundHandoff?.wasPlaying ?: savedPlayback?.isPlaying ?: true
        updatePlayerTitle(intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { File(path).nameWithoutExtension })
        playerPrefs = PlayerPrefsManager(this)
        volumePercent = playerPrefs.savedVolumePercent
        val savedResizeModes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        aspect = savedResizeModes.indexOf(playerPrefs.savedResizeMode).takeIf { it >= 0 } ?: 0
        playerView.resizeMode = savedResizeModes[aspect]
        val savedBrightness = playerPrefs.savedBrightnessPercent
        if (savedBrightness >= 0) {
            window.attributes = window.attributes.apply { screenBrightness = savedBrightness / 100f }
        }
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val exo = ExoPlayer.Builder(this, renderersFactory).setLoadControl(loadControl).build().apply { setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true) }
        player = exo
        playerView.player = exo
        playerView.useController = false
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(path))))
        exo.seekTo(resumePositionMs)
        exo.playWhenReady = resumeWasPlaying
        PlaybackStateTracker.save(this, path, resumePositionMs, 0L, resumeWasPlaying, false)
        exo.setPlaybackSpeed(playerPrefs.savedPlaybackSpeed)
        applyGranularVolumePercent(this, exo, volumePercent)
        updateVolumeIcon(volumePercent)
        findViewById<TextView>(R.id.player_speed_label).text = speedLabel(playerPrefs.savedPlaybackSpeed)
        exo.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    recordPlaybackPosition()
                    seekCommitPending = false
                    updateProgress()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                recordPlaybackPosition(isPlaying)
                findViewById<ImageButton>(R.id.player_play).setImageResource(
                    if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                )
                if (isPlaying) handler.postDelayed(hideControls, 3000L)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) recordPlaybackPosition(false)
            }
        })
        exo.prepare()
        setupControls()
        setupGestures()
        showControls(false)
        populatePlaylist(File(path).parentFile)
        handler.post(clock); handler.post(progress)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun applyImmersivePlayerMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { applyImmersivePlayerMode() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { applyImmersivePlayerMode() }
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.player_close).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.player_more).setOnClickListener { anchor -> showModernMoreMenu(anchor) }
        playlistToggle.setOnClickListener { togglePlaylistDrawer(true) }
        findViewById<ImageButton>(R.id.player_playlist_close).setOnClickListener { togglePlaylistDrawer(false) }
        playlistScrim.setOnClickListener { togglePlaylistDrawer(false) }
        findViewById<ImageButton>(R.id.player_rewind).setOnClickListener { seekBy(-10_000L) }
        findViewById<ImageButton>(R.id.player_play).setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        findViewById<ImageButton>(R.id.player_forward).setOnClickListener { seekBy(10_000L) }
        val unlock = findViewById<ImageButton>(R.id.player_unlock)
        findViewById<ImageButton>(R.id.player_lock).setOnClickListener {
            locked = true; showControls(false); showLockIndicator()
            Toast.makeText(this, "Screen locked", Toast.LENGTH_SHORT).show()
        }
        unlock.setOnClickListener {
            locked = false; handler.removeCallbacks(hideLockIndicator); unlock.visibility = View.GONE; showControls(true)
            Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.player_mute).setOnClickListener {
            muted = !muted
            player?.let { active ->
                if (muted) active.volume = 0f else applyGranularVolumePercent(this, active, volumePercent)
            }
            it.isSelected = muted
            updateVolumeIcon(if (muted) 0 else volumePercent)
        }
        findViewById<ImageButton>(R.id.player_pip).setOnClickListener { enterPip() }
        findViewById<ImageButton>(R.id.player_aspect).setOnClickListener { cycleAspect() }
        findViewById<ImageButton>(R.id.player_speed).setOnClickListener { showSpeed() }
        findViewById<ImageButton>(R.id.player_background).setOnClickListener { backgroundAudio() }
        findViewById<ImageButton>(R.id.player_rotate).setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        listOf(R.id.player_close, R.id.player_more, R.id.player_rewind, R.id.player_play, R.id.player_forward,
            R.id.player_lock, R.id.player_mute, R.id.player_pip,
            R.id.player_aspect, R.id.player_speed, R.id.player_background, R.id.player_rotate)
            .forEach { id -> findViewById<View>(id).setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) v.animate().scaleX(.88f).scaleY(.88f).setDuration(75).start()
                if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2.2f)).start()
                false
            } }
        seek.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                isUserScrubbing = true
                cancelAutoHideCallbacks()
                controls.visibility = View.VISIBLE
                bottomPanel.visibility = View.VISIBLE
                bottomPanel.alpha = 1f
            }
            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                cancelAutoHideCallbacks()
                currentTime.text = time(position)
            }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                isUserScrubbing = false
                if (!canceled) {
                    seekCommitPending = true
                    player?.seekTo(position)
                    handler.postDelayed({ seekCommitPending = false; updateProgress() }, 180L)
                } else seekCommitPending = false
                handler.removeCallbacks(hideControls)
                handler.removeCallbacks(hideHud)
                handler.postDelayed(hideHud, 400L)
                handler.postDelayed(hideControls, 3000L)
            }
        })
    }

    private fun cancelAutoHideCallbacks() {
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(hideHud)
    }

    private fun finishTouchInteraction() {
        isTouchDown = false
        isScaling = false
        isSwipeSeeking = false
        isAdjustingVolume = false
        isAdjustingBrightness = false
        isUserScrubbing = false
        gestureMode = 0
        cancelAutoHideCallbacks()
        handler.postDelayed(hideHud, 400L)
        handler.postDelayed(hideControls, 3500L)
    }

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                gestureMode = 0
                isSwipeSeeking = false
                isAdjustingVolume = false
                isAdjustingBrightness = false
                isUserScrubbing = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomScale = (zoomScale * detector.scaleFactor).coerceIn(1f, 4f)
                if (zoomScale <= 1.001f) { zoomScale = 1f; panX = 0f; panY = 0f }
                playerView.scaleX = zoomScale; playerView.scaleY = zoomScale
                val maxX = playerView.width * (zoomScale - 1f) / 2f
                val maxY = playerView.height * (zoomScale - 1f) / 2f
                panX = panX.coerceIn(-maxX, maxX); panY = panY.coerceIn(-maxY, maxY)
                playerView.translationX = panX; playerView.translationY = panY
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
                gestureMode = 0
                isSwipeSeeking = false
                isAdjustingVolume = false
                isAdjustingBrightness = false
                isUserScrubbing = false
                gestureStartX = detector.focusX
                gestureStartY = detector.focusY
                gestureStartPosition = player?.currentPosition ?: 0L
                pendingSeekPosition = gestureStartPosition
                lastPanX = gestureStartX
                lastPanY = gestureStartY
            }
        })
        val detector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (locked) return false
                val start = e1 ?: return false
                val dx = e2.x - gestureStartX
                val dy = e2.y - gestureStartY
                if (gestureMode == 0 && (abs(dx) > 0f || abs(dy) > 0f)) {
                    gestureMode = if (abs(dx) > abs(dy)) 3
                    else if (gestureStartX < playerView.width * .5f) 1 else 2
                    showGestureControls()
                    if (gestureMode == 3) {
                        isUserScrubbing = true
                        pendingSeekPosition = gestureStartPosition
                    }
                }
                when (gestureMode) {
                    1 -> {
                        val percent = (gestureStartValue + (gestureStartY - e2.y) / playerView.height * 100f)
                            .roundToInt().coerceIn(0, 100)
                        playerPrefs.savedBrightnessPercent = percent
                        window.attributes = window.attributes.apply { screenBrightness = percent / 100f }
                        showVerticalHud(brightnessHud, percent / 100f, "☼")
                    }
                    2 -> {
                        volumePercent = (gestureStartValue + (gestureStartY - e2.y) / playerView.height * 100f)
                            .roundToInt().coerceIn(0, 100)
                        playerPrefs.savedVolumePercent = volumePercent
                        muted = false
                        player?.let { applyGranularVolumePercent(this@VideoPlayerActivity, it, volumePercent) }
                        findViewById<ImageButton>(R.id.player_mute).isSelected = false
                        showVerticalHud(volumeHud, volumePercent / 100f, "")
                    }
                    3 -> {
                        val duration = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                        if (duration > 0L) {
                            val delta = (dx / playerView.width.coerceAtLeast(1) *
                                duration.coerceAtMost(3_600_000L) * 1.4f).toLong()
                            pendingSeekPosition = (gestureStartPosition + delta).coerceIn(0L, duration)
                            isUserScrubbing = true
                            seek.setDuration(duration)
                            seek.setPosition(pendingSeekPosition)
                            currentTime.text = time(pendingSeekPosition)
                            showSeekPill(pendingSeekPosition, duration)
                        }
                    }
                }
                return gestureMode != 0
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!locked && gestureMode == 0) {
                    val forward = e.x >= playerView.width / 2f
                    seekBy(if (forward) 10_000L else -10_000L)
                    val hud = if (forward) rightTapHud else leftTapHud
                    val label = hud.findViewById<TextView>(
                        if (forward) R.id.player_right_tap_text else R.id.player_left_tap_text
                    )
                    label.text = if (forward) "10s »" else "« 10s"
                    hud.visibility = View.VISIBLE
                    hud.alpha = 1f
                    hud.scaleX = .75f
                    hud.scaleY = .75f
                    hud.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setDuration(420L)
                        .withEndAction { hud.visibility = View.GONE }.start()
                }
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (locked) showLockIndicator() else showControls(!controlsVisible)
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true
        })

        // This transparent layer sits above PlayerView but below the clickable player controls.
        // It receives the very first DOWN event, even while ExoPlayer controls are fully hidden.
        gestureOverlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE && isTouchDown) cancelAutoHideCallbacks()
            scaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                isScaling = true
                gestureMode = 0
                isSwipeSeeking = false
                isAdjustingVolume = false
                isAdjustingBrightness = false
                isUserScrubbing = false
            }
            if (locked) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) showLockIndicator()
                return@setOnTouchListener true
            }
            if (event.pointerCount > 1 || isScaling || scaleDetector.isInProgress) {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    isScaling = false
                    gestureMode = 0
                    isSwipeSeeking = false
                    isAdjustingVolume = false
                    isAdjustingBrightness = false
                    isUserScrubbing = false
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    if (remainingIndex < event.pointerCount) {
                        gestureStartX = event.getX(remainingIndex)
                        gestureStartY = event.getY(remainingIndex)
                        gestureStartPosition = player?.currentPosition ?: 0L
                        pendingSeekPosition = gestureStartPosition
                        gestureStartValue = if (gestureStartX < playerView.width * .5f) {
                            (playerPrefs.savedBrightnessPercent.takeIf { it >= 0 }?.toFloat()
                                ?: (window.attributes.screenBrightness.takeIf { it >= 0f }?.times(100f) ?: 50f))
                        } else volumePercent.toFloat()
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    finishTouchInteraction()
                }
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isTouchDown = true
                    isSwipeSeeking = false
                    isAdjustingVolume = false
                    isAdjustingBrightness = false
                    cancelAutoHideCallbacks()
                    gestureStartX = event.x
                    gestureStartY = event.y
                    lastPanX = event.x
                    lastPanY = event.y
                    gestureMode = 0
                    lastSeekDelta = 0L
                    gestureStartPosition = player?.currentPosition ?: 0L
                    pendingSeekPosition = gestureStartPosition
                    gestureStartValue = if (event.x < playerView.width * .5f) {
                        (playerPrefs.savedBrightnessPercent.takeIf { it >= 0 }?.toFloat()
                            ?: (window.attributes.screenBrightness.takeIf { it >= 0f }?.times(100f) ?: 50f))
                    } else {
                        volumePercent.toFloat()
                    }
                    handler.removeCallbacks(hideHud)
                }
                MotionEvent.ACTION_MOVE -> {
                    cancelAutoHideCallbacks()
                    // Single-finger movement after zoom is handled by brightness, volume, and seek gestures.
                }
            }

            detector.onTouchEvent(event)

            if (event.actionMasked == MotionEvent.ACTION_MOVE && isTouchDown) {
                isSwipeSeeking = gestureMode == 3
                isAdjustingBrightness = gestureMode == 1
                isAdjustingVolume = gestureMode == 2
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureMode == 3 && !locked) {
                        isUserScrubbing = false
                        seekCommitPending = true
                        player?.seekTo(pendingSeekPosition)
                        handler.postDelayed({ seekCommitPending = false; updateProgress() }, 180L)
                        seekPill.animate().cancel()
                        seekPill.visibility = View.GONE
                        seekPill.alpha = 1f
                    } else if (gestureMode != 0 && event.actionMasked == MotionEvent.ACTION_UP) {
                        handler.postDelayed(hideHud, 400L)
                    }
                    finishTouchInteraction()
                }
            }
            true
        }
    }

    private fun showLockIndicator() {
        val icon = findViewById<View>(R.id.player_unlock)
        handler.removeCallbacks(hideLockIndicator)
        icon.visibility = View.VISIBLE
        icon.alpha = 1f
        handler.postDelayed(hideLockIndicator, 2500L)
    }

    private fun showGestureControls() {
        controls.visibility = View.VISIBLE
        if (gestureMode == 3) {
            topBar.visibility = View.GONE
            centerControls.visibility = View.GONE
            bottomPanel.visibility = View.VISIBLE
            bottomIcons.visibility = View.GONE
            bottomPanel.alpha = 1f
            bottomPanel.translationY = 0f
            controlsVisible = false
        }
        volumeHud.visibility = View.GONE
        brightnessHud.visibility = View.GONE
        seekHud.visibility = View.GONE
        if (gestureMode != 3) seekPill.visibility = View.GONE
    }

    private fun showSeekPill(position: Long, duration: Long) {
        val current = time(position)
        val total = time(duration)
        seekPill.text = androidx.core.text.HtmlCompat.fromHtml(
            "<font color='#00FFFF'>$current</font><font color='#FFFFFF'>/$total</font>",
            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        seekPill.animate().cancel()
        seekPill.visibility = View.VISIBLE
        seekPill.alpha = 1f
    }

    private fun showVerticalHud(hud: View, fraction: Float, glyph: String) {
        val isBrightness = hud.id == R.id.player_brightness_hud
        if (isBrightness) {
            volumeHud.visibility = View.GONE
            findViewById<ImageView>(R.id.player_brightness_icon).setImageResource(R.drawable.ic_player_brightness)
        } else {
            brightnessHud.visibility = View.GONE
            updateVolumeIcon((fraction * 100).roundToInt())
        }
        findViewById<TextView>(if (isBrightness) R.id.player_hud_percent else R.id.player_volume_percent).text =
            "${(fraction * 100).roundToInt()}%"
        findViewById<ProgressBar>(if (isBrightness) R.id.player_hud_progress else R.id.player_volume_progress).progress =
            (fraction * 100).roundToInt()
        showHud(hud)
    }

    private fun updateVolumeIcon(percent: Int) {
        val icon = findViewById<ImageView>(R.id.player_volume_icon)
        icon.setImageResource(if (percent <= 0) R.drawable.ic_player_volume_muted else R.drawable.ic_player_volume_on)
        icon.contentDescription = if (percent <= 0) "Muted" else "Volume"
    }

    private fun speedLabel(speed: Float): String = when (speed) {
        0.5f -> "0.5x"
        1f -> "1.0x"
        1.25f -> "1.25x"
        1.5f -> "1.5x"
        2f -> "2.0x"
        else -> "${speed}x"
    }
    private fun showHud(view: View) {
        handler.removeCallbacks(hideHud)
        view.animate().cancel(); view.visibility = View.VISIBLE; view.alpha = 1f
        handler.postDelayed(hideHud, 400L)
    }
    private fun fadeHud(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().alpha(0f).setDuration(180L).withEndAction { view.visibility = View.GONE }.start()
    }
    private fun showControls(show: Boolean) {
        controlsVisible = show
        handler.removeCallbacks(hideControls)
        val interp = android.view.animation.DecelerateInterpolator()
        if (show) {
            controls.visibility = View.VISIBLE
            topBar.visibility = View.VISIBLE
            titleView.post { titleView.isSelected = true }
            bottomPanel.visibility = View.VISIBLE
            bottomIcons.visibility = View.VISIBLE
            centerControls.visibility = View.VISIBLE
            topBar.animate().cancel()
            bottomPanel.animate().cancel()
            centerControls.animate().cancel()
            topBar.translationY = -dp(76).toFloat()
            bottomPanel.translationY = dp(110).toFloat()
            topBar.alpha = 0f
            bottomPanel.alpha = 0f
            centerControls.alpha = 0f
            centerControls.scaleX = 0.9f
            centerControls.scaleY = 0.9f
            topBar.animate().alpha(1f).translationY(0f).setDuration(240L).setInterpolator(interp).start()
            bottomPanel.animate().alpha(1f).translationY(0f).setDuration(240L).setInterpolator(interp).start()
            centerControls.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240L).setInterpolator(interp).start()
            if (player?.isPlaying == true) handler.postDelayed(hideControls, 3500L)
        } else {
            topBar.animate().alpha(0f).translationY(-dp(76).toFloat()).setDuration(220L).setInterpolator(interp).start()
            bottomPanel.animate().alpha(0f).translationY(dp(110).toFloat()).setDuration(220L).setInterpolator(interp).start()
            centerControls.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(220L).setInterpolator(interp)
                .withEndAction { if (!controlsVisible) controls.visibility = View.GONE }.start()
        }
    }
    private fun cycleAspect() {
        aspect = (aspect + 1) % 3
        val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)
        val labels = arrayOf("FIT", "CROP", "100%")
        playerView.resizeMode = modes[aspect]
        playerPrefs.savedResizeMode = modes[aspect]
        zoomHud.text = labels[aspect]
        zoomHud.visibility = View.VISIBLE; zoomHud.alpha = 1f
        handler.removeCallbacks(hideHud); handler.postDelayed({ fadeHud(zoomHud) }, 800L)
        findViewById<ImageButton>(R.id.player_aspect).isSelected = aspect != 0
    }
    private fun showModernMoreMenu(anchor: View) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF21A2232.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x553D4B66)
            }
            elevation = dp(18).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val popup = PopupWindow(menu, dp(224), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(18).toFloat()
        }
        val actions = listOf(
            "Playback speed" to { showSpeed() },
            "Picture-in-picture" to { enterPip() },
            "Background audio" to { backgroundAudio() }
        )
        actions.forEach { (label, action) ->
            val item = TextView(this).apply {
                text = label
                setTextColor(0xFFF1F5F9.toInt())
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                setOnClickListener { popup.dismiss(); action() }
            }
            menu.addView(item, LinearLayout.LayoutParams(-1, dp(48)))
        }
        popup.showAsDropDown(anchor, -dp(184), dp(5))
        menu.alpha = 0f
        menu.scaleX = .94f
        menu.scaleY = .94f
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun showMore() {
        AlertDialog.Builder(this).setItems(arrayOf("Fit", "Crop", "Fill", "Playback speed")) { _, which ->
            when (which) {
                0 -> { aspect = 0; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; playerPrefs.savedResizeMode = playerView.resizeMode }
                1 -> { aspect = 1; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; playerPrefs.savedResizeMode = playerView.resizeMode }
                2 -> { aspect = 2; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; playerPrefs.savedResizeMode = playerView.resizeMode }
                3 -> showSpeed()
            }
        }.show()
    }
    private fun showSpeed() {
        val values = floatArrayOf(.5f, 1f, 1.25f, 1.5f, 2f)
        val labels = arrayOf("0.5x", "1.0x", "1.25x", "1.5x", "2.0x")
        AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels) { _, which ->
            player?.setPlaybackSpeed(values[which])
            playerPrefs.savedPlaybackSpeed = values[which]
            findViewById<TextView>(R.id.player_speed_label).text = labels[which]
        }.show()
    }
    private var playlistLoadGeneration = 0
    private fun populatePlaylist(folder: File?) {
        val generation = ++playlistLoadGeneration
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                val supported = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi")
                val discovered = LinkedHashMap<String, File>()
                val permission = if (Build.VERSION.SDK_INT >= 33) {
                    ContextCompat.checkSelfPermission(this@VideoPlayerActivity, android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(this@VideoPlayerActivity, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (permission) runCatching {
                    val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME)
                    contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { cursor ->
                        val dataIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                        while (cursor.moveToNext()) {
                            if (dataIndex >= 0) {
                                val path = cursor.getString(dataIndex).orEmpty()
                                if (path.isNotBlank()) {
                                    val file = File(path)
                                    if (file.isFile && file.extension.lowercase(Locale.US) in supported) discovered[file.absolutePath] = file
                                }
                            }
                        }
                    }
                }.onFailure { android.util.Log.w("JET-VideoPlayer", "MediaStore playlist query failed", it) }
                folder?.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.US) in supported }?.forEach { discovered[it.absolutePath] = it }
                val current = File(currentPath)
                if (current.isFile && current.extension.lowercase(Locale.US) in supported) discovered[current.absolutePath] = current
                discovered.values.sortedByDescending { it.lastModified() }
            }
            if (!isFinishing && !isDestroyed && generation == playlistLoadGeneration) renderPlaylistFiles(files)
        }
    }
    private fun renderPlaylistFiles(files: List<File>) {
        playlistAdapter.submitFiles(files)
    }

    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.Holder>() {
        private var items: List<File> = emptyList()
        inner class Holder(val row: LinearLayout, val image: ImageView, val duration: TextView, val title: TextView, val size: TextView) : RecyclerView.ViewHolder(row) {
            var thumbnailJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@VideoPlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, dp(82)).apply { bottomMargin = dp(8) }
                background = GradientDrawable().apply { setColor(0xFF191E29.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF2A3140.toInt()) }
            }
            val thumb = FrameLayout(this@VideoPlayerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(112), dp(64)).apply { marginEnd = dp(10) }
                background = GradientDrawable().apply { setColor(0xFF303746.toInt()); cornerRadius = dp(8).toFloat() }; clipToOutline = true
            }
            val image = ImageView(this@VideoPlayerActivity).apply { layoutParams = FrameLayout.LayoutParams(-1, -1); scaleType = ImageView.ScaleType.CENTER_CROP }
            val duration = TextView(this@VideoPlayerActivity).apply {
                textSize = 9f; setTextColor(-1); setPadding(dp(4), dp(2), dp(4), dp(2))
                background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dp(4).toFloat() }
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(4), dp(4)) }
            }
            thumb.addView(image); thumb.addView(duration)
            val column = LinearLayout(this@VideoPlayerActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -1, 1f) }
            val title = TextView(this@VideoPlayerActivity).apply { setTextColor(-1); textSize = 12f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val size = TextView(this@VideoPlayerActivity).apply { setTextColor(0xFF9AA4B5.toInt()); textSize = 10f }
            column.addView(title); column.addView(size); row.addView(thumb); row.addView(column)
            return Holder(row, image, duration, title, size)
        }

        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = items[position]
            holder.row.tag = file.absolutePath
            holder.image.tag = file.absolutePath
            holder.title.text = file.nameWithoutExtension
            holder.size.text = android.text.format.Formatter.formatShortFileSize(this@VideoPlayerActivity, file.length())
            holder.duration.text = "--:--"
            holder.row.background = GradientDrawable().apply {
                setColor(if (file.absolutePath == currentPath) 0xFF202B43.toInt() else 0xFF191E29.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), if (file.absolutePath == currentPath) 0xFF4F7CFF.toInt() else 0xFF2A3140.toInt())
            }
            holder.row.setOnClickListener {
                currentPath = file.absolutePath; updatePlayerTitle(file.nameWithoutExtension)
                player?.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                player?.prepare(); player?.play(); refreshSelection(); togglePlaylistDrawer(false)
            }
            val cached = thumbCache.get(file.absolutePath)
            if (cached != null) holder.image.setImageBitmap(cached) else {
                holder.image.setImageDrawable(null)
                if (thumbLoading.add(file.absolutePath)) holder.thumbnailJob = lifecycleScope.launch(thumbDispatcher) {
                    var bitmap: Bitmap? = null
                    var durationMs = 0L
                    try {
                        // Use the system thumbnail pipeline first, then fall back to retriever decoding.
                        bitmap = loadSystemThumbnail(file)
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            if (bitmap == null) {
                                bitmap = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(
                                    1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dp(320), dp(180)
                                ) else retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            }
                            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } catch (_: Exception) {
                        } finally { runCatching { retriever.release() } }
                        if (bitmap != null && (bitmap!!.width > dp(320) || bitmap!!.height > dp(180))) {
                            val scaled = Bitmap.createScaledBitmap(bitmap!!, dp(320), dp(180), true)
                            if (scaled !== bitmap) bitmap!!.recycle()
                            bitmap = scaled
                        }
                        bitmap?.let { decoded ->
                            thumbCache.put(file.absolutePath, decoded)
                            withContext(Dispatchers.Main) {
                                if (!isDestroyed && holder.image.tag == file.absolutePath) holder.image.setImageBitmap(decoded)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            if (!isDestroyed && holder.row.tag == file.absolutePath) holder.duration.text = if (durationMs > 0L) time(durationMs) else "--:--"
                        }
                    } finally {
                        thumbLoading.remove(file.absolutePath)
                    }
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.thumbnailJob?.cancel()
            (holder.row.tag as? String)?.let { thumbLoading.remove(it) }
            holder.thumbnailJob = null
            holder.image.tag = null
            holder.row.tag = null
            super.onViewRecycled(holder)
        }

        fun submitFiles(next: List<File>) {
            val previous = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = previous[oldItemPosition].absolutePath == next[newItemPosition].absolutePath
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    previous[oldItemPosition].length() == next[newItemPosition].length() && previous[oldItemPosition].lastModified() == next[newItemPosition].lastModified()
            })
            items = next
            diff.dispatchUpdatesTo(this)
        }
    }

    private fun loadSystemThumbnail(file: File): Bitmap? {
        return try {
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val selection = MediaStore.Video.Media.DATA + " = ?"
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(file.absolutePath),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val id = cursor.getLong(0)
                val contentUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(contentUri, Size(dp(320), dp(180)), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                }
            }
        } catch (_: Exception) { null }
    }

    private fun updatePlayerTitle(value: String) {
        titleView.text = value
        titleView.isSelected = true
        titleView.post { titleView.isSelected = true }
    }

    private fun togglePlaylistDrawer(open: Boolean) {
        if (open) {
            (playlist.parent as? View)?.visibility = View.VISIBLE
            playlist.visibility = View.VISIBLE
            if (playlistAdapter.itemCount == 0) populatePlaylist(File(currentPath).parentFile)
            playlistScrim.visibility = View.VISIBLE
            playlistDrawer.visibility = View.VISIBLE
            playlistDrawer.translationX = dp(320).toFloat()
            playlistScrim.alpha = 0f
            playlistScrim.animate().alpha(1f).setDuration(180L).start()
            playlistDrawer.animate().translationX(0f).setDuration(240L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else {
            playlistScrim.animate().alpha(0f).setDuration(160L)
                .withEndAction { playlistScrim.visibility = View.GONE }.start()
            playlistDrawer.animate().translationX(dp(320).toFloat()).setDuration(200L)
                .withEndAction { playlistDrawer.visibility = View.GONE }.start()
        }
    }
    private fun refreshSelection() {
        if (::playlistAdapter.isInitialized) playlistAdapter.notifyDataSetChanged()
    }
    private fun backgroundAudio() {
        if (isStartingBackgroundService || currentPath.isBlank() || !File(currentPath).isFile) return
        if (player == null) return
        isStartingBackgroundService = true
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                backgroundNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (error: Exception) {
                android.util.Log.w("JET-Video", "Notification permission prompt unavailable; continuing background playback", error)
                startBackgroundAudioNow()
            }
        } else {
            startBackgroundAudioNow()
        }
    }

    private fun startBackgroundAudioNow() {
        val activePlayer = player
        if (activePlayer == null || currentPath.isBlank() || !File(currentPath).isFile) {
            isStartingBackgroundService = false
            return
        }
        val resumePositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
        val playWhenReady = activePlayer.playWhenReady
        PlaybackStateTracker.save(
            this, currentPath, resumePositionMs,
            activePlayer.duration.takeIf { it > 0L } ?: 0L,
            playWhenReady, true
        )
        bgAudioEnabled = true
        findViewById<ImageButton>(R.id.player_background).isSelected = true
        try {
            ContextCompat.startForegroundService(this, Intent(this, PlayerPlaybackService::class.java).apply {
                putExtra(PlayerPlaybackService.EXTRA_PATH, currentPath)
                putExtra(PlayerPlaybackService.EXTRA_TITLE, titleView.text.toString())
                putExtra(PlayerPlaybackService.EXTRA_START_POSITION, resumePositionMs)
                putExtra(PlayerPlaybackService.EXTRA_PLAY_WHEN_READY, playWhenReady)
            })
            activePlayer.pause()
            Toast.makeText(this, "Background audio ON", Toast.LENGTH_SHORT).show()
            finish()
        } catch (error: Exception) {
            bgAudioEnabled = false
            isStartingBackgroundService = false
            if (playWhenReady) activePlayer.play()
            android.util.Log.e("JET-Video", "Background audio handoff failed", error)
            Toast.makeText(this, "Could not start background audio", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordPlaybackPosition()
        recreate()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9).let { ratio ->
                    val clamped = ratio.toFloat().coerceIn(0.42f, 2.39f)
                    android.util.Rational((clamped * 1000).roundToInt(), 1000)
                })
                .build()
            runCatching { enterPictureInPictureMode(params) }
                .onFailure { android.util.Log.w("JET-PiP", "Unable to enter picture-in-picture mode", it) }
        }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isFinishing && !isDestroyed) {
            runCatching { enterPip() }.onFailure { android.util.Log.w("JET-PiP", "PiP lifecycle request failed", it) }
        }
    }
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        runCatching {
            val unlockView = findViewById<View>(R.id.player_unlock)
            if (isInPictureInPictureMode) {
                handler.removeCallbacks(hideControls)
                controls.visibility = View.GONE
                unlockView?.visibility = View.GONE
            } else if (!locked) {
                showControls(true)
            } else {
                controls.visibility = View.GONE
                unlockView?.visibility = View.VISIBLE
            }
        }.onFailure { android.util.Log.w("JET-PiP", "PiP UI transition failed", it) }
    }

    private fun recordPlaybackPosition(isPlaying: Boolean? = null) {
        val activePlayer = player ?: return
        if (currentPath.isBlank()) return
        PlaybackStateTracker.save(
            this,
            currentPath,
            activePlayer.currentPosition.coerceAtLeast(0L),
            activePlayer.duration.takeIf { it > 0L } ?: 0L,
            isPlaying ?: activePlayer.playWhenReady,
            false
        )
    }

    override fun onPause() {
        super.onPause()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip && !bgAudioEnabled && !isChangingConfigurations) {
            recordPlaybackPosition()
            player?.pause()
            recordPlaybackPosition(false)
        }
    }
    private fun seekBy(delta: Long) {
        val p = player ?: return
        val end = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + delta).coerceIn(0L, end))
        showControls(true)
    }
    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: C.TIME_UNSET
        seek.setDuration(duration)
        if (!isUserScrubbing && !seekCommitPending) {
            seek.setPosition(p.currentPosition.coerceAtLeast(0L))
            seek.setBufferedPosition(p.bufferedPosition.coerceAtLeast(0L))
            currentTime.text = time(p.currentPosition)
        }
        durationTime.text = if (duration == C.TIME_UNSET) "--:--" else time(duration)
    }
    private fun time(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000L
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    override fun onDestroy() {
        if (!bgAudioEnabled) recordPlaybackPosition()
        handler.removeCallbacksAndMessages(null)
        thumbLoading.clear()
        try { unregisterReceiver(battery) } catch (_: Exception) {}
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
