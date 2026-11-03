package io.jcz.predestination.exo

import android.content.Context

internal class BufferSettings(context: Context) {
    private val preferences = context.getSharedPreferences("buffer", Context.MODE_PRIVATE)

    data class Limits(val memoryMiB: Int, val seconds: Int)

    fun read(): Limits {
        val values = preferences.all
        return Limits(values["memoryMiB"] as? Int ?: 64, values["seconds"] as? Int ?: 60)
    }

    fun save(limits: Limits): Boolean =
        preferences.edit().putInt("memoryMiB", limits.memoryMiB)
            .putInt("seconds", limits.seconds).commit()

    companion object {
        val memoryRange = 16..256
        val durationRange = 5..300
    }
}
