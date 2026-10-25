// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live

import tv.stationbreak.core.domain.lineup.ChannelAiring
import kotlin.time.Instant

/**
 * The lineup as the lineup list alone knows it, before any day is built: the channels that are switched on, in number
 * order ([order]), and the channel [anchor] the banner counts from.
 */
internal class EarlyLineup(
    val order: FlipOrder,
    val anchor: ChannelAiring?,
)

/** One Up or Down press and when it was made. */
internal typealias Press = Pair<FlipDirection, Instant>

/**
 * Presses made before the first sitting has a channel (a cold launch, while the day is still being built). They are
 * kept, never dropped, and the banner follows them as soon as the lineup list is loaded; when the first sitting lands,
 * the [Flipper] applies the whole run as one flip from the channel the sitting is on.
 *
 * The early anchor rule: the banner counts from the channel saved as last watched, when it is in the lineup list, else
 * from the lowest-numbered channel on. The flip itself always counts from the channel the first sitting actually lands
 * on, so the two agree whenever the saved channel is on the air, and the landing wins when it is not.
 *
 * Accepted limit (#299): the lineup list does not know which channels are empty today, so the early banner may step
 * onto one that the built schedule hides. The commit on the built schedule still skips hidden and empty channels, as
 * every flip does, so the viewer can see the number change once more when the flip lands.
 */
internal class EarlyPresses {
    private val presses = ArrayList<Press>()

    /** The lineup list, once it has been read (null before, or when it could not be). */
    var lineup: EarlyLineup? = null

    val isEmpty: Boolean get() = presses.isEmpty()

    fun add(
        direction: FlipDirection,
        at: Instant,
    ) {
        presses += direction to at
    }

    /** The channel the banner shows: the anchor stepped by every press so far, or null with no lineup. */
    fun target(): ChannelAiring? {
        val known = lineup ?: return null
        return presses.fold(known.anchor) { at, (direction, _) -> at?.let { known.order.step(it.number, direction) } }
    }

    /** Hands over all presses made so far, in order, and forgets them. */
    fun take(): List<Press> = presses.toList().also { presses.clear() }
}
