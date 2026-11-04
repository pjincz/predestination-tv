package io.jcz.predestination.exo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

internal class MusicInfo(context: Context) : LinearLayout(context) {
    private val title = TextView(context).apply {
        textSize = 22f
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val state = TextView(context).apply { textSize = 15f; setTextColor(Color.LTGRAY) }
    private val time = TextView(context).apply { textSize = 15f; setTextColor(Color.LTGRAY) }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(context.getColor(R.color.osd_accent))
    }

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(24))
        for (label in listOf(title, state, time)) {
            label.setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        }
        addView(title)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
            addView(state, LayoutParams(0, -2, 1f))
            addView(time, LayoutParams(-2, -2))
        })
        addView(progress, LayoutParams(-1, dp(4)))
        visibility = GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun update(name: CharSequence, status: CharSequence, position: Double, duration: Double) {
        title.text = name
        state.text = status
        time.text = "${Protocol.time(position)} / ${Protocol.time(duration)}"
        progress.progress = if (duration > 0 && position.isFinite())
            (position / duration * 1000).toInt().coerceIn(0, 1000) else 0
    }
}
