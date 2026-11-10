// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.ui.mark.markFallbackInitialsOf
import tv.stationbreak.core.ui.mark.markInitialsOf

/** The tile families of the ChannelMarks spec. [Custom] (600 and up) is not in the spec yet; it reads like General. */
internal enum class MarkBand { Kids, General, Drama, Genre, World, Premium, Custom }

private const val NUMBERS_PER_BAND = 100
private val NumberedBands = MarkBand.entries - MarkBand.Custom

/** The band a channel number falls in: a band per hundred from Kids (1 to 99) to Premium (500 to 599), then Custom. */
internal fun markBandOf(number: Int): MarkBand =
    if (number < 1) MarkBand.Kids else NumberedBands.getOrElse(number / NUMBERS_PER_BAND) { MarkBand.Custom }

/** What the initials rules need to know about a channel. */
internal data class MarkChannel(
    val id: ChannelId,
    val number: Int,
    val name: String,
)

/**
 * Two-character initials for each channel (ChannelMarks "Initials rules"). Each band is processed in number order. Two
 * or more significant words give the first letters of the first and the last; one word gives its first two letters. A
 * channel whose initials are already taken in its band falls back to its first word alone; if that is taken too it is
 * kept as it is. Never throws; an empty name gives "?"; the result is never more than two code points.
 */
internal fun assignInitials(channels: List<MarkChannel>): Map<ChannelId, String> {
    val result = HashMap<ChannelId, String>(channels.size)
    channels
        .groupBy { markBandOf(it.number) }
        .values
        .forEach { band ->
            val taken = HashSet<String>()
            for (channel in band.sortedWith(compareBy<MarkChannel> { it.number }.thenBy { it.name })) {
                var initials = markInitialsOf(channel.name)
                if (initials in taken) initials = markFallbackInitialsOf(channel.name) ?: initials
                taken += initials
                result[channel.id] = initials
            }
        }
    return result
}
