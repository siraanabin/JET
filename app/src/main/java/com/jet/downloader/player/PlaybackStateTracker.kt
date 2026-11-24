package com.jet.downloader.player

import android.content.Context
import android.net.Uri
import java.io.File

data class PlaybackSnapshot(
    val mediaKey: String,
    val positionMs: Long,
    val isPlaying: Boolean,
    val isBackgroundMode: Boolean
)

/** Process-wide playback state plus durable per-media resume positions. */
object PlaybackStateTracker {
    private const val PREFS_NAME = "jet_playback_state"
    @Volatile private var activeSnapshot: PlaybackSnapshot? = null

    fun normalizeMediaKey(mediaId: String): String {
        val value = mediaId.trim()
        if (value.isEmpty()) return ""
        return runCatching {
            val uri = Uri.parse(value)
            when {
                uri.scheme.equals("file", ignoreCase = true) ->
                    File(uri.path ?: value).canonicalPath
                uri.scheme.isNullOrBlank() -> File(value).canonicalPath
                else -> uri.normalizeScheme().toString()
            }
        }.getOrElse { value }
    }

    private fun preferenceSuffix(key: String): String =
        key.hashCode().toUInt().toString(16)

    fun save(
        context: Context,
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        isBackgroundMode: Boolean
    ): PlaybackSnapshot? {
        val key = normalizeMediaKey(mediaId)
        if (key.isBlank()) return null
        val completed = durationMs > 0L &&
            positionMs.coerceAtLeast(0L).toDouble() / durationMs.toDouble() >= 0.98
        val safePosition = if (completed) 0L else positionMs.coerceAtLeast(0L)
        val safePlaying = isPlaying && !completed
        val suffix = preferenceSuffix(key)
        val snapshot = PlaybackSnapshot(key, safePosition, safePlaying, isBackgroundMode && !completed)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("media_key_$suffix", key)
            .putLong("position_$suffix", safePosition)
            .putBoolean("playing_$suffix", safePlaying)
            .putBoolean("background_$suffix", isBackgroundMode && !completed)
            .putString("active_media_key", key)
            .putLong("active_position_ms", safePosition)
            .putBoolean("active_is_playing", safePlaying)
            .putBoolean("active_background_mode", isBackgroundMode && !completed)
            .apply()
        activeSnapshot = snapshot
        return snapshot
    }

    fun restore(context: Context, mediaId: String): PlaybackSnapshot? {
        val key = normalizeMediaKey(mediaId)
        if (key.isBlank()) return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val suffix = preferenceSuffix(key)
        if (prefs.getString("media_key_$suffix", null) != key) return null
        return PlaybackSnapshot(
            mediaKey = key,
            positionMs = prefs.getLong("position_$suffix", 0L).coerceAtLeast(0L),
            isPlaying = prefs.getBoolean("playing_$suffix", true),
            isBackgroundMode = prefs.getBoolean("background_$suffix", false)
        )
    }

    fun active(): PlaybackSnapshot? = activeSnapshot

    fun clearActive(mediaId: String? = null) {
        val current = activeSnapshot ?: return
        if (mediaId == null || current.mediaKey == normalizeMediaKey(mediaId)) {
            activeSnapshot = null
        }
    }
}
