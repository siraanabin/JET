package com.jet.downloader

import android.app.PictureInPictureParams
import android.content.*
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.*
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
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
    private lateinit var seek: DefaultTimeBar
    private lateinit var currentTime: TextView
    private lateinit var durationTime: TextView
    private lateinit var controls: View
    private lateinit var topBar: View
    private lateinit var bottomPanel: View
    private lateinit var centerControls: View
    private lateinit var playlist: LinearLayout
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
    private val thumbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(24)
    private var currentPath = ""
    private var locked = false
    private var muted = false
    private var aspect = 0
    private var playbackSpeed = 1f
    private var repeatEnabled = false
    private var zoomScale = 1f
    private var zoomPanX = 0f
    private var zoomPanY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var multiTouchGesture = false
    private lateinit var scaleDetector: ScaleGestureDetector
    private val playerPrefs by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }
    private var controlsVisible = true
    private var scrubbing = false
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
            if (level >= 0) findViewById<TextView>(R.id.player_battery).text = "▰ ${level * 100 / scale}%"
        }
    }
    private val progress = object : Runnable {
        override fun run() { updateProgress(); handler.postDelayed(this, 300L) }
    }
    private val hideHud = Runnable {
        fadeHud(volumeHud); fadeHud(brightnessHud); fadeHud(seekHud); fadeHud(seekPill)
        fadeHud(leftTapHud); fadeHud(rightTapHud); fadeHud(zoomHud)
    }
    private val hideControls = Runnable { if (!locked && player?.isPlaying == true) showControls(false) }
    private val hideIsolatedHudContainer = Runnable {
        if (!controlsVisible && ::controls.isInitialized) controls.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        playlist = findViewById(R.id.player_playlist)
        volumeHud = findViewById(R.id.player_volume_hud)
        brightnessHud = findViewById(R.id.player_brightness_hud)
        seekHud = findViewById(R.id.player_seek_hud)
        leftTapHud = findViewById(R.id.player_left_tap_hud)
        rightTapHud = findViewById(R.id.player_right_tap_hud)
        zoomHud = findViewById(R.id.player_zoom_hud)
        hudPercent = findViewById(R.id.player_hud_percent)
        gestureOverlay = findViewById(R.id.player_gesture_overlay)
        seekPill = findViewById(R.id.player_seek_pill)

        val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        if (path.isBlank() || !File(path).isFile) {
            Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        currentPath = path
        titleView.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { File(path).nameWithoutExtension }
        titleView.isSelected = true
        aspect = playerPrefs.getInt("aspect", 0).coerceIn(0, 2)
        playbackSpeed = playerPrefs.getFloat("speed", 1f).coerceIn(0.25f, 3f)
        repeatEnabled = playerPrefs.getBoolean("repeat", false)
        muted = playerPrefs.getBoolean("muted", false)
        findViewById<TextView>(R.id.player_speed_label).text = String.format(Locale.US, "%.2gx", playbackSpeed)
        applySystemOrientationPolicy()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        playerView.useController = false
        playerView.resizeMode = resizeModeForAspect(aspect)
        exo.setPlaybackSpeed(playbackSpeed)
        exo.repeatMode = if (repeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo.volume = if (muted) 0f else 1f
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(path))))
        exo.prepare(); exo.play()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                findViewById<ImageButton>(R.id.player_play).setImageResource(
                    if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                )
                if (isPlaying) handler.postDelayed(hideControls, 3000L)
            }
        })
        setupControls()
        setupGestures()
        populatePlaylist(File(path).parentFile)
        handler.post(clock); handler.post(progress)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.postDelayed(hideControls, 3500L)
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
        if (!locked) applySystemOrientationPolicy()
        window.decorView.post { applyImmersivePlayerMode() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { applyImmersivePlayerMode() }
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.player_close).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.player_rewind).setOnClickListener { seekBy(-10_000L) }
        findViewById<ImageButton>(R.id.player_play).setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        findViewById<ImageButton>(R.id.player_forward).setOnClickListener { seekBy(10_000L) }
        val unlock = findViewById<Button>(R.id.player_unlock)
        findViewById<ImageButton>(R.id.player_lock).setOnClickListener {
            locked = true; requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED; showControls(false); unlock.visibility = View.VISIBLE
            Toast.makeText(this, "Screen locked", Toast.LENGTH_SHORT).show()
        }
        unlock.setOnClickListener {
            locked = false; unlock.visibility = View.GONE; applySystemOrientationPolicy(); showControls(true)
            Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
        }
        findViewById<ImageButton>(R.id.player_mute).setOnClickListener {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            playerPrefs.edit().putBoolean("muted", muted).apply()
            it.isSelected = muted
        }
        findViewById<ImageButton>(R.id.player_pip).setOnClickListener { enterPip() }
        findViewById<ImageButton>(R.id.player_aspect).setOnClickListener { cycleAspect() }
        findViewById<ImageButton>(R.id.player_speed).setOnClickListener { showSpeed() }
        findViewById<ImageButton>(R.id.player_background).setOnClickListener { backgroundAudio() }
        findViewById<ImageButton>(R.id.player_rotate).setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        findViewById<ImageButton>(R.id.player_repeat).setOnClickListener {
            repeatEnabled = !repeatEnabled
            player?.repeatMode = if (repeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            playerPrefs.edit().putBoolean("repeat", repeatEnabled).apply()
            it.isSelected = repeatEnabled
        }
        findViewById<ImageButton>(R.id.player_repeat).isSelected = repeatEnabled
        findViewById<ImageButton>(R.id.player_mute).isSelected = muted
        listOf(R.id.player_close, R.id.player_rewind, R.id.player_play, R.id.player_forward,
            R.id.player_lock, R.id.player_mute, R.id.player_pip,
            R.id.player_aspect, R.id.player_speed, R.id.player_background, R.id.player_rotate, R.id.player_repeat)
            .forEach { id -> findViewById<View>(id).setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) v.animate().scaleX(.88f).scaleY(.88f).setDuration(75).start()
                if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2.2f)).start()
                false
            } }
        seek.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { scrubbing = true; handler.removeCallbacks(hideControls) }
            override fun onScrubMove(timeBar: TimeBar, position: Long) { currentTime.text = time(position) }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled) player?.seekTo(position)
                handler.postDelayed(hideControls, 3000L)
            }
        })
    }

    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomScale = (zoomScale * detector.scaleFactor).coerceIn(1f, 4f)
                if (zoomScale <= 1.01f) { zoomScale = 1f; zoomPanX = 0f; zoomPanY = 0f }
                applyVideoTransform()
                zoomHud.text = String.format(Locale.US, "%.1fx", zoomScale)
                showHud(zoomHud)
                return true
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
                    else if (start.x < playerView.width * .5f) 1 else 2
                    if (gestureMode == 3) showGestureControls()
                    if (gestureMode == 3) {
                        pendingSeekPosition = gestureStartPosition
                    }
                }
                when (gestureMode) {
                    1 -> {
                        val percent = (gestureStartValue + (gestureStartY - e2.y) / playerView.height * 100f).coerceIn(0f, 100f)
                        window.attributes = window.attributes.apply { screenBrightness = percent / 100f }
                        showVerticalHud(brightnessHud, percent / 100f, "☼")
                    }
                    2 -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val percent = (gestureStartValue + (gestureStartY - e2.y) / playerView.height * 100f).coerceIn(0f, 100f)
                        val value = (percent / 100f * max).roundToInt().coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                        showVerticalHud(volumeHud, percent / 100f, "◖")
                    }
                    3 -> {
                        val duration = player?.duration?.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
                        if (duration > 0L) {
                            val delta = (dx / playerView.width.coerceAtLeast(1) *
                                duration.coerceAtMost(3_600_000L) * 1.4f).toLong()
                            pendingSeekPosition = (gestureStartPosition + delta).coerceIn(0L, duration)
                            seek.setDuration(duration)
                            seek.setPosition(pendingSeekPosition)
                            currentTime.text = time(pendingSeekPosition)
                            seekPill.visibility = View.GONE
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

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!locked) showControls(!controlsVisible)
                return true
            }
        })

        // This transparent layer sits above PlayerView but below the clickable player controls.
        // It receives the very first DOWN event, even while ExoPlayer controls are fully hidden.
        gestureOverlay.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x
                    gestureStartY = event.y
                    lastPanX = event.x; lastPanY = event.y
                    multiTouchGesture = false
                    gestureMode = 0
                    lastSeekDelta = 0L
                    gestureStartPosition = player?.currentPosition ?: 0L
                    pendingSeekPosition = gestureStartPosition
                    gestureStartValue = if (event.x < playerView.width * .5f) {
                        val currentBrightness = window.attributes.screenBrightness
                        if (currentBrightness >= 0f) currentBrightness * 100f else Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f * 100f
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max * 100f
                    }
                    handler.removeCallbacks(hideHud)
                }
            }

            if (event.pointerCount > 1) {
                multiTouchGesture = true; gestureMode = 0
                return@setOnTouchListener true
            }
            if (multiTouchGesture) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) multiTouchGesture = false
                return@setOnTouchListener true
            }
            if (zoomScale > 1.01f && gestureMode == 0) {
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    zoomPanX += event.x - lastPanX; zoomPanY += event.y - lastPanY
                    lastPanX = event.x; lastPanY = event.y; applyVideoTransform()
                }
                return@setOnTouchListener true
            }
            detector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    if (gestureMode == 3 && !locked) {
                        player?.seekTo(pendingSeekPosition)
                        seekPill.animate().cancel()
                        seekPill.visibility = View.GONE
                        seekPill.alpha = 1f
                    } else if (gestureMode != 0) {
                        handler.postDelayed(hideHud, 400L)
                        if (gestureMode == 1 || gestureMode == 2) {
                            handler.postDelayed(hideIsolatedHudContainer, 650L)
                        }
                    }
                    if (gestureMode == 3) handler.postDelayed(hideControls, 3500L)
                    gestureMode = 0
                }
                MotionEvent.ACTION_CANCEL -> {
                    seekPill.animate().cancel()
                    seekPill.visibility = View.GONE
                    seekPill.alpha = 1f
                    if (gestureMode != 0) {
                        handler.postDelayed(hideHud, 400L)
                        if (gestureMode == 1 || gestureMode == 2) {
                            handler.postDelayed(hideIsolatedHudContainer, 650L)
                        }
                    }
                    gestureMode = 0
                }
            }
            true
        }
    }

    private fun applySystemOrientationPolicy() {
        if (locked) { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED; return }
        val autoRotate = runCatching { Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }.getOrDefault(false)
        requestedOrientation = if (autoRotate) ActivityInfo.SCREEN_ORIENTATION_SENSOR else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    private fun resizeModeForAspect(value: Int): Int = when (value) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    private fun applyVideoTransform() {
        val maxX = (playerView.width * (zoomScale - 1f) / 2f).coerceAtLeast(0f)
        val maxY = (playerView.height * (zoomScale - 1f) / 2f).coerceAtLeast(0f)
        zoomPanX = zoomPanX.coerceIn(-maxX, maxX); zoomPanY = zoomPanY.coerceIn(-maxY, maxY)
        playerView.scaleX = zoomScale; playerView.scaleY = zoomScale
        playerView.translationX = zoomPanX; playerView.translationY = zoomPanY
    }

    private fun showGestureControls() {
        controls.visibility = View.VISIBLE
        topBar.visibility = View.GONE
        centerControls.visibility = View.GONE
        bottomPanel.getChildAt(1)?.visibility = View.GONE
        seekPill.visibility = View.GONE
        bottomPanel.visibility = View.VISIBLE
        bottomPanel.alpha = 1f
        bottomPanel.translationY = 0f
        controlsVisible = false
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

    private fun revealHudWithoutControls() {
        if (controlsVisible) return
        handler.removeCallbacks(hideIsolatedHudContainer)
        controls.visibility = View.VISIBLE
        topBar.visibility = View.GONE
        bottomPanel.visibility = View.GONE
        centerControls.visibility = View.GONE
    }

    private fun showVerticalHud(hud: View, fraction: Float, glyph: String) {
        revealHudWithoutControls()
        val isBrightness = hud.id == R.id.player_brightness_hud
        if (isBrightness) findViewById<TextView>(R.id.player_brightness_icon).text = glyph
        else findViewById<ImageView>(R.id.player_volume_icon).setImageResource(R.drawable.ic_player_music_note)
        findViewById<TextView>(if (isBrightness) R.id.player_hud_percent else R.id.player_volume_percent).text =
            "${(fraction * 100).roundToInt()}%"
        findViewById<ProgressBar>(if (isBrightness) R.id.player_hud_progress else R.id.player_volume_progress).progress =
            (fraction * 100).roundToInt()
        // Keep the vertical indicator visible for the entire active swipe.
        // It is dismissed from ACTION_UP/ACTION_CANCEL, never by a mid-gesture timer.
        handler.removeCallbacks(hideHud)
        hud.animate().cancel()
        hud.visibility = View.VISIBLE
        hud.alpha = 1f
    }
    private fun showHud(view: View) {
        revealHudWithoutControls()
        handler.removeCallbacks(hideHud)
        view.animate().cancel(); view.visibility = View.VISIBLE; view.alpha = 1f
        handler.postDelayed(hideHud, 400L)
        if (!controlsVisible) handler.postDelayed(hideIsolatedHudContainer, 650L)
    }
    private fun fadeHud(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().alpha(0f).setDuration(180L).withEndAction { view.visibility = View.GONE }.start()
    }
    private fun showControls(show: Boolean) {
        controlsVisible = show
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(hideIsolatedHudContainer)
        val interp = android.view.animation.DecelerateInterpolator()
        if (show) {
            bottomPanel.getChildAt(1)?.visibility = View.VISIBLE
            controls.visibility = View.VISIBLE
            topBar.visibility = View.VISIBLE
            bottomPanel.visibility = View.VISIBLE
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
        playerPrefs.edit().putInt("aspect", aspect).apply()
        zoomHud.text = labels[aspect]
        zoomHud.visibility = View.VISIBLE; zoomHud.alpha = 1f
        handler.removeCallbacks(hideHud); handler.postDelayed({ fadeHud(zoomHud) }, 800L)
        findViewById<ImageButton>(R.id.player_aspect).isSelected = aspect != 0
    }
    private fun showMore() {
        AlertDialog.Builder(this).setItems(arrayOf("Fit", "Crop", "Fill", "Playback speed")) { _, which ->
            when (which) {
                0 -> { aspect = 0; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; playerPrefs.edit().putInt("aspect", aspect).apply() }
                1 -> { aspect = 1; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; playerPrefs.edit().putInt("aspect", aspect).apply() }
                2 -> { aspect = 2; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; playerPrefs.edit().putInt("aspect", aspect).apply() }
                3 -> showSpeed()
            }
        }.show()
    }
    private fun showSpeed() {
        val values = floatArrayOf(.5f, 1f, 1.25f, 1.5f, 2f)
        val labels = arrayOf("0.5x", "1.0x", "1.25x", "1.5x", "2.0x")
        val selected = values.indexOfFirst { abs(it - playbackSpeed) < 0.01f }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Playback speed").setSingleChoiceItems(labels, selected) { dialog, which ->
            playbackSpeed = values[which]
            player?.setPlaybackSpeed(playbackSpeed)
            playerPrefs.edit().putFloat("speed", playbackSpeed).apply()
            findViewById<TextView>(R.id.player_speed_label).text = labels[which]
            dialog.dismiss()
        }.show()
    }
    private fun populatePlaylist(folder: File?) {
        playlist.removeAllViews()
        val files = folder?.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.US) in
            setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "mp3", "m4a", "opus") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        files.forEach { file ->
            val card = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(36)).apply { marginEnd = dp(3) }
                tag = file.absolutePath
                background = ContextCompat.getDrawable(this@VideoPlayerActivity, R.drawable.player_playlist_item)
                clipToOutline = true
                setOnClickListener {
                    currentPath = file.absolutePath
                    titleView.text = file.nameWithoutExtension
                    player?.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                    player?.prepare()
                    player?.setPlaybackSpeed(playbackSpeed)
                    player?.repeatMode = if (repeatEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    player?.play()
                    refreshSelection()
                }
            }
            val image = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.player_thumbnail_bg)
                clipToOutline = true
                tag = file.absolutePath
            }
            card.addView(image)
            val isAudio = file.extension.lowercase(Locale.US) in setOf("mp3", "m4a", "opus")
            if (isAudio) image.setImageResource(android.R.drawable.ic_media_play)
            val cached = thumbCache.get(file.absolutePath)
            if (cached != null) image.setImageBitmap(cached) else if (!isAudio) {
                // Extract frames on a worker thread; never decode thumbnails on the UI thread.
                thumbExecutor.execute {
                    var bitmap: android.graphics.Bitmap? = null
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            bitmap = runCatching {
                                android.media.ThumbnailUtils.createVideoThumbnail(
                                    file, android.util.Size(dp(144), dp(96)), null
                                )
                            }.getOrNull()
                        }
                        if (bitmap == null) {
                            retriever.setDataSource(file.absolutePath)
                            bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(
                                    1_000_000L,
                                    MediaMetadataRetriever.OPTION_CLOSEST,
                                    dp(144),
                                    dp(96)
                                )
                            } else null
                            bitmap = bitmap
                                ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { retriever.release() } catch (_: Exception) {}
                    }
                    bitmap?.let { decoded ->
                        thumbCache.put(file.absolutePath, decoded)
                        runOnUiThread {
                            if (!isDestroyed && image.tag == file.absolutePath) image.setImageBitmap(decoded)
                        }
                    }
                }
            }
            playlist.addView(card)
        }
        refreshSelection()
    }
    private fun refreshSelection() {
        for (i in 0 until playlist.childCount) {
            val child = playlist.getChildAt(i)
            val selected = child.tag == currentPath
            child.setBackgroundResource(if (selected) R.drawable.player_playlist_selected else R.drawable.player_playlist_item)
            child.scaleX = if (selected) 1.05f else 1f
            child.scaleY = if (selected) 1.05f else 1f
        }
    }
    private fun backgroundAudio() {
        if (currentPath.isBlank() || !File(currentPath).isFile) return
        bgAudioEnabled = true
        findViewById<ImageButton>(R.id.player_background).isSelected = true
        ContextCompat.startForegroundService(this, Intent(this, PlayerPlaybackService::class.java).apply {
            putExtra(PlayerPlaybackService.EXTRA_PATH, currentPath)
            putExtra(PlayerPlaybackService.EXTRA_TITLE, titleView.text.toString())
        })
        Toast.makeText(this, "Background audio ON", Toast.LENGTH_SHORT).show()
        finish()
    }
    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT >= 26) enterPip() }
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            handler.removeCallbacks(hideControls)
            controls.visibility = View.GONE
            findViewById<Button>(R.id.player_unlock).visibility = View.GONE
        } else if (!locked) {
            showControls(true)
        } else {
            controls.visibility = View.GONE
            findViewById<Button>(R.id.player_unlock).visibility = View.VISIBLE
        }
    }

    override fun onPause() {
        super.onPause()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip && !bgAudioEnabled && !isChangingConfigurations) player?.pause()
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
        if (!scrubbing) {
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
        handler.removeCallbacksAndMessages(null)
        thumbExecutor.shutdownNow()
        try { unregisterReceiver(battery) } catch (_: Exception) {}
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
