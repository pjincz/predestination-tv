package io.jcz.predestination.exo

import android.view.KeyEvent

/** Consume the whole wake gesture before focused views or Activity callbacks see it. */
internal class WakeKeyFilter {
    private var gesture: Triple<Int, Int, Long>? = null

    fun consume(event: KeyEvent, sleeping: Boolean): Boolean {
        val current = Triple(event.deviceId, event.keyCode, event.downTime)
        if (!sleeping && gesture != current) return false
        gesture = if (event.action == KeyEvent.ACTION_UP) null else current
        return true
    }
}
