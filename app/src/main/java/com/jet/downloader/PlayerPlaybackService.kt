package com.jet.downloader

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import java.io.File

class PlayerPlaybackService : MediaSessionService() {
    companion object {
        const val EXTRA_PATH = "background_audio_path"
        const val EXTRA_TITLE = "background_audio_title"
        const val EXTRA_QUEUE_JSON = "music_queue_json"
        const val EXTRA_SELECTED_PATH = "music_selected_path"
        const val EXTRA_SOURCE = "music_source"
        const val EXTRA_SLEEP_TIMER_MINUTES = "music_sleep_timer_minutes"
        const val ACTION_SET_QUEUE = "com.jet.downloader.action.SET_MUSIC_QUEUE"
        const val ACTION_SLEEP_TIMER = "com.jet.downloader.action.MUSIC_SLEEP_TIMER"
    }
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var initializationFailed = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private val sleepTimer = Runnable { player?.pause() }
    override fun onCreate() {
        super.onCreate()
        try {
            val exo = ExoPlayer.Builder(this).build()
            player = exo
            session = MediaSession.Builder(this, exo).build()
        } catch (t: Throwable) {
            initializationFailed = true
            Log.e("JET-MusicService", "Playback service initialization failed", t)
            runCatching { session?.release() }
            session = null
            runCatching { player?.release() }
            player = null
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (initializationFailed || player == null || session == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SLEEP_TIMER -> {
                timerHandler.removeCallbacks(sleepTimer)
                val minutes = intent.getIntExtra(EXTRA_SLEEP_TIMER_MINUTES, 0).coerceIn(0, 240)
                if (minutes > 0) timerHandler.postDelayed(sleepTimer, minutes * 60_000L)
            }
            ACTION_SET_QUEUE -> {
                timerHandler.removeCallbacks(sleepTimer)
                setQueue(intent.getStringExtra(EXTRA_QUEUE_JSON).orEmpty(), intent.getStringExtra(EXTRA_SELECTED_PATH).orEmpty())
            }
            else -> {
                val path = intent?.getStringExtra(EXTRA_PATH)
                if (!path.isNullOrBlank() && File(path).isFile) {
                    val file = File(path)
                    val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { file.nameWithoutExtension }
                    player?.setMediaItem(buildMediaItem(file, title))
                    player?.prepare()
                    player?.play()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }
    private fun setQueue(payload: String, selectedPath: String) {
        val items = ArrayList<MediaItem>()
        var selectedIndex = 0
        runCatching {
            val rows = JSONArray(payload)
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val path = row.optString("path")
                val file = File(path)
                if (!file.isFile) continue
                if (path == selectedPath) selectedIndex = items.size
                items.add(buildMediaItem(file, row.optString("title").ifBlank { file.nameWithoutExtension }))
            }
        }.onFailure { android.util.Log.e("JET-MusicService", "Invalid queue payload", it) }
        if (items.isEmpty() && selectedPath.isNotBlank() && File(selectedPath).isFile) items.add(buildMediaItem(File(selectedPath), File(selectedPath).nameWithoutExtension))
        if (items.isEmpty()) { player?.stop(); player?.clearMediaItems(); return }
        player?.setMediaItems(items, selectedIndex.coerceIn(items.indices), 0L)
        player?.prepare()
        player?.play()
    }
    private fun buildMediaItem(file: File, title: String): MediaItem =
        MediaItem.Builder().setMediaId(file.absolutePath).setUri(Uri.fromFile(file))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
        runCatching { session?.release() }
        session = null
        runCatching { player?.release() }
        player = null
        super.onDestroy()
    }
}
