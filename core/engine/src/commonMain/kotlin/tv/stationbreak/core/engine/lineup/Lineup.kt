// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.ChannelRules

/** Where a lineup channel stands. */
enum class ChannelState {
    /** Switched on: on the guide and in flipping. */
    On,

    /** Known but not switched on: the planner may switch it on by itself, if it has a number and fills. */
    Shelf,

    /** Hidden or removed by the user: the planner never changes it. */
    UserHidden,
}

/** What a lineup channel airs. */
sealed interface ChannelSource {
    /**
     * A catalog recipe resolved into [rules]. While not [edited] it follows catalog updates; once edited it is never
     * touched by them.
     */
    data class Starter(
        val recipeKey: String,
        val edited: Boolean,
        val rules: ChannelRules,
    ) : ChannelSource

    /** A leftovers channel: its pool is the engine's, worked out from the other channels, under [maxRating]. */
    data class Leftovers(
        val recipeKey: String,
        val maxRating: String?,
    ) : ChannelSource

    /** The user's own rules, or a starter whose recipe left the catalog. The planner never changes them. */
    data class Custom(
        val rules: ChannelRules,
    ) : ChannelSource

    /** A stored channel that could not be read. Kept as written, so nothing is lost; only decoding makes one. */
    data class Unusable(
        val rawJson: String,
    ) : ChannelSource
}

/**
 * One channel of a [Lineup]. Its [id] is permanent and separate from its [number], so renumbering never breaks
 * favourites. [numberOverride] and [nameOverride] are the user's and do not detach a starter from the catalog.
 */
data class LineupChannel(
    val id: ChannelId,
    val defaultNumber: Int?,
    val numberOverride: Int?,
    val nameOverride: String?,
    val state: ChannelState,
    val source: ChannelSource,
    val addedAtMs: Long,
) {
    /** The number the channel is shown at, or null while it has none (it waits on the shelf for the user). */
    val number: Int? get() = numberOverride ?: defaultNumber
}

/**
 * One account's channel lineup on one server (spec 12). Every starter and leftovers channel uses the one
 * [utcOffsetMinutes] chosen at first setup. [hintsResolvedWithCollections] records that the collection hints were
 * resolved after a full collection pass.
 *
 * Invariants: channel IDs are unique, numbers that are set are unique, and every switched-on channel has a number.
 */
data class Lineup(
    val serverId: CanonicalId,
    val userId: CanonicalId,
    val catalogVersion: Int,
    val utcOffsetMinutes: Int,
    val finalised: Boolean,
    val hintsResolvedWithCollections: Boolean,
    val channels: List<LineupChannel>,
) {
    init {
        require(channels.map { it.id }.toSet().size == channels.size) { "Channel IDs are unique" }
        val numbers = channels.mapNotNull { it.number }
        require(numbers.toSet().size == numbers.size) { "Channel numbers are unique" }
        val unnumbered = channels.any { it.state == ChannelState.On && it.number == null }
        require(!unnumbered) { "A switched-on channel has a number" }
    }
}

/** The permanent IDs of catalog channels. A starter keeps its ID when it becomes a custom channel. */
object LineupIds {
    /** `starter:<key>`. */
    fun starter(recipeKey: String): ChannelId = ChannelId("starter:$recipeKey")

    /** `leftovers:<key>`. */
    fun leftovers(recipeKey: String): ChannelId = ChannelId("leftovers:$recipeKey")
}
