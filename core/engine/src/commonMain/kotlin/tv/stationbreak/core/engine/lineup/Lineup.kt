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
     * touched by them. A change of [rules] by the planner starts at a day boundary: [previousRules] are the rules
     * before it, kept for the days that start before [rulesFromMs] (both set, or both null). Use [withPrevious],
     * [withoutPrevious] and [mapRules], and `withNewRules` for a change, never a copy of one field.
     */
    data class Starter(
        val recipeKey: String,
        val edited: Boolean,
        val rules: ChannelRules,
        val previousRules: ChannelRules? = null,
        val rulesFromMs: Long? = null,
    ) : ChannelSource {
        init {
            require((previousRules == null) == (rulesFromMs == null)) { "Previous rules go with their boundary" }
        }

        /** This starter with [rules] kept for the days before [fromMs]: the pair is always set together. */
        fun withPrevious(
            rules: ChannelRules,
            fromMs: Long,
        ): Starter = copy(previousRules = rules, rulesFromMs = fromMs)

        /** This starter with [rules] at once and no previous rules: for a starter nothing of which airs today. */
        fun withRulesNow(rules: ChannelRules): Starter = copy(rules = rules, previousRules = null, rulesFromMs = null)

        /** This starter with no previous rules and no boundary. */
        fun withoutPrevious(): Starter = copy(previousRules = null, rulesFromMs = null)

        /** This starter with [transform] applied to its rules and to its previous rules, so the two stay in step. */
        fun mapRules(transform: (ChannelRules) -> ChannelRules): Starter =
            copy(rules = transform(rules), previousRules = previousRules?.let(transform))
    }

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

/** Why an untouched starter is on the shelf (set by the planner). */
enum class ShelfReason {
    /** Nothing in the libraries fits the channel. */
    Empty,

    /** Too little fits once every show has its best home. */
    TooThin,

    /** It fits, but stronger channels filled the lineup. */
    BelowCap,

    /** It is opt-in: only on if the viewer switches it on. */
    OptIn,
}

/**
 * What the curation looked at on the days before [fromMs]: the channels [on] (the curated on-set) and the
 * [household] flag then in force.
 */
data class CurationBasis(
    val fromMs: Long,
    val on: Set<ChannelId>,
    val household: Boolean,
)

/**
 * One channel of a [Lineup]. Its [id] is permanent and separate from its [number], so renumbering never breaks
 * favourites. [numberOverride] and [nameOverride] are the user's and do not detach a starter from the catalog.
 * [onSinceMs] is when the channel was last switched on (for a channel that was never on, when it joined the lineup).
 * [chosenByViewer] is true once the viewer switched the channel on or off or gave it a number. [leavesAtMs] is when an
 * untouched starter the planner dropped goes to the shelf, and [shelfReason] why it is there (starters only).
 */
data class LineupChannel(
    val id: ChannelId,
    val defaultNumber: Int?,
    val numberOverride: Int?,
    val nameOverride: String?,
    val state: ChannelState,
    val source: ChannelSource,
    val onSinceMs: Long,
    val chosenByViewer: Boolean = false,
    val leavesAtMs: Long? = null,
    val shelfReason: ShelfReason? = null,
) {
    init {
        require(source is ChannelSource.Starter || (leavesAtMs == null && shelfReason == null)) {
            "Only a starter leaves the lineup or sits on the shelf for a reason"
        }
    }

    /** The number the channel is shown at, or null while it has none (it waits on the shelf for the user). */
    val number: Int? get() = numberOverride ?: defaultNumber
}

/**
 * One account's channel lineup on one server (spec 12). Every starter and leftovers channel uses the one
 * [utcOffsetMinutes] chosen at first setup.
 *
 * [useMyCollections] is the "Use my collections" flag. [curationBasis] keeps the old on-set and flag for days before
 * its boundary. [viewerChoicesKnown] is false for a lineup read from a file before version 3, whose
 * [LineupChannel.chosenByViewer] is only inferred.
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
    val useMyCollections: Boolean = true,
    val curationBasis: CurationBasis? = null,
    val viewerChoicesKnown: Boolean = true,
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
            useMyCollections: Boolean = true,
            curationBasis: CurationBasis? = null,
            viewerChoicesKnown: Boolean = true,
        ): Lineup? {
            val valid = uniqueIds(channels) && uniqueNumbers(channels) && onAreNumbered(channels)
            return if (valid) {
                Lineup(
                    serverId,
                    userId,
                    catalogVersion,
                    utcOffsetMinutes,
                    finalised,
                    channels,
                    useMyCollections,
                    curationBasis,
                    viewerChoicesKnown,
                )
            } else {
                null
            }
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
