// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup.builder

import tv.stationbreak.core.engine.lineup.Band
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.model.Seed

/** How a channel plays its pool. */
enum class PlayOrder { Shuffle, Rotation, InOrder }

/**
 * Something a channel draws from or leaves out. [key] is what the rules store (an ID, or the exact name of a genre or
 * tag); [label] is what the viewer sees.
 */
sealed interface DraftSource {
    val key: String
    val label: String

    /** The same source under another [label]. */
    fun withLabel(label: String): DraftSource

    data class Library(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    data class Collection(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    data class Tag(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    data class Genre(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    /** A show: every episode of the series, live episodes included. */
    data class Show(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }

    /** One film the viewer picked by title. */
    data class Pick(
        override val key: String,
        override val label: String,
    ) : DraftSource {
        override fun withLabel(label: String) = copy(label = label)
    }
}

/** What narrows the pool beyond its sources. Empty and null parts narrow nothing. */
data class DraftFilters(
    val genres: List<String> = emptyList(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val minMinutes: Int? = null,
    val maxMinutes: Int? = null,
    val only: OnlyKind? = null,
    val maxRating: String? = null,
)

enum class OnlyKind { Films, Shows }

/**
 * Settings of a copied built-in channel that the builder cannot show or change, as the screen's read-only "Kept from"
 * lines. Structured values only: the screen words them.
 */
data class KeptSettings(
    val fromName: String,
    val items: List<KeptItem>,
)

sealed interface KeptItem {
    data class Order(
        val mode: KeptOrder,
    ) : KeptItem

    data class YearsSource(
        val from: Int,
        val to: Int,
    ) : KeptItem

    data class BlockLength(
        val n: Int,
    ) : KeptItem

    /** A shuffle with no caps: it stays uncapped until the viewer turns caps on or takes the builder's settings. */
    data object NoCaps : KeptItem

    /** The day is cut into epochs shorter than 24 hours; caps would need 24. */
    data class EpochHours(
        val hours: Int,
    ) : KeptItem

    /** The channel is laid out on the grid (the builder's channels play back to back). */
    data object BackToBackOff : KeptItem

    data object Filler : KeptItem

    /** Anything else the builder has no control for. */
    data object OtherSettings : KeptItem
}

enum class KeptOrder { Chronological, RandomInOrder }

/** Why a draft cannot be saved yet. */
enum class DraftProblem {
    NameEmpty,
    NameTooLong,
    NoSources,
    CapOutOfRange,
    YearsIncomplete,
    YearsOutOfRange,
    YearsReversed,
    MinutesOutOfRange,
    MinutesReversed,

    /** A cap given for one of series and films but not the other. */
    CapsIncomplete,

    /** More sources than the rules format holds (256). */
    TooManySources,

    /** More left-out sources than the rules format holds (256). */
    TooManyLeftOut,

    /** More genres in the filter than the rules format holds (256). */
    TooManyGenres,

    /** A kids channel with a picked number outside 1 to 99. */
    KidsNumberOutsideBand,

    /** A grown-up channel with a picked number inside 1 to 99, the kids band. */
    GrownUpNumberInKidsBand,
}

/**
 * A channel as the builder holds it. [numberChosen] is true only when the viewer picked [number]; otherwise creating
 * works the number out inside the lineup lock. [seed] is the preview's; blank drafts and copies draw a fresh one, an
 * edit keeps the channel's, and Save keeps the STORED seed unless the draft came from a saved Reshuffle.
 *
 * What the builder cannot show is kept ([kept]) and stays in force until the viewer chooses otherwise, which the type
 * says: a null [order] is the kept order of a copied channel (a chronological or in-turn one), and null [seriesCap]
 * and [filmCap] together are the kept uncapped Shuffle. Giving [order] or the caps a value is the viewer's choice and
 * takes the kept item's place; no other change to the draft can lose or keep one by mistake. [keptInForce] is what
 * is still kept. A null order without a kept order is Shuffle; null caps without a kept uncapped Shuffle are the
 * defaults. A screen shows [keptInForce], never [kept]: [kept] still lists items the viewer has replaced.
 *
 * [origin] is the channel the draft was opened from: the built-in channel of a copy, the custom channel of an edit,
 * null for a new channel. The kept settings are read from its rules when the draft is created, saved or previewed, so
 * a draft with kept settings whose origin is null or gone cannot be (create refuses with `UnknownChannel`, a preview
 * is `Unavailable`).
 */
@Suppress("LongParameterList") // The draft's fields, one for one.
data class ChannelDraft(
    val name: String,
    val sources: List<DraftSource>,
    val leaveOut: List<DraftSource>,
    val filters: DraftFilters,
    val order: PlayOrder? = PlayOrder.Shuffle,
    val seriesCap: Int? = DEFAULT_SERIES_CAP,
    val filmCap: Int? = DEFAULT_FILM_CAP,
    val kids: Boolean,
    val number: Int? = null,
    val kept: KeptSettings? = null,
    val numberChosen: Boolean = false,
    val origin: ChannelId? = null,
    val seed: Seed,
) {
    /** The first thing wrong with the draft, or null when it can be saved. */
    fun problem(): DraftProblem? {
        val trimmed = name.trim()
        val points = trimmed.codePointCount(0, trimmed.length)
        return when {
            points == 0 -> DraftProblem.NameEmpty
            points > MAX_NAME_CODE_POINTS -> DraftProblem.NameTooLong
            sources.isEmpty() && !hasKeptYears() -> DraftProblem.NoSources
            sources.size + keptYearRanges() > MAX_LIST -> DraftProblem.TooManySources
            leaveOut.size > MAX_LIST -> DraftProblem.TooManyLeftOut
            (filters.genres.size) > MAX_LIST -> DraftProblem.TooManyGenres
            else -> capsProblem() ?: yearsProblem() ?: minutesProblem() ?: numberProblem()
        }
    }

    // A year range the copy keeps is a source of the channel too.
    private fun hasKeptYears(): Boolean = keptYearRanges() > 0

    // The year ranges a copy carries are sources of the rules too, and count toward the format's 256.
    private fun keptYearRanges(): Int = kept?.items.orEmpty().count { it is KeptItem.YearsSource }

    /** The kept items that are still in force: the viewer has not chosen an order or caps that replace them. */
    val keptInForce: List<KeptItem>
        get() = kept?.items.orEmpty().filter { inForce(it) }

    /** True when the kept order stays: the viewer left [order] alone and the copied channel has one. */
    fun keepsOrder(): Boolean = order == null && kept?.items.orEmpty().any { it is KeptItem.Order }

    private fun inForce(item: KeptItem): Boolean =
        when (item) {
            is KeptItem.Order -> order == null
            KeptItem.NoCaps -> keepsUncapped()
            is KeptItem.EpochHours -> !writesCaps()
            else -> true
        }

    /** True when the kept uncapped Shuffle stays: the viewer left the order and both caps alone. */
    internal fun keepsUncapped(): Boolean =
        KeptItem.NoCaps in kept?.items.orEmpty() && seriesCap == null && filmCap == null &&
            (order ?: PlayOrder.Shuffle) == PlayOrder.Shuffle

    /** True when the rules written carry caps: a Shuffle or Rotation that is neither a kept order nor uncapped. */
    fun writesCaps(): Boolean {
        val caps = (order ?: PlayOrder.Shuffle) != PlayOrder.InOrder
        return caps && !keepsOrder() && !keepsUncapped()
    }

    // Caps matter only where they are written; one given without the other is incomplete.
    private fun capsProblem(): DraftProblem? =
        when {
            !writesCaps() -> null
            (seriesCap == null) != (filmCap == null) -> DraftProblem.CapsIncomplete
            seriesCap == null || filmCap == null -> null
            seriesCap !in CAPS || filmCap !in CAPS -> DraftProblem.CapOutOfRange
            else -> null
        }

    // Both ends or neither; in 1..9999; in order.
    private fun yearsProblem(): DraftProblem? {
        val from = filters.yearFrom
        val to = filters.yearTo
        return when {
            (from == null) != (to == null) -> DraftProblem.YearsIncomplete
            from == null || to == null -> null
            from !in YEARS || to !in YEARS -> DraftProblem.YearsOutOfRange
            from > to -> DraftProblem.YearsReversed
            else -> null
        }
    }

    // A number the viewer picked must be on the side of the band their kids setting says.
    private fun numberProblem(): DraftProblem? {
        val picked = number?.takeIf { numberChosen } ?: return null
        val inKidsBand = picked in Band.Kids.numbers
        return when {
            kids && !inKidsBand -> DraftProblem.KidsNumberOutsideBand
            !kids && inKidsBand -> DraftProblem.GrownUpNumberInKidsBand
            else -> null
        }
    }

    private fun minutesProblem(): DraftProblem? {
        val min = filters.minMinutes
        val max = filters.maxMinutes
        return when {
            listOfNotNull(min, max).any { it !in MINUTES } -> DraftProblem.MinutesOutOfRange
            min != null && max != null && min > max -> DraftProblem.MinutesReversed
            else -> null
        }
    }

    companion object {
        const val DEFAULT_SERIES_CAP = 3
        const val DEFAULT_FILM_CAP = 1
        const val MAX_NAME_CODE_POINTS = 100
        private const val MAX_LIST = 256
        private val CAPS = 1..10
        private val YEARS = 1..9999
        private val MINUTES = 1..1440
    }
}
