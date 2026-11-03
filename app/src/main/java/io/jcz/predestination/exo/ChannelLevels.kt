package io.jcz.predestination.exo

internal data class ChannelGroup(val label: String, val indices: List<Int>)

internal fun channelGroups(count: Int): List<ChannelGroup> {
    fun group(name: String, vararg indices: Int) = ChannelGroup(name, indices.toList())
    // These are Android's standard positional PCM layouts, as used by the audio sink.
    return when (count) {
        1 -> listOf(group("单声道", 0))
        2 -> listOf(group("前左 / 右", 0, 1))
        3 -> listOf(group("中置", 2), group("前左 / 右", 0, 1))
        4 -> listOf(group("前左 / 右", 0, 1), group("后环绕", 2, 3))
        5 -> listOf(group("中置", 2), group("前左 / 右", 0, 1), group("环绕", 3, 4))
        6 -> listOf(group("中置", 2), group("前左 / 右", 0, 1),
            group("环绕", 4, 5), group("低频 LFE", 3))
        7 -> listOf(group("中置", 2), group("前左 / 右", 0, 1),
            group("后左 / 右", 4, 5), group("后中置", 6), group("低频 LFE", 3))
        8 -> listOf(group("中置", 2), group("前左 / 右", 0, 1),
            group("侧环绕", 6, 7), group("后环绕", 4, 5), group("低频 LFE", 3))
        else -> (0 until count).map { group("声道 ${it + 1}", it) }
    }
}
