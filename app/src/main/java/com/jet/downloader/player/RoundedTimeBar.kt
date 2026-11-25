package com.jet.downloader.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class RoundedTimeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), TimeBar {
    private val density = resources.displayMetrics.density
    private val barHeight = 6f * density
    private val thumbRadius = 5f * density
    private val track = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val listeners = LinkedHashSet<TimeBar.OnScrubListener>()
    private var durationMs = 0L
    private var positionMs = 0L
    private var bufferedPositionMs = 0L
    private var scrubbing = false
    private var keyTimeIncrementMs: Long? = null
    private var keyCountIncrement = 20

    override fun addListener(listener: TimeBar.OnScrubListener) { listeners.add(listener) }
    override fun removeListener(listener: TimeBar.OnScrubListener) { listeners.remove(listener) }
    override fun setDuration(duration: Long) {
        durationMs = duration.coerceAtLeast(0L)
        positionMs = positionMs.coerceAtMost(durationMs)
        bufferedPositionMs = bufferedPositionMs.coerceAtMost(durationMs)
        invalidate()
    }
    override fun setPosition(position: Long) { positionMs = position.coerceIn(0L, durationMs); invalidate() }
    override fun setBufferedPosition(bufferedPosition: Long) { bufferedPositionMs = bufferedPosition.coerceIn(0L, durationMs); invalidate() }
    override fun setKeyTimeIncrement(time: Long) { keyTimeIncrementMs = time.coerceAtLeast(1L) }
    override fun setKeyCountIncrement(count: Int) { keyCountIncrement = count.coerceAtLeast(1); keyTimeIncrementMs = null }
    override fun setAdGroupTimesMs(adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int) = Unit
    override fun getPreferredUpdateDelay(): Long {
        val widthDp = ((width - paddingLeft - paddingRight) / density).toInt()
        return if (widthDp <= 0 || durationMs <= 0L) Long.MAX_VALUE else max(1L, durationMs / widthDp)
    }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); invalidate() }

    private fun trackBounds(): RectF {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val centerY = height / 2f
        return RectF(left, centerY - barHeight / 2f, max(left, right), centerY + barHeight / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        track.set(trackBounds())
        if (track.width() <= 0f) return
        val radius = barHeight / 2f
        paint.color = if (isEnabled) 0x40FFFFFF else 0x20FFFFFF
        canvas.drawRoundRect(track, radius, radius, paint)
        if (durationMs <= 0L) return

        val bufferedX = track.left + track.width() * (bufferedPositionMs.toDouble() / durationMs).toFloat()
        val playedX = track.left + track.width() * (positionMs.toDouble() / durationMs).toFloat()
        if (bufferedX > track.left) {
            paint.color = if (isEnabled) 0xA6FFFFFF.toInt() else 0x50FFFFFF
            canvas.drawRoundRect(RectF(track.left, track.top, min(track.right, bufferedX), track.bottom), radius, radius, paint)
        }
        if (playedX > track.left) {
            paint.color = if (isEnabled) 0xFF7FA8FF.toInt() else 0x807FA8FF.toInt()
            canvas.drawRoundRect(RectF(track.left, track.top, min(track.right, playedX), track.bottom), radius, radius, paint)
        }
        val scrubberX = playedX.coerceIn(track.left + thumbRadius, track.right - thumbRadius)
        paint.color = if (isEnabled) android.graphics.Color.WHITE else 0xFF9CA3AF.toInt()
        canvas.drawCircle(scrubberX, track.centerY(), if (scrubbing) 6f * density else thumbRadius, paint)
    }

    private fun updateFromTouch(x: Float) {
        val bounds = trackBounds()
        if (bounds.width() <= 0f || durationMs <= 0L) return
        val ratio = ((x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        positionMs = (durationMs * ratio).toLong().coerceIn(0L, durationMs)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0L) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                updateFromTouch(event.x)
                listeners.toList().forEach { it.onScrubStart(this, positionMs) }
                listeners.toList().forEach { it.onScrubMove(this, positionMs) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing) return false
                updateFromTouch(event.x)
                listeners.toList().forEach { it.onScrubMove(this, positionMs) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scrubbing) return false
                updateFromTouch(event.x)
                scrubbing = false
                listeners.toList().forEach { it.onScrubStop(this, positionMs, event.actionMasked == MotionEvent.ACTION_CANCEL) }
                performClick()
                invalidate()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled && durationMs > 0L && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val increment = keyTimeIncrementMs ?: max(1L, durationMs / keyCountIncrement)
            val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -increment else increment
            setPosition((positionMs + delta).coerceIn(0L, durationMs))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
