// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import tv.stationbreak.core.model.OrderingMode
import tv.stationbreak.core.model.Seed

/**
 * The channel rules of format versions 1 to 3 (docs/rules-format.md). A reader builds this from a file and a writer
 * turns it back into the same file. A file holds only what its [formatVersion] allows: [requiredVersion] is the lowest
 * version that has every feature the rules use, and the version must not be below it.
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
    val filters: Filters? = null,
    val extras: JsonObject = NO_EXTRAS,
) {
    init {
        require(formatVersion in 1..RulesLimits.SUPPORTED_FORMAT_VERSION.toInt()) {
            "Only format versions 1 to 3 can be held"
        }
        require(formatVersion >= requiredVersion) { "The file version is below the lowest that has these features" }
        name?.let { RulesVocabulary.requireText(it, RulesLimits.MAX_NAME_CODE_POINTS, "The name") }
        require(sources.size in 1..RulesLimits.MAX_LIST_ENTRIES) { "There are 1 to 256 sources" }
        require(effectiveExclusions.size <= RulesLimits.MAX_LIST_ENTRIES) { "There are at most 256 exclusions" }
        require(maxRating == null || maxRating in RulesVocabulary.RATINGS) { "The rating is not on the ladder" }
        require(effectiveCaps == null || timing.effectiveEpochHours >= RulesVocabulary.CAPS_EPOCH_HOURS) {
            "Caps need a 24-hour epoch"
        }
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.topLevel(formatVersion))
        ordering?.let { RulesVocabulary.requireFreeExtras(it.extras, RulesVocabulary.ordering(formatVersion)) }
        filters?.let {
            RulesVocabulary.requireFreeExtras(it.extras, RulesVocabulary.filters(formatVersion))
            RulesVocabulary.requireFreeExtras(it.runtimeExtras, RulesVocabulary.filterRuntime(formatVersion))
        }
    }

    /** The ordering mode to use: `shuffle` when the file has no `ordering`. */
    val orderingMode: OrderingMode get() = ordering?.mode ?: OrderingMode.Shuffle

    /** The filler mode to use: `none` when the file has no `filler`. */
    val fillerMode: FillerMode get() = filler?.mode ?: FillerMode.None

    /** The exclusions to use: none when the file has no `exclusions`. */
    val effectiveExclusions: List<Source> get() = exclusions.orEmpty()

    /**
     * The caps in force, or null when none are. `rotation` always has caps (the defaults when the file gives none), a
     * `shuffle` has them only when the file gives `caps`, and the other modes ignore them.
     */
    val effectiveCaps: OrderingCaps?
        get() =
            when (orderingMode) {
                OrderingMode.Rotation -> ordering?.caps ?: OrderingCaps(null, null)
                OrderingMode.Shuffle -> ordering?.caps
                else -> null
            }

    /**
     * The lowest format version that has every feature these rules use: 3 for a version 3 feature (a studio source or
     * exclusion, a `tags`, `studios` or `notGenres` filter, a per-kind runtime, the `curated` order or one of its
     * tunables), 2 for a version 2 feature, else 1.
     */
    val requiredVersion: Int
        get() {
            val usesV3 =
                sources.any { it is Source.Studio } ||
                    effectiveExclusions.any { it is Source.Studio } ||
                    filters?.usesVersion3 == true ||
                    orderingMode == OrderingMode.Curated ||
                    ordering?.repeatGapMinutes != null ||
                    ordering?.pairUpToMinutes != null
            val usesV2 =
                sources.any { it is Source.Item } ||
                    effectiveExclusions.any { it is Source.Item } ||
                    filters != null ||
                    orderingMode == OrderingMode.Rotation ||
                    orderingMode == OrderingMode.InOrder ||
                    ordering?.caps != null
            return if (usesV3) {
                3
            } else if (usesV2) {
                2
            } else {
                1
            }
        }

    /**
     * These rules at [version], with any extra whose key [version] knows dropped (top level, `ordering`, `filters` and
     * `filters.runtime`), so a file that kept `filters`, `caps` or `tags` as unknown fields can move up and get real
     * ones. That drops data: call it
     * only on an explicit upgrade, never to normalise a file on the way through.
     */
    fun withVersion(version: Int): ChannelRules =
        copy(
            formatVersion = version,
            ordering =
                ordering?.copy(
                    extras = JsonObject(ordering.extras.filterKeys { it !in RulesVocabulary.ordering(version) }),
                ),
            filters =
                filters?.copy(
                    extras = JsonObject(filters.extras.filterKeys { it !in RulesVocabulary.filters(version) }),
                    runtimeExtras =
                        JsonObject(filters.runtimeExtras.filterKeys { it !in RulesVocabulary.filterRuntime(version) }),
                ),
            extras = JsonObject(extras.filterKeys { it !in RulesVocabulary.topLevel(version) }),
        )
}

/** These rules at the lowest version that has their features: what a writer that edits rules should write. */
fun ChannelRules.normalised(): ChannelRules = withVersion(requiredVersion)

/**
 * The `ordering` object. [caps] is a version 2 field, and [repeatGapMinutes] and [pairUpToMinutes] are version 3
 * fields; in an older file those names are among the [extras]. The extras are checked against the file's version by
 * [ChannelRules], which knows it; here only the names every version knows and the names this object holds. The two
 * tunables are read by `curated` and ignored, but checked, with any other mode.
 */
data class Ordering(
    val mode: OrderingMode,
    val caps: OrderingCaps? = null,
    val repeatGapMinutes: Int? = null,
    val pairUpToMinutes: Int? = null,
    val extras: JsonObject = NO_EXTRAS,
) {
    init {
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.ORDERING_V1)
        require(caps == null || "caps" !in extras) { "An unknown field cannot reuse the known field name 'caps'" }
        require(mode != OrderingMode.Curated || caps == null) { "The curated order has no caps" }
        require(repeatGapMinutes == null || repeatGapMinutes in 0..RulesVocabulary.MAX_REPEAT_GAP_MINUTES) {
            "The repeat gap is 0 to 1440 minutes"
        }
        val pairs = RulesVocabulary.MIN_PAIR_UP_MINUTES..RulesVocabulary.MAX_PAIR_UP_MINUTES
        require(pairUpToMinutes == null || pairUpToMinutes in pairs) { "The pair-up length is 1 to 1440 minutes" }
        require(repeatGapMinutes == null || "repeatGapMinutes" !in extras) {
            "An unknown field cannot reuse the known field name 'repeatGapMinutes'"
        }
        require(pairUpToMinutes == null || "pairUpToMinutes" !in extras) {
            "An unknown field cannot reuse the known field name 'pairUpToMinutes'"
        }
    }

    /** The shortest time before a title or franchise may air again under `curated`: 180 when the file does not say. */
    val effectiveRepeatGapMinutes: Int get() = repeatGapMinutes ?: RulesVocabulary.DEFAULT_REPEAT_GAP_MINUTES

    /** The longest episode that is paired with the next under `curated`: 35 when the file does not say. */
    val effectivePairUpToMinutes: Int get() = pairUpToMinutes ?: RulesVocabulary.DEFAULT_PAIR_UP_MINUTES
}

/** The `ordering.caps` object of version 2. A part the file leaves out is `null` here. */
data class OrderingCaps(
    val series: Int?,
    val film: Int?,
    val extras: JsonObject = NO_EXTRAS,
) {
    init {
        val range = RANGE
        require(series == null || series in range) { "A series cap is 1 to 10" }
        require(film == null || film in range) { "A film cap is 1 to 10" }
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.CAPS)
    }

    /** The most episodes of one show in a row: 3 when the file does not say. */
    val effectiveSeries: Int get() = series ?: DEFAULT_SERIES

    /** The most films of one collection in a row: 1 when the file does not say. */
    val effectiveFilm: Int get() = film ?: DEFAULT_FILM

    companion object {
        const val DEFAULT_SERIES = 3
        const val DEFAULT_FILM = 1
        val RANGE = RulesVocabulary.MIN_CAP..RulesVocabulary.MAX_CAP
    }
}

/** The `timing` object. */
data class Timing(
    val blockLength: Int?,
    val alignToGrid: Boolean?,
    val epochHours: Int?,
    val graceHours: Int?,
    val gridMinutes: Int?,
    val minRuntimeSeconds: Int?,
    val broadcastDay: BroadcastDay,
    val extras: JsonObject = NO_EXTRAS,
) {
    init {
        val blocks = 1..RulesVocabulary.MAX_BLOCK_LENGTH
        require(blockLength == null || blockLength in blocks) { "A block is 1 to 100 items" }
        require(epochHours == null || epochHours in RulesVocabulary.EPOCH_HOURS) { "An epoch length must divide 24" }
        require(graceHours == null || graceHours in 0..RulesVocabulary.MAX_GRACE_HOURS) { "Grace is 0 to 48 hours" }
        require(minRuntimeSeconds == null || minRuntimeSeconds in 1..RulesVocabulary.MAX_MIN_RUNTIME_SECONDS) {
            "The minimum runtime is 1 to 3600 seconds"
        }
        require(gridMinutes == null || gridMinutes in RulesVocabulary.GRID_MINUTES) {
            "The grid is 15, 30 or 60 minutes"
        }
        require(effectiveGridMinutes != RulesVocabulary.HOURLY_GRID || broadcastDay.effectiveStart.endsWith(":00")) {
            "With an hourly grid the day starts on the hour"
        }
        RulesVocabulary.requireFreeExtras(extras, RulesVocabulary.TIMING)
    }

    val effectiveBlockLength: Int get() = blockLength ?: DEFAULT_BLOCK_LENGTH
    val effectiveAlignToGrid: Boolean get() = alignToGrid ?: DEFAULT_ALIGN_TO_GRID
    val effectiveEpochHours: Int get() = epochHours ?: DEFAULT_EPOCH_HOURS

    /** The grace window G in hours: how long before an epoch starts a new item must have been added to air in it. */
    val effectiveGraceHours: Int get() = graceHours ?: DEFAULT_GRACE_HOURS

    /** The shortest runtime, in seconds, that counts as a program; the pool leaves out anything shorter. */
    val effectiveMinRuntimeSeconds: Int get() = minRuntimeSeconds ?: DEFAULT_MIN_RUNTIME_SECONDS

    /** The grid spacing in minutes: 30 when the file does not say. */
    val effectiveGridMinutes: Int get() = gridMinutes ?: DEFAULT_GRID_MINUTES

    companion object {
        /** The default of `timing.minRuntimeSeconds`. */
        const val DEFAULT_MIN_RUNTIME_SECONDS = 60

        private const val DEFAULT_BLOCK_LENGTH = 1
        private const val DEFAULT_ALIGN_TO_GRID = true
        private const val DEFAULT_EPOCH_HOURS = 24
        private const val DEFAULT_GRACE_HOURS = 6
        private const val DEFAULT_GRID_MINUTES = 30
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
