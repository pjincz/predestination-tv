package io.jcz.predestination.exo

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Keeps artwork stationary when the OSD opens; reserves space for music information below. */
internal class MusicStage(context: Context, background: View, artwork: View) : FrameLayout(context) {
    private val rows = mutableListOf<TextView>()
    private val lineGap = dp(18)
    private fun textHeight(row: TextView): Int =
        kotlin.math.ceil((row.paint.fontMetrics.descent - row.paint.fontMetrics.ascent).toDouble()).toInt()
    private var lyrics = EmbeddedLyrics(emptyList())
    private var positionMs = 0L
    private var skipNextAnimation = false
    private var scrollAnimator: android.animation.ValueAnimator? = null
    private val colorEvaluator = android.animation.ArgbEvaluator()
    private val lyricColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    var split = false
        set(value) {
            if (field == value) return
            field = value
            resetScroll()
            lyricColumn.visibility = if (value) VISIBLE else GONE
            requestLayout()
        }

    init {
        addView(background, LayoutParams(-1, -1))
        addView(artwork, LayoutParams(-1, -1))
        addView(lyricColumn, LayoutParams(-1, -1))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun updateLyrics(value: EmbeddedLyrics, position: Long) {
        val previousIndex = lyrics.indexAt(positionMs)
        val nextIndex = value.indexAt(position)
        val sameTrack = value === lyrics
        val animate = !skipNextAnimation && sameTrack && nextIndex == previousIndex + 1 &&
            position - positionMs in 0..1000 && split && isShown && rows.isNotEmpty()
        val changed = !sameTrack || previousIndex != nextIndex
        if (changed || skipNextAnimation) resetScroll()
        lyrics = value
        positionMs = position
        skipNextAnimation = false
        refreshText()
        if (animate) {
            // New rows start one line below their final position, preserving their old screen positions.
            transition(0f)
            scrollAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 500
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { transition(it.animatedValue as Float) }
                start()
            }
        }
    }

    fun skipAnimation() {
        skipNextAnimation = true
        resetScroll()
    }

    private fun resetScroll() {
        scrollAnimator?.cancel()
        scrollAnimator = null
        transition(1f)
    }

    private fun transition(progress: Float) {
        val middle = rows.size / 2
        rows.forEachIndexed { index, row ->
            val emphasis = when (index) {
                middle -> progress
                middle - 1 -> 1f - progress
                else -> 0f
            }
            row.textSize = 19f + 6f * emphasis
            val height = textHeight(row) + lineGap
            if (row.layoutParams.height != height) {
                row.layoutParams = row.layoutParams.apply { this.height = height }
            }
            row.alpha = 0.7f + 0.3f * emphasis
            row.setTextColor(colorEvaluator.evaluate(emphasis,
                context.getColor(R.color.player_text_secondary), Color.WHITE) as Int)
        }
        if (rows.isEmpty()) {
            lyricColumn.translationY = 0f
            return
        }
        val heights = rows.map { it.layoutParams.height }
        val centerOffset = heights.take(middle).sum() + heights[middle] / 2f - heights.sum() / 2f
        val step = (heights.getOrElse(middle - 1) { heights[middle] } + heights[middle]) / 2f
        // Variable-height rows stay evenly spaced; interpolate the anchor from the old to the new line.
        lyricColumn.translationY = step * (1f - progress) - centerOffset
    }

    override fun onDetachedFromWindow() {
        resetScroll()
        super.onDetachedFromWindow()
    }

    private fun refreshText() {
        if (rows.isEmpty()) return
        val lines = lyrics.window(positionMs, rows.size / 2)
        rows.forEachIndexed { index, row ->
            val text = lines[index]
            if (row.text.toString() != text) row.text = text
        }
    }

    private val rowSizing by lazy {
        val probe = TextView(context).apply { includeFontPadding = false; textSize = 19f }
        val normalHeight = textHeight(probe) + lineGap
        probe.textSize = 25f
        val extra = textHeight(probe) + lineGap - normalHeight
        normalHeight to extra
    }

    private fun sizeRows(height: Int) {
        val (normalHeight, extra) = rowSizing
        val fitting = ((height - extra) / normalHeight).coerceAtLeast(1)
        val count = if (fitting % 2 == 0) fitting - 1 else fitting
        if (rows.size == count) return
        resetScroll()
        lyricColumn.removeAllViews()
        rows.clear()
        repeat(count) { index ->
            val current = index == count / 2
            val row = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = if (current) 25f else 19f
                setTextColor(if (current) Color.WHITE else context.getColor(R.color.player_text_secondary))
                alpha = if (current) 1f else 0.7f
                includeFontPadding = false
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            }
            rows.add(row)
            lyricColumn.addView(row, LinearLayout.LayoutParams(-1, textHeight(row) + lineGap))
        }
        refreshText()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val areaHeight = if (split) (height - dp(112)).coerceAtLeast(height / 2) else height
        val padding = if (split) dp(20) else 0
        val areaWidth = if (split) width / 2 else width
        sizeRows((areaHeight - padding * 2).coerceAtLeast(1))
        for (index in 0 until childCount) {
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec((areaWidth - padding * 2).coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((areaHeight - padding * 2).coerceAtLeast(1), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val padding = if (split) dp(20) else 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val x = padding + if (index == 2 && split) width / 2 else 0
            child.layout(x, padding, x + child.measuredWidth, padding + child.measuredHeight)
        }
    }
}
