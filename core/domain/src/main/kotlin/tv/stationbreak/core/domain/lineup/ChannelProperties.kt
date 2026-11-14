// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.domain.lineup.builder.RulesDescription
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ordering.fnv1a64
import kotlin.time.Instant

/** What a theme's anchor names. */
enum class ThemeAnchorKind { Collection, Title, Studio }

/** One thing a themed channel is built round, by the name the catalog gives it (not an ID). */
data class ThemeAnchor(
    val kind: ThemeAnchorKind,
    val name: String,
)

/**
 * How a channel is laid out, as a viewer reads it: the time its broadcast day starts ([dayStart], `HH:MM`), the items
 * in a block when it is more than one ([blockLength], else null), and the [scheduleCode] that two TVs compare to see
 * they are on the same lineup.
 */
data class ChannelTiming(
    val dayStart: String,
    val blockLength: Int?,
    val scheduleCode: String,
)

/**
 * The schedule code of [seed]: the first 32 bits (the high ones) of its FNV-1a 64 digest over the seed's canonical
 * decimal text, as `XXXX-XXXX` in upper-case hex. It is the same on every TV that holds the same seed, and a
 * reshuffle (a new seed) changes it. It is a label to compare, never a way back to the seed.
 */
fun scheduleCodeOf(seed: Seed): String {
    val digest = fnv1a64(seed.format().encodeToByteArray())
    val first32 = (digest shr HIGH_WORD_SHIFT).toString(HEX).padStart(HEX_DIGITS, '0')
    return first32.uppercase().chunked(HEX_DIGITS / 2).joinToString("-")
}

private const val HIGH_WORD_SHIFT = 32
private const val HEX = 16
private const val HEX_DIGITS = 8

/**
 * Everything about one channel that a properties screen shows and [ChannelSummary] hides, in the viewer's terms (no
 * wire names, no engine types). Nothing is written by reading it.
 *
 * - [personality]: a starter's one line from the catalog; null for any other channel.
 * - [description]: what the channel plays and in what order ([RulesDescription], the one the builder's edit sheet
 *   words too), with its sources named by the library; null for a leftovers or an unreadable channel.
 * - [anchors]: the theme anchors of a themed starter; empty otherwise.
 * - [timing]: null where there are no rules to read (leftovers, unreadable).
 * - [leftoversCeiling]: a leftovers channel's rating ceiling (a rating-ladder name), null for none or another kind.
 * - [hidden]: why a switched-on channel has nothing to air in its day at the asked-for moment, as the schedule says;
 *   null when it has something, and for a channel that is not on.
 * - [holdings]: what the channel holds today ([ChannelHoldings], the value the guide's channel box shows too); null
 *   for a channel that is off, hidden or has nothing today, and while it could not be read.
 */
data class ChannelProperties(
    val summary: ChannelSummary,
    val personality: String?,
    val description: RulesDescription?,
    val anchors: List<ThemeAnchor>,
    val timing: ChannelTiming?,
    val leftoversCeiling: String?,
    val hidden: HiddenReason?,
    val holdings: ChannelHoldings? = null,
)

/** How features read a channel's properties. Read-only; none of it throws, but for cancellation. */
interface ChannelPropertiesReader {
    /**
     * The properties of channel [id] of the active account's lineup at [at], or null while nobody is signed in, there
     * is no lineup, or the channel is not in it. [at] is the moment for [ChannelProperties.hidden]; the rest is the
     * lineup as it is now.
     */
    suspend fun read(
        id: ChannelId,
        at: Instant,
    ): ChannelProperties?
}
