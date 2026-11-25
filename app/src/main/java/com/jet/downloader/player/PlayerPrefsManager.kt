package com.jet.downloader.player

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import kotlin.math.ceil

class PlayerPrefsManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jet_player_persistent_prefs", Context.MODE_PRIVATE)

    var savedResizeMode: Int
        get() = prefs.getInt("resize_mode", AspectRatioFrameLayout.RESIZE_MODE_FIT)
        set(value) = prefs.edit().putInt("resize_mode", value).apply()

    var savedVolumePercent: Int
        get() = prefs.getInt("volume_percent", 50).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("volume_percent", value.coerceIn(0, 100)).apply()

    var savedBrightnessPercent: Int
        get() = prefs.getInt("brightness_percent", -1).coerceIn(-1, 100)
        set(value) = prefs.edit().putInt("brightness_percent", value.coerceIn(-1, 100)).apply()

    var savedPlaybackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1f).coerceIn(0.25f, 4f)
        set(value) = prefs.edit().putFloat("playback_speed", value.coerceIn(0.25f, 4f)).apply()
}

fun applyGranularVolumePercent(context: Context, player: Player, percent: Int) {
    val target = percent.coerceIn(0, 100)
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxStep = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    if (target == 0) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        player.volume = 0f
        return
    }
    val exactStep = target / 100f * maxStep
    val systemStep = ceil(exactStep.toDouble()).toInt().coerceIn(1, maxStep)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, systemStep, 0)
    player.volume = (exactStep / systemStep).coerceIn(0.01f, 1f)
}
