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
 * lines. Structured values only: the screen words them. [fromName] is the built-in channel they were copied from, or
 * empty when that is not known (a saved copy being edited).
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
@Suppress("LongParameterList", "TooManyFunctions") // The draft's fields one for one, and its checks.
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
        val name = nameProblem()
        return when {
            name != null -> name
            !hasPool() -> DraftProblem.NoSources
            else -> listsProblem() ?: capsProblem() ?: yearsProblem() ?: minutesProblem() ?: numberProblem()
        }
    }

    /**
     * Everything wrong with the draft, grouped: what it draws on (sources, years, minutes), then how it plays (the
     * caps), then its identity (name and number); within a group, in the order checked. A screen may lay the groups
     * out as it likes. Empty exactly when [problem] is null.
     */
    fun problems(): List<DraftProblem> =
        listOfNotNull(
            DraftProblem.NoSources.takeIf { !hasPool() },
            listsProblem(),
            yearsProblem(),
            minutesProblem(),
            capsProblem(),
            nameProblem(),
            numberProblem(),
        )

    private fun nameProblem(): DraftProblem? {
        val trimmed = name.trim()
        val points = trimmed.codePointCount(0, trimmed.length)
        return when {
            points == 0 -> DraftProblem.NameEmpty
            points > MAX_NAME_CODE_POINTS -> DraftProblem.NameTooLong
            else -> null
        }
    }

    // More of a list than the rules format holds.
    private fun listsProblem(): DraftProblem? =
        when {
            sources.size + keptYearRanges() > MAX_LIST -> DraftProblem.TooManySources
            leaveOut.size > MAX_LIST -> DraftProblem.TooManyLeftOut
            filters.genres.size > MAX_LIST -> DraftProblem.TooManyGenres
            else -> null
        }

    /** True when the draft draws on something: a source, or a year range a copy keeps (which is a source too). */
    fun hasPool(): Boolean = sources.isNotEmpty() || keptYearRanges() > 0

    // The year ranges a copy carries are sources of the rules too, and count toward the format's 256.
    private fun keptYearRanges(): Int = kept?.items.orEmpty().count { it is KeptItem.YearsSource }

    /** The kept items that are still in force: the viewer has not chosen an order or caps that replace them. */
    val keptInForce: List<KeptItem>
        get() = kept?.items.orEmpty().filter { inForce(it) }

    /**
     * True when a kept setting that is still in force is one the builder has a control for, which "Use the builder's
     * settings instead" replaces: the kept order, the kept uncapped Shuffle, the day cut shorter than 24 hours. The
     * other kept items (block length, back-to-back off, filler, other settings, a decade's year range) have no
     * control: they are information, and stay.
     */
    fun keepsReplaceable(): Boolean {
        val uncapped = KeptItem.NoCaps
        return keptInForce.any { it is KeptItem.Order || it == uncapped || it is KeptItem.EpochHours }
    }

    /** True when the kept order stays: the viewer left [order] alone and the copied channel has one. */
    internal fun keepsOrder(): Boolean = order == null && kept?.items.orEmpty().any { it is KeptItem.Order }

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
    internal fun writesCaps(): Boolean {
        val caps = (order ?: PlayOrder.Shuffle) != PlayOrder.InOrder
        return caps && !keepsOrder() && !keepsUncapped()
    }

    /** The episodes in a row the screen shows: null (no limit) while a kept uncapped Shuffle is in force. */
    fun shownSeriesCap(): Int? = if (keepsUncapped()) null else (seriesCap ?: DEFAULT_SERIES_CAP).coerceIn(CAP_RANGE)

    /** The films of a collection in a row the screen shows: null (no limit) while a kept uncapped Shuffle holds. */
    fun shownFilmCap(): Int? = if (keepsUncapped()) null else (filmCap ?: DEFAULT_FILM_CAP).coerceIn(CAP_RANGE)

    /** Whether the screen lists the caps: not for In order, nor for a kept order. */
    fun showsCaps(): Boolean = order != PlayOrder.InOrder && !keepsOrder()

    /** Whether the draft is a Shuffle, the only order a Reshuffle (a new seed) changes. */
    fun canReshuffle(): Boolean = order == PlayOrder.Shuffle || (order == null && !keepsOrder())

    /**
     * The draft with the kids switch [on] or off at [number] (a number the builder found, not one the viewer picked).
     * A kids channel's ceiling is one of the kids hold's own choices: any other is replaced by the choice that holds
     * it to what it held it to, never looser, so the draft, the screen and the file agree.
     */
    fun withKids(
        on: Boolean,
        number: Int?,
    ): ChannelDraft = copy(kids = on, number = number, numberChosen = false).kidsNormalised()

    /** [this] with a kids channel's ceiling made one the kids hold knows; a grown-up draft is returned as it is. */
    internal fun kidsNormalised(): ChannelDraft {
        if (!kids || filters.maxRating in DraftChoices.ratingCeilings(kids = true)) return this
        return copy(filters = filters.copy(maxRating = DraftChoices.kidsCeilingFor(filters.maxRating)))
    }

    // Caps matter only where they are written; one given without the other is incomplete.
    private fun capsProblem(): DraftProblem? =
        when {
            !writesCaps() -> null
            (seriesCap == null) != (filmCap == null) -> DraftProblem.CapsIncomplete
            seriesCap == null || filmCap == null -> null
            seriesCap !in CAP_RANGE || filmCap !in CAP_RANGE -> DraftProblem.CapOutOfRange
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

        /** The caps a channel can have, in a row. */
        val CAP_RANGE = 1..10
        private const val MAX_LIST = 256
        private val YEARS = 1..9999
        private val MINUTES = 1..1440
    }
}
