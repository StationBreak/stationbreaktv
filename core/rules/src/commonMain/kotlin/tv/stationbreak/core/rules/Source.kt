// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.model.CanonicalId

/**
 * One source or exclusion, or a filler source (docs/rules-format.md, "Sources"). Every kind keeps the fields it does
 * not know in [extras] and writes them back unchanged.
 */
sealed interface Source {
    /** Fields this version does not know, kept as read. */
    val extras: JsonObject

    /** Every item in the library with this ID. */
    data class Library(
        val id: CanonicalId,
        override val extras: JsonObject = NO_EXTRAS,
    ) : Source

    /** Every item with a genre of this name. */
    data class Genre(
        val name: String,
        override val extras: JsonObject = NO_EXTRAS,
    ) : Source

    /** Every item in the collection with this ID. */
    data class Collection(
        val id: CanonicalId,
        override val extras: JsonObject = NO_EXTRAS,
    ) : Source

    /** Every item with a tag of this name. */
    data class Tag(
        val name: String,
        override val extras: JsonObject = NO_EXTRAS,
    ) : Source

    /** Every item whose production year is from [from] to [to], both included. */
    data class YearRange(
        val from: Int,
        val to: Int,
        override val extras: JsonObject = NO_EXTRAS,
    ) : Source

    /** Every episode of the series with this ID. */
    data class Series(
        val id: CanonicalId,
        override val extras: JsonObject = NO_EXTRAS,
    ) : Source
}

/** No unknown fields. */
internal val NO_EXTRAS: JsonObject = JsonObject(emptyMap())
