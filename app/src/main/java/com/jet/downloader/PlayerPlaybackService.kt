package com.jet.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import com.jet.downloader.player.PlaybackStateTracker
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
        const val ACTION_STOP_FOR_VIDEO = "com.jet.downloader.action.STOP_MUSIC_FOR_VIDEO"
        const val ACTION_TOGGLE_PLAYBACK = "com.jet.downloader.action.TOGGLE_BACKGROUND_PLAYBACK"
        const val ACTION_STOP_PLAYBACK = "com.jet.downloader.action.STOP_BACKGROUND_PLAYBACK"
        private const val NOTIFICATION_CHANNEL_ID = "jet_bg_playback"
        private const val NOTIFICATION_ID = 3912
        const val EXTRA_START_POSITION = "background_audio_start_position_ms"
        const val EXTRA_PLAY_WHEN_READY = "background_audio_play_when_ready"

        data class VideoHandoff(val positionMs: Long, val wasPlaying: Boolean)

        @Volatile private var activeInstance: PlayerPlaybackService? = null

        fun handoffToVideo(context: Context, requestedPath: String): VideoHandoff? {
            val service = activeInstance
            val exo = service?.player
            val currentMediaId = exo?.currentMediaItem?.mediaId.orEmpty()
            val matches = service?.backgroundVideoMode == true &&
                PlaybackStateTracker.normalizeMediaKey(currentMediaId) ==
                PlaybackStateTracker.normalizeMediaKey(requestedPath)
            val handoff = if (matches && exo != null) {
                val position = exo.currentPosition.coerceAtLeast(0L)
                val wasPlaying = exo.playWhenReady
                PlaybackStateTracker.save(
                    context, currentMediaId, position, exo.duration.coerceAtLeast(0L),
                    wasPlaying, true
                )
                VideoHandoff(position, wasPlaying)
            } else null

            if (service != null) {
                service.stopPlaybackForVideoHandoff()
            } else {
                context.stopService(Intent(context, PlayerPlaybackService::class.java))
            }
            return handoff
        }
    }

    private var player: ExoPlayer? = null
    private var backgroundVideoMode = false
    private var session: MediaSession? = null
    private var initializationFailed = false
    private var activeVideoFile: File? = null
    private var activeVideoTitle: String = ""
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> runCatching { player?.stop(); player?.clearMediaItems() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> runCatching { player?.pause() }
        }
    }
    @Suppress("DEPRECATION")
    private fun requestMusicAudioFocus() { audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) }
    @Suppress("DEPRECATION")
    private fun abandonMusicAudioFocus() { runCatching { audioManager.abandonAudioFocus(audioFocusListener) } }
    private val timerHandler = Handler(Looper.getMainLooper())
    private val positionTicker = object : Runnable {
        override fun run() {
            persistCurrentPosition()
            if (player?.currentMediaItem != null) timerHandler.postDelayed(this, 250L)
        }
    }
    private val playbackListener = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            persistCurrentPosition()
            updateMediaNotification()
        }

        override fun onPositionDiscontinuity(
            oldPosition: androidx.media3.common.Player.PositionInfo,
            newPosition: androidx.media3.common.Player.PositionInfo,
            reason: Int
        ) {
            persistCurrentPosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            persistCurrentPosition()
        }
    }
    private val sleepTimer = Runnable {
        try {
            player?.pause()
        } catch (t: Throwable) {
            Log.e("JET-MusicService", "Sleep timer pause failed", t)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildMediaNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildMediaNotification())
        }
        try {
            val exo = ExoPlayer.Builder(this).build().apply { setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), false) }
            player = exo
            exo.addListener(playbackListener)
            session = MediaSession.Builder(this, exo).build()
            activeInstance = this
        } catch (t: Throwable) {
            initializationFailed = true
            Log.e("JET-MusicService", "Playback service initialization failed", t)
            runCatching { session?.release() }
            session = null
            runCatching { player?.release() }
            player = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildMediaNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildMediaNotification())
        }
        try {
            if (intent?.action == ACTION_STOP_PLAYBACK) {
                stopBackgroundPlayback(startId)
                return START_NOT_STICKY
            }
            if (initializationFailed || player == null || session == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }

            when (intent?.action) {
                ACTION_STOP_FOR_VIDEO -> {
                    stopPlaybackForVideoHandoff()
                    return START_NOT_STICKY
                }
                ACTION_TOGGLE_PLAYBACK -> toggleBackgroundPlayback()
                ACTION_SLEEP_TIMER -> {
                    timerHandler.removeCallbacks(sleepTimer)
                    val minutes = intent.getIntExtra(EXTRA_SLEEP_TIMER_MINUTES, 0).coerceIn(0, 240)
                    if (minutes > 0) {
                        timerHandler.postDelayed(sleepTimer, minutes * 60_000L)
                    }
                }

                ACTION_SET_QUEUE -> {
                    timerHandler.removeCallbacks(sleepTimer)
                    setQueue(
                        intent.getStringExtra(EXTRA_QUEUE_JSON).orEmpty(),
                        intent.getStringExtra(EXTRA_SELECTED_PATH).orEmpty()
                    )
                }

                else -> {
                    val path = intent?.getStringExtra(EXTRA_PATH)
                    if (!path.isNullOrBlank()) {
                        val file = File(path)
                        if (file.isFile) {
                            val title = intent.getStringExtra(EXTRA_TITLE)
                                .orEmpty().ifBlank { file.nameWithoutExtension }
                            val mediaItem = buildMediaItem(file, title)
                            val exo = player ?: return START_NOT_STICKY
                            val startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0L).coerceAtLeast(0L)
                            val playWhenReady = intent.getBooleanExtra(EXTRA_PLAY_WHEN_READY, true)
                            backgroundVideoMode = true
                            activeVideoFile = file
                            activeVideoTitle = title
                            requestMusicAudioFocus()
                            setVideoSessionActivity(file, title)
                            updateMediaNotification()
                            exo.setMediaItem(mediaItem)
                            exo.seekTo(startPosition)
                            exo.playWhenReady = playWhenReady
                            exo.prepare()
                            PlaybackStateTracker.save(
                                this, file.absolutePath, startPosition, 0L, playWhenReady, true
                            )
                            timerHandler.removeCallbacks(positionTicker)
                            timerHandler.post(positionTicker)
                            updateMediaNotification()
                        } else {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf(startId)
                            return START_NOT_STICKY
                        }
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("JET-MusicService", "Playback start command failed", t)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun setVideoSessionActivity(file: File, title: String) {
        val openVideo = Intent(this, VideoPlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(VideoPlayerActivity.EXTRA_FILE_PATH, file.absolutePath)
            putExtra(VideoPlayerActivity.EXTRA_TITLE, title)
            putExtra(EXTRA_START_POSITION, player?.currentPosition?.coerceAtLeast(0L) ?: 0L)
            putExtra(EXTRA_PLAY_WHEN_READY, player?.playWhenReady ?: true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            file.absolutePath.hashCode(),
            openVideo,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session?.setSessionActivity(pendingIntent)
    }


    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Background playback",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildMediaNotification(): Notification {
        val exo = player
        val title = activeVideoTitle.ifBlank {
            exo?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "JET Background Play"
        }
        val isPlaying = exo?.playWhenReady == true
        val contentIntent = activeVideoFile?.let { file ->
            val openVideo = Intent(this, VideoPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(VideoPlayerActivity.EXTRA_FILE_PATH, file.absolutePath)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, title)
                putExtra(EXTRA_START_POSITION, exo?.currentPosition?.coerceAtLeast(0L) ?: 0L)
                putExtra(EXTRA_PLAY_WHEN_READY, isPlaying)
            }
            PendingIntent.getActivity(
                this,
                file.absolutePath.hashCode(),
                openVideo,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } ?: PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlayerPlaybackService::class.java).setAction(ACTION_TOGGLE_PLAYBACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlayerPlaybackService::class.java).setAction(ACTION_STOP_PLAYBACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
            .setContentTitle(title)
            .setContentText(
                if (backgroundVideoMode) {
                    if (isPlaying) "Playing in background" else "Paused"
                } else {
                    "Media playback"
                }
            )
            .setSubText(
                if (backgroundVideoMode) {
                    if (isPlaying) "Playing in background" else "Paused"
                } else {
                    "Media playback"
                }
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                toggleIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (backgroundVideoMode) "Stop" else "Close",
                stopIntent
            )
            .build()
    }

    private fun updateMediaNotification() {
        if (backgroundVideoMode) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildMediaNotification())
            }.onFailure { Log.w("JET-MusicService", "Could not refresh playback notification", it) }
        }
    }

    private fun toggleBackgroundPlayback() {
        val exo = player ?: return
        if (exo.playWhenReady) exo.pause() else exo.play()
        persistCurrentPosition()
        updateMediaNotification()
    }

    private fun stopBackgroundPlayback(startId: Int) {
        timerHandler.removeCallbacksAndMessages(null)
        persistCurrentPosition()
        runCatching { player?.pause() }
        runCatching { player?.stop() }
        runCatching { player?.clearMediaItems() }
        backgroundVideoMode = false
        activeVideoFile = null
        activeVideoTitle = ""
        PlaybackStateTracker.clearActive(this)
        abandonMusicAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun persistCurrentPosition() {
        val exo = player ?: return
        val mediaId = exo.currentMediaItem?.mediaId ?: return
        PlaybackStateTracker.save(
            this,
            mediaId,
            exo.currentPosition.coerceAtLeast(0L),
            exo.duration.takeIf { it > 0L } ?: 0L,
            exo.playWhenReady,
            backgroundVideoMode
        )
    }

    private fun stopPlaybackForVideoHandoff() {
        timerHandler.removeCallbacksAndMessages(null)
        persistCurrentPosition()
        runCatching { player?.pause() }
        runCatching { player?.stop() }
        runCatching { player?.clearMediaItems() }
        backgroundVideoMode = false
        activeVideoFile = null
        activeVideoTitle = ""
        PlaybackStateTracker.clearActive(this)
        abandonMusicAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setQueue(payload: String, selectedPath: String) {
        try {
            backgroundVideoMode = false
            timerHandler.removeCallbacks(positionTicker)
            val items = ArrayList<MediaItem>()
            var selectedIndex = 0

            try {
                val rows = JSONArray(payload)
                for (i in 0 until rows.length()) {
                    try {
                        val row = rows.optJSONObject(i) ?: continue
                        val path = row.optString("path")
                        if (path.isBlank()) continue
                        val file = File(path)
                        if (!file.isFile) continue
                        if (path == selectedPath) selectedIndex = items.size
                        val title = row.optString("title")
                            .ifBlank { file.nameWithoutExtension }
                        items.add(buildMediaItem(file, title))
                    } catch (t: Throwable) {
                        Log.w("JET-MusicService", "Skipping invalid queue item at index $i", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e("JET-MusicService", "Invalid queue payload", t)
            }

            if (items.isEmpty() && selectedPath.isNotBlank()) {
                try {
                    val selectedFile = File(selectedPath)
                    if (selectedFile.isFile) {
                        items.add(buildMediaItem(selectedFile, selectedFile.nameWithoutExtension))
                    }
                } catch (t: Throwable) {
                    Log.w("JET-MusicService", "Invalid selected queue path", t)
                }
            }

            val exo = player ?: return
            if (items.isEmpty()) {
                exo.stop()
                exo.clearMediaItems()
                return
            }

            requestMusicAudioFocus()
            exo.setMediaItems(items, selectedIndex.coerceIn(items.indices), 0L)
            exo.prepare()
            exo.play()
            timerHandler.removeCallbacks(positionTicker)
            timerHandler.post(positionTicker)
        } catch (t: Throwable) {
            Log.e("JET-MusicService", "Queue playback operation failed", t)
        }
    }

    private fun buildMediaItem(file: File, title: String): MediaItem =
        MediaItem.Builder().setMediaId(file.absolutePath).setUri(Uri.fromFile(file))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
        persistCurrentPosition()
        abandonMusicAudioFocus()
        player?.removeListener(playbackListener)
        runCatching { session?.release() }
        session = null
        runCatching { player?.release() }
        player = null
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }
}
