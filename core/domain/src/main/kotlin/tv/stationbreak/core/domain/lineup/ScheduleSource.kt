// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import kotlin.time.Instant

/**
 * Why a switched-on channel has nothing to air in its broadcast day, so it is hidden from the guide and flipping and
 * shown with this reason in channel management (#299). It comes back by itself once the day has something.
 *
 * The entries are in **precedence order**: when more than one could explain an empty day, the first one that applies
 * is given.
 *
 * It is optional for a source that is not built in (for example a later XMLTV one): such a source leaves
 * [ChannelAiring.hidden] null, or uses only the reasons that make sense for it. The built-in source works the reason
 * out from the engine's pool counts, but those are its own detail: nothing in this type needs them, so a source with no
 * pool counts loses nothing here.
 */
enum class HiddenReason {
    /**
     * A collection the channel reads (as a source or an exclusion) is not known yet, and no full collection pass has
     * run, so the emptiness may only mean the collections have not been listed yet.
     */
    CollectionsNotSynced,

    /** Nothing in the library matches the channel's sources. For a leftovers channel: nothing is left over. */
    NoMatches,

    /** Everything that matched was removed by the channel's exclusions. */
    AllExcluded,

    /** Everything that matched and was not excluded is above the channel's rating ceiling, or has no rating. */
    AllBlockedByCeiling,

    /** The channel has content, but none for this day (all too new, gone before it, or too long or short to air). */
    EmptyToday,
}

/**
 * A switched-on channel in its broadcast day at a moment: the day that holds that moment starts at [epochStart].
 * [hidden] is null when the day has something to air, otherwise why it is hidden (#299).
 */
data class ChannelAiring(
    val id: ChannelId,
    val number: Int,
    val name: String,
    val epochStart: Instant,
    val hidden: HiddenReason?,
)

/**
 * Where the guide and flipping learn which channels air (spec 12). A source works out for itself whose lineup and which
 * libraries it is asked about: callers give only the moment. F1.3 (spec 14) extends it with [onAir], what is on one
 * channel and the blocks that follow, so the live screen can tune at the schedule's offset and name the show. The
 * built-in source's days (`ChannelDay`) are internal to it and are not part of the port.
 *
 * Implementations are main-safe: they never block the caller's thread (building a day is real work).
 */
interface ScheduleSource {
    /**
     * What is on [channel] at [at], with the [next] blocks that follow it ([next] of them: when the broadcast day
     * runs out they continue into the following days, each built from its own library snapshot). Null while nobody is
     * signed in or no libraries are chosen, and when the channel is not switched on or is hidden at [at] (see
     * [ChannelAiring.hidden]).
     */
    suspend fun onAir(
        channel: ChannelId,
        at: Instant,
        next: Int,
    ): ChannelOnAir?

    /**
     * The stand-ins for [block] of [channel], a block that cannot play: items of the channel's own pool for the
     * broadcast day that holds the block, laid end to end from the block's start (the last clipped to its end), each an
     * [Airing.StandIn] from the start of its media. It never includes an id in [exclude] or an item gone from the
     * library, and is empty when the pool has nothing else to give, or the channel is not on the air. Join it at the
     * clock with [AiringBlock.positionAt].
     *
     * [block] must be the original scheduled block exactly as [onAir] returned it, so its airing is an
     * [Airing.Program] (anything else, a stand-in included, throws [IllegalArgumentException]). Asking again for the
     * same dead block, because a stand-in failed too, passes the same [block] with a larger [exclude]; a stand-in's
     * own block is never passed back in.
     *
     * The guarantee is this much and no more (ADR-0017): the same channel settings, broadcast day, scheduled block
     * start, library contents and [exclude] give the same stand-ins. [exclude] and which pool items still have a
     * current row are device-local and the stand-ins are laid end to end, so TVs whose libraries or failures differ can
     * differ for that block.
     */
    suspend fun standIns(
        channel: ChannelId,
        block: AiringBlock,
        exclude: Set<CanonicalId>,
    ): List<AiringBlock>

    /**
     * Every switched-on channel of the active account's lineup that can be scheduled, in number order, as it is at
     * [at]. Empty while nobody is signed in, no libraries are chosen, or there is no lineup.
     */
    suspend fun airings(at: Instant): List<ChannelAiring>
}
