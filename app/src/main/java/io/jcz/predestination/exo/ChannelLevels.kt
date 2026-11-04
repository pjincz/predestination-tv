package io.jcz.predestination.exo

internal data class ChannelGroup(val label: Int, val indices: List<Int>)

internal fun channelGroups(count: Int): List<ChannelGroup> {
    fun group(name: Int, vararg indices: Int) = ChannelGroup(name, indices.toList())
    // These are Android's standard positional PCM layouts, as used by the audio sink.
    return when (count) {
        1 -> listOf(group(R.string.channel_mono, 0))
        2 -> listOf(group(R.string.channel_front, 0, 1))
        3 -> listOf(group(R.string.channel_center, 2), group(R.string.channel_front, 0, 1))
        4 -> listOf(group(R.string.channel_front, 0, 1), group(R.string.channel_back, 2, 3))
        5 -> listOf(group(R.string.channel_center, 2), group(R.string.channel_front, 0, 1), group(R.string.channel_surround, 3, 4))
        6 -> listOf(group(R.string.channel_center, 2), group(R.string.channel_front, 0, 1),
            group(R.string.channel_surround, 4, 5), group(R.string.channel_lfe, 3))
        7 -> listOf(group(R.string.channel_center, 2), group(R.string.channel_front, 0, 1),
            group(R.string.channel_back_lr, 4, 5), group(R.string.channel_back_center, 6), group(R.string.channel_lfe, 3))
        8 -> listOf(group(R.string.channel_center, 2), group(R.string.channel_front, 0, 1),
            group(R.string.channel_side, 6, 7), group(R.string.channel_back, 4, 5), group(R.string.channel_lfe, 3))
        else -> (0 until count).map { group(R.string.channel_number, it) }
    }
}
