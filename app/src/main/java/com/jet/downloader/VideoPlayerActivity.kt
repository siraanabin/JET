package com.jet.downloader

import android.app.PictureInPictureParams
import android.content.*
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.*
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var leftTapHud: View
    private lateinit var rightTapHud: View
    private lateinit var zoomHud: TextView
    private lateinit var hudPercent: TextView
    private lateinit var seekTarget: TextView
    private lateinit var seekDelta: TextView
    private var player: ExoPlayer? = null
    private var bgAudioEnabled = false
    private val thumbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(24)
    private var currentPath = ""
    private var locked = false
    private var muted = false
    private var ccEnabled = true
    private var aspect = 0
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
                SimpleDateFormat(if (BuildConfig.DEBUG) "hh:mm a" else "HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 30_000L)
        }
    }
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0) {
                val percentage = level * 100 / scale
                findViewById<TextView>(R.id.player_battery).text =
                    if (BuildConfig.DEBUG) "$percentage%" else "▰ $percentage%"
            }
        }
    }
    private val progress = object : Runnable {
        override fun run() { updateProgress(); handler.postDelayed(this, 300L) }
    }
    private val hideHud = Runnable {
        fadeHud(volumeHud); fadeHud(brightnessHud); fadeHud(seekHud)
        fadeHud(leftTapHud); fadeHud(rightTapHud); fadeHud(zoomHud)
    }
    private val hideControls = Runnable {
        if (!locked && (BuildConfig.DEBUG || player?.isPlaying == true)) showControls(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        )
        setContentView(R.layout.activity_video_player)
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
        seekTarget = findViewById(R.id.player_seek_target)
        seekDelta = findViewById(R.id.player_seek_delta)

        val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        if (path.isBlank() || !File(path).isFile) {
            Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        currentPath = path
        val rawTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { File(path).nameWithoutExtension }
        titleView.text = if (BuildConfig.DEBUG) {
            val prefix = intent.getStringExtra("video_prefix")
                ?: intent.getStringExtra("video_quality").orEmpty()
            if (prefix.isNotBlank() && !rawTitle.contains(" : ")) "$prefix : $rawTitle" else rawTitle
        } else {
            intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { File(path).nameWithoutExtension }
        }
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        playerView.useController = false
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(path))))
        exo.prepare(); exo.play()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                findViewById<ImageButton>(R.id.player_play).setImageResource(
                    if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                )
                if (BuildConfig.DEBUG) {
                    handler.removeCallbacks(hideControls)
                    handler.postDelayed(hideControls, 3500L)
                } else if (isPlaying) handler.postDelayed(hideControls, 3000L)
            }
        })
        setupControls()
        setupGestures()
        populatePlaylist(File(path).parentFile)
        handler.post(clock); handler.post(progress)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.postDelayed(hideControls, 3500L)
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.player_close).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.player_rewind).setOnClickListener { seekBy(-10_000L) }
        findViewById<ImageButton>(R.id.player_play).setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        findViewById<ImageButton>(R.id.player_forward).setOnClickListener { seekBy(10_000L) }
        findViewById<TextView>(R.id.player_cc).setOnClickListener {
            ccEnabled = !ccEnabled
            player?.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !ccEnabled).build()
            it.alpha = if (ccEnabled) 1f else .45f
        }
        findViewById<View>(R.id.player_more).setOnClickListener { showMore() }
        if (BuildConfig.DEBUG) {
            val unlock = findViewById<ImageButton>(R.id.player_unlock)
            findViewById<ImageButton>(R.id.player_lock).setOnClickListener {
                locked = true
                showControls(false)
                unlock.setImageResource(R.drawable.ic_player_unlock)
                unlock.visibility = View.VISIBLE
                Toast.makeText(this, "Screen locked", Toast.LENGTH_SHORT).show()
            }
            unlock.setOnClickListener {
                locked = false
                unlock.visibility = View.GONE
                showControls(true)
                Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
            }
        } else {
            val unlock = findViewById<Button>(R.id.player_unlock)
            findViewById<ImageButton>(R.id.player_lock).setOnClickListener {
                locked = true; showControls(false); unlock.visibility = View.VISIBLE
                Toast.makeText(this, "Screen locked", Toast.LENGTH_SHORT).show()
            }
            unlock.setOnClickListener {
                locked = false; unlock.visibility = View.GONE; showControls(true)
                Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<ImageButton>(R.id.player_mute).setOnClickListener {
            muted = !muted; player?.volume = if (muted) 0f else 1f
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
        listOf(R.id.player_close, R.id.player_rewind, R.id.player_play, R.id.player_forward,
            R.id.player_more, R.id.player_lock, R.id.player_mute, R.id.player_pip,
            R.id.player_aspect, R.id.player_speed, R.id.player_background, R.id.player_rotate)
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
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!locked) {
                    val forward = e.x >= playerView.width / 2f
                    seekBy(if (forward) 10_000L else -10_000L)
                    val hud = if (forward) rightTapHud else leftTapHud
                    val label = hud.findViewById<TextView>(if (forward) R.id.player_right_tap_text else R.id.player_left_tap_text)
                    label.text = if (forward) "10s »" else "« 10s"
                    hud.visibility = View.VISIBLE; hud.alpha = 1f
                    hud.scaleX = .75f; hud.scaleY = .75f
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
        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x; gestureStartY = event.y
                    gestureMode = 0; lastSeekDelta = 0L
                    gestureStartPosition = player?.currentPosition ?: 0L
                    gestureStartValue = if (event.x < playerView.width * .5f)
                        (window.attributes.screenBrightness.takeIf { it >= 0f } ?: .5f)
                    else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                }
                MotionEvent.ACTION_MOVE -> if (!locked) {
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY
                    if (gestureMode == 0 && (abs(dx) > 24f || abs(dy) > 24f)) {
                        gestureMode = if (abs(dx) > abs(dy)) 3 else if (gestureStartX < playerView.width * .5f) 1 else 2
                    }
                    when (gestureMode) {
                        1 -> {
                            val value = (gestureStartValue + (gestureStartY - event.y) / playerView.height).coerceIn(.02f, 1f)
                            window.attributes = window.attributes.apply { screenBrightness = value }
                            showVerticalHud(brightnessHud, value, "☼")
                        }
                        2 -> {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val value = (gestureStartValue + (gestureStartY - event.y) / playerView.height * max).roundToInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                            showVerticalHud(volumeHud, value.toFloat() / max, "◖")
                        }
                        3 -> {
                            val duration = player?.duration?.takeIf { it > 0 } ?: 0L
                            val delta = (dx / playerView.width * duration.coerceAtMost(3_600_000L) * 1.4f).toLong()
                            lastSeekDelta = delta
                            val target = (gestureStartPosition + delta).coerceIn(0L, duration)
                            seekTarget.text = time(target)
                            seekDelta.text = (if (delta >= 0) "+" else "−") + time(abs(delta))
                            showHud(seekHud)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureMode == 3 && !locked) seekBy(lastSeekDelta)
                    if (gestureMode != 0) handler.postDelayed(hideHud, 400L)
                    gestureMode = 0
                }
            }
            true
        }
    }

    private fun showVerticalHud(hud: View, fraction: Float, glyph: String) {
        findViewById<TextView>(if (hud.id == R.id.player_brightness_hud) R.id.player_brightness_icon else R.id.player_volume_icon).text = glyph
        val isBrightness = hud.id == R.id.player_brightness_hud
        findViewById<TextView>(if (isBrightness) R.id.player_hud_percent else R.id.player_volume_percent).text =
            "${(fraction * 100).roundToInt()}%"
        findViewById<ProgressBar>(if (isBrightness) R.id.player_hud_progress else R.id.player_volume_progress).progress =
            (fraction * 100).roundToInt()
        showHud(hud)
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
        if (BuildConfig.DEBUG) {
            if (show) {
                controls.visibility = View.VISIBLE
                topBar.animate().cancel()
                bottomPanel.animate().cancel()
                centerControls.animate().cancel()
                topBar.alpha = 0f
                bottomPanel.alpha = 0f
                centerControls.alpha = 0f
                centerControls.scaleX = 0.9f
                centerControls.scaleY = 0.9f
                topBar.animate().alpha(1f).setDuration(220L).start()
                bottomPanel.animate().alpha(1f).setDuration(220L).start()
                centerControls.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L).start()
                handler.postDelayed(hideControls, 3500L)
            } else {
                topBar.animate().alpha(0f).setDuration(200L).start()
                bottomPanel.animate().alpha(0f).setDuration(200L).start()
                centerControls.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                    .setDuration(200L)
                    .withEndAction { if (!controlsVisible) controls.visibility = View.GONE }
                    .start()
            }
            return
        }
        val interp = android.view.animation.DecelerateInterpolator()
        if (show) {
            controls.visibility = View.VISIBLE
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
        zoomHud.text = labels[aspect]
        zoomHud.visibility = View.VISIBLE; zoomHud.alpha = 1f
        handler.removeCallbacks(hideHud); handler.postDelayed({ fadeHud(zoomHud) }, 800L)
        findViewById<ImageButton>(R.id.player_aspect).isSelected = aspect != 0
    }
    private fun showMore() {
        AlertDialog.Builder(this).setItems(arrayOf("Fit", "Crop", "Fill", "Playback speed")) { _, which ->
            when (which) {
                0 -> { aspect = 0; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT }
                1 -> { aspect = 1; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM }
                2 -> { aspect = 2; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL }
                3 -> showSpeed()
            }
        }.show()
    }
    private fun showSpeed() {
        val values = floatArrayOf(.5f, 1f, 1.25f, 1.5f, 2f)
        val labels = arrayOf("0.5x", "1.0x", "1.25x", "1.5x", "2.0x")
        AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels) { _, which ->
            player?.setPlaybackSpeed(values[which])
            findViewById<TextView>(R.id.player_speed_label).text = labels[which]
        }.show()
    }
    private fun populatePlaylist(folder: File?) {
        playlist.removeAllViews()
        val files = folder?.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.US) in
            setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "mp3", "m4a", "opus") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        files.forEach { file ->
            val card = FrameLayout(this).apply {
                layoutParams = if (BuildConfig.DEBUG) {
                    val metrics = resources.displayMetrics
                    LinearLayout.LayoutParams(
                        (metrics.widthPixels * 0.0975f).roundToInt(),
                        (metrics.heightPixels * 0.1194f).roundToInt()
                    ).apply { marginEnd = (metrics.widthPixels * 0.0067f).roundToInt() }
                } else {
                    LinearLayout.LayoutParams(dp(68), dp(42)).apply { marginEnd = dp(6) }
                }
                tag = file.absolutePath
                background = ContextCompat.getDrawable(this@VideoPlayerActivity, R.drawable.player_playlist_item)
                clipToOutline = true
                setOnClickListener {
                    currentPath = file.absolutePath
                    titleView.text = if (BuildConfig.DEBUG) {
                        val prefix = intent.getStringExtra("video_prefix")
                            ?: intent.getStringExtra("video_quality").orEmpty()
                        if (prefix.isNotBlank()) "$prefix : ${file.nameWithoutExtension}" else file.nameWithoutExtension
                    } else file.nameWithoutExtension
                    player?.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                    player?.prepare()
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
            val cached = thumbCache.get(file.absolutePath)
            if (cached != null) image.setImageBitmap(cached) else {
                // Extract frames on a worker thread; never decode thumbnails on the UI thread.
                thumbExecutor.execute {
                    var bitmap: android.graphics.Bitmap? = null
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        bitmap = retriever.getFrameAtTime(1_000_000L)
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
            if (BuildConfig.DEBUG) {
                val metrics = resources.displayMetrics
                val params = child.layoutParams as LinearLayout.LayoutParams
                params.width = (metrics.widthPixels * if (selected) 0.1117f else 0.0975f).roundToInt()
                params.height = (metrics.heightPixels * if (selected) 0.1519f else 0.1194f).roundToInt()
                child.layoutParams = params
                child.scaleX = 1f
                child.scaleY = 1f
            } else {
                child.scaleX = if (selected) 1.05f else 1f
                child.scaleY = if (selected) 1.05f else 1f
            }
        }
        if (BuildConfig.DEBUG) {
            val scrollView = findViewById<HorizontalScrollView>(R.id.player_playlist_scroll)
            scrollView.post {
                val selectedIndex = (0 until playlist.childCount).firstOrNull {
                    playlist.getChildAt(it).tag == currentPath
                } ?: return@post
                val selectedChild = playlist.getChildAt(selectedIndex)
                val previousChild = playlist.getChildAt(selectedIndex - 1)
                val gap = (resources.displayMetrics.widthPixels * 0.0067f).roundToInt()
                val scrollX = (selectedChild.left - (previousChild?.width ?: 0) - gap).coerceAtLeast(0)
                scrollView.smoothScrollTo(scrollX, 0)
            }
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
            if (BuildConfig.DEBUG) findViewById<ImageButton>(R.id.player_unlock).visibility = View.GONE
            else findViewById<Button>(R.id.player_unlock).visibility = View.GONE
        } else if (!locked) {
            showControls(true)
        } else {
            controls.visibility = View.GONE
            if (BuildConfig.DEBUG) findViewById<ImageButton>(R.id.player_unlock).visibility = View.VISIBLE
            else findViewById<Button>(R.id.player_unlock).visibility = View.VISIBLE
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
            else if (BuildConfig.DEBUG) String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
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
