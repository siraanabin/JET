package com.jet.downloader

import android.app.PictureInPictureParams
import android.content.*
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.*
import android.view.*
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
import androidx.media3.ui.PlayerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class VideoPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_FILE_PATH = "video_file_path"
    }
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var seek: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var durationTime: TextView
    private lateinit var controls: View
    private lateinit var playlist: LinearLayout
    private var player: ExoPlayer? = null
    private var currentPath = ""
    private var locked = false
    private var muted = false
    private var ccEnabled = true
    private var aspect = 0
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
            if (level >= 0) findViewById<TextView>(R.id.player_battery).text = "▰ " + (level * 100 / scale) + "%"
        }
    }
    private val progress = object : Runnable {
        override fun run() { updateProgress(); handler.postDelayed(this, 500L) }
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
        playlist = findViewById(R.id.player_playlist)

        val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        if (path.isBlank() || !File(path).isFile) {
            Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentPath = path
        titleView.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { File(path).nameWithoutExtension }
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        playerView.useController = false
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(path))))
        exo.prepare()
        exo.play()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                findViewById<TextView>(R.id.player_play).text = if (isPlaying) "Ⅱ" else "▶"
            }
        })
        setupControls()
        setupGestures()
        populatePlaylist(File(path).parentFile)
        handler.post(clock)
        handler.post(progress)
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun setupControls() {
        findViewById<ImageButton>(R.id.player_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.player_rewind).setOnClickListener { seekBy(-10_000L) }
        findViewById<TextView>(R.id.player_play).setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        findViewById<TextView>(R.id.player_forward).setOnClickListener { seekBy(10_000L) }
        findViewById<TextView>(R.id.player_cc).setOnClickListener {
            ccEnabled = !ccEnabled
            player?.trackSelectionParameters = player!!.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !ccEnabled).build()
            findViewById<TextView>(R.id.player_cc).alpha = if (ccEnabled) 1f else .45f
        }
        findViewById<ImageButton>(R.id.player_more).setOnClickListener { showMore() }
        val unlockButton = findViewById<Button>(R.id.player_unlock)
        findViewById<Button>(R.id.player_lock).setOnClickListener {
            locked = true
            controls.visibility = View.GONE
            unlockButton.visibility = View.VISIBLE
            Toast.makeText(this, "Screen locked", Toast.LENGTH_SHORT).show()
        }
        unlockButton.setOnClickListener {
            locked = false
            controls.visibility = View.VISIBLE
            unlockButton.visibility = View.GONE
            Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.player_mute).setOnClickListener {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            findViewById<Button>(R.id.player_mute).text = if (muted) "Unmute" else "Mute"
        }
        findViewById<Button>(R.id.player_pip).setOnClickListener { enterPip() }
        findViewById<Button>(R.id.player_aspect).setOnClickListener {
            aspect = (aspect + 1) % 3
            val modes = intArrayOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            val labels = arrayOf("Fit", "Fill", "Zoom")
            playerView.resizeMode = modes[aspect]
            findViewById<Button>(R.id.player_aspect).text = labels[aspect]
        }
        findViewById<Button>(R.id.player_speed).setOnClickListener { showSpeed() }
        findViewById<Button>(R.id.player_background).setOnClickListener { backgroundAudio() }
        findViewById<Button>(R.id.player_rotate).setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) currentTime.text = time(value.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) { player?.seekTo(bar?.progress?.toLong() ?: 0L) }
        })
    }

    private fun setupGestures() {
        var downX = 0f
        var downY = 0f
        var initial = 0f
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!locked) seekBy(if (e.x < playerView.width / 2f) -10_000L else 10_000L)
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!locked) controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
        })
        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    initial = if (downX < playerView.width / 2f)
                        (window.attributes.screenBrightness.takeIf { it >= 0f } ?: .5f)
                    else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                }
                MotionEvent.ACTION_MOVE -> if (!locked && abs(event.y - downY) > 18f && abs(event.y - downY) > abs(event.x - downX)) {
                    val delta = (downY - event.y) / playerView.height
                    if (downX < playerView.width / 2f) {
                        window.attributes = window.attributes.apply { screenBrightness = (initial + delta).coerceIn(.02f, 1f) }
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (initial + delta * max).coerceIn(0f, max).toInt(), 0)
                    }
                }
            }
            true
        }
    }

    private fun showMore() {
        AlertDialog.Builder(this).setItems(arrayOf("Fit", "Fill", "Zoom", "Playback speed")) { _, which ->
            when (which) {
                0 -> { aspect = 0; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT }
                1 -> { aspect = 1; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL }
                2 -> { aspect = 2; playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM }
                3 -> showSpeed()
            }
        }.show()
    }
    private fun showSpeed() {
        val values = floatArrayOf(.5f, 1f, 1.25f, 1.5f, 2f)
        val labels = arrayOf("0.5x", "1.0x", "1.25x", "1.5x", "2.0x")
        AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels) { _, which ->
            player?.setPlaybackSpeed(values[which])
            findViewById<Button>(R.id.player_speed).text = labels[which]
        }.show()
    }

    private fun populatePlaylist(folder: File?) {
        val files = folder?.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.US) in
            setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "mp3", "m4a", "opus") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        files.forEach { file ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(6, 4, 6, 4)
                layoutParams = LinearLayout.LayoutParams(132, -1)
                tag = file.absolutePath
                setOnClickListener {
                    currentPath = file.absolutePath
                    titleView.text = file.nameWithoutExtension
                    player?.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                    player?.prepare(); player?.play()
                    refreshSelection()
                }
            }
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(116, 62)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF30343B.toInt())
            }
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(1_000_000L)?.let { image.setImageBitmap(it) }
                retriever.release()
            } catch (_: Exception) {}
            val name = TextView(this).apply {
                text = file.nameWithoutExtension; setTextColor(-1); textSize = 10f; maxLines = 1
            }
            card.addView(image); card.addView(name); playlist.addView(card)
        }
        refreshSelection()
    }
    private fun refreshSelection() {
        for (i in 0 until playlist.childCount) {
            val child = playlist.getChildAt(i)
            child.setBackgroundColor(if (child.tag == currentPath) 0xFF365D9D.toInt() else 0x66000000)
        }
    }
    private fun backgroundAudio() {
        ContextCompat.startForegroundService(this, Intent(this, PlayerPlaybackService::class.java).apply {
            putExtra(PlayerPlaybackService.EXTRA_PATH, currentPath)
            putExtra(PlayerPlaybackService.EXTRA_TITLE, titleView.text.toString())
        })
        finish()
    }
    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build())
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT >= 26) enterPip() }
    override fun onPictureInPictureModeChanged(inPip: Boolean, config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, config)
        controls.visibility = if (inPip || locked) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.player_unlock).visibility = if (inPip) View.GONE else if (locked) View.VISIBLE else View.GONE
    }
    private fun seekBy(delta: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + delta).coerceIn(0L, p.duration.coerceAtLeast(0L)))
    }
    private fun updateProgress() {
        val p = player ?: return
        seek.max = p.duration.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        seek.progress = p.currentPosition.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        currentTime.text = time(p.currentPosition)
        durationTime.text = time(p.duration)
    }
    private fun time(ms: Long): String {
        val s = ms.coerceAtLeast(0L) / 1000L
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(battery) } catch (_: Exception) {}
        playerView.player = null
        player?.release(); player = null
        super.onDestroy()
    }
}
