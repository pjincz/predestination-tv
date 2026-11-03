package io.jcz.predestination.exo

import android.util.Log
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object AppLog {
    private val lines = ArrayDeque<String>()
    private val timestamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private var characters = 0

    @Synchronized
    fun record(tag: String, message: String) {
        val line = "${LocalDateTime.now().format(timestamp)} $tag ${message.take(1024)}"
        lines.addLast(line)
        characters += line.length + 1
        while (characters > 64 * 1024) characters -= lines.removeFirst().length + 1
        Log.i(tag, message)
    }

    @Synchronized
    fun read(): String = lines.joinToString("\n")
}
