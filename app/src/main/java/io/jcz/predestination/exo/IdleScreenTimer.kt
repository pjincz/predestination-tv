package io.jcz.predestination.exo

/** One-shot until reset; polling and discovery requests must not restart the idle interval. */
internal class IdleScreenTimer(private val delayMillis: Long = 30000) {
    private var since: Long? = null
    private var fired = false

    fun reset() { since = null; fired = false }

    fun countdownSeconds(now: Long): Int? {
        val start = since ?: return null
        val remaining = delayMillis - (now - start)
        if (fired || remaining <= 0) return null
        return ((remaining + 999) / 1000).toInt()
    }

    fun update(now: Long, eligible: Boolean): Boolean {
        if (!eligible) { reset(); return false }
        if (fired) return false
        val start = since ?: now.also { since = it }
        if (now - start < delayMillis) return false
        fired = true
        return true
    }
}
