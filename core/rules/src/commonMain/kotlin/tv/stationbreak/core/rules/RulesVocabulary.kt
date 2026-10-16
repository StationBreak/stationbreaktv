// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject

/** The fixed names and ranges of format version 1, shared by the reader and by the checks on the types. */
internal object RulesVocabulary {
    val RATINGS =
        setOf(
            "TV-Y",
            "TV-G",
            "G",
            "Approved",
            "TV-Y7",
            "TV-Y7-FV",
            "TV-PG",
            "PG",
            "PG-13",
            "TV-14",
            "R",
            "TV-MA",
            "NC-17",
            "TV-X",
            "TV-AO",
        )
    val EPOCH_HOURS = setOf(1, 2, 3, 4, 6, 8, 12, 24)
    val START_TIME = Regex("^([01][0-9]|2[0-3]):(00|30)$")
    val TOP_LEVEL =
        setOf("formatVersion", "name", "seed", "sources", "exclusions", "maxRating", "ordering", "timing", "filler")
    val TIMING = setOf("blockLength", "alignToGrid", "epochHours", "broadcastDay")
    val BROADCAST_DAY = setOf("utcOffsetMinutes", "start")
    val FILLER = setOf("mode", "every", "source")

    const val MAX_BLOCK_LENGTH = 100
    const val MAX_EVERY = 1000
    const val MIN_YEAR = 1
    const val MAX_YEAR = 9999
    const val MIN_OFFSET = -720
    const val MAX_OFFSET = 840
    const val OFFSET_STEP = 15

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
