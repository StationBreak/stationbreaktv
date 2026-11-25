// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.engine.lineup.Band

/**
 * The cap a viewer gives a custom channel (#1152): how young its audience is. The tier IS the number band, so nothing
 * is stored but the number: [Tots] is [Band.Kids] (1 to 33), [Kids] is [Band.Family] (34 to 66), [Teens] is
 * [Band.Teen] (67 to 99), the same ranges and names as [NumberBand]. A channel with no tier is a grown-up one. Family
 * Mode reads the number alone ([tv.stationbreak.core.domain.kids.KidsLockState.allows]), so a channel's tier and the
 * mode's cap agree by construction.
 */
enum class FamilyTier(
    internal val band: Band,
) {
    Tots(Band.Kids),
    Kids(Band.Family),
    Teens(Band.Teen),
    ;

    /** The numbers of the tier. */
    val numbers: IntRange get() = band.numbers

    /** True when the channel is held to the kids hold's own ceilings (G, TV-Y7, PG); Teens use the rating ladder. */
    val keepsKidCeilings: Boolean get() = this != Teens

    companion object {
        /** The tier whose band [number] is in, or null for a grown-up number or none. */
        fun of(number: Int?): FamilyTier? = number?.let { n -> entries.firstOrNull { n in it.numbers } }

        /** The tier of the young engine [band]. */
        internal fun ofBand(band: Band): FamilyTier = entries.first { it.band == band }
    }
}
