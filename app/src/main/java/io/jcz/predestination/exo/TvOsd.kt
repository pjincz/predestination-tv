package io.jcz.predestination.exo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.*

internal class TvRemoteFrame(context: Context, private val navigate: (KeyEvent) -> Boolean) : FrameLayout(context) {
    private val taps = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: android.view.MotionEvent) = true

        override fun onSingleTapUp(event: android.view.MotionEvent): Boolean {
            performClick()
            return true
        }
    })
    init {
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = navigate(event) || super.dispatchKeyEvent(event)

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        taps.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Remote navigation is handled before focused Buttons/SeekBar consume key events. */
internal class TvOsd(context: Context) : LinearLayout(context) {
    val title = TextView(context).apply {
        textSize = 22f
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val source = TextView(context).apply {
        textSize = 14f
        setTextColor(context.getColor(R.color.player_text_secondary))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val playbackState = TextView(context).apply { textSize = 15f; setTextColor(context.getColor(R.color.player_text_secondary)) }
    val timeline = SeekBar(context).apply {
        max = 1000
        isFocusable = true
        isFocusableInTouchMode = true
        minimumHeight = 0
        minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            minHeight = dp(3)
            maxHeight = dp(3)
        }
        progressTintList = ColorStateList.valueOf(context.getColor(R.color.osd_accent))
        thumbTintList = progressTintList
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(dp(10), dp(10))
            setColor(context.getColor(R.color.osd_accent))
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(context.getColor(R.color.osd_focus_hint))
            })
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }
    private val clock = TextView(context).apply {
        textSize = 15f
        setTextColor(context.getColor(R.color.player_text_secondary))
        gravity = Gravity.END
    }
    val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
    var toggle: () -> Unit = {}
    var seek: (Int) -> Unit = {}
    var seekFraction: (Double) -> Unit = {}
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { hide() }
    private var lastButton = 0
    private var dragging = false
    var visibilityChanged: () -> Unit = {}

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundColor(context.getColor(R.color.osd_panel))
        addView(title)
        addView(source)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), 0)
            addView(playbackState, LayoutParams(0, -2, 1f))
            addView(clock, LayoutParams(-2, -2))
        })
        addView(timeline, LayoutParams(-1, dp(24)))
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(buttons)
        })
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { }

            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true; touch() }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                seekFraction(bar.progress / 1000.0)
                touch()
            }
        })
        visibility = GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun addButton(label: String, action: () -> Unit): Button {
        val index = buttons.childCount
        buttons.addView(Button(context).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = false
            setPadding(dp(18), dp(8), dp(18), dp(8))
            backgroundTintList = null
            setBackgroundResource(R.drawable.osd_focus)
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(context.getColor(R.color.osd_focus_text), Color.WHITE)))
            setOnFocusChangeListener { _, focused -> if (focused) lastButton = index }
            setOnClickListener { action(); touch() }
        }, LayoutParams(-2, dp(48)).apply { setMargins(dp(4), dp(10), dp(4), 0) })
        return buttons.getChildAt(index) as Button
    }

    fun addButton(label: Int, action: () -> Unit): Button =
        addButton(context.tr(label), action).apply { tag = label }

    fun refreshLanguage() {
        for (index in 0 until buttons.childCount) {
            val button = buttons.getChildAt(index) as Button
            (button.tag as? Int)?.let { button.text = context.tr(it) }
        }
    }

    fun show(seekFocus: Boolean = false) {
        val wasHidden = visibility != VISIBLE
        visibility = VISIBLE
        if (wasHidden) visibilityChanged()
        if (seekFocus) timeline.requestFocus()
        else if (wasHidden || !hasFocus()) {
            val button = buttons.getChildAt(lastButton)
            if (button?.visibility == VISIBLE) button.requestFocus()
            else buttons.getChildAt(0)?.requestFocus()
        }
        touch()
    }

    fun hide() {
        val wasVisible = visibility == VISIBLE
        handler.removeCallbacks(timeout)
        clearFocus()
        visibility = GONE
        if (wasVisible) visibilityChanged()
    }

    private fun touch() {
        handler.removeCallbacks(timeout)
        if (visibility == VISIBLE) handler.postDelayed(timeout, 10000)
    }

    fun updateProgress(position: Double, duration: Double, seekable: Boolean) {
        if (!dragging) timeline.progress = if (duration > 0 && position.isFinite())
            (position / duration * 1000).toInt().coerceIn(0, 1000) else 0
        clock.text = "${Protocol.time(position)} / ${Protocol.time(duration)}"
        timeline.contentDescription = if (seekable) context.tr(R.string.playback_progress) else context.tr(R.string.not_seekable)
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.keyCode !in NAVIGATION) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val key = event.keyCode
        val confirm = key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        if (confirm && event.repeatCount > 0) return true
        if (visibility != VISIBLE) {
            if (confirm) lastButton = 0
            show(key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)
            return true
        }
        touch()
        when (key) {
            KeyEvent.KEYCODE_MENU -> show()
            KeyEvent.KEYCODE_DPAD_UP -> timeline.requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> buttons.getChildAt(lastButton)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val direction = if (key == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                if (timeline.hasFocus()) seek(direction * 10)
                else {
                    lastButton = (lastButton + direction).coerceIn(0, (buttons.childCount - 1).coerceAtLeast(0))
                    buttons.getChildAt(lastButton)?.requestFocus()
                }
            }
            else -> if (confirm) {
                if (timeline.hasFocus()) toggle() else buttons.getChildAt(lastButton)?.performClick()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(timeout)
        super.onDetachedFromWindow()
    }

    companion object {
        fun displayTitle(rawTitle: String?, mediaUrl: String, castTitle: String? = null, fallback: String = "Cast media"): String {
            if (!castTitle.isNullOrBlank()) {
                val clean = displayTitle(castTitle, mediaUrl, fallback = fallback)
                if (clean != displaySource(mediaUrl) && clean != fallback) return clean
            }
            val title = rawTitle.orEmpty().trim()
            val source = displaySource(mediaUrl)
            if (title.isEmpty()) return source.ifEmpty { fallback }
            // Do not display signed URL query parameters in the title.
            val urlLike = title.startsWith("http://", true) || title.startsWith("https://", true) ||
                title.startsWith("rtsp://", true) || title == mediaUrl ||
                Regex("[?&][A-Za-z0-9_%.-]+=").containsMatchIn(title)
            return if (urlLike) source.ifEmpty { title.substringBefore('?').substringBefore('#') }
                .ifEmpty { fallback } else title
        }

        fun displaySource(value: String): String = runCatching {
            val uri = java.net.URI(value)
            if (uri.host.isNullOrBlank() || uri.scheme !in listOf("http", "https")) return@runCatching ""
            java.net.URI(uri.scheme, null, uri.host, uri.port, null, null, null).toASCIIString()
        }.getOrDefault("")

        private val NAVIGATION = setOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    }
}
