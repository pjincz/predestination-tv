package io.jcz.predestination.exo

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class MusicStageTest {
    @Test fun splitsArtworkAndLyricsThenRestoresFullScreenArtwork() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val record = View(activity)
        val cover = View(activity)
        val stage = MusicStage(activity, record, cover)
        fun layout() {
            stage.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            stage.layout(0, 0, 1280, 720)
        }
        stage.split = true
        stage.updateLyrics(EmbeddedLyrics.parse("[00:01]First\n[00:02]Second\n[00:03]Current\n[00:04]Next\n[00:05]Last"), 3000)
        layout()
        val column = stage.getChildAt(2) as LinearLayout
        assertTrue(cover.right < column.left)
        assertEquals(record.left, cover.left)
        assertEquals(record.bottom, cover.bottom)
        assertTrue(column.childCount > 5)
        val middle = column.getChildAt(column.childCount / 2) as TextView
        assertEquals("Current", middle.text.toString())
        assertTrue(middle.textSize > (column.getChildAt(0) as TextView).textSize)
        assertEquals(column.height / 2f, (middle.top + middle.bottom) / 2f, 1f)
        val step = column.getChildAt(1).top - column.getChildAt(0).top
        assertTrue(middle.height > step)
        fun padding(row: TextView): Float {
            val metrics = row.paint.fontMetrics
            return row.height - (metrics.descent - metrics.ascent)
        }
        assertEquals(padding(column.getChildAt(0) as TextView), padding(middle), 1f)
        stage.updateLyrics(EmbeddedLyrics.parse("[00:01]First\n[00:02]Second"), 1000)
        assertEquals("First", middle.text.toString())
        assertTrue(cover.bottom < stage.height)
        stage.split = false
        layout()
        assertEquals(0, cover.left)
        assertEquals(1280, cover.width)
        assertEquals(720, cover.height)
        assertEquals(View.GONE, column.visibility)
        controller.pause().stop().destroy()
    }

    @Test fun animatesNormalAdvanceButSnapsOnSeekAndTrackChange() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val stage = MusicStage(activity, View(activity), View(activity))
        activity.setContentView(stage, android.view.ViewGroup.LayoutParams(1280, 720))
        stage.split = true
        stage.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        stage.layout(0, 0, 1280, 720)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val lyrics = EmbeddedLyrics.parse("[00:01]First\n[00:02]Second\n[00:03]Third")
        stage.updateLyrics(lyrics, 1900)
        stage.updateLyrics(lyrics, 2000)
        val column = stage.getChildAt(2) as LinearLayout
        assertTrue(column.translationY > 0f)
        val current = column.getChildAt(column.childCount / 2) as TextView
        val previous = column.getChildAt(column.childCount / 2 - 1) as TextView
        val normalSize = current.textSize
        val largeSize = previous.textSize
        assertTrue(normalSize < largeSize)
        assertEquals(android.graphics.Color.WHITE, previous.currentTextColor)
        val animator = org.robolectric.util.ReflectionHelpers
            .getField<android.animation.ValueAnimator>(stage, "scrollAnimator")
        animator.currentPlayTime = 100
        assertTrue("size=${current.textSize}, range=$normalSize..$largeSize", current.textSize > normalSize && current.textSize < largeSize)
        assertTrue(previous.textSize > normalSize && previous.textSize < largeSize)
        fun gap(row: TextView) = row.layoutParams.height -
            (row.paint.fontMetrics.descent - row.paint.fontMetrics.ascent)
        assertEquals(gap(current), gap(previous), 1f)
        assertTrue(current.alpha > 0.7f && current.alpha < 1f)
        assertNotEquals(android.graphics.Color.WHITE, current.currentTextColor)
        assertNotEquals(activity.getColor(R.color.player_text_secondary), current.currentTextColor)
        animator.end()
        assertEquals(0f, column.translationY, 0.1f)
        assertEquals(largeSize, current.textSize, 0.1f)
        assertEquals(normalSize, previous.textSize, 0.1f)
        assertEquals(android.graphics.Color.WHITE, current.currentTextColor)
        assertEquals(activity.getColor(R.color.player_text_secondary), previous.currentTextColor)
        stage.updateLyrics(lyrics, 2900)
        stage.skipAnimation()
        stage.updateLyrics(lyrics, 3000)
        assertEquals(0f, column.translationY, 0f)
        stage.updateLyrics(EmbeddedLyrics.parse("[00:01]New"), 1000)
        assertEquals(0f, column.translationY, 0f)
        controller.pause().stop().destroy()
    }

}
