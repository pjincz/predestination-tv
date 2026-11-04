package io.jcz.predestination.exo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/** Decorative ripples around the fallback record, not an audio spectrum or beat detector. */
internal class MusicBackgroundView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = context.getColor(R.color.music_ripple)
    }
    private var phase = 0f
    private var playing = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init { setBackgroundResource(R.drawable.music_background) }

    fun setPlaying(value: Boolean) {
        playing = value
        updateAnimation()
    }

    private fun updateAnimation() {
        val animate = playing && isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (animate) {
            if (animator.isPaused) animator.resume()
            else if (!animator.isStarted) animator.start()
        } else if (animator.isStarted && !animator.isPaused) animator.pause()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val origin = 122f * density
        val travel = (minOf(width, height) / 2f - origin - 8f * density)
            .coerceIn(0f, 140f * density)
        if (travel <= 0f) return
        repeat(3) { index ->
            val progress = (phase + index / 3f) % 1f
            ring.alpha = (sin(progress * Math.PI) * (1f - progress) * 55).toInt()
            canvas.drawCircle(width / 2f, height / 2f, origin + travel * progress, ring)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
