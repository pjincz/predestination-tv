package io.jcz.predestination.exo

internal class BackExit {
    private var previous: Long? = null

    fun onBack(topLevel: Boolean, now: Long): Boolean {
        val elapsed = previous?.let { now - it }
        previous = now
        return topLevel && elapsed != null && elapsed in 0L..500L
    }

    fun reset() {
        previous = null
    }
}
