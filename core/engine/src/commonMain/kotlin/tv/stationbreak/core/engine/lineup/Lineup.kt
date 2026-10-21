// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.RatingLadder

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

    /**
     * A leftovers channel: its pool is the engine's, worked out from the other channels, under [maxRating]. The
     * ceiling is a rating-ladder name or null. While [recipeKey] is in the catalog the planner takes the ceiling from
     * the catalog on every call; the stored one counts only once the key has left it.
     */
    data class Leftovers(
        val recipeKey: String,
        val maxRating: String?,
    ) : ChannelSource {
        init {
            require(maxRating == null || maxRating in RatingLadder.names) { "The rating ceiling is not on the ladder" }
        }
    }

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
 * [onSinceMs] is when the channel was last switched on (for a channel that was never on, when it joined the lineup).
 */
data class LineupChannel(
    val id: ChannelId,
    val defaultNumber: Int?,
    val numberOverride: Int?,
    val nameOverride: String?,
    val state: ChannelState,
    val source: ChannelSource,
    val onSinceMs: Long,
) {
    /** The number the channel is shown at, or null while it has none (it waits on the shelf for the user). */
    val number: Int? get() = numberOverride ?: defaultNumber
}

/**
 * One account's channel lineup on one server (spec 12). Every starter and leftovers channel uses the one
 * [utcOffsetMinutes] chosen at first setup.
 *
 * Invariants: channel IDs are unique, numbers that are set are unique, and every switched-on channel has a number.
 */
data class Lineup(
    val serverId: CanonicalId,
    val userId: CanonicalId,
    val catalogVersion: Int,
    val utcOffsetMinutes: Int,
    val finalised: Boolean,
    val channels: List<LineupChannel>,
) {
    init {
        require(uniqueIds(channels)) { "Channel IDs are unique" }
        require(uniqueNumbers(channels)) { "Channel numbers are unique" }
        require(onAreNumbered(channels)) { "A switched-on channel has a number" }
    }

    companion object {
        /**
         * The lineup, or null when [channels] break an invariant (duplicate IDs, duplicate numbers, or a switched-on
         * channel without a number), for callers such as decoding that must not throw.
         */
        @Suppress("LongParameterList")
        fun ofOrNull(
            serverId: CanonicalId,
            userId: CanonicalId,
            catalogVersion: Int,
            utcOffsetMinutes: Int,
            finalised: Boolean,
            channels: List<LineupChannel>,
        ): Lineup? {
            val valid = uniqueIds(channels) && uniqueNumbers(channels) && onAreNumbered(channels)
            return if (valid) Lineup(serverId, userId, catalogVersion, utcOffsetMinutes, finalised, channels) else null
        }
    }
}

private fun uniqueIds(channels: List<LineupChannel>): Boolean = channels.map { it.id }.toSet().size == channels.size

private fun uniqueNumbers(channels: List<LineupChannel>): Boolean {
    val numbers = channels.mapNotNull { it.number }
    return numbers.toSet().size == numbers.size
}

private fun onAreNumbered(channels: List<LineupChannel>): Boolean {
    val unnumbered = channels.any { it.state == ChannelState.On && it.number == null }
    return !unnumbered
}

/** The permanent IDs of catalog channels. A starter keeps its ID when it becomes a custom channel. */
object LineupIds {
    /** `starter:<key>`. */
    fun starter(recipeKey: String): ChannelId = ChannelId("starter:$recipeKey")

    /** `leftovers:<key>`. */
    fun leftovers(recipeKey: String): ChannelId = ChannelId("leftovers:$recipeKey")
}
