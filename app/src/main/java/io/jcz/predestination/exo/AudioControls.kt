package io.jcz.predestination.exo

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

internal fun audioControls(
    context: Context,
    audio: AudioGain,
    totalGain: (Int) -> Unit
): AlertDialog {
    val rows = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24, 12, 24, 12)
    }
    val sliders = mutableListOf<SeekBar>()
    val resetters = mutableListOf<() -> Unit>()
    fun slider(label: String, min: Int, max: Int, value: Int, change: (Int) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
        }
        val title = TextView(context).apply { textSize = 18f }
        fun display(db: Int) { title.text = "$label：${if (db > 0) "+" else ""}$db dB" }
        val seek = SeekBar(context).apply {
            this.max = (max - min) / 3
            progress = (value - min) / 3
            keyProgressIncrement = 1
            isFocusableInTouchMode = true
            minimumHeight = 48
            setOnFocusChangeListener { _, focused ->
                row.setBackgroundColor(if (focused) 0xff35536b.toInt() else Color.TRANSPARENT)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val db = min + progress * 3
                    display(db)
                    if (fromUser) change(db)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        progress = (progress + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                            .coerceIn(0, this.max)
                        change(min + progress * 3)
                    }
                    true
                } else if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    val next = sliders.indexOf(this) + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (next in sliders.indices) {
                        if (event.action == KeyEvent.ACTION_DOWN) sliders[next].requestFocus()
                        true
                    } else false
                } else false
            }
        }
        display(value)
        sliders.add(seek)
        resetters.add { seek.progress = -min / 3; display(0) }
        row.addView(title)
        row.addView(seek, LinearLayout.LayoutParams(-1, -2))
        rows.addView(row)
    }
    slider("总增益", 0, 18, audio.decibels, totalGain)
    for (group in channelGroups(audio.channelCount)) {
        slider(group.label, -30, 12, audio.channelLevel(group.indices.first())) {
            audio.setChannels(group.indices, it)
        }
    }
    val night = CheckBox(context).apply {
        text = "动态压缩"
        isChecked = audio.nightMode
        setOnCheckedChangeListener { _, checked ->
            audio.nightMode = checked
            totalGain(audio.decibels)
        }
    }
    rows.addView(night)
    rows.addView(TextView(context).apply {
        text = "上下选择，左右每档 3 dB。成对声道联动；保留原声道，由电视混音。新视频恢复默认。"
    })
    val dialog = AlertDialog.Builder(context)
        .setTitle("音频设置 · ${audio.channelCount} 声道")
        .setView(ScrollView(context).apply { addView(rows) })
        .setPositiveButton("完成", null)
        .setNeutralButton("全部重置", null)
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            audio.resetChannels()
            audio.nightMode = false
            night.isChecked = false
            totalGain(0)
            resetters.forEach { it() }
        }
        sliders.first().requestFocus()
    }
    return dialog
}
