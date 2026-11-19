package com.jet.downloader

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File

class PlayerPlaybackService : MediaSessionService() {
    companion object {
        const val EXTRA_PATH = "background_audio_path"
        const val EXTRA_TITLE = "background_audio_title"
    }
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        session = MediaSession.Builder(this, exo).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_PATH)
        if (!path.isNullOrBlank() && File(path).isFile) {
            player?.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            player?.prepare()
            player?.play()
        }
        return super.onStartCommand(intent, flags, startId)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        session?.release(); session = null
        player?.release(); player = null
        super.onDestroy()
    }
}
