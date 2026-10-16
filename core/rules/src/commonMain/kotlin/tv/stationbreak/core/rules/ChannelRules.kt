// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed

/**
 * The channel rules of format version 1 (docs/rules-format.md). A reader builds this from a file and a writer turns it
 * back into the same file.
 *
 * A field with a default is `null` when the file did not write it, so a rewrite does not invent it. The `effective…`
 * properties give the value to use. Fields this version does not know are in the `extras` of the object that held them.
 * [seed] is a number here and is written back as the same canonical decimal string it was read from.
 */
data class ChannelRules(
    val formatVersion: Int,
    val name: String?,
    val seed: Seed,
    val sources: List<Source>,
    val exclusions: List<Source>?,
    val maxRating: String?,
    val ordering: Ordering?,
    val timing: Timing,
    val filler: Filler?,
    val extras: JsonObject = NO_EXTRAS,
) {
    init {
        require(formatVersion.toLong() == RulesLimits.SUPPORTED_FORMAT_VERSION) { "Only format version 1 can be held" }
        name?.let { RulesVocabulary.requireText(it, RulesLimits.MAX_NAME_CODE_POINTS, "The name") }
        require(sources.size in 1..RulesLimits.MAX_LIST_ENTRIES) { "There are 1 to 256 sources" }
        require(effectiveExclusions.size <= RulesLimits.MAX_LIST_ENTRIES) { "There are at most 256 exclusions" }
        require(maxRating == null || maxRating in RulesVocabulary.RATINGS) { "The rating is not on the ladder" }
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.TOP_LEVEL)
    }

    /** The ordering mode to use: `shuffle` when the file has no `ordering`. */
    val orderingMode: OrderingMode get() = ordering?.mode ?: OrderingMode.Shuffle

    /** The filler mode to use: `none` when the file has no `filler`. */
    val fillerMode: FillerMode get() = filler?.mode ?: FillerMode.None

    /** The exclusions to use: none when the file has no `exclusions`. */
    val effectiveExclusions: List<Source> get() = exclusions.orEmpty()
}

/** The `ordering` object. */
data class Ordering(
    val mode: OrderingMode,
    val extras: JsonObject = NO_EXTRAS,
) {
    init {
        RulesVocabulary.requireFreeExtras(extras, setOf("mode"))
    }
}

/** The `timing` object. */
data class Timing(
    val blockLength: Int?,
    val alignToGrid: Boolean?,
    val epochHours: Int?,
    val graceHours: Int?,
    val gridMinutes: Int?,
    val broadcastDay: BroadcastDay,
    val extras: JsonObject = NO_EXTRAS,
) {
    init {
        val blocks = 1..RulesVocabulary.MAX_BLOCK_LENGTH
        require(blockLength == null || blockLength in blocks) { "A block is 1 to 100 items" }
        require(epochHours == null || epochHours in RulesVocabulary.EPOCH_HOURS) { "An epoch length must divide 24" }
        require(graceHours == null || graceHours in 0..RulesVocabulary.MAX_GRACE_HOURS) { "Grace is 0 to 48 hours" }
        require(gridMinutes == null || gridMinutes in RulesVocabulary.GRID_MINUTES) { "The grid is 15, 30 or 60 minutes" }
        require(effectiveGridMinutes != RulesVocabulary.HOURLY_GRID || broadcastDay.effectiveStart.endsWith(":00")) {
            "With an hourly grid the day starts on the hour"
        }
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.TIMING)
    }

    val effectiveBlockLength: Int get() = blockLength ?: DEFAULT_BLOCK_LENGTH
    val effectiveAlignToGrid: Boolean get() = alignToGrid ?: true
    val effectiveEpochHours: Int get() = epochHours ?: DEFAULT_EPOCH_HOURS

    /** The grace window G in hours: how long before an epoch starts a new item must have been added to air in it. */
    val effectiveGraceHours: Int get() = graceHours ?: DEFAULT_GRACE_HOURS

    /** The grid spacing in minutes: 30 when the file does not say. */
    val effectiveGridMinutes: Int get() = gridMinutes ?: DEFAULT_GRID_MINUTES

    private companion object {
        const val DEFAULT_BLOCK_LENGTH = 1
        const val DEFAULT_EPOCH_HOURS = 24
        const val DEFAULT_GRACE_HOURS = 6
        const val DEFAULT_GRID_MINUTES = 30
    }
}

/** The `timing.broadcastDay` object. [utcOffsetMinutes] has no default. */
data class BroadcastDay(
    val utcOffsetMinutes: Int,
    val start: String?,
    val extras: JsonObject = NO_EXTRAS,
) {
    init {
        val range = RulesVocabulary.MIN_OFFSET..RulesVocabulary.MAX_OFFSET
        require(utcOffsetMinutes in range) { "The offset is out of range" }
        require(utcOffsetMinutes % RulesVocabulary.OFFSET_STEP == 0) { "The offset is a multiple of 15 minutes" }
        require(start == null || RulesVocabulary.START_TIME.matches(start)) { "The start is HH:MM on a half hour" }
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.BROADCAST_DAY)
    }

    val effectiveStart: String get() = start ?: "06:00"
}

/** The filler modes of version 1, with the file's exact names. Only [None] is scheduled by a version 1 reader. */
enum class FillerMode(
    val wireName: String,
) {
    None("none"),
    PadToGrid("padToGrid"),
    BetweenEveryN("betweenEveryN"),
}

/** The `filler` object. [source] is a library, collection or tag source. */
data class Filler(
    val mode: FillerMode,
    val every: Int?,
    val source: Source?,
    val extras: JsonObject = NO_EXTRAS,
) {
    init {
        require(every == null || every in 1..RulesVocabulary.MAX_EVERY) { "Every is 1 to 1000" }
        require(mode != FillerMode.BetweenEveryN || every != null) { "betweenEveryN needs every" }
        require(mode == FillerMode.None || source != null) { "A filler mode other than none needs a source" }
        require(source == null || source is Source.Library || source is Source.Collection || source is Source.Tag) {
            "Filler comes from a library, a collection or a tag"
        }
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.FILLER)
    }
}
