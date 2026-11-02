// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject

/** The fixed names and ranges of format versions 1 and 2, shared by the reader and by the checks on the types. */
internal object RulesVocabulary {
    /** The names a `maxRating` may take: the rating ladder. */
    val RATINGS: Set<String> get() = RatingLadder.names
    val EPOCH_HOURS = setOf(1, 2, 3, 4, 6, 8, 12, 24)
    val START_TIME = Regex("^([01][0-9]|2[0-3]):(00|30)$")
    val TOP_LEVEL_V1 =
        setOf("formatVersion", "name", "seed", "sources", "exclusions", "maxRating", "ordering", "timing", "filler")
    val TOP_LEVEL_V2 = TOP_LEVEL_V1 + "filters"
    val ORDERING_V1 = setOf("mode")
    val ORDERING_V2 = ORDERING_V1 + "caps"
    val CAPS = setOf("series", "film")
    val FILTERS = setOf("genres", "years", "runtime", "kind")
    val FILTER_YEARS = setOf("from", "to")
    val FILTER_RUNTIME = setOf("minMinutes", "maxMinutes")
    val GRID_MINUTES = setOf(15, 30, 60)
    val TIMING =
        setOf(
            "blockLength",
            "alignToGrid",
            "epochHours",
            "graceHours",
            "gridMinutes",
            "minRuntimeSeconds",
            "broadcastDay",
        )
    val BROADCAST_DAY = setOf("utcOffsetMinutes", "start")
    val FILLER = setOf("mode", "every", "source")

    const val MAX_BLOCK_LENGTH = 100
    const val MIN_CAP = 1
    const val MAX_CAP = 10
    const val MAX_RUNTIME_MINUTES = 1440
    const val CAPS_EPOCH_HOURS = 24
    const val MAX_GRACE_HOURS = 48
    const val MAX_MIN_RUNTIME_SECONDS = 3600
    const val HOURLY_GRID = 60
    const val MAX_EVERY = 1000
    const val MIN_YEAR = 1
    const val MAX_YEAR = 9999
    const val MIN_OFFSET = -720
    const val MAX_OFFSET = 840
    const val OFFSET_STEP = 15

    /** The top-level field names that [version] knows. */
    fun topLevel(version: Int): Set<String> = if (version >= 2) TOP_LEVEL_V2 else TOP_LEVEL_V1

    /** The `ordering` field names that [version] knows. */
    fun ordering(version: Int): Set<String> = if (version >= 2) ORDERING_V2 else ORDERING_V1

    /** Fails when [extras] holds a key the object already writes itself, which could change what the file means. */
    fun requireFreeExtras(
        extras: JsonObject,
        known: Set<String>,
    ) {
        val clash = extras.keys.firstOrNull { it in known }
        require(clash == null) { "An unknown field cannot reuse the known field name '$clash'" }
    }

    /** Fails unless [text] is 1 to [max] code points with no unpaired surrogate. */
    fun requireText(
        text: String,
        max: Int,
        what: String,
    ) {
        require(RulesLimits.codePoints(text) in 1..max) { "$what must be 1 to $max characters" }
        require(!RulesLimits.hasUnpairedSurrogate(text)) { "$what has a character that is not valid text" }
    }
}
